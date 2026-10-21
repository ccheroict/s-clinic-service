package com.sclinic.patient;

import com.sclinic.audit.AuditAction;
import com.sclinic.common.exception.BadRequestException;
import com.sclinic.audit.AuditService;
import com.sclinic.access.BreakGlassGrantRepository;
import com.sclinic.access.PatientAccessGuard;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.crypto.BlindIndex;
import com.sclinic.patient.dto.PatientDeleteRequest;
import com.sclinic.patient.dto.PatientRequest;
import com.sclinic.patient.dto.PatientResponse;
import com.sclinic.retention.RetentionCalculator;
import com.sclinic.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");

    private PatientRepository repository;
    private AuditService auditService;
    private RetentionCalculator retentionCalculator;
    private StaffRepository staffRepository;
    private BlindIndex blindIndex;
    private PatientAccessGuard accessGuard;
    private BreakGlassGrantRepository breakGlassRepository;
    private PatientService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(PatientRepository.class);
        auditService = Mockito.mock(AuditService.class);
        retentionCalculator = Mockito.mock(RetentionCalculator.class);
        staffRepository = Mockito.mock(StaffRepository.class);
        blindIndex = Mockito.mock(BlindIndex.class);
        accessGuard = Mockito.mock(PatientAccessGuard.class);
        breakGlassRepository = Mockito.mock(BreakGlassGrantRepository.class);

        // This class covers the record lifecycle; the access matrix has its own
        // test class, so here the guard is permissive and reports the full view.
        when(accessGuard.viewFor(any())).thenReturn(PatientAccessGuard.View.FULL);

        service = new PatientService(
                repository,
                Mappers.getMapper(PatientMapper.class),
                auditService,
                retentionCalculator,
                staffRepository,
                blindIndex,
                accessGuard,
                breakGlassRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PatientRequest request() {
        return new PatientRequest(
                "P001", "Nguyen Van A", null, "M",
                "0900000000", null, null, null, null, null, null, null, null);
    }

    private Patient existing() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFullName("Nguyen Van A");
        patient.setCreatedAt(Instant.parse("2020-03-15T02:00:00Z"));
        return patient;
    }

    private void saveReturnsArgument() {
        when(repository.saveAndFlush(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void noRetentionPolicy() {
        when(retentionCalculator.retentionUntil(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void create_mapsRequestAndPersists() {
        saveReturnsArgument();
        noRetentionPolicy();

        PatientResponse response = service.create(request());

        assertThat(response.fullName()).isEqualTo("Nguyen Van A");
        assertThat(response.sex()).isEqualTo("M");
        assertThat(response.phone()).isEqualTo("0900000000");
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id, UUID.randomUUID(), "ADMIN"))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Not found is decided before access is. Answering "forbidden" for a patient
     * that does not exist would let a caller map which patient ids are real by
     * reading which error comes back.
     */
    @Test
    void getById_doesNotRevealWhetherAnInaccessiblePatientExists() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id, UUID.randomUUID(), "DOCTOR"))
                .isInstanceOf(NotFoundException.class);
        verify(accessGuard, never()).requireAccess(any(), any(), any());
    }

    @Nested
    class Withdrawal {

        @Test
        void marksTheRecordInsteadOfRemovingIt() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();
            noRetentionPolicy();

            service.delete(patient.getId(), new PatientDeleteRequest("Trung lap voi BN khac"));

            assertThat(patient.getDeletedAt()).isEqualTo(NOW);
            assertThat(patient.getDeleteReason()).isEqualTo("Trung lap voi BN khac");
            // The one thing that must never happen to a medical record.
            verify(repository, never()).delete(any());
            verify(repository, never()).deleteById(any());
        }

        @Test
        void trimsTheReasonSoWhitespaceIsNotAcceptedAsOne() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();
            noRetentionPolicy();

            service.delete(patient.getId(), new PatientDeleteRequest("  nhap trung  "));

            assertThat(patient.getDeleteReason()).isEqualTo("nhap trung");
        }

        @Test
        void recomputesTheRetentionDateFromTheWithdrawal() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();
            when(retentionCalculator.retentionUntil(eq("patient"), any(), any(), any()))
                    .thenReturn(Optional.of(LocalDate.of(2040, 1, 1)));

            service.delete(patient.getId(), new PatientDeleteRequest("het dieu tri"));

            assertThat(patient.getRetentionUntil()).isEqualTo(LocalDate.of(2040, 1, 1));
        }

        @Test
        void leavesTheRetentionDateUnsetWhenNoPolicyIsConfigured() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();
            noRetentionPolicy();

            service.delete(patient.getId(), new PatientDeleteRequest("het dieu tri"));

            // Null is what keeps the record out of every destruction query.
            assertThat(patient.getRetentionUntil()).isNull();
        }

        @Test
        void recordsTheWithdrawalInTheAuditTrailWithoutCopyingTheReason() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();
            noRetentionPolicy();

            service.delete(patient.getId(),
                    new PatientDeleteRequest("Benh nhan yeu cau xoa du lieu"));

            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditService).record(eq(AuditAction.DELETE), eq("patient"),
                    eq(patient.getId()), captor.capture());

            // The reason lives on the patient row. Copying it into the trail would
            // put a sentence about the patient's circumstances in a second place
            // with different protections, and one that outlives the record.
            assertThat(captor.getValue().values())
                    .doesNotContain("Benh nhan yeu cau xoa du lieu");
        }

        @Test
        void refusesToWithdrawARecordThatIsAlreadyWithdrawn() {
            UUID id = UUID.randomUUID();
            when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(id, new PatientDeleteRequest("lan hai")))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class DateOfDeath {

        private PatientRequest withDeceasedOn(LocalDate deceasedOn) {
            return new PatientRequest(
                    "P001", "Nguyen Van A", null, "M",
                    "0900000000", null, null, null, null, null, null, null, deceasedOn);
        }

        @Test
        void recomputesTheRetentionDateWhenRecorded() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();
            when(retentionCalculator.retentionUntil(eq("patient"), any(), any(),
                    eq(LocalDate.of(2026, 1, 10))))
                    .thenReturn(Optional.of(LocalDate.of(2046, 1, 10)));

            service.update(patient.getId(), withDeceasedOn(LocalDate.of(2026, 1, 10)));

            assertThat(patient.getRetentionUntil()).isEqualTo(LocalDate.of(2046, 1, 10));
        }

        @Test
        void doesNotRecomputeWhenTheDateHasNotChanged() {
            Patient patient = existing();
            patient.setDeceasedOn(LocalDate.of(2026, 1, 10));
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();

            service.update(patient.getId(), withDeceasedOn(LocalDate.of(2026, 1, 10)));

            verify(retentionCalculator, never()).retentionUntil(any(), any(), any(), any());
        }

        /**
         * A future date would start the retention clock later than the record
         * actually closed, which pushes destruction further out rather than
         * nearer -- but it is still a data-entry error, and one that quietly
         * changes a legally meaningful date.
         */
        @Test
        void rejectsADateInTheFuture() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));

            assertThatThrownBy(() -> service.update(patient.getId(),
                    withDeceasedOn(LocalDate.of(2026, 12, 31))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("future");
        }

        @Test
        void acceptsToday() {
            Patient patient = existing();
            when(repository.findByIdAndDeletedAtIsNull(patient.getId()))
                    .thenReturn(Optional.of(patient));
            saveReturnsArgument();
            noRetentionPolicy();

            service.update(patient.getId(), withDeceasedOn(LocalDate.of(2026, 8, 29)));

            assertThat(patient.getDeceasedOn()).isEqualTo(LocalDate.of(2026, 8, 29));
        }
    }
}
