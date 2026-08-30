package com.sclinic.security.dto;

import com.sclinic.security.session.TokenScope;

import java.time.Instant;

/**
 * Result of a login attempt.
 *
 * <p>When {@code passwordChangeRequired} is true the token only works against
 * {@code POST /api/auth/change-password}; business endpoints reject it. The
 * frontend uses that flag to route the user to the change-password screen.
 *
 * @param token                  raw session token, to be sent as {@code Authorization: Bearer}
 * @param scope                  what the token may do
 * @param expiresAt              when the token stops working
 * @param username               resolved account name
 * @param role                   DOCTOR / RECEPTIONIST / ADMIN
 * @param passwordChangeRequired the account must set a new password first
 */
public record LoginResponse(
        String token,
        TokenScope scope,
        Instant expiresAt,
        String username,
        String role,
        boolean passwordChangeRequired
) {
}
