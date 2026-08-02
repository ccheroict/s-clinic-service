package com.sclinic.appointment;

import com.sclinic.appointment.dto.AppointmentCreateRequest;
import com.sclinic.appointment.dto.AppointmentResponse;
import com.sclinic.appointment.dto.AppointmentUpdateRequest;
import com.sclinic.appointment.dto.StatusUpdateRequest;
import com.sclinic.appointment.exception.NotEditableException;
import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.patient.Patient;
import com.sclinic.patient.PatientRepository;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final String ENTITY_TYPE = "appointment";

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;
    private final BusinessHoursValidator businessHoursValidator;
    private final ConflictChecker conflictChecker;
    private final AppointmentStatusMachine statusMachine;
    private final AppointmentMapper appointmentMapper;
    private final AuditService auditService;

    @Transactional
    public AppointmentResponse create(AppointmentCreateRequest request) {
        // 1. Validate patient exists
        Patient patient = patientRepository.findById(request.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + request.patientId()));

        // 2. Validate doctor exists and has DOCTOR role (if doctorId provided)
        Staff doctor = null;
        if (request.doctorId() != null) {
            doctor = staffRepository.findById(request.doctorId())
                    .orElseThrow(() -> new NotFoundException("Doctor not found: " + request.doctorId()));
            if (!"DOCTOR".equals(doctor.getRole())) {
                throw new IllegalArgumentException(
                        "Staff member " + request.doctorId() + " does not have DOCTOR role");
            }
        }

        // 3. Determine effective duration (default 30 if null)
        int durationMin = request.durationMin() != null ? request.durationMin() : 30;

        // 4. Validate business hours
        businessHoursValidator.validate(request.scheduledAt(), durationMin);

        // 5. Check doctor conflict (if doctorId provided)
        if (request.doctorId() != null) {
            conflictChecker.checkDoctorConflict(request.doctorId(), request.scheduledAt(), durationMin, null);
        }

        // 6. Check patient conflict
        conflictChecker.checkPatientConflict(request.patientId(), request.scheduledAt(), durationMin, null);

        // 7. Build and save Appointment entity
        Appointment appointment = new Appointment();
        appointment.setPatientId(request.patientId());
        appointment.setDoctorId(request.doctorId());
        appointment.setScheduledAt(request.scheduledAt());
        appointment.setDurationMin(durationMin);
        appointment.setStatus(AppointmentStatus.BOOKED);
        appointment.setReason(request.reason());
        appointment.setNote(request.note());

        Appointment saved = appointmentRepository.saveAndFlush(appointment);

        // 8. Record audit log
        auditService.record(AuditAction.CREATE, ENTITY_TYPE, saved.getId());

        // 9. Map to response with resolved names
        String patientName = patient.getFullName();
        String patientPhone = patient.getPhone();
        String doctorName = doctor != null ? doctor.getFullName() : null;

        return appointmentMapper.toResponse(saved, patientName, patientPhone, doctorName);
    }

    @Transactional
    public AppointmentResponse update(UUID id, AppointmentUpdateRequest request) {
        // 1. Find appointment by ID → 404 if not found
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found: " + id));

        // 2. Check if status is BOOKED or CONFIRMED → throw NotEditableException otherwise
        if (appointment.getStatus() != AppointmentStatus.BOOKED
                && appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new NotEditableException(appointment.getStatus());
        }

        // Track changed fields for audit
        Map<String, Object> changedFields = new HashMap<>();

        // 3. Update allowed fields from request
        boolean timeChanged = false;
        boolean doctorChanged = false;

        if (request.scheduledAt() != null && !request.scheduledAt().equals(appointment.getScheduledAt())) {
            changedFields.put("scheduledAt", Map.of(
                    "old", appointment.getScheduledAt().toString(),
                    "new", request.scheduledAt().toString()));
            appointment.setScheduledAt(request.scheduledAt());
            timeChanged = true;
        }

        if (request.durationMin() != null && request.durationMin() != appointment.getDurationMin()) {
            changedFields.put("durationMin", Map.of(
                    "old", appointment.getDurationMin(),
                    "new", request.durationMin()));
            appointment.setDurationMin(request.durationMin());
            timeChanged = true;
        }

        if (request.doctorId() != null && !request.doctorId().equals(appointment.getDoctorId())) {
            // Validate doctor exists and has DOCTOR role
            Staff doctor = staffRepository.findById(request.doctorId())
                    .orElseThrow(() -> new NotFoundException("Doctor not found: " + request.doctorId()));
            if (!"DOCTOR".equals(doctor.getRole())) {
                throw new IllegalArgumentException(
                        "Staff member " + request.doctorId() + " does not have DOCTOR role");
            }
            changedFields.put("doctorId", Map.of(
                    "old", Objects.toString(appointment.getDoctorId(), null),
                    "new", request.doctorId().toString()));
            appointment.setDoctorId(request.doctorId());
            doctorChanged = true;
        }

        if (request.reason() != null && !request.reason().equals(appointment.getReason())) {
            changedFields.put("reason", Map.of(
                    "old", Objects.toString(appointment.getReason(), ""),
                    "new", request.reason()));
            appointment.setReason(request.reason());
        }

        if (request.note() != null && !request.note().equals(appointment.getNote())) {
            changedFields.put("note", Map.of(
                    "old", Objects.toString(appointment.getNote(), ""),
                    "new", request.note()));
            appointment.setNote(request.note());
        }

        // 4. If scheduledAt or durationMin changed: validate business hours
        if (timeChanged) {
            businessHoursValidator.validate(appointment.getScheduledAt(), appointment.getDurationMin());
        }

        // 5. If scheduledAt, durationMin, or doctorId changed: check doctor conflict (excludeId = self)
        if ((timeChanged || doctorChanged) && appointment.getDoctorId() != null) {
            conflictChecker.checkDoctorConflict(
                    appointment.getDoctorId(), appointment.getScheduledAt(),
                    appointment.getDurationMin(), appointment.getId());
        }

        // 6. If scheduledAt or durationMin changed: check patient conflict (excludeId = self)
        if (timeChanged) {
            conflictChecker.checkPatientConflict(
                    appointment.getPatientId(), appointment.getScheduledAt(),
                    appointment.getDurationMin(), appointment.getId());
        }

        // 7. Save updated appointment
        Appointment saved = appointmentRepository.saveAndFlush(appointment);

        // 8. Record audit log (UPDATE with changed fields)
        auditService.record(AuditAction.UPDATE, ENTITY_TYPE, saved.getId(),
                changedFields.isEmpty() ? null : changedFields);

        // 9. Map to AppointmentResponse
        Patient patient = patientRepository.findById(saved.getPatientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + saved.getPatientId()));
        String patientName = patient.getFullName();
        String patientPhone = patient.getPhone();
        String doctorName = null;
        if (saved.getDoctorId() != null) {
            doctorName = staffRepository.findById(saved.getDoctorId())
                    .map(Staff::getFullName)
                    .orElse(null);
        }

        return appointmentMapper.toResponse(saved, patientName, patientPhone, doctorName);
    }

    @Transactional
    public AppointmentResponse updateStatus(UUID id, StatusUpdateRequest request,
                                            UUID currentStaffId, String currentRole) {
        // 1. Find appointment by ID → 404 if not found
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found: " + id));

        // 2. For DOCTOR role: verify the appointment's doctorId matches currentStaffId
        if ("DOCTOR".equals(currentRole) && !appointment.getDoctorId().equals(currentStaffId)) {
            throw new AccessDeniedException(
                    "Doctor can only update status of their own appointments");
        }

        // 3. Validate state transition
        AppointmentStatus oldStatus = appointment.getStatus();
        statusMachine.validateTransition(oldStatus, request.status());

        // 4. Set new status and save
        appointment.setStatus(request.status());
        Appointment saved = appointmentRepository.saveAndFlush(appointment);

        // 5. Record audit (UPDATE with oldStatus/newStatus)
        auditService.record(AuditAction.UPDATE, ENTITY_TYPE, saved.getId(),
                Map.of("status", oldStatus.name() + " → " + request.status().name()));

        // 6. Map to AppointmentResponse
        Patient patient = patientRepository.findById(saved.getPatientId())
                .orElseThrow(() -> new NotFoundException("Patient not found: " + saved.getPatientId()));
        String patientName = patient.getFullName();
        String patientPhone = patient.getPhone();
        String doctorName = null;
        if (saved.getDoctorId() != null) {
            doctorName = staffRepository.findById(saved.getDoctorId())
                    .map(Staff::getFullName)
                    .orElse(null);
        }

        return appointmentMapper.toResponse(saved, patientName, patientPhone, doctorName);
    }

    @Transactional(readOnly = true)
    public Page<AppointmentResponse> list(LocalDate date, UUID doctorId, AppointmentStatus status,
                                           Pageable pageable, UUID currentStaffId, String currentRole) {
        // 1. DOCTOR role auto-filter: doctor can only see their own appointments
        if ("DOCTOR".equals(currentRole) && doctorId == null) {
            doctorId = currentStaffId;
        }

        // 2. Convert date to Instant range (start/end of day in system timezone)
        Instant from = null;
        Instant to = null;
        if (date != null) {
            ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
            from = date.atStartOfDay(zone).toInstant();
            to = date.plusDays(1).atStartOfDay(zone).toInstant();
        }

        // 3. Query with filters and pagination
        Page<Appointment> page = appointmentRepository.findWithFilters(from, to, doctorId, status, pageable);

        // 4. Map each Appointment to AppointmentResponse (resolve patient/doctor names)
        return page.map(appointment -> {
            String patientName = patientRepository.findById(appointment.getPatientId())
                    .map(Patient::getFullName)
                    .orElse(null);
            String patientPhone = patientRepository.findById(appointment.getPatientId())
                    .map(Patient::getPhone)
                    .orElse(null);
            String doctorName = appointment.getDoctorId() != null
                    ? staffRepository.findById(appointment.getDoctorId())
                            .map(Staff::getFullName)
                            .orElse(null)
                    : null;
            return appointmentMapper.toResponse(appointment, patientName, patientPhone, doctorName);
        });
    }
}
