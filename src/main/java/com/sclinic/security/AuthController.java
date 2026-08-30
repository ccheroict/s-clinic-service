package com.sclinic.security;

import com.sclinic.security.authevent.RequestContext;
import com.sclinic.security.dto.ChangePasswordRequest;
import com.sclinic.security.dto.LoginRequest;
import com.sclinic.security.dto.LoginResponse;
import com.sclinic.security.dto.MfaCodeRequest;
import com.sclinic.security.dto.MfaConfirmResponse;
import com.sclinic.security.dto.MfaEnrollResponse;
import com.sclinic.security.mfa.MfaService;
import com.sclinic.security.session.BearerTokenReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Authentication endpoints.
 *
 * <p>These are reachable without an authenticated {@code SecurityContext}
 * because two of them legitimately run before a session exists, and
 * change-password must also accept an interim token that the authentication
 * filter refuses to promote. Each method therefore validates its own token.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        return authService.login(request, RequestContext.from(httpRequest));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(BearerTokenReader.read(httpRequest), RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * Also accepts a change-password-scope token, which is how an account forced
     * to rotate its password gets back in. Returns a fresh full-scope session.
     */
    @PostMapping("/change-password")
    public LoginResponse changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                        HttpServletRequest httpRequest) {
        return authService.changePassword(
                BearerTokenReader.read(httpRequest), request, RequestContext.from(httpRequest));
    }

    @PostMapping("/revoke-sessions/{staffId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> revokeSessions(@PathVariable UUID staffId,
                                              HttpServletRequest httpRequest) {
        int revoked = authService.revokeSessions(staffId, RequestContext.from(httpRequest));
        return Map.of("staffId", staffId, "revokedSessions", revoked);
    }

    // ---------- Two-factor authentication ----------

    /** Issues a TOTP secret. Accepts an enrolment token or a full session. */
    @PostMapping("/mfa/enroll")
    public MfaEnrollResponse beginMfaEnrolment(HttpServletRequest httpRequest) {
        MfaService.Enrolment enrolment = authService.beginMfaEnrolment(
                BearerTokenReader.read(httpRequest), RequestContext.from(httpRequest));
        return new MfaEnrollResponse(enrolment.secret(), enrolment.provisioningUri());
    }

    /** Completes enrolment and returns the recovery codes, shown only here. */
    @PostMapping("/mfa/confirm")
    public MfaConfirmResponse confirmMfaEnrolment(@Valid @RequestBody MfaCodeRequest request,
                                                  HttpServletRequest httpRequest) {
        AuthService.MfaConfirmResult result = authService.confirmMfaEnrolment(
                BearerTokenReader.read(httpRequest), request.code(),
                RequestContext.from(httpRequest));
        return new MfaConfirmResponse(result.session(), result.backupCodes());
    }

    /** Answers the login-time challenge. Accepts a TOTP or a recovery code. */
    @PostMapping("/mfa/verify")
    public LoginResponse verifyMfa(@Valid @RequestBody MfaCodeRequest request,
                                   HttpServletRequest httpRequest) {
        return authService.verifyMfa(
                BearerTokenReader.read(httpRequest), request.code(),
                RequestContext.from(httpRequest));
    }

    /**
     * Clears another account's second factor after a lost device. Admin only, and
     * deliberately not self-service: a user who could reset their own second
     * factor with just a session would defeat the point of having one.
     */
    @PostMapping("/mfa/reset/{staffId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetMfa(@PathVariable UUID staffId,
                                         HttpServletRequest httpRequest) {
        authService.resetMfa(staffId, RequestContext.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
