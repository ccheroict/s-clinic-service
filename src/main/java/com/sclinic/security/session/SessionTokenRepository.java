package com.sclinic.security.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionTokenRepository extends JpaRepository<SessionToken, UUID> {

    Optional<SessionToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live session of one staff member (dismissal, lost device).
     *
     * <p>Flushes and clears the persistence context: this is a bulk update that
     * bypasses managed entities, so without clearing, a later read in the same
     * transaction could still see a session as live.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE SessionToken s
               SET s.revokedAt = :now
             WHERE s.staffId = :staffId
               AND s.revokedAt IS NULL
               AND s.expiresAt > :now
            """)
    int revokeAllForStaff(@Param("staffId") UUID staffId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM SessionToken s WHERE s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);

    long countByStaffIdAndRevokedAtIsNullAndExpiresAtAfter(UUID staffId, Instant now);
}
