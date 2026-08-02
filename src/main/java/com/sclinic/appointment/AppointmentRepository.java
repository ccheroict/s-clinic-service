package com.sclinic.appointment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

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

    /**
     * Phân trang + lọc với các tham số tùy chọn (AND logic).
     * Nếu tham số là null thì bỏ qua điều kiện đó.
     */
    @Query("""
            SELECT a FROM Appointment a
            WHERE (:from IS NULL OR a.scheduledAt >= :from)
              AND (:to IS NULL OR a.scheduledAt < :to)
              AND (:doctorId IS NULL OR a.doctorId = :doctorId)
              AND (:status IS NULL OR a.status = :status)
            """)
    Page<Appointment> findWithFilters(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("doctorId") UUID doctorId,
            @Param("status") AppointmentStatus status,
            Pageable pageable);
}
