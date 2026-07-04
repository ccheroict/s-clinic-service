package com.sclinic.patient;

import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.patient.dto.PatientRequest;
import com.sclinic.patient.dto.PatientResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock
    PatientRepository repository;

    @Mock
    AuditService auditService;

    @Spy
    PatientMapper mapper = Mappers.getMapper(PatientMapper.class);

    @InjectMocks
    PatientService service;

    @Test
    void create_mapsRequestAndPersists() {
        PatientRequest request = new PatientRequest(
                "P001", "Nguyen Van A", null, "M",
                "0900000000", null, null, null, null, null, null, null);
        when(repository.saveAndFlush(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse response = service.create(request);

        assertThat(response.fullName()).isEqualTo("Nguyen Van A");
        assertThat(response.sex()).isEqualTo("M");
        assertThat(response.phone()).isEqualTo("0900000000");
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(NotFoundException.class);
    }
}
