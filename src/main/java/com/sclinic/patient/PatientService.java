package com.sclinic.patient;

import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.patient.dto.PatientRequest;
import com.sclinic.patient.dto.PatientResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientService {

    private static final String ENTITY_TYPE = "patient";

    private final PatientRepository repository;
    private final PatientMapper mapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<PatientResponse> search(String keyword, Pageable pageable) {
        Page<Patient> page = StringUtils.hasText(keyword)
                ? repository.findByFullNameContainingIgnoreCaseOrPhoneContaining(keyword, keyword, pageable)
                : repository.findAll(pageable);
        return page.map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PatientResponse getById(UUID id) {
        Patient patient = find(id);
        auditService.record(AuditAction.VIEW, ENTITY_TYPE, id);
        return mapper.toResponse(patient);
    }

    @Transactional
    public PatientResponse create(PatientRequest request) {
        Patient patient = mapper.toEntity(request);
        // flush so DB-/Hibernate-generated values (timestamps) are populated in the response
        Patient saved = repository.saveAndFlush(patient);
        auditService.record(AuditAction.CREATE, ENTITY_TYPE, saved.getId());
        return mapper.toResponse(saved);
    }

    @Transactional
    public PatientResponse update(UUID id, PatientRequest request) {
        Patient patient = find(id);
        mapper.updateEntity(request, patient);
        Patient saved = repository.saveAndFlush(patient);
        auditService.record(AuditAction.UPDATE, ENTITY_TYPE, id);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("Patient not found: " + id);
        }
        repository.deleteById(id);
        auditService.record(AuditAction.DELETE, ENTITY_TYPE, id);
    }

    private Patient find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + id));
    }
}
