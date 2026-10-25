package com.sclinic.maintenance;

import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.security.AuthProperties;
import com.sclinic.security.authevent.AuthEventRepository;
import com.sclinic.security.password.StaffPasswordHistoryRepository;
import com.sclinic.security.session.SessionTokenRepository;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Keeps the authentication housekeeping tables from growing without bound.
 *
 * <p>Three tables accumulate a row per login or per password change and nothing
 * ever removed any of them. {@code SessionTokenRepository.deleteExpiredBefore}
 * had existed since the session work and was never called by anything, which is
 * the sort of gap that stays invisible until a table is large enough to be a
 * problem.
 *
 * <p>What this does <em>not</em> do is delete anything by default. Password
 * history is pruned, because the password policy already defines how many
 * previous hashes matter and the rest cannot affect a decision. The other two
 * are only touched when an operator has configured a period, because each one
 * holds something a later investigation would want — see
 * {@link MaintenanceProperties}.
 *
 * <p>Every run that deletes anything writes an audit entry with the counts. A
 * purge that leaves no trace is indistinguishable from tampering, and this one
 * removes rows that security reviews read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityHousekeepingService {

    private static final String ENTITY_TYPE = "security_housekeeping";

    private final SessionTokenRepository sessionTokenRepository;
    private final AuthEventRepository authEventRepository;
    private final StaffPasswordHistoryRepository passwordHistoryRepository;
    private final StaffRepository staffRepository;
    private final MaintenanceProperties properties;
    private final AuthProperties authProperties;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Runs one pass and reports what it removed.
     *
     * <p>Each table is handled in its own call rather than one sweep, so a
     * failure on one does not leave the others undone and so the counts in the
     * audit entry mean what they say.
     */
    @Transactional
    public Result run() {
        Instant now = clock.instant();

        int prunedHistory = pruneSurplusPasswordHistory();
        int prunedSessions = properties.prunesSessionTokens()
                ? sessionTokenRepository.deleteExpiredBefore(
                        now.minus(properties.sessionTokenRetention()))
                : 0;
        int prunedAuthEvents = properties.prunesAuthEvents()
                ? authEventRepository.deleteCreatedBefore(
                        now.minus(properties.authEventRetention()))
                : 0;

        Result result = new Result(prunedHistory, prunedSessions, prunedAuthEvents);

        if (result.removedAnything()) {
            // Counts only. Which accounts had rows removed is not recorded,
            // because the point of the entry is that a deletion happened and how
            // much, and naming accounts would copy staff activity patterns into
            // a second table that outlives the rows it describes.
            auditService.record(AuditAction.PURGE, ENTITY_TYPE, null,
                    AuditDetail.builder()
                            .code("passwordHistory", String.valueOf(prunedHistory))
                            .code("sessionTokens", String.valueOf(prunedSessions))
                            .code("authEvents", String.valueOf(prunedAuthEvents))
                            .build());

            log.info("Security housekeeping removed {} password history rows, {} expired "
                            + "sessions, {} auth events",
                    prunedHistory, prunedSessions, prunedAuthEvents);
        }

        return result;
    }

    /**
     * Drops password hashes older than the reuse window.
     *
     * <p>Pruned by count, not by age, which is why this needs no configured
     * period: {@code passwordHistorySize} already says how many previous
     * passwords may not be reused, so the ones past that position cannot change
     * the answer to any question the system asks. Keeping them would be keeping
     * bcrypt hashes of old staff passwords for no purpose, which is worth
     * avoiding on its own.
     */
    private int pruneSurplusPasswordHistory() {
        int keep = authProperties.passwordHistorySize();
        int removed = 0;

        // A loop over staff, which is a few dozen rows in a clinic of this size
        // and runs once a night. Keeping the newest N per account is a window
        // function, which JPQL cannot express, and a native query would have to
        // be written twice over if the database ever changes. If the staff table
        // ever grows enough for this to matter, that is the point to write it.

        for (Staff staff : staffRepository.findAll()) {
            UUID staffId = staff.getId();
            var all = passwordHistoryRepository.findByStaffIdOrderByCreatedAtDesc(staffId);

            if (all.size() > keep) {
                var surplus = all.subList(keep, all.size());
                passwordHistoryRepository.deleteAll(surplus);
                removed += surplus.size();
            }
        }
        return removed;
    }

    /**
     * @param passwordHistoryRows hashes dropped from beyond the reuse window
     * @param sessionTokens       expired session rows removed, 0 when not configured
     * @param authEvents          authentication events removed, 0 when not configured
     */
    public record Result(int passwordHistoryRows, int sessionTokens, int authEvents) {

        boolean removedAnything() {
            return passwordHistoryRows > 0 || sessionTokens > 0 || authEvents > 0;
        }
    }
}
