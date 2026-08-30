package com.sclinic.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * Authentication tuning. Defaults are deliberately strict because the data
 * behind these endpoints is sensitive health data.
 *
 * @param sessionTtl          how long a fully authenticated session lasts
 * @param interimTokenTtl     lifetime of a half-finished login token
 *                            (change-password / MFA steps); short on purpose
 * @param maxFailedAttempts   consecutive failures before the account locks
 * @param lockoutDuration     how long the lock lasts
 * @param passwordHistorySize how many previous passwords may not be reused
 * @param minPasswordLength   minimum characters in a new password
 * @param mfaRequiredRoles    roles that must use TOTP; empty disables MFA
 * @param totpWindowSteps     how many 30s steps either side of now are accepted,
 *                            to tolerate clock drift on the user's phone
 * @param backupCodeCount     single-use recovery codes issued at enrolment
 * @param totpIssuer          issuer label shown in the authenticator app
 */
@ConfigurationProperties(prefix = "sclinic.auth")
public record AuthProperties(
        Duration sessionTtl,
        Duration interimTokenTtl,
        int maxFailedAttempts,
        Duration lockoutDuration,
        int passwordHistorySize,
        int minPasswordLength,
        Set<String> mfaRequiredRoles,
        int totpWindowSteps,
        int backupCodeCount,
        String totpIssuer
) {

    public AuthProperties {
        if (sessionTtl == null) {
            sessionTtl = Duration.ofHours(8);
        }
        if (interimTokenTtl == null) {
            interimTokenTtl = Duration.ofMinutes(10);
        }
        if (maxFailedAttempts <= 0) {
            maxFailedAttempts = 5;
        }
        if (lockoutDuration == null) {
            lockoutDuration = Duration.ofMinutes(15);
        }
        if (passwordHistorySize <= 0) {
            passwordHistorySize = 5;
        }
        if (minPasswordLength <= 0) {
            minPasswordLength = 12;
        }
        if (mfaRequiredRoles == null) {
            mfaRequiredRoles = Set.of("ADMIN", "DOCTOR");
        }
        if (totpWindowSteps < 0) {
            totpWindowSteps = 1;
        }
        if (backupCodeCount <= 0) {
            backupCodeCount = 10;
        }
        if (totpIssuer == null || totpIssuer.isBlank()) {
            totpIssuer = "S-Clinic";
        }
    }

    /** Whether a role must complete a TOTP challenge to reach a full session. */
    public boolean requiresMfa(String role) {
        return role != null && mfaRequiredRoles.contains(role);
    }
}
