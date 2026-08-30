package com.sclinic.security.mfa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StaffBackupCodeRepository extends JpaRepository<StaffBackupCode, UUID> {

    List<StaffBackupCode> findByStaffIdAndUsedAtIsNull(UUID staffId);

    void deleteByStaffId(UUID staffId);

    long countByStaffIdAndUsedAtIsNull(UUID staffId);

    /**
     * Spends a recovery code, and reports whether this caller is the one that spent
     * it.
     *
     * <p>A conditional update rather than a read followed by a write: two requests
     * presenting the same code at the same time would both find it unused and both
     * mark it used, so a single-use code would work twice. The {@code used_at is
     * null} predicate is evaluated by the database while it holds the row, so
     * exactly one of them gets a row count of 1.
     *
     * @return 1 if this call spent the code, 0 if it was already spent
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StaffBackupCode code
               SET code.usedAt = :now
             WHERE code.id = :id
               AND code.usedAt IS NULL
            """)
    int spend(@Param("id") UUID id, @Param("now") Instant now);
}
