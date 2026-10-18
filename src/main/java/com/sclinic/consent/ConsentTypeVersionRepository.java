package com.sclinic.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentTypeVersionRepository
        extends JpaRepository<ConsentTypeVersion, UUID> {

    /**
     * The wording currently in force for a type.
     *
     * <p>Highest published version whose effective date has arrived — not the
     * one with the latest {@code effectiveFrom}. A version dated in the future is
     * a planned change, and serving it early would collect consent against
     * wording that does not apply yet.
     */
    @Query("""
            select v from ConsentTypeVersion v
            where v.typeId = :typeId
              and v.publishedAt is not null
              and v.retiredAt is null
              and v.effectiveFrom <= :today
            order by v.version desc
            limit 1
            """)
    Optional<ConsentTypeVersion> findCurrent(@Param("typeId") UUID typeId,
                                             @Param("today") LocalDate today);

    List<ConsentTypeVersion> findByTypeIdOrderByVersionDesc(UUID typeId);

    @Query("select coalesce(max(v.version), 0) from ConsentTypeVersion v "
            + "where v.typeId = :typeId")
    int highestVersion(@Param("typeId") UUID typeId);
}
