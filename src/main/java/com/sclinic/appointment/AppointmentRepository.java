package com.sclinic.appointment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID>,
        JpaSpecificationExecutor<Appointment> {

    /**
     * Whether this doctor treats this patient.
     *
     * <p>The treating relationship behind the access rule is derived from here
     * rather than kept in a table of its own: that would be the same fact stored
     * twice, and the copy would drift — a doctor reassigned away from a patient
     * would keep access until something remembered to delete the row.
     */
    boolean existsByPatientIdAndDoctorId(UUID patientId, UUID doctorId);

    /**
     * Tìm lịch hẹn xung đột cho bác sĩ.
     * Overlap condition: existing.start < new.end AND new.start < existing.end
     * existing.end = existing.scheduled_at + existing.duration_min * interval '1 minute'
     * Loại trừ CANCELLED và NO_SHOW.
     */
    @Query(value = """
            SELECT * FROM appointment a
            WHERE a.doctor_id = :doctorId
              AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
              AND a.scheduled_at < :endTime
              AND :startTime < (a.scheduled_at + (a.duration_min * interval '1 minute'))
              AND (:excludeId IS NULL OR a.id <> :excludeId)
            """, nativeQuery = true)
    List<Appointment> findDoctorConflicts(
            @Param("doctorId") UUID doctorId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("excludeId") UUID excludeId);

    /**
     * Tìm lịch hẹn xung đột cho bệnh nhân.
     * Cùng logic overlap như findDoctorConflicts nhưng theo patient_id.
     */
    @Query(value = """
            SELECT * FROM appointment a
            WHERE a.patient_id = :patientId
              AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
              AND a.scheduled_at < :endTime
              AND :startTime < (a.scheduled_at + (a.duration_min * interval '1 minute'))
              AND (:excludeId IS NULL OR a.id <> :excludeId)
            """, nativeQuery = true)
    List<Appointment> findPatientConflicts(
            @Param("patientId") UUID patientId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("excludeId") UUID excludeId);

    /*
     * The filtered list is a Specification, not a method here.
     *
     * It used to be a JPQL query with a (:param IS NULL OR column = :param)
     * clause per filter, which fails outright on PostgreSQL: that compiles to
     * (? is null or ...), the driver cannot infer a type for a bare parameter
     * compared against null, and the statement is rejected whenever any filter
     * is omitted. See AppointmentSpecifications.
     */
}
