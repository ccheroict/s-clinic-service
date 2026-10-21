package com.sclinic.allergy;

import com.sclinic.allergy.dto.AllergyRecordRequest;
import com.sclinic.common.exception.BadRequestException;
import com.sclinic.allergy.dto.AllergyResponse;
import com.sclinic.allergy.dto.AllergyRetirementRequest;
import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.catalogue.CatalogAllergen;
import com.sclinic.catalogue.CatalogAllergenRepository;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.patient.Patient;
import com.sclinic.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Recording and retiring patient allergies.
 */
@Service
@RequiredArgsConstructor
public class PatientAllergyService {

    private static final String ENTITY_TYPE = "patient_allergy";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final PatientAllergyRepository repository;
    private final CatalogAllergenRepository allergenRepository;
    private final PatientRepository patientRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<AllergyResponse> listActive(UUID patientId) {
        requirePatient(patientId);

        return toResponses(repository.findActiveByPatient(patientId));
    }

    /** Including retired records, for the full history. */
    @Transactional(readOnly = true)
    public List<AllergyResponse> listAll(UUID patientId) {
        requirePatient(patientId);

        return toResponses(repository.findAllByPatient(patientId));
    }

    /**
     * Records an allergy.
     *
     * @throws DuplicateAllergyException if a live record already names this
     *                                   substance for this patient
     */
    @Transactional
    public AllergyResponse record(UUID patientId, AllergyRecordRequest request, UUID staffId) {
        requirePatient(patientId);
        CatalogAllergen allergen = allergenRepository.findByCode(request.allergenCode())
                .orElseThrow(() -> new NotFoundException(
                        "Allergen not found: " + request.allergenCode()));

        // Checked here as well as by the unique index, so the caller gets an
        // explanation naming the substance rather than a constraint violation.
        repository.findByPatientIdAndAllergenIdAndActiveTrue(patientId, allergen.getId())
                .ifPresent(existing -> {
                    throw new DuplicateAllergyException(allergen.getName());
                });

        rejectFutureDate(request.recordedOn());

        PatientAllergy allergy = new PatientAllergy();
        allergy.setPatientId(patientId);
        allergy.setAllergenId(allergen.getId());
        allergy.setReaction(request.reaction());
        allergy.setSeverity(request.severity());
        allergy.setCertainty(request.certainty() == null
                ? AllergyCertainty.REPORTED : request.certainty());
        allergy.setRecordedOn(request.recordedOn() == null ? today() : request.recordedOn());
        allergy.setRecordedBy(staffId);
        allergy.setNote(request.note());

        PatientAllergy saved = repository.saveAndFlush(allergy);

        // The substance and severity are recorded in the trail: both are codes
        // from a fixed set, neither is free text about the patient, and knowing
        // that a SEVERE allergy was added is the point of auditing this at all.
        auditService.record(AuditAction.CREATE, ENTITY_TYPE, saved.getId(),
                AuditDetail.builder()
                        .code("patientId", patientId.toString())
                        .code("allergen", allergen.getCode())
                        .code("severity", request.severity().name())
                        .build());

        return toResponse(saved);
    }

    /**
     * Retires a record that turned out to be wrong.
     *
     * <p>Not a delete. That the allergy was believed, and prescribing decisions
     * were made around it, is part of the record; and retiring rather than
     * deleting is what lets the same substance be recorded again later without
     * the unique index objecting.
     */
    @Transactional
    public AllergyResponse retire(UUID patientId, UUID allergyId,
                                  AllergyRetirementRequest request, UUID staffId) {
        PatientAllergy allergy = repository.findById(allergyId)
                .filter(a -> a.getPatientId().equals(patientId))
                .orElseThrow(() -> new NotFoundException("Allergy record not found: " + allergyId));

        if (!allergy.isActive()) {
            throw new BadRequestException("This allergy record is already retired");
        }

        allergy.setActive(false);
        allergy.setInactivatedAt(clock.instant());
        allergy.setInactivatedBy(staffId);
        allergy.setInactivationReason(request.reason().trim());

        PatientAllergy saved = repository.saveAndFlush(allergy);

        auditService.record(AuditAction.UPDATE, ENTITY_TYPE, allergyId,
                AuditDetail.builder()
                        .code("patientId", patientId.toString())
                        .code("retired", "true")
                        .build());

        return toResponse(saved);
    }

    /**
     * Marks a patient's migrated free-text allergies as reviewed.
     *
     * <p>The flag exists because V13 moved sentences into a note without
     * interpreting them. Clearing it is a clinician saying they have read the
     * note and recorded whatever coded allergies it implied — which is why only
     * this action clears it, and nothing does so automatically.
     */
    @Transactional
    public void markReviewed(UUID patientId, UUID staffId) {
        Patient patient = requirePatient(patientId);

        if (!patient.isAllergyReviewRequired()) {
            return;
        }

        patient.setAllergyReviewRequired(false);
        patientRepository.saveAndFlush(patient);

        auditService.record(AuditAction.UPDATE, "patient", patientId,
                AuditDetail.builder().changed("allergyReviewRequired").build());
    }

    /** Whether this patient has any allergy that must stop a prescription. */
    @Transactional(readOnly = true)
    public boolean hasSevereAllergy(UUID patientId) {
        return repository.existsByPatientIdAndActiveTrueAndSeverity(
                patientId, AllergySeverity.SEVERE);
    }

    private Patient requirePatient(UUID patientId) {
        return patientRepository.findByIdAndDeletedAtIsNull(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));
    }

    private void rejectFutureDate(LocalDate recordedOn) {
        if (recordedOn != null && recordedOn.isAfter(today())) {
            throw new BadRequestException("recordedOn must not be in the future");
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), CLINIC_ZONE);
    }

    /**
     * Builds responses for a list, resolving every allergen in one query.
     *
     * <p>Not {@code map(this::toResponse)}: that is one select per row, and the
     * allergy list is read on every chart open, so the cost lands on the hottest
     * read in the clinical path.
     */
    private List<AllergyResponse> toResponses(List<PatientAllergy> allergies) {
        if (allergies.isEmpty()) {
            return List.of();
        }

        Map<UUID, CatalogAllergen> allergens = allergenRepository
                .findAllById(allergies.stream().map(PatientAllergy::getAllergenId).toList())
                .stream()
                .collect(Collectors.toMap(CatalogAllergen::getId, Function.identity()));

        return allergies.stream()
                .map(allergy -> AllergyResponse.from(allergy, requireAllergen(allergens, allergy)))
                .toList();
    }

    private AllergyResponse toResponse(PatientAllergy allergy) {
        CatalogAllergen allergen = allergenRepository.findById(allergy.getAllergenId())
                .orElseThrow(() -> missingAllergen(allergy.getAllergenId()));

        return AllergyResponse.from(allergy, allergen);
    }

    private CatalogAllergen requireAllergen(Map<UUID, CatalogAllergen> allergens,
                                            PatientAllergy allergy) {
        CatalogAllergen allergen = allergens.get(allergy.getAllergenId());

        if (allergen == null) {
            // A foreign key makes this unreachable. Thrown rather than skipped
            // because dropping the row would hide an allergy from a prescriber.
            throw missingAllergen(allergy.getAllergenId());
        }

        return allergen;
    }

    private IllegalStateException missingAllergen(UUID allergenId) {
        return new IllegalStateException("Allergen missing from the catalogue: " + allergenId);
    }
}
