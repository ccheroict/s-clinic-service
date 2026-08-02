package com.sclinic.appointment;

import com.sclinic.appointment.dto.AppointmentCreateRequest;
import com.sclinic.appointment.dto.AppointmentResponse;
import com.sclinic.appointment.dto.AppointmentUpdateRequest;
import com.sclinic.appointment.dto.StatusUpdateRequest;
import com.sclinic.appointment.exception.BusinessHoursException;
import com.sclinic.appointment.exception.ConflictException;
import com.sclinic.appointment.exception.InvalidStatusTransitionException;
import com.sclinic.appointment.exception.NotEditableException;
import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.patient.Patient;
import com.sclinic.patient.PatientRepository;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    AppointmentRepository appointmentRepository;

    @Mock
    PatientRepository patientRepository;

    @Mock
    StaffRepository staffRepository;

    @Mock
    BusinessHoursValidator businessHoursValidator;

    @Mock
    ConflictChecker conflictChecker;

    @Mock
    AppointmentStatusMachine statusMachine;

    @Mock
    AppointmentMapper appointmentMapper;

    @Mock
    AuditService auditService;

    @InjectMocks
    AppointmentService service;

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private Patient buildPatient(UUID id) {
        Patient p = new Patient();
        p.setId(id);
        p.setFullName("Nguyen Van A");
        p.setPhone("0900000000");
        return p;
    }

    private Staff buildDoctor(UUID id) {
        Staff s = new Staff();
        s.setId(id);
        s.setFullName("BS Tran Van B");
        s.setRole("DOCTOR");
        s.setActive(true);
        return s;
    }

    private Staff buildNonDoctor(UUID id) {
        Staff s = new Staff();
        s.setId(id);
        s.setFullName("Le Thi C");
        s.setRole("RECEPTIONIST");
        s.setActive(true);
        return s;
    }

    private Appointment buildAppointment(UUID id, AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setId(id);
        a.setPatientId(UUID.randomUUID());
        a.setDoctorId(UUID.randomUUID());
        a.setScheduledAt(Instant.parse("2025-03-10T09:00:00Z"));
        a.setDurationMin(30);
        a.setStatus(status);
        a.setReason("Check up");
        a.setCreatedAt(Instant.now());
        return a;
    }

    private AppointmentResponse dummyResponse() {
        return new AppointmentResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Nguyen Van A", "0900000000",
                UUID.randomUUID(), "BS Tran Van B",
                Instant.parse("2025-03-10T09:00:00Z"), 30,
                AppointmentStatus.BOOKED, "Check up", null,
                Instant.now(), null);
    }

    // ─── create() tests ─────────────────────────────────────────────────────────

    @Nested
    class CreateTests {

        @Test
        void happyPath_returnsBookedAppointment() {
            UUID patientId = UUID.randomUUID();
            UUID doctorId = UUID.randomUUID();
            Patient patient = buildPatient(patientId);
            Staff doctor = buildDoctor(doctorId);
            Instant scheduledAt = Instant.parse("2025-03-10T09:00:00Z");

            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, doctorId, scheduledAt, 45, "Checkup", "Note");

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(staffRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
            when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                    .thenAnswer(inv -> {
                        Appointment saved = inv.getArgument(0);
                        saved.setId(UUID.randomUUID());
                        saved.setCreatedAt(Instant.now());
                        return saved;
                    });
            AppointmentResponse expectedResponse = dummyResponse();
            when(appointmentMapper.toResponse(any(), eq("Nguyen Van A"), eq("0900000000"), eq("BS Tran Van B")))
                    .thenReturn(expectedResponse);

            AppointmentResponse result = service.create(request);

            assertThat(result).isEqualTo(expectedResponse);
            verify(businessHoursValidator).validate(scheduledAt, 45);
            verify(conflictChecker).checkDoctorConflict(doctorId, scheduledAt, 45, null);
            verify(conflictChecker).checkPatientConflict(patientId, scheduledAt, 45, null);
            verify(appointmentRepository).saveAndFlush(argThat(a ->
                    a.getStatus() == AppointmentStatus.BOOKED && a.getDurationMin() == 45));
        }

        @Test
        void patientNotFound_throwsNotFoundException() {
            UUID patientId = UUID.randomUUID();
            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, null, Instant.parse("2025-03-10T09:00:00Z"), 30, null, null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(patientId.toString());
        }

        @Test
        void doctorNotFound_throwsNotFoundException() {
            UUID patientId = UUID.randomUUID();
            UUID doctorId = UUID.randomUUID();
            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, doctorId, Instant.parse("2025-03-10T09:00:00Z"), 30, null, null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(buildPatient(patientId)));
            when(staffRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(doctorId.toString());
        }

        @Test
        void doctorWrongRole_throwsIllegalArgumentException() {
            UUID patientId = UUID.randomUUID();
            UUID doctorId = UUID.randomUUID();
            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, doctorId, Instant.parse("2025-03-10T09:00:00Z"), 30, null, null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(buildPatient(patientId)));
            when(staffRepository.findById(doctorId)).thenReturn(Optional.of(buildNonDoctor(doctorId)));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOCTOR role");
        }

        @Test
        void durationMinNull_defaultsTo30() {
            UUID patientId = UUID.randomUUID();
            Patient patient = buildPatient(patientId);
            Instant scheduledAt = Instant.parse("2025-03-10T09:00:00Z");

            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, null, scheduledAt, null, null, null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                    .thenAnswer(inv -> {
                        Appointment saved = inv.getArgument(0);
                        saved.setId(UUID.randomUUID());
                        return saved;
                    });
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            service.create(request);

            verify(businessHoursValidator).validate(scheduledAt, 30);
            verify(conflictChecker).checkPatientConflict(patientId, scheduledAt, 30, null);
            verify(appointmentRepository).saveAndFlush(argThat(a -> a.getDurationMin() == 30));
        }

        @Test
        void businessHoursValidatorThrows_propagates() {
            UUID patientId = UUID.randomUUID();
            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, null, Instant.parse("2025-03-10T01:00:00Z"), 30, null, null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(buildPatient(patientId)));
            doThrow(new BusinessHoursException("Ngoài giờ", "OUTSIDE_HOURS"))
                    .when(businessHoursValidator).validate(any(), eq(30));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessHoursException.class);
        }

        @Test
        void conflictCheckerThrows_propagates() {
            UUID patientId = UUID.randomUUID();
            UUID doctorId = UUID.randomUUID();
            Instant scheduledAt = Instant.parse("2025-03-10T09:00:00Z");
            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, doctorId, scheduledAt, 30, null, null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(buildPatient(patientId)));
            when(staffRepository.findById(doctorId)).thenReturn(Optional.of(buildDoctor(doctorId)));
            doThrow(new ConflictException("Trùng lịch", UUID.randomUUID(), "Patient X", Instant.now(), 30))
                    .when(conflictChecker).checkDoctorConflict(eq(doctorId), eq(scheduledAt), eq(30), isNull());

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void auditIsRecorded() {
            UUID patientId = UUID.randomUUID();
            Patient patient = buildPatient(patientId);
            Instant scheduledAt = Instant.parse("2025-03-10T09:00:00Z");
            UUID savedId = UUID.randomUUID();

            AppointmentCreateRequest request = new AppointmentCreateRequest(
                    patientId, null, scheduledAt, 30, null, null);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(appointmentRepository.saveAndFlush(any(Appointment.class)))
                    .thenAnswer(inv -> {
                        Appointment saved = inv.getArgument(0);
                        saved.setId(savedId);
                        return saved;
                    });
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            service.create(request);

            verify(auditService).record(AuditAction.CREATE, "appointment", savedId);
        }
    }

    // ─── update() tests ─────────────────────────────────────────────────────────

    @Nested
    class UpdateTests {

        @Test
        void happyPath_bookedStatus_updatesSuccessfully() {
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.BOOKED);
            UUID patientId = existing.getPatientId();
            UUID doctorId = existing.getDoctorId();
            Instant newTime = Instant.parse("2025-03-11T10:00:00Z");

            AppointmentUpdateRequest request = new AppointmentUpdateRequest(
                    newTime, 60, null, "New reason", "New note");

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));
            when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(buildPatient(patientId)));
            when(staffRepository.findById(doctorId)).thenReturn(Optional.of(buildDoctor(doctorId)));
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            AppointmentResponse result = service.update(appointmentId, request);

            assertThat(result).isNotNull();
            verify(businessHoursValidator).validate(newTime, 60);
            verify(conflictChecker).checkDoctorConflict(doctorId, newTime, 60, appointmentId);
            verify(conflictChecker).checkPatientConflict(patientId, newTime, 60, appointmentId);
        }

        @Test
        void nonEditableStatus_done_throwsNotEditableException() {
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.DONE);

            AppointmentUpdateRequest request = new AppointmentUpdateRequest(
                    null, null, null, "note", null);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.update(appointmentId, request))
                    .isInstanceOf(NotEditableException.class);
        }

        @Test
        void nonEditableStatus_cancelled_throwsNotEditableException() {
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.CANCELLED);

            AppointmentUpdateRequest request = new AppointmentUpdateRequest(
                    null, null, null, "note", null);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.update(appointmentId, request))
                    .isInstanceOf(NotEditableException.class);
        }

        @Test
        void timeChanged_validatesBusinessHoursAndConflicts() {
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.CONFIRMED);
            Instant newTime = Instant.parse("2025-03-12T14:00:00Z");

            AppointmentUpdateRequest request = new AppointmentUpdateRequest(
                    newTime, null, null, null, null);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));
            when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(patientRepository.findById(existing.getPatientId()))
                    .thenReturn(Optional.of(buildPatient(existing.getPatientId())));
            when(staffRepository.findById(existing.getDoctorId()))
                    .thenReturn(Optional.of(buildDoctor(existing.getDoctorId())));
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            service.update(appointmentId, request);

            verify(businessHoursValidator).validate(newTime, existing.getDurationMin());
            verify(conflictChecker).checkDoctorConflict(
                    existing.getDoctorId(), newTime, existing.getDurationMin(), appointmentId);
            verify(conflictChecker).checkPatientConflict(
                    existing.getPatientId(), newTime, existing.getDurationMin(), appointmentId);
        }

        @Test
        void doctorChanged_validatesDoctorExistsAndRoleAndConflict() {
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.BOOKED);
            UUID newDoctorId = UUID.randomUUID();
            Staff newDoctor = buildDoctor(newDoctorId);

            AppointmentUpdateRequest request = new AppointmentUpdateRequest(
                    null, null, newDoctorId, null, null);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));
            when(staffRepository.findById(newDoctorId)).thenReturn(Optional.of(newDoctor));
            when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(patientRepository.findById(existing.getPatientId()))
                    .thenReturn(Optional.of(buildPatient(existing.getPatientId())));
            when(staffRepository.findById(newDoctorId)).thenReturn(Optional.of(newDoctor));
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            service.update(appointmentId, request);

            verify(conflictChecker).checkDoctorConflict(
                    newDoctorId, existing.getScheduledAt(), existing.getDurationMin(), appointmentId);
        }

        @Test
        void appointmentNotFound_throwsNotFoundException() {
            UUID appointmentId = UUID.randomUUID();
            AppointmentUpdateRequest request = new AppointmentUpdateRequest(
                    null, null, null, "note", null);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(appointmentId, request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // ─── updateStatus() tests ────────────────────────────────────────────────────

    @Nested
    class UpdateStatusTests {

        @Test
        void validTransition_success() {
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.BOOKED);
            StatusUpdateRequest request = new StatusUpdateRequest(AppointmentStatus.CONFIRMED);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));
            when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(patientRepository.findById(existing.getPatientId()))
                    .thenReturn(Optional.of(buildPatient(existing.getPatientId())));
            when(staffRepository.findById(existing.getDoctorId()))
                    .thenReturn(Optional.of(buildDoctor(existing.getDoctorId())));
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            UUID staffId = UUID.randomUUID();
            AppointmentResponse result = service.updateStatus(appointmentId, request, staffId, "RECEPTIONIST");

            assertThat(result).isNotNull();
            verify(statusMachine).validateTransition(AppointmentStatus.BOOKED, AppointmentStatus.CONFIRMED);
        }

        @Test
        void invalidTransition_throwsInvalidStatusTransitionException() {
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.BOOKED);
            StatusUpdateRequest request = new StatusUpdateRequest(AppointmentStatus.DONE);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));
            doThrow(new InvalidStatusTransitionException(
                    AppointmentStatus.BOOKED, AppointmentStatus.DONE,
                    java.util.EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)))
                    .when(statusMachine).validateTransition(AppointmentStatus.BOOKED, AppointmentStatus.DONE);

            assertThatThrownBy(() -> service.updateStatus(appointmentId, request, UUID.randomUUID(), "RECEPTIONIST"))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void doctorRole_ownAppointment_success() {
            UUID doctorId = UUID.randomUUID();
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.ARRIVED);
            existing.setDoctorId(doctorId);
            StatusUpdateRequest request = new StatusUpdateRequest(AppointmentStatus.IN_PROGRESS);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));
            when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(patientRepository.findById(existing.getPatientId()))
                    .thenReturn(Optional.of(buildPatient(existing.getPatientId())));
            when(staffRepository.findById(doctorId)).thenReturn(Optional.of(buildDoctor(doctorId)));
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            AppointmentResponse result = service.updateStatus(appointmentId, request, doctorId, "DOCTOR");

            assertThat(result).isNotNull();
            verify(statusMachine).validateTransition(AppointmentStatus.ARRIVED, AppointmentStatus.IN_PROGRESS);
        }

        @Test
        void doctorRole_otherAppointment_throwsAccessDeniedException() {
            UUID doctorId = UUID.randomUUID();
            UUID otherDoctorId = UUID.randomUUID();
            UUID appointmentId = UUID.randomUUID();
            Appointment existing = buildAppointment(appointmentId, AppointmentStatus.BOOKED);
            existing.setDoctorId(otherDoctorId);
            StatusUpdateRequest request = new StatusUpdateRequest(AppointmentStatus.CONFIRMED);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.updateStatus(appointmentId, request, doctorId, "DOCTOR"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void appointmentNotFound_throwsNotFoundException() {
            UUID appointmentId = UUID.randomUUID();
            StatusUpdateRequest request = new StatusUpdateRequest(AppointmentStatus.CONFIRMED);

            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateStatus(appointmentId, request, UUID.randomUUID(), "ADMIN"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // ─── list() tests ────────────────────────────────────────────────────────────

    @Nested
    class ListTests {

        @Test
        void returnsMappedPage() {
            Appointment appt = buildAppointment(UUID.randomUUID(), AppointmentStatus.BOOKED);
            Page<Appointment> page = new PageImpl<>(List.of(appt));
            Pageable pageable = PageRequest.of(0, 20);

            when(appointmentRepository.findWithFilters(any(), any(), any(), any(), eq(pageable)))
                    .thenReturn(page);
            when(patientRepository.findById(appt.getPatientId()))
                    .thenReturn(Optional.of(buildPatient(appt.getPatientId())));
            when(staffRepository.findById(appt.getDoctorId()))
                    .thenReturn(Optional.of(buildDoctor(appt.getDoctorId())));
            when(appointmentMapper.toResponse(any(), any(), any(), any())).thenReturn(dummyResponse());

            Page<AppointmentResponse> result = service.list(null, null, null, pageable, UUID.randomUUID(), "ADMIN");

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        void doctorRole_autoFiltersByDoctorId() {
            UUID doctorStaffId = UUID.randomUUID();
            Pageable pageable = PageRequest.of(0, 20);
            Page<Appointment> emptyPage = new PageImpl<>(List.of());

            when(appointmentRepository.findWithFilters(isNull(), isNull(), eq(doctorStaffId), isNull(), eq(pageable)))
                    .thenReturn(emptyPage);

            Page<AppointmentResponse> result = service.list(null, null, null, pageable, doctorStaffId, "DOCTOR");

            assertThat(result.getContent()).isEmpty();
            verify(appointmentRepository).findWithFilters(isNull(), isNull(), eq(doctorStaffId), isNull(), eq(pageable));
        }

        @Test
        void dateFilter_convertsToInstantRange() {
            LocalDate date = LocalDate.of(2025, 3, 10);
            Pageable pageable = PageRequest.of(0, 20);
            Page<Appointment> emptyPage = new PageImpl<>(List.of());

            when(appointmentRepository.findWithFilters(any(Instant.class), any(Instant.class), isNull(), isNull(), eq(pageable)))
                    .thenReturn(emptyPage);

            service.list(date, null, null, pageable, UUID.randomUUID(), "ADMIN");

            verify(appointmentRepository).findWithFilters(
                    argThat(from -> from != null && from.toString().contains("2025-03-09")),
                    argThat(to -> to != null && to.toString().contains("2025-03-10")),
                    isNull(), isNull(), eq(pageable));
        }
    }
}
