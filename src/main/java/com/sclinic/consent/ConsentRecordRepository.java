package com.sclinic.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    /**
     * The live consent of one type for one patient, or empty.
     *
     * <p>The query behind every gated action. Backed by a partial index, and
     * guaranteed to return at most one row by a partial unique index on the same
     * predicate — so "which of the two applies" is a question that cannot arise.
     */
    @Query("""
            select r from ConsentRecord r
            where r.patientId = :patientId
              and r.consentTypeId = :typeId
              and r.withdrawnAt is null
              and r.supersededAt is null
            """)
    Optional<ConsentRecord> findLive(@Param("patientId") UUID patientId,
                                     @Param("typeId") UUID typeId);

    /** Everything ever recorded for a patient, newest first. */
    List<ConsentRecord> findByPatientIdOrderByGrantedAtDesc(UUID patientId);

    List<ConsentRecord> findByPatientIdAndConsentTypeIdOrderByGrantedAtDesc(
            UUID patientId, UUID typeId);
}
