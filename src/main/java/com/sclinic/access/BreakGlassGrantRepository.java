package com.sclinic.access;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BreakGlassGrantRepository extends JpaRepository<BreakGlassGrant, UUID> {

    /**
     * The live grant a staff member holds over a patient, if any.
     *
     * <p>Expiry is evaluated in the query rather than by filtering afterwards, so
     * a grant stops working the moment it lapses without anything having to run
     * to clean it up. A scheduled expiry job would leave a window in which a
     * lapsed grant still opened the record.
     */
    @Query("""
            SELECT grant FROM BreakGlassGrant grant
             WHERE grant.staffId = :staffId
               AND grant.patientId = :patientId
               AND grant.revokedAt IS NULL
               AND grant.expiresAt > :now
             ORDER BY grant.expiresAt DESC
             LIMIT 1
            """)
    Optional<BreakGlassGrant> findActive(@Param("staffId") UUID staffId,
                                         @Param("patientId") UUID patientId,
                                         @Param("now") Instant now);

    /** Patients a staff member currently holds a grant over, for list filtering. */
    @Query("""
            SELECT grant.patientId FROM BreakGlassGrant grant
             WHERE grant.staffId = :staffId
               AND grant.revokedAt IS NULL
               AND grant.expiresAt > :now
            """)
    List<UUID> findPatientIdsWithActiveGrant(@Param("staffId") UUID staffId,
                                             @Param("now") Instant now);

    /** Admin action: end every live grant a staff member holds. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE BreakGlassGrant grant
               SET grant.revokedAt = :now
             WHERE grant.staffId = :staffId
               AND grant.revokedAt IS NULL
               AND grant.expiresAt > :now
            """)
    int revokeAllForStaff(@Param("staffId") UUID staffId, @Param("now") Instant now);

    List<BreakGlassGrant> findByStaffIdOrderByGrantedAtDesc(UUID staffId);
}
