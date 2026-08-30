package com.sclinic.facility;

import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.facility.dto.FacilityRequest;
import com.sclinic.facility.dto.FacilityResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FacilityServiceTest {

    @Mock
    FacilityRepository repository;

    @Mock
    AuditService auditService;

    @Spy
    FacilityMapper mapper = Mappers.getMapper(FacilityMapper.class);

    @InjectMocks
    FacilityService service;

    private static Facility existing() {
        Facility facility = new Facility();
        facility.setId(UUID.randomUUID());
        facility.setName("Phong kham da lieu S-Clinic");
        facility.setKcbCode("KCB-001");
        facility.setActive(true);
        return facility;
    }

    private static FacilityRequest request(String name, String kcbCode) {
        return new FacilityRequest(name, kcbCode, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Nested
    class GetCurrentTests {

        @Test
        void returnsActiveFacility() {
            Facility facility = existing();
            when(repository.findFirstByActiveTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(facility));

            FacilityResponse response = service.getCurrent();

            assertThat(response.kcbCode()).isEqualTo("KCB-001");
            assertThat(response.name()).isEqualTo("Phong kham da lieu S-Clinic");
            assertThat(response.active()).isTrue();
        }

        @Test
        void throwsWhenNoFacilityConfigured() {
            when(repository.findFirstByActiveTrueOrderByCreatedAtAsc()).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCurrent())
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("No active facility configured");
        }
    }

    @Nested
    class UpdateTests {

        @Test
        void updatesFieldsAndRecordsAudit() {
            Facility facility = existing();
            when(repository.findFirstByActiveTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(facility));
            when(repository.findByKcbCode("KCB-002")).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

            FacilityResponse response = service.update(request("Phong kham moi", "KCB-002"));

            assertThat(response.name()).isEqualTo("Phong kham moi");
            assertThat(response.kcbCode()).isEqualTo("KCB-002");
            verify(auditService).record(eq(AuditAction.UPDATE), eq("facility"),
                    eq(facility.getId()), any());
        }

        @Test
        void recordsChangedFieldNamesWithoutValues() {
            Facility facility = existing();
            when(repository.findFirstByActiveTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(facility));
            when(repository.findByKcbCode("KCB-001")).thenReturn(Optional.of(facility));
            when(repository.saveAndFlush(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

            service.update(request("Ten moi", "KCB-001"));

            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(auditService).record(eq(AuditAction.UPDATE), eq("facility"),
                    eq(facility.getId()), captor.capture());

            Map<String, Object> detail = captor.getValue();
            // Only the field name is recorded; the audit table must not become a
            // second copy of business data.
            assertThat(detail).containsKey("name");
            assertThat(detail.get("name")).isEqualTo("changed");
            assertThat(detail).doesNotContainKey("kcbCode");
            assertThat(detail.values()).doesNotContain("Ten moi", "Phong kham da lieu S-Clinic");
        }

        @Test
        void allowsKeepingOwnKcbCode() {
            Facility facility = existing();
            when(repository.findFirstByActiveTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(facility));
            when(repository.findByKcbCode("KCB-001")).thenReturn(Optional.of(facility));
            when(repository.saveAndFlush(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

            FacilityResponse response = service.update(request("Ten khac", "KCB-001"));

            assertThat(response.kcbCode()).isEqualTo("KCB-001");
        }

        @Test
        void rejectsKcbCodeTakenByAnotherFacility() {
            Facility facility = existing();
            Facility branch = existing();
            branch.setKcbCode("KCB-002");
            when(repository.findFirstByActiveTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(facility));
            when(repository.findByKcbCode("KCB-002")).thenReturn(Optional.of(branch));

            assertThatThrownBy(() -> service.update(request("Ten", "KCB-002")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already used");

            verify(repository, never()).saveAndFlush(any(Facility.class));
            verify(auditService, never()).record(any(), any(), any(), any());
        }

        @Test
        void nullFieldsLeaveExistingValuesUntouched() {
            Facility facility = existing();
            facility.setTaxCode("0101234567");
            facility.setLicenseIssuedAt(LocalDate.of(2024, 1, 15));
            when(repository.findFirstByActiveTrueOrderByCreatedAtAsc()).thenReturn(Optional.of(facility));
            when(repository.findByKcbCode("KCB-001")).thenReturn(Optional.of(facility));
            when(repository.saveAndFlush(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

            FacilityResponse response = service.update(request("Ten moi", "KCB-001"));

            assertThat(response.taxCode()).isEqualTo("0101234567");
            assertThat(response.licenseIssuedAt()).isEqualTo(LocalDate.of(2024, 1, 15));
        }
    }
}
