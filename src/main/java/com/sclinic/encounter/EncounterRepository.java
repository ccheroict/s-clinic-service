package com.sclinic.encounter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    Optional<Encounter> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * A patient's record, newest first.
     *
     * <p>Includes amendments as rows of their own, because they are rows of their
     * own: the chain is assembled by the service, which knows what the clinical
     * reader needs to see.
     */
    List<Encounter> findByPatientIdAndDeletedAtIsNullOrderByEncounteredAtDesc(UUID patientId);

    Optional<Encounter> findByAppointmentIdAndAmendsEncounterIdIsNullAndDeletedAtIsNull(
            UUID appointmentId);

    /** The amendments correcting one record, oldest first (requirement 14.8). */
    List<Encounter> findByAmendsEncounterIdOrderByCreatedAtAsc(UUID encounterId);

    @Query("""
            select count(e) from Encounter e
            where e.doctorId = :doctorId and e.patientId = :patientId
              and e.deletedAt is null
            """)
    long countByDoctorAndPatient(@Param("doctorId") UUID doctorId,
                                 @Param("patientId") UUID patientId);
}
