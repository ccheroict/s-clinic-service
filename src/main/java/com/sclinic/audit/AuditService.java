package com.sclinic.audit;

import com.sclinic.security.authevent.RequestContext;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Records audit trail entries for actions on sensitive records.
 *
 * <p>Runs in a REQUIRES_NEW transaction so the audit entry is committed
 * independently — it works even when the caller runs read-only (e.g. a VIEW),
 * and a failed business operation does not silently drop the access record.
 *
 * <p>Each entry closes over the hash of the previous one, which makes the trail
 * tamper-evident (see V7). Building that chain needs the current head, so writers
 * take a row lock on {@link AuditChainHead} first: two concurrent writers would
 * otherwise read the same head and fork the chain, leaving two entries claiming
 * the same position. Writers block rather than fail — an audit entry that gives
 * up because another was in flight is a missing audit entry, which is worse than
 * waiting.
 *
 * <p>The lock is only ever taken on this table, and the audit transaction never
 * touches business rows, so it cannot deadlock against the business transaction
 * that triggered it.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final AuditChainHeadRepository chainHeadRepository;
    private final StaffRepository staffRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, UUID entityId, Map<String, Object> detail) {
        AuditChainHead head = chainHeadRepository.lockHead()
                .orElseThrow(() -> new IllegalStateException(
                        "Audit chain head row is missing; migration V7 did not complete"));

        String prevHash = head.getHeadHash();
        RequestContext context = currentRequestContext();
        UUID staffId = currentStaffId();
        UUID sessionId = currentSessionId();
        // Microsecond precision because that is what PostgreSQL keeps; hashing a
        // value the database would round would break every verification.
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> safeDetail = detail == null || detail.isEmpty() ? null : detail;

        String entryHash = AuditHash.of(prevHash, staffId, action.name(), entityType, entityId,
                safeDetail, context.ip(), context.userAgent(), sessionId, createdAt);

        AuditLog entry = new AuditLog();
        entry.setStaffId(staffId);
        entry.setAction(action.name());
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetail(safeDetail);
        entry.setIp(context.ip());
        entry.setUserAgent(context.userAgent());
        entry.setSessionId(sessionId);
        entry.setCreatedAt(createdAt);
        entry.setPrevHash(prevHash);
        entry.setEntryHash(entryHash);
        auditLogRepository.save(entry);

        head.setHeadHash(entryHash);
        head.setEntryCount(head.getEntryCount() + 1);
        chainHeadRepository.save(head);
    }

    /**
     * Carries its own propagation rather than leaning on the overload it
     * delegates to. A call from here to the four-argument {@code record} does not
     * pass through the Spring proxy, so the REQUIRES_NEW declared there would be
     * silently ignored and the entry would run inside the caller's transaction —
     * rolled back with it on failure, and rejected outright when the caller is
     * read-only, which every VIEW is.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, UUID entityId) {
        record(action, entityType, entityId, null);
    }

    /** Resolve the current authenticated staff id, or null if unauthenticated. */
    private UUID currentStaffId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return staffRepository.findByUsernameAndActiveTrue(auth.getName())
                .map(Staff::getId)
                .orElse(null);
    }

    /**
     * The session behind the current request. {@code SessionTokenAuthFilter}
     * parks the session id on the {@code Authentication} details, which is the
     * only place it survives from the filter chain down to here.
     */
    private UUID currentSessionId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return auth.getDetails() instanceof UUID sessionId ? sessionId : null;
    }

    /**
     * The caller's network context, or an empty one for work that has no request
     * behind it (startup seeding, scheduled jobs).
     */
    private RequestContext currentRequestContext() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return RequestContext.from(servletAttributes.getRequest());
        }
        return new RequestContext(null, null);
    }
}
