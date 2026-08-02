package com.sclinic.appointment;

import com.sclinic.appointment.exception.ConflictException;
import com.sclinic.patient.Patient;
import com.sclinic.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Kiểm tra xung đột lịch hẹn cho bác sĩ và bệnh nhân.
 * <p>
 * Interval overlap: [A_start, A_end) ∩ [B_start, B_end) ≠ ∅
 * ⟺ A_start < B_end AND B_start < A_end
 * Kề nhau (A_end = B_start) KHÔNG phải xung đột.
 */
@Component
@RequiredArgsConstructor
public class ConflictChecker {

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;

    /**
     * Kiểm tra xung đột lịch bác sĩ.
     *
     * @param doctorId    ID bác sĩ cần kiểm tra
     * @param start       thời điểm bắt đầu lịch hẹn mới
     * @param durationMin thời lượng (phút) lịch hẹn mới
     * @param excludeId   ID lịch hẹn cần loại trừ (dùng khi update), có thể null
     * @throws ConflictException nếu phát hiện xung đột
     */
    public void checkDoctorConflict(UUID doctorId, Instant start, int durationMin, UUID excludeId) {
        if (doctorId == null) {
            return;
        }

        Instant endTime = start.plus(durationMin, ChronoUnit.MINUTES);
        List<Appointment> conflicts = appointmentRepository.findDoctorConflicts(doctorId, start, endTime, excludeId);

        if (!conflicts.isEmpty()) {
            Appointment conflict = conflicts.get(0);
            String patientName = resolvePatientName(conflict.getPatientId());
            throw new ConflictException(
                    "Bác sĩ đã có lịch hẹn trùng thời gian",
                    conflict.getId(),
                    patientName,
                    conflict.getScheduledAt(),
                    conflict.getDurationMin()
            );
        }
    }

    /**
     * Kiểm tra xung đột lịch bệnh nhân.
     *
     * @param patientId   ID bệnh nhân cần kiểm tra
     * @param start       thời điểm bắt đầu lịch hẹn mới
     * @param durationMin thời lượng (phút) lịch hẹn mới
     * @param excludeId   ID lịch hẹn cần loại trừ (dùng khi update), có thể null
     * @throws ConflictException nếu phát hiện xung đột
     */
    public void checkPatientConflict(UUID patientId, Instant start, int durationMin, UUID excludeId) {
        Instant endTime = start.plus(durationMin, ChronoUnit.MINUTES);
        List<Appointment> conflicts = appointmentRepository.findPatientConflicts(patientId, start, endTime, excludeId);

        if (!conflicts.isEmpty()) {
            Appointment conflict = conflicts.get(0);
            String patientName = resolvePatientName(conflict.getPatientId());
            throw new ConflictException(
                    "Bệnh nhân đã có lịch hẹn trùng thời gian",
                    conflict.getId(),
                    patientName,
                    conflict.getScheduledAt(),
                    conflict.getDurationMin()
            );
        }
    }

    private String resolvePatientName(UUID patientId) {
        return patientRepository.findById(patientId)
                .map(Patient::getFullName)
                .orElse(patientId.toString());
    }
}
