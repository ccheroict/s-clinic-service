package com.sclinic.appointment;

import com.sclinic.appointment.exception.ConflictException;
import com.sclinic.patient.Patient;
import com.sclinic.patient.PatientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConflictCheckerTest {

    @Mock
    AppointmentRepository appointmentRepository;

    @Mock
    PatientRepository patientRepository;

    @InjectMocks
    ConflictChecker conflictChecker;

    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final Instant START = Instant.parse("2025-01-15T09:00:00Z");
    private static final int DURATION = 30;

    @Test
    void checkDoctorConflict_noConflict_doesNotThrow() {
        Instant endTime = START.plus(DURATION, ChronoUnit.MINUTES);
        when(appointmentRepository.findDoctorConflicts(eq(DOCTOR_ID), eq(START), eq(endTime), eq(null)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> conflictChecker.checkDoctorConflict(DOCTOR_ID, START, DURATION, null));
    }

    @Test
    void checkDoctorConflict_withConflict_throwsConflictException() {
        Instant endTime = START.plus(DURATION, ChronoUnit.MINUTES);
        UUID conflictPatientId = UUID.randomUUID();

        Appointment conflicting = new Appointment();
        conflicting.setId(UUID.randomUUID());
        conflicting.setPatientId(conflictPatientId);
        conflicting.setScheduledAt(START.minus(15, ChronoUnit.MINUTES));
        conflicting.setDurationMin(30);

        Patient patient = new Patient();
        patient.setId(conflictPatientId);
        patient.setFullName("Nguyen Van B");

        when(appointmentRepository.findDoctorConflicts(eq(DOCTOR_ID), eq(START), eq(endTime), eq(null)))
                .thenReturn(List.of(conflicting));
        when(patientRepository.findById(conflictPatientId)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> conflictChecker.checkDoctorConflict(DOCTOR_ID, START, DURATION, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> {
                    ConflictException ce = (ConflictException) ex;
                    assertThat(ce.getConflictingAppointmentId()).isEqualTo(conflicting.getId());
                    assertThat(ce.getConflictingPatientName()).isEqualTo("Nguyen Van B");
                    assertThat(ce.getConflictingScheduledAt()).isEqualTo(conflicting.getScheduledAt());
                    assertThat(ce.getConflictingDurationMin()).isEqualTo(30);
                });
    }

    @Test
    void checkDoctorConflict_nullDoctorId_doesNotThrow() {
        assertDoesNotThrow(() -> conflictChecker.checkDoctorConflict(null, START, DURATION, null));
    }

    @Test
    void checkDoctorConflict_withExcludeId_passesItToRepository() {
        UUID excludeId = UUID.randomUUID();
        Instant endTime = START.plus(DURATION, ChronoUnit.MINUTES);
        when(appointmentRepository.findDoctorConflicts(eq(DOCTOR_ID), eq(START), eq(endTime), eq(excludeId)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> conflictChecker.checkDoctorConflict(DOCTOR_ID, START, DURATION, excludeId));
    }

    @Test
    void checkPatientConflict_noConflict_doesNotThrow() {
        Instant endTime = START.plus(DURATION, ChronoUnit.MINUTES);
        when(appointmentRepository.findPatientConflicts(eq(PATIENT_ID), eq(START), eq(endTime), eq(null)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> conflictChecker.checkPatientConflict(PATIENT_ID, START, DURATION, null));
    }

    @Test
    void checkPatientConflict_withConflict_throwsConflictException() {
        Instant endTime = START.plus(DURATION, ChronoUnit.MINUTES);

        Appointment conflicting = new Appointment();
        conflicting.setId(UUID.randomUUID());
        conflicting.setPatientId(PATIENT_ID);
        conflicting.setScheduledAt(START.minus(10, ChronoUnit.MINUTES));
        conflicting.setDurationMin(20);

        Patient patient = new Patient();
        patient.setId(PATIENT_ID);
        patient.setFullName("Tran Thi C");

        when(appointmentRepository.findPatientConflicts(eq(PATIENT_ID), eq(START), eq(endTime), eq(null)))
                .thenReturn(List.of(conflicting));
        when(patientRepository.findById(PATIENT_ID)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> conflictChecker.checkPatientConflict(PATIENT_ID, START, DURATION, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> {
                    ConflictException ce = (ConflictException) ex;
                    assertThat(ce.getConflictingAppointmentId()).isEqualTo(conflicting.getId());
                    assertThat(ce.getConflictingPatientName()).isEqualTo("Tran Thi C");
                    assertThat(ce.getConflictingScheduledAt()).isEqualTo(conflicting.getScheduledAt());
                    assertThat(ce.getConflictingDurationMin()).isEqualTo(20);
                });
    }

    @Test
    void checkPatientConflict_patientNotFound_usesIdAsFallback() {
        Instant endTime = START.plus(DURATION, ChronoUnit.MINUTES);

        Appointment conflicting = new Appointment();
        conflicting.setId(UUID.randomUUID());
        conflicting.setPatientId(PATIENT_ID);
        conflicting.setScheduledAt(START);
        conflicting.setDurationMin(30);

        when(appointmentRepository.findPatientConflicts(eq(PATIENT_ID), eq(START), eq(endTime), eq(null)))
                .thenReturn(List.of(conflicting));
        when(patientRepository.findById(PATIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conflictChecker.checkPatientConflict(PATIENT_ID, START, DURATION, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> {
                    ConflictException ce = (ConflictException) ex;
                    assertThat(ce.getConflictingPatientName()).isEqualTo(PATIENT_ID.toString());
                });
    }
}
