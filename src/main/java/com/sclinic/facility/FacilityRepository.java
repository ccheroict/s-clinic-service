package com.sclinic.facility;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {

    /**
     * The facility this installation serves. Ordered by creation so the result is
     * stable if a branch record is added later.
     */
    Optional<Facility> findFirstByActiveTrueOrderByCreatedAtAsc();

    Optional<Facility> findByKcbCode(String kcbCode);
}
