package com.sclinic.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EncounterDiagnosisRepository extends JpaRepository<EncounterDiagnosis, UUID> {

    List<EncounterDiagnosis> findByEncounterIdOrderByPrimaryDescCreatedAtAsc(UUID encounterId);

    long countByEncounterIdAndPrimaryTrue(UUID encounterId);

    void deleteByEncounterId(UUID encounterId);
}
