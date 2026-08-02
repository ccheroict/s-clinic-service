package com.sclinic.appointment;

import com.sclinic.appointment.exception.ConflictException;
import com.sclinic.patient.Patient;
import com.sclinic.patient.PatientRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for ConflictChecker.
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4
 *
 * Testing approach: mock AppointmentRepository to simulate overlap detection at the service layer.
 * Generate random time intervals and verify ConflictChecker behavior matches overlap semantics.
 */
class ConflictCheckerPropertyTest {

    private static final Instant BASE_TIME = Instant.parse("2025-06-01T00:00:00Z");

    // --- Property 3: Doctor interval overlap detection ---

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 3: Doctor interval overlap detection")
    void doctorOverlappingIntervals_shouldThrowConflictException(
            @ForAll("startOffsetMinutes") int startA,
            @ForAll("durationMinutes") int durationA,
            @ForAll("startOffsetMinutes") int startB,
            @ForAll("durationMinutes") int durationB
    ) {
        Instant startTimeA = BASE_TIME.plus(startA, ChronoUnit.MINUTES);
        Instant endTimeA = startTimeA.plus(durationA, ChronoUnit.MINUTES);
        Instant startTimeB = BASE_TIME.plus(startB, ChronoUnit.MINUTES);
        Instant endTimeB = startTimeB.plus(durationB, ChronoUnit.MINUTES);

        boolean overlaps = startTimeA.isBefore(endTimeB) && startTimeB.isBefore(endTimeA);
        // Adjacent intervals (A.end == B.start or B.end == A.start) are NOT conflicts
        boolean adjacent = endTimeA.equals(startTimeB) || endTimeB.equals(startTimeA);

        boolean isConflict = overlaps && !adjacent;

        // Setup mocks
        AppointmentRepository repo = Mockito.mock(AppointmentRepository.class);
        PatientRepository patientRepo = Mockito.mock(PatientRepository.class);
        ConflictChecker checker = new ConflictChecker(repo, patientRepo);

        UUID doctorId = UUID.randomUUID();
        UUID excludeId = null;
        Instant endTimeForQuery = startTimeB.plus(durationB, ChronoUnit.MINUTES);

        if (isConflict) {
            // Repository would return the conflicting appointment
            Appointment existing = new Appointment();
            existing.setId(UUID.randomUUID());
            existing.setPatientId(UUID.randomUUID());
            existing.setScheduledAt(startTimeA);
            existing.setDurationMin(durationA);
            existing.setStatus(AppointmentStatus.BOOKED);

            Patient patient = new Patient();
            patient.setId(existing.getPatientId());
            patient.setFullName("Test Patient");

            when(repo.findDoctorConflicts(eq(doctorId), eq(startTimeB), eq(endTimeForQuery), eq(excludeId)))
                    .thenReturn(List.of(existing));
            when(patientRepo.findById(existing.getPatientId())).thenReturn(Optional.of(patient));

            // Should throw ConflictException with correct details
            assertThatThrownBy(() -> checker.checkDoctorConflict(doctorId, startTimeB, durationB, excludeId))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> {
                        ConflictException ce = (ConflictException) ex;
                        assertThat(ce.getConflictingAppointmentId()).isEqualTo(existing.getId());
                        assertThat(ce.getConflictingPatientName()).isEqualTo("Test Patient");
                        assertThat(ce.getConflictingScheduledAt()).isEqualTo(startTimeA);
                        assertThat(ce.getConflictingDurationMin()).isEqualTo(durationA);
                    });
        } else {
            // No conflict: repository returns empty list
            when(repo.findDoctorConflicts(eq(doctorId), eq(startTimeB), eq(endTimeForQuery), eq(excludeId)))
                    .thenReturn(Collections.emptyList());

            assertDoesNotThrow(() -> checker.checkDoctorConflict(doctorId, startTimeB, durationB, excludeId));
        }
    }

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 3: Doctor interval overlap detection")
    void doctorAdjacentIntervals_shouldNotConflict(
            @ForAll("startOffsetMinutes") int startA,
            @ForAll("durationMinutes") int durationA
    ) {
        // Adjacent: interval B starts exactly when A ends
        Instant startTimeA = BASE_TIME.plus(startA, ChronoUnit.MINUTES);
        Instant endTimeA = startTimeA.plus(durationA, ChronoUnit.MINUTES);
        Instant startTimeB = endTimeA; // B starts exactly at A's end
        int durationB = 30;
        Instant endTimeB = startTimeB.plus(durationB, ChronoUnit.MINUTES);

        // Verify they are adjacent (not overlapping)
        // A.start < B.end = true (A starts before B ends)
        // B.start < A.end = false because B.start == A.end (not strictly less than)
        // So overlap condition fails → no conflict

        AppointmentRepository repo = Mockito.mock(AppointmentRepository.class);
        PatientRepository patientRepo = Mockito.mock(PatientRepository.class);
        ConflictChecker checker = new ConflictChecker(repo, patientRepo);

        UUID doctorId = UUID.randomUUID();

        // Repository returns empty for adjacent intervals (as it should per the SQL query logic)
        when(repo.findDoctorConflicts(eq(doctorId), eq(startTimeB), eq(endTimeB), eq(null)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> checker.checkDoctorConflict(doctorId, startTimeB, durationB, null));
    }

    // --- Property 4: Patient interval overlap detection ---

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 4: Patient interval overlap detection")
    void patientOverlappingIntervals_shouldThrowConflictException(
            @ForAll("startOffsetMinutes") int startA,
            @ForAll("durationMinutes") int durationA,
            @ForAll("startOffsetMinutes") int startB,
            @ForAll("durationMinutes") int durationB
    ) {
        Instant startTimeA = BASE_TIME.plus(startA, ChronoUnit.MINUTES);
        Instant endTimeA = startTimeA.plus(durationA, ChronoUnit.MINUTES);
        Instant startTimeB = BASE_TIME.plus(startB, ChronoUnit.MINUTES);
        Instant endTimeB = startTimeB.plus(durationB, ChronoUnit.MINUTES);

        boolean overlaps = startTimeA.isBefore(endTimeB) && startTimeB.isBefore(endTimeA);
        boolean adjacent = endTimeA.equals(startTimeB) || endTimeB.equals(startTimeA);
        boolean isConflict = overlaps && !adjacent;

        AppointmentRepository repo = Mockito.mock(AppointmentRepository.class);
        PatientRepository patientRepo = Mockito.mock(PatientRepository.class);
        ConflictChecker checker = new ConflictChecker(repo, patientRepo);

        UUID patientId = UUID.randomUUID();
        UUID excludeId = null;
        Instant endTimeForQuery = startTimeB.plus(durationB, ChronoUnit.MINUTES);

        if (isConflict) {
            Appointment existing = new Appointment();
            existing.setId(UUID.randomUUID());
            existing.setPatientId(patientId);
            existing.setScheduledAt(startTimeA);
            existing.setDurationMin(durationA);
            existing.setStatus(AppointmentStatus.CONFIRMED);

            Patient patient = new Patient();
            patient.setId(patientId);
            patient.setFullName("Patient Test Name");

            when(repo.findPatientConflicts(eq(patientId), eq(startTimeB), eq(endTimeForQuery), eq(excludeId)))
                    .thenReturn(List.of(existing));
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));

            assertThatThrownBy(() -> checker.checkPatientConflict(patientId, startTimeB, durationB, excludeId))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(ex -> {
                        ConflictException ce = (ConflictException) ex;
                        assertThat(ce.getConflictingAppointmentId()).isEqualTo(existing.getId());
                        assertThat(ce.getConflictingPatientName()).isEqualTo("Patient Test Name");
                        assertThat(ce.getConflictingScheduledAt()).isEqualTo(startTimeA);
                        assertThat(ce.getConflictingDurationMin()).isEqualTo(durationA);
                    });
        } else {
            when(repo.findPatientConflicts(eq(patientId), eq(startTimeB), eq(endTimeForQuery), eq(excludeId)))
                    .thenReturn(Collections.emptyList());

            assertDoesNotThrow(() -> checker.checkPatientConflict(patientId, startTimeB, durationB, excludeId));
        }
    }

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 4: Patient interval overlap detection")
    void patientAdjacentIntervals_shouldNotConflict(
            @ForAll("startOffsetMinutes") int startA,
            @ForAll("durationMinutes") int durationA
    ) {
        Instant startTimeA = BASE_TIME.plus(startA, ChronoUnit.MINUTES);
        Instant endTimeA = startTimeA.plus(durationA, ChronoUnit.MINUTES);
        Instant startTimeB = endTimeA; // Adjacent: B starts exactly when A ends
        int durationB = 30;
        Instant endTimeB = startTimeB.plus(durationB, ChronoUnit.MINUTES);

        AppointmentRepository repo = Mockito.mock(AppointmentRepository.class);
        PatientRepository patientRepo = Mockito.mock(PatientRepository.class);
        ConflictChecker checker = new ConflictChecker(repo, patientRepo);

        UUID patientId = UUID.randomUUID();

        when(repo.findPatientConflicts(eq(patientId), eq(startTimeB), eq(endTimeB), eq(null)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> checker.checkPatientConflict(patientId, startTimeB, durationB, null));
    }

    // --- Property 5: Update conflict check excludes self ---

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 5: Update conflict check excludes self")
    void updateConflictCheck_excludesSelf_doctorConflict(
            @ForAll("startOffsetMinutes") int startOffset,
            @ForAll("durationMinutes") int durationMin
    ) {
        // When updating an appointment to its own current time, the conflict check
        // should exclude the appointment itself (excludeId = own ID).
        // This means the repository is called with excludeId matching the appointment's own ID,
        // and since self is excluded, no conflict should be returned.

        AppointmentRepository repo = Mockito.mock(AppointmentRepository.class);
        PatientRepository patientRepo = Mockito.mock(PatientRepository.class);
        ConflictChecker checker = new ConflictChecker(repo, patientRepo);

        UUID doctorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID(); // The appointment being updated
        Instant start = BASE_TIME.plus(startOffset, ChronoUnit.MINUTES);
        Instant end = start.plus(durationMin, ChronoUnit.MINUTES);

        // When excludeId is the appointment's own ID, the repo should return empty
        // (the appointment itself is excluded from the comparison set)
        when(repo.findDoctorConflicts(eq(doctorId), eq(start), eq(end), eq(appointmentId)))
                .thenReturn(Collections.emptyList());

        // Updating to own time should always pass when excludeId is provided
        assertDoesNotThrow(() -> checker.checkDoctorConflict(doctorId, start, durationMin, appointmentId));
    }

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 5: Update conflict check excludes self")
    void updateConflictCheck_excludesSelf_patientConflict(
            @ForAll("startOffsetMinutes") int startOffset,
            @ForAll("durationMinutes") int durationMin
    ) {
        AppointmentRepository repo = Mockito.mock(AppointmentRepository.class);
        PatientRepository patientRepo = Mockito.mock(PatientRepository.class);
        ConflictChecker checker = new ConflictChecker(repo, patientRepo);

        UUID patientId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        Instant start = BASE_TIME.plus(startOffset, ChronoUnit.MINUTES);
        Instant end = start.plus(durationMin, ChronoUnit.MINUTES);

        // Self-exclusion: repo returns empty when checking with own ID as excludeId
        when(repo.findPatientConflicts(eq(patientId), eq(start), eq(end), eq(appointmentId)))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> checker.checkPatientConflict(patientId, start, durationMin, appointmentId));
    }

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 5: Update conflict check excludes self")
    void updateConflictCheck_excludeIdPassedToRepository(
            @ForAll("startOffsetMinutes") int startOffset,
            @ForAll("durationMinutes") int durationMin
    ) {
        // Verify that the excludeId parameter is correctly passed through to the repository.
        // This ensures the ConflictChecker properly delegates self-exclusion to the query layer.

        AppointmentRepository repo = Mockito.mock(AppointmentRepository.class);
        PatientRepository patientRepo = Mockito.mock(PatientRepository.class);
        ConflictChecker checker = new ConflictChecker(repo, patientRepo);

        UUID doctorId = UUID.randomUUID();
        UUID excludeId = UUID.randomUUID();
        Instant start = BASE_TIME.plus(startOffset, ChronoUnit.MINUTES);
        Instant end = start.plus(durationMin, ChronoUnit.MINUTES);

        when(repo.findDoctorConflicts(eq(doctorId), eq(start), eq(end), eq(excludeId)))
                .thenReturn(Collections.emptyList());

        checker.checkDoctorConflict(doctorId, start, durationMin, excludeId);

        // Verify the exact excludeId was passed to the repository
        Mockito.verify(repo).findDoctorConflicts(eq(doctorId), eq(start), eq(end), eq(excludeId));
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<Integer> startOffsetMinutes() {
        // Offsets from 0 to 1440 minutes (24 hours) to create varied intervals
        return Arbitraries.integers().between(0, 1440);
    }

    @Provide
    Arbitrary<Integer> durationMinutes() {
        // Duration from 5 to 240 minutes (as per business rules)
        return Arbitraries.integers().between(5, 240);
    }
}
