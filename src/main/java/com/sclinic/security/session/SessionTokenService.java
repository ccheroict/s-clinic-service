package com.sclinic.security.session;

import com.sclinic.security.AuthProperties;
import com.sclinic.security.authevent.RequestContext;
import com.sclinic.staff.Staff;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, validates and revokes session tokens.
 *
 * <p>The raw token is returned to the caller once and never persisted; only its
 * SHA-256 is stored. SHA-256 rather than bcrypt here on purpose: the token is
 * 256 bits of {@link SecureRandom} output, so it has no brute-force surface that
 * a slow hash would protect, and it is verified on every request where bcrypt's
 * cost would be a denial-of-service lever.
 */
@Service
@RequiredArgsConstructor
public class SessionTokenService {

    private static final int TOKEN_BYTES = 32;

    private final SessionTokenRepository repository;
    private final AuthProperties properties;
    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates a session and returns the raw token, which the caller must pass to
     * the client immediately; it cannot be recovered later.
     */
    @Transactional
    public IssuedToken issue(Staff staff, TokenScope scope, RequestContext context) {
        String rawToken = generateRawToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(
                scope == TokenScope.FULL ? properties.sessionTtl() : properties.interimTokenTtl());

        SessionToken token = new SessionToken();
        token.setStaffId(staff.getId());
        token.setTokenHash(hash(rawToken));
        token.setScope(scope);
        token.setExpiresAt(expiresAt);
        if (context != null) {
            token.setIp(context.ip());
            token.setUserAgent(context.userAgent());
        }
        repository.save(token);

        return new IssuedToken(rawToken, scope, expiresAt);
    }

    /** Looks up a usable token, without touching {@code last_used_at}. */
    @Transactional(readOnly = true)
    public Optional<SessionToken> find(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return repository.findByTokenHash(hash(rawToken))
                .filter(token -> token.isUsableAt(now));
    }

    /** Looks up a usable token and records the access. */
    @Transactional
    public Optional<SessionToken> touch(String rawToken) {
        Optional<SessionToken> found = find(rawToken);
        found.ifPresent(token -> {
            token.setLastUsedAt(clock.instant());
            repository.save(token);
        });
        return found;
    }

    @Transactional
    public boolean revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return repository.findByTokenHash(hash(rawToken))
                .filter(token -> !token.isRevoked())
                .map(token -> {
                    token.setRevokedAt(clock.instant());
                    repository.save(token);
                    return true;
                })
                .orElse(false);
    }

    /** Revokes every live session of a staff member. Returns how many were live. */
    @Transactional
    public int revokeAllForStaff(UUID staffId) {
        return repository.revokeAllForStaff(staffId, clock.instant());
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec; unreachable on a valid JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param rawToken  the value the client must send back; not stored server-side
     * @param scope     what the token is allowed to do
     * @param expiresAt when it stops working
     */
    public record IssuedToken(String rawToken, TokenScope scope, Instant expiresAt) {
    }
}
