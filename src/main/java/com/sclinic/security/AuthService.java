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
 * Failure responses are uniform so an attacker cannot tell an unknown account
 * from a wrong password from a locked account.
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

        if (!passwordEncoder.matches(request.currentPassword(), staff.getPasswordHash())) {
            authEventLogger.failure(AuthEventType.PASSWORD_CHANGE_REJECTED, staff.getUsername(),
                    staff.getId(), context, "current password did not match");
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
                .orElseThrow(AuthenticationFailedException::new);

        return staffRepository.findById(token.getStaffId())
                .filter(Staff::isActive)
                .orElseThrow(AuthenticationFailedException::new);
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
