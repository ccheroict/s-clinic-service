package com.sclinic.access;

import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Grants a clinician temporary access to a patient they do not treat.
 *
 * <p>Deliberately easy to do and impossible to do quietly. The grant is issued
 * immediately, because the situations that need it are urgent; what it costs is a
 * stated reason, an audit entry of its own kind, and a notification to the
 * administrators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BreakGlassService {

    private static final String ENTITY_TYPE = "patient";

    private final BreakGlassGrantRepository grantRepository;
    private final PatientRepository patientRepository;
    private final AuditService auditService;
    private final BreakGlassNotifier notifier;
    private final AccessProperties properties;
    private final Clock clock;

    /**
     * Issues a grant. The reason has already been validated as non-blank by the
     * request DTO, and the database enforces it again.
     *
     * <p>Runs in its own transaction so the grant and its audit entry survive even
     * if whatever the caller does next fails. A clinician who was told they had
     * access must not find the record of that grant missing afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BreakGlassGrant grant(UUID patientId, UUID staffId, String username, String reason) {
        // Checked against withdrawn records too: there is nothing to grant access
        // to on a record the business API no longer serves.
        if (patientRepository.findByIdAndDeletedAtIsNull(patientId).isEmpty()) {
            throw new NotFoundException("Patient not found: " + patientId);
        }

        Instant now = clock.instant();

        BreakGlassGrant grant = new BreakGlassGrant();
        grant.setStaffId(staffId);
        grant.setPatientId(patientId);
        grant.setReason(reason.trim());
        grant.setGrantedAt(now);
        grant.setExpiresAt(now.plus(properties.breakGlassDuration()));
        grantRepository.save(grant);

        // Its own action rather than a severity column on audit_log: the action is
        // part of what the audit hash covers, so it cannot be downgraded later
        // without breaking the chain. A severity field added outside the hash
        // could be quietly rewritten from "warning" to "routine".
        auditService.record(AuditAction.BREAK_GLASS, ENTITY_TYPE, patientId,
                AuditDetail.builder()
                        .code("grantId", grant.getId().toString())
                        .code("expiresAt", grant.getExpiresAt().toString())
                        .build());

        log.warn("BREAK-GLASS: {} opened patient {} without a treating relationship, until {}",
                username, patientId, grant.getExpiresAt());

        notifier.notifyAdministrators(grant, username);

        return grant;
    }

    /** Admin action: end every live grant a staff member holds. */
    @Transactional
    public int revokeAllFor(UUID staffId) {
        int revoked = grantRepository.revokeAllForStaff(staffId, clock.instant());

        if (revoked > 0) {
            auditService.record(AuditAction.BREAK_GLASS_REVOKED, "staff", staffId,
                    AuditDetail.builder().code("revokedGrants", String.valueOf(revoked)).build());
        }
        return revoked;
    }
}
