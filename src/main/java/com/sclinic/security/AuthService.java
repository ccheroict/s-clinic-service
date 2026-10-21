package com.sclinic.security;

import com.sclinic.security.authevent.AuthEventLogger;
import com.sclinic.security.authevent.AuthEventType;
import com.sclinic.security.authevent.RequestContext;
import com.sclinic.security.dto.ChangePasswordRequest;
import com.sclinic.security.dto.LoginRequest;
import com.sclinic.security.dto.LoginResponse;
import com.sclinic.security.mfa.MfaFailedException;
import com.sclinic.security.mfa.MfaService;
import com.sclinic.security.password.PasswordPolicy;
import com.sclinic.security.session.SessionToken;
import com.sclinic.security.session.SessionTokenService;
import com.sclinic.security.session.TokenScope;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Login, logout, password change and session revocation.
 *
 * <p>Every path through here records an {@link AuthEventType}, including the
 * failures, because a burst of failures is the signal a security review needs.
 *
 * <p><b>How much a failure says.</b> At {@code login}, nothing: an unknown
 * account, a wrong password and a locked account all produce the same response,
 * because the caller has proved nothing and any difference between those answers
 * is a way to find out which usernames exist. Past that point the caller holds a
 * token bound to one account, so there is no longer anything to enumerate, and
 * failures say what went wrong — a wrong current password, an expired pending
 * login, a code that did not verify. Flattening those would cost a user the
 * chance of fixing their own mistake and buy nothing, since the account is
 * already known to whoever holds the token.
 *
 * <p>The one thing never distinguished past login is a replayed authentication
 * code from a wrong one: see {@code MfaService.claimTotpCode}.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final StaffRepository staffRepository;
    private final SessionTokenService sessionTokenService;
    private final PasswordPolicy passwordPolicy;
    private final MfaService mfaService;
    private final LoginAttemptTracker loginAttemptTracker;
    private final AuthEventLogger authEventLogger;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock;

    @Transactional
    public LoginResponse login(LoginRequest request, RequestContext context) {
        String username = request.username();

        Optional<Staff> found = staffRepository.findByUsernameAndActiveTrue(username);
        if (found.isEmpty()) {
            // Still spend the work of a hash comparison? Not worth it here: the
            // lookup is indexed and constant-ish, and the response is uniform.
            authEventLogger.failure(AuthEventType.LOGIN_UNKNOWN_ACCOUNT, username, null, context,
                    "unknown or inactive account");
            throw new AuthenticationFailedException();
        }

        Staff staff = found.get();
        Instant now = clock.instant();

        if (staff.isLockedAt(now)) {
            authEventLogger.failure(AuthEventType.LOGIN_BLOCKED, username, staff.getId(), context,
                    "account locked");
            throw new AuthenticationFailedException();
        }

        if (!passwordEncoder.matches(request.password(), staff.getPasswordHash())) {
            loginAttemptTracker.registerFailure(staff.getId(), username, context,
                    AuthEventType.LOGIN_FAILED, "wrong password");
            throw new AuthenticationFailedException();
        }

        // Success: clear the failure counter and any expired lock.
        staff.setFailedAttempts(0);
        staff.setLockedUntil(null);
        staffRepository.save(staff);

        authEventLogger.success(AuthEventType.LOGIN_SUCCESS, username, staff.getId(), context);

        return issueForNextGate(staff, context);
    }

    /**
     * Decides what the account still has to clear and issues a token for that
     * step. Order: second factor first (prove identity), then password rotation.
     */
    private LoginResponse issueForNextGate(Staff staff, RequestContext context) {
        TokenScope scope = nextScope(staff);
        SessionTokenService.IssuedToken issued = sessionTokenService.issue(staff, scope, context);
        return toResponse(staff, issued);
    }

    private TokenScope nextScope(Staff staff) {
        if (properties.requiresMfa(staff.getRole())) {
            if (!staff.isTotpEnabled()) {
                return TokenScope.ENROLL_MFA;
            }
            return TokenScope.MFA_PENDING;
        }
        return staff.isMustChangePassword() ? TokenScope.CHANGE_PASSWORD : TokenScope.FULL;
    }

    private LoginResponse toResponse(Staff staff, SessionTokenService.IssuedToken issued) {
        return new LoginResponse(
                issued.rawToken(),
                issued.scope(),
                issued.expiresAt(),
                staff.getUsername(),
                staff.getRole(),
                issued.scope() == TokenScope.CHANGE_PASSWORD);
    }

    @Transactional
    public void logout(String rawToken, RequestContext context) {
        Optional<SessionToken> token = sessionTokenService.find(rawToken);
        String username = token
                .flatMap(t -> staffRepository.findById(t.getStaffId()))
                .map(Staff::getUsername)
                .orElse("");
        UUID staffId = token.map(SessionToken::getStaffId).orElse(null);

        sessionTokenService.revoke(rawToken);
        authEventLogger.success(AuthEventType.LOGOUT, username, staffId, context);
    }

    /**
     * Changes the password and returns a fresh full-scope session, so the client
     * does not have to log in again immediately after being forced to change.
     *
     * <p>Accepts either a full-scope or a change-password-scope token. All other
     * live sessions of the account are revoked: a password change is the standard
     * response to a suspected compromise.
     */
    @Transactional
    public LoginResponse changePassword(String rawToken, ChangePasswordRequest request,
                                        RequestContext context) {
        SessionToken token = sessionTokenService.find(rawToken)
                .filter(t -> t.getScope() == TokenScope.FULL || t.getScope() == TokenScope.CHANGE_PASSWORD)
                .orElseThrow(AuthenticationFailedException::new);

        Staff staff = staffRepository.findById(token.getStaffId())
                .filter(Staff::isActive)
                .orElseThrow(AuthenticationFailedException::new);

        // A locked account cannot change its password either. Without this, the
        // lockout stops the login endpoint and leaves this one open, and this one
        // also takes the current password — so the lock would just move the
        // guessing to a different URL.
        if (staff.isLockedAt(clock.instant())) {
            authEventLogger.failure(AuthEventType.PASSWORD_CHANGE_REJECTED, staff.getUsername(),
                    staff.getId(), context, "account locked");
            throw new AuthenticationFailedException();
        }

        if (!passwordEncoder.matches(request.currentPassword(), staff.getPasswordHash())) {
            // Counted toward the lockout, like a failed login. The current
            // password field is a password oracle: before this, the login
            // endpoint allowed five guesses and then locked, while this endpoint
            // allowed unlimited guesses at the same secret for anyone holding any
            // session — including a stolen one, where guessing the password is
            // precisely the step from "can act as this user now" to "can act as
            // them whenever they like".
            loginAttemptTracker.registerFailure(staff.getId(), staff.getUsername(), context,
                    AuthEventType.PASSWORD_CHANGE_REJECTED, "current password did not match");

            // Named rather than uniform, unlike the login failures. The caller
            // already holds a valid token for this account, so they have proved
            // which account they are: there is nothing left to enumerate, and a
            // blank refusal would only leave someone retyping a correct new
            // password while the old one was the problem.
            throw new AuthenticationFailedException("Current password is incorrect");
        }

        // Throws WeakPasswordException, mapped to 400 by the global handler.
        passwordPolicy.validateForStaff(staff.getId(), staff.getPasswordHash(), request.newPassword());

        String previousHash = staff.getPasswordHash();
        String newHash = passwordEncoder.encode(request.newPassword());

        staff.setPasswordHash(newHash);
        staff.setPasswordChangedAt(clock.instant());
        staff.setMustChangePassword(false);
        staff.setFailedAttempts(0);
        staff.setLockedUntil(null);
        staffRepository.save(staff);

        passwordPolicy.remember(staff.getId(), previousHash);

        // Drop every existing session, including the one used for this call.
        sessionTokenService.revokeAllForStaff(staff.getId());
        SessionTokenService.IssuedToken issued =
                sessionTokenService.issue(staff, TokenScope.FULL, context);

        authEventLogger.success(AuthEventType.PASSWORD_CHANGED, staff.getUsername(), staff.getId(), context);

        return new LoginResponse(
                issued.rawToken(),
                issued.scope(),
                issued.expiresAt(),
                staff.getUsername(),
                staff.getRole(),
                false);
    }

    // ---------- Two-factor authentication ----------

    /**
     * Issues a fresh TOTP secret for an account holding an enrolment token, or
     * for a fully authenticated user setting up voluntarily.
     */
    @Transactional
    public MfaService.Enrolment beginMfaEnrolment(String rawToken, RequestContext context) {
        Staff staff = staffForScopes(rawToken, TokenScope.ENROLL_MFA, TokenScope.FULL);
        return mfaService.beginEnrolment(staff);
    }

    /**
     * Confirms enrolment with a first valid code and returns the recovery codes
     * plus a token for whatever gate remains.
     */
    @Transactional
    public MfaConfirmResult confirmMfaEnrolment(String rawToken, String code, RequestContext context) {
        Staff staff = staffForScopes(rawToken, TokenScope.ENROLL_MFA, TokenScope.FULL);

        List<String> backupCodes;
        try {
            backupCodes = mfaService.confirmEnrolment(staff, code);
        } catch (MfaFailedException e) {
            authEventLogger.failure(AuthEventType.MFA_FAILED, staff.getUsername(), staff.getId(),
                    context, "enrolment confirmation rejected");
            throw e;
        }

        authEventLogger.success(AuthEventType.MFA_ENROLLED, staff.getUsername(), staff.getId(), context);

        sessionTokenService.revoke(rawToken);
        // Enrolment is done, so the MFA gate is cleared; only a pending password
        // rotation can still stand in the way.
        TokenScope scope = staff.isMustChangePassword() ? TokenScope.CHANGE_PASSWORD : TokenScope.FULL;
        SessionTokenService.IssuedToken issued = sessionTokenService.issue(staff, scope, context);

        return new MfaConfirmResult(toResponse(staff, issued), backupCodes);
    }

    /**
     * Answers the login-time second-factor challenge. A wrong code counts toward
     * the lockout, so codes cannot be brute forced.
     */
    @Transactional
    public LoginResponse verifyMfa(String rawToken, String code, RequestContext context) {
        Staff staff = staffForScopes(rawToken, TokenScope.MFA_PENDING);
        Instant now = clock.instant();

        if (staff.isLockedAt(now)) {
            authEventLogger.failure(AuthEventType.LOGIN_BLOCKED, staff.getUsername(), staff.getId(),
                    context, "account locked");
            throw new AuthenticationFailedException();
        }

        try {
            mfaService.verifyChallenge(staff, code);
        } catch (MfaFailedException e) {
            loginAttemptTracker.registerFailure(staff.getId(), staff.getUsername(), context,
                    AuthEventType.MFA_FAILED, "wrong authentication code");
            throw e;
        }

        staff.setFailedAttempts(0);
        staffRepository.save(staff);
        authEventLogger.success(AuthEventType.MFA_SUCCESS, staff.getUsername(), staff.getId(), context);

        sessionTokenService.revoke(rawToken);
        TokenScope scope = staff.isMustChangePassword() ? TokenScope.CHANGE_PASSWORD : TokenScope.FULL;
        SessionTokenService.IssuedToken issued = sessionTokenService.issue(staff, scope, context);

        return toResponse(staff, issued);
    }

    /**
     * Issues a fresh set of recovery codes, replacing any outstanding ones.
     *
     * <p>Exists because the codes are shown exactly once, at the end of
     * enrolment. Reloading that page lost them permanently, and the only way
     * back was an administrator clearing the second factor altogether — a
     * heavier, more disruptive operation than the problem called for, and one
     * that leaves the account briefly without a second factor at all.
     *
     * <p>Requires a current authentication code as well as a full session. A
     * full session on its own is not enough: recovery codes outlive the session
     * that minted them and each one bypasses the second factor, so letting a
     * session alone produce them would turn a stolen session into permanent
     * second-factor bypass. Demanding the code means the caller is holding the
     * authenticator now, which is the same bar {@code beginEnrolment} applies
     * when it refuses to replace a confirmed factor.
     *
     * @return the plain codes, shown exactly once
     */
    @Transactional
    public List<String> regenerateBackupCodes(String rawToken, String code,
                                              RequestContext context) {
        Staff staff = staffForScopes(rawToken, TokenScope.FULL);

        if (!staff.isTotpEnabled()) {
            // Nothing to issue codes against: recovery codes are a way past the
            // second factor, and there is no second factor here.
            throw new MfaFailedException("Two-factor authentication is not set up");
        }

        try {
            mfaService.verifyChallenge(staff, code);
        } catch (MfaFailedException e) {
            // Counted toward the lockout for the same reason wrong codes are at
            // the login challenge: this endpoint takes a code, so without it the
            // endpoint is a place to guess codes without limit.
            loginAttemptTracker.registerFailure(staff.getId(), staff.getUsername(), context,
                    AuthEventType.MFA_FAILED, "wrong code at recovery code reissue");
            throw e;
        }

        List<String> codes = mfaService.regenerateBackupCodes(staff.getId());

        authEventLogger.record(AuthEventType.MFA_BACKUP_CODES_REISSUED, staff.getUsername(),
                staff.getId(), true, context,
                "replaced outstanding recovery codes with " + codes.size() + " new ones");

        return codes;
    }

    /** Admin recovery for a lost authenticator device. */
    @Transactional
    public void resetMfa(UUID staffId, RequestContext context) {
        Staff staff = staffRepository.findById(staffId)
                .orElseThrow(() -> new com.sclinic.common.exception.NotFoundException(
                        "Staff not found: " + staffId));

        mfaService.reset(staff);
        // The account can no longer satisfy the MFA gate, so nothing it holds
        // should stay valid.
        sessionTokenService.revokeAllForStaff(staffId);
        authEventLogger.record(AuthEventType.MFA_RESET, staff.getUsername(), staffId, true, context,
                "second factor cleared by admin");
    }

    /**
     * Resolves the staff member behind a token, accepting only the given scopes.
     */
    private Staff staffForScopes(String rawToken, TokenScope... allowed) {
        Set<TokenScope> allowedScopes = Set.of(allowed);

        SessionToken token = sessionTokenService.find(rawToken)
                .filter(t -> allowedScopes.contains(t.getScope()))
                .orElseThrow(() -> tokenRejection(rawToken));

        return staffRepository.findById(token.getStaffId())
                .filter(Staff::isActive)
                .orElseThrow(AuthenticationFailedException::new);
    }

    /**
     * Why a token was refused, told apart where it helps the user.
     *
     * <p>An expired pending login and a wrong code both ended as 401 with the
     * same wording, so the person at the screen retyped a code that was never
     * the problem. A token that was issued and has run out is reported as
     * expired; anything else — never issued, wrong scope, garbage — stays the
     * uniform failure.
     */
    private RuntimeException tokenRejection(String rawToken) {
        return sessionTokenService.existsButUnusable(rawToken)
                ? new SessionExpiredException()
                : new AuthenticationFailedException();
    }

    /**
     * @param session     token and identity for the next step
     * @param backupCodes single-use recovery codes, shown exactly once
     */
    public record MfaConfirmResult(LoginResponse session, List<String> backupCodes) {
    }

    /** Admin action: drop every live session of another account. */
    @Transactional
    public int revokeSessions(UUID staffId, RequestContext context) {
        Staff staff = staffRepository.findById(staffId)
                .orElseThrow(() -> new com.sclinic.common.exception.NotFoundException(
                        "Staff not found: " + staffId));

        int revoked = sessionTokenService.revokeAllForStaff(staffId);
        authEventLogger.record(AuthEventType.SESSIONS_REVOKED, staff.getUsername(), staffId,
                true, context, "revoked " + revoked + " session(s)");
        return revoked;
    }
}
