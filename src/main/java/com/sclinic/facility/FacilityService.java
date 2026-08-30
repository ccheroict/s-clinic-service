package com.sclinic.facility;

import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.facility.dto.FacilityRequest;
import com.sclinic.facility.dto.FacilityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class FacilityService {

    private static final String ENTITY_TYPE = "facility";

    private final FacilityRepository repository;
    private final FacilityMapper mapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public FacilityResponse getCurrent() {
        return mapper.toResponse(currentEntity());
    }

    /**
     * The facility record other modules build on (prescription codes, e-invoice
     * config, printed document headers).
     */
    @Transactional(readOnly = true)
    public Facility currentEntity() {
        return repository.findFirstByActiveTrueOrderByCreatedAtAsc()
                .orElseThrow(() -> new NotFoundException(
                        "No active facility configured. Set sclinic.facility.* and restart."));
    }

    @Transactional
    public FacilityResponse update(FacilityRequest request) {
        Facility facility = currentEntity();

        // Reject a code already taken by a different facility row (branch scenario).
        repository.findByKcbCode(request.kcbCode())
                .filter(other -> !other.getId().equals(facility.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException(
                            "kcbCode already used by another facility: " + request.kcbCode());
                });

        Map<String, Object> changedFields = collectChangedFieldNames(facility, request);

        mapper.updateEntity(request, facility);
        Facility saved = repository.saveAndFlush(facility);

        auditService.record(AuditAction.UPDATE, ENTITY_TYPE, saved.getId(), changedFields);

        return mapper.toResponse(saved);
    }

    /**
     * Records which fields changed, without the values. Facility data is not
     * patient data, but keeping the audit detail value-free matches the policy
     * applied across the system so the audit table never becomes a second store
     * of business data.
     */
    private Map<String, Object> collectChangedFieldNames(Facility current, FacilityRequest request) {
        AuditDetail detail = AuditDetail.builder();
        putIfChanged(detail, "name", current.getName(), request.name());
        putIfChanged(detail, "kcbCode", current.getKcbCode(), request.kcbCode());
        putIfChanged(detail, "interopCode", current.getInteropCode(), request.interopCode());
        putIfChanged(detail, "taxCode", current.getTaxCode(), request.taxCode());
        putIfChanged(detail, "address", current.getAddress(), request.address());
        putIfChanged(detail, "phone", current.getPhone(), request.phone());
        putIfChanged(detail, "email", current.getEmail(), request.email());
        putIfChanged(detail, "licenseNo", current.getLicenseNo(), request.licenseNo());
        putIfChanged(detail, "licenseIssuedAt", current.getLicenseIssuedAt(), request.licenseIssuedAt());
        putIfChanged(detail, "technicalDirector", current.getTechnicalDirector(), request.technicalDirector());
        putIfChanged(detail, "einvoiceTemplateCode",
                current.getEinvoiceTemplateCode(), request.einvoiceTemplateCode());
        putIfChanged(detail, "einvoiceSerial", current.getEinvoiceSerial(), request.einvoiceSerial());
        putIfChanged(detail, "einvoiceUnitCode", current.getEinvoiceUnitCode(), request.einvoiceUnitCode());
        return detail.build();
    }

    private void putIfChanged(AuditDetail detail, String field, Object oldValue, Object newValue) {
        // Null in the request means "leave unchanged" (partial-safe update).
        if (newValue != null && !newValue.equals(oldValue)) {
            detail.changed(field);
        }
    }
}
