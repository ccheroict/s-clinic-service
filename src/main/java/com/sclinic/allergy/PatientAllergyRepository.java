package com.sclinic.allergy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientAllergyRepository extends JpaRepository<PatientAllergy, UUID> {

    /**
     * What this patient reacts to. The query the prescribing check runs.
     *
     * <p>Severity first, so the record that would stop a prescription is at the
     * top of the list rather than wherever insertion order puts it.
     *
     * <p>Ordered by an explicit CASE and not by {@code severity desc}. The enum
     * is persisted as a string, and sorting those strings happens to give
     * SEVERE, MODERATE, MILD only because that is their alphabetical order
     * reversed. Adding a level — CRITICAL, say — would silently sort it below
     * MILD and drop the worst allergy a patient has to the bottom of the
     * prescriber's screen. The ranking is written out so it survives that.
     */
    @Query("""
            select a from PatientAllergy a
            where a.patientId = :patientId and a.active = true
            order by case a.severity
                       when com.sclinic.allergy.AllergySeverity.SEVERE then 0
                       when com.sclinic.allergy.AllergySeverity.MODERATE then 1
                       else 2
                     end,
                     a.recordedOn desc
            """)
    List<PatientAllergy> findActiveByPatient(@Param("patientId") UUID patientId);

    /** Including retired records, for the full history of one patient. */
    @Query("""
            select a from PatientAllergy a
            where a.patientId = :patientId
            order by a.active desc,
                     case a.severity
                       when com.sclinic.allergy.AllergySeverity.SEVERE then 0
                       when com.sclinic.allergy.AllergySeverity.MODERATE then 1
                       else 2
                     end,
                     a.recordedOn desc
            """)
    List<PatientAllergy> findAllByPatient(@Param("patientId") UUID patientId);

    /**
     * The live record for one substance, which is what makes a duplicate
     * detectable before the unique index has to reject it.
     */
    Optional<PatientAllergy> findByPatientIdAndAllergenIdAndActiveTrue(
            UUID patientId, UUID allergenId);

    boolean existsByPatientIdAndActiveTrueAndSeverity(UUID patientId, AllergySeverity severity);
}
