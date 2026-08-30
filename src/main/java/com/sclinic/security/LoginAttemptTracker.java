package com.sclinic.security;

import com.sclinic.security.authevent.AuthEventLogger;
import com.sclinic.security.authevent.AuthEventType;
import com.sclinic.security.authevent.RequestContext;
import com.sclinic.security.session.SessionTokenService;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Counts failed authentication attempts and locks the account at the threshold.
 *
 * <p>A separate bean running in its own transaction, for a reason that is easy to
 * get wrong: a rejected login ends in an exception, and that exception rolls back
 * the transaction of the method that raised it. Counting the failure inside that
 * transaction means the count is thrown away with it, so the counter never
 * advances and the account never locks, no matter how many attempts are made.
 * Committing separately is what makes the lockout real.
 *
 * <p>The password gate and the second-factor gate share one counter, so an
 * attacker cannot earn extra attempts by alternating between them. The counter
 * row is locked while it is incremented, so the limit holds against parallel
 * attempts and not just sequential ones.
 *
 * <p>One window remains, deliberately: the caller checks the lock and compares
 * the password before reaching this class, so requests already in flight when the
 * account locks still get their comparison. The extra attempts that leak through
 * are bounded by how many requests the server handles concurrently, not by how
 * many the attacker sends, and the account stays locked for the whole lockout
 * period afterwards. Closing it entirely would mean taking the row lock in the
 * caller's transaction, which cannot be done here: this method runs in its own
 * transaction, so it would then wait for a lock the caller holds while the caller
 * waits for it to finish.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptTracker {

    private final StaffRepository staffRepository;
    private final SessionTokenService sessionTokenService;
    private final AuthEventLogger authEventLogger;
    private final AuthProperties properties;
    private final java.time.Clock clock;

    /**
     * Records one failed attempt against an account.
     *
     * @param failureType which gate rejected the attempt, for the audit trail
     * @return whether this attempt tipped the account into a lockout
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerFailure(UUID staffId, String username, RequestContext context,
                                   AuthEventType failureType, String detail) {
        // Locked, not just read: the increment below is a read-modify-write, and
        // parallel attempts would otherwise overwrite each other's count. See
        // StaffRepository.findByIdForUpdate.
        Optional<Staff> found = staffRepository.findByIdForUpdate(staffId);
        if (found.isEmpty()) {
            // Nothing to count against, but the attempt is still worth recording.
            authEventLogger.failureInCurrentTransaction(failureType, username, staffId, context, detail);
            return false;
        }

        Staff staff = found.get();
        int attempts = staff.getFailedAttempts() + 1;
        boolean lockedNow = attempts >= properties.maxFailedAttempts();

        // The counter restarts at zero once the lock is in place; the lock itself
        // is what blocks further attempts until it expires.
        staff.setFailedAttempts(lockedNow ? 0 : attempts);
        if (lockedNow) {
            staff.setLockedUntil(clock.instant().plus(properties.lockoutDuration()));
        }
        staffRepository.save(staff);

        // Written inside this transaction, not a nested one. This transaction
        // already commits independently of the failing request, so a third
        // transaction would buy nothing and would hold a third database connection
        // for every failed login.
        authEventLogger.failureInCurrentTransaction(failureType, username, staffId, context, detail);

        if (lockedNow) {
            authEventLogger.failureInCurrentTransaction(AuthEventType.ACCOUNT_LOCKED, username,
                    staffId, context, "locked for " + properties.lockoutDuration());
            // An account under attack should not keep whatever sessions it holds.
            sessionTokenService.revokeAllForStaff(staffId);
        }
        return lockedNow;
    }
}
