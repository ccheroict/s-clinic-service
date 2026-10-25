package com.sclinic.encounter;

import com.sclinic.access.PatientAccessGuard;
import com.sclinic.appointment.Appointment;
import com.sclinic.appointment.AppointmentRepository;
import com.sclinic.appointment.AppointmentStatus;
import com.sclinic.appointment.AppointmentStatusMachine;
import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.catalogue.CatalogIcd10;
import com.sclinic.catalogue.CatalogIcd10Repository;
import com.sclinic.common.exception.BadRequestException;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.consent.ConsentService;
import com.sclinic.consent.ConsentTypeCode;
import com.sclinic.encounter.dto.EncounterRequests;
import com.sclinic.encounter.dto.EncounterResponse;
import com.sclinic.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writing and reading the encounter record.
 *
 * <p>Drafts only, for now. Signing, locking and amending arrive with the signing
 * work; what is here is the state a record passes through while a doctor is
 * typing it, which is also the only state in which it may change at all.
 */
@Service
@RequiredArgsConstructor
public class EncounterService {

    private static final String ENTITY_TYPE = "encounter";

    private final EncounterRepository repository;
    private final EncounterDiagnosisRepository diagnosisRepository;
    private final ClinicalDataSchemaRepository schemaRepository;
    private final ClinicalDataValidator validator;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentStatusMachine statusMachine;
    private final CatalogIcd10Repository icd10Repository;
    private final PatientAccessGuard accessGuard;
    private final ConsentService consentService;
    private final AuditService auditService;
    private final EncounterProperties properties;
    private final Clock clock;

    // ---------- writing ----------

    /**
     * Opens a record for an examination.
     *
     * <p>Consent to treatment is required first (requirement 11.5). Writing a
     * clinical record is the processing of health data this clinic exists to do,
     * and doing it before the patient has agreed is the case the consent module
     * was built to prevent — so the check belongs here, at the point of
     * processing, not only on a screen that can be skipped.
     */
    @Transactional
    public EncounterResponse create(EncounterRequests.Create request, UUID doctorId, String role) {
        requirePatient(request.patientId());
        accessGuard.requireAccess(request.patientId(), doctorId, role);

        consentService.requireConsent(request.patientId(), ConsentTypeCode.TREATMENT);
        consentService.requireConsent(request.patientId(), ConsentTypeCode.SENSITIVE_DATA);

        Instant now = clock.instant();
        Instant encounteredAt = request.encounteredAt() == null ? now : request.encounteredAt();

        if (encounteredAt.isAfter(now)) {
            // An examination that has not happened yet is an appointment. The two
            // are different records and conflating them would put unperformed
            // work in the medical history.
            throw new BadRequestException("encounteredAt must not be in the future");
        }

        // Checked here as well as by the unique index. The index alone answers a
        // double-clicked button with a 500, which says the server broke when in
        // fact the record the caller wants already exists — and its id is what
        // they need in order to carry on writing it.
        if (request.appointmentId() != null) {
            repository.findByAppointmentIdAndAmendsEncounterIdIsNullAndDeletedAtIsNull(
                            request.appointmentId())
                    .ifPresent(existing -> {
                        throw new DuplicateEncounterException(
                                request.appointmentId(), existing.getId());
                    });
        }

        ClinicalDataSchema schema = currentSchema(request.specialtyCode());
        validator.validate(request.clinicalData(), schema);

        Encounter encounter = new Encounter();
        encounter.setPatientId(request.patientId());
        encounter.setDoctorId(doctorId);
        encounter.setAppointmentId(request.appointmentId());
        encounter.setEncounteredAt(encounteredAt);
        encounter.setReason(request.reason());
        encounter.setStatus(EncounterStatus.DRAFT);
        encounter.setClinicalData(request.clinicalData());
        encounter.setClinicalDataSchemaId(schema.getId());
        encounter.setTreatmentPlan(request.treatmentPlan());
        encounter.setAdvice(request.advice());
        encounter.setFollowUpDate(request.followUpDate());
        encounter.setReferralNote(request.referralNote());
        encounter.setUpdatedAt(now);

        Encounter saved = repository.saveAndFlush(encounter);

        replaceDiagnoses(saved.getId(), request.diagnoses());
        startAppointment(request.appointmentId(), doctorId);

        auditService.record(AuditAction.CREATE, ENTITY_TYPE, saved.getId(),
                AuditDetail.builder()
                        .code("patientId", request.patientId().toString())
                        .code("schemaVersion", String.valueOf(schema.getVersion()))
                        .build());

        return toResponse(saved);
    }

    /**
     * Replaces the content of a draft.
     *
     * @throws EncounterNotEditableException once the record has been signed
     */
    @Transactional
    public EncounterResponse update(UUID id, EncounterRequests.Update request, UUID staffId,
                                    String role) {
        Encounter encounter = find(id);
        accessGuard.requireAccess(encounter.getPatientId(), staffId, role);

        if (!encounter.isEditable()) {
            throw new EncounterNotEditableException(encounter.getStatus());
        }

        Instant now = clock.instant();

        if (request.encounteredAt() != null) {
            if (request.encounteredAt().isAfter(now)) {
                throw new BadRequestException("encounteredAt must not be in the future");
            }
            encounter.setEncounteredAt(request.encounteredAt());
        }

        // Validated against the version this record was opened under, not the
        // version current today. A schema published mid-examination must not
        // invalidate the draft on the screen in front of someone.
        ClinicalDataSchema schema = schemaRepository.findById(encounter.getClinicalDataSchemaId())
                .orElseThrow(() -> new IllegalStateException(
                        "Clinical schema missing for encounter " + id));
        validator.validate(request.clinicalData(), schema);

        encounter.setReason(request.reason());
        encounter.setClinicalData(request.clinicalData());
        encounter.setTreatmentPlan(request.treatmentPlan());
        encounter.setAdvice(request.advice());
        encounter.setFollowUpDate(request.followUpDate());
        encounter.setReferralNote(request.referralNote());
        encounter.setUpdatedAt(now);

        Encounter saved = repository.saveAndFlush(encounter);
        replaceDiagnoses(id, request.diagnoses());

        // Field names only, as everywhere else: the values are the examination
        // findings, and copying them into the audit trail would put a second copy
        // of the clinical record in a table with different retention.
        auditService.record(AuditAction.UPDATE, ENTITY_TYPE, id,
                AuditDetail.builder().changed("clinicalData").build());

        return toResponse(saved);
    }

    // ---------- reading ----------

    @Transactional(readOnly = true)
    public EncounterResponse getById(UUID id, UUID staffId, String role) {
        Encounter encounter = find(id);
        accessGuard.requireAccess(encounter.getPatientId(), staffId, role);

        if (accessGuard.alwaysAudit(role)) {
            auditService.record(AuditAction.VIEW, ENTITY_TYPE, id);
        }

        return toResponse(encounter);
    }

    /** A patient's encounters, newest first. */
    @Transactional(readOnly = true)
    public List<EncounterResponse> listForPatient(UUID patientId, UUID staffId, String role) {
        requirePatient(patientId);
        accessGuard.requireAccess(patientId, staffId, role);

        if (accessGuard.alwaysAudit(role)) {
            auditService.record(AuditAction.VIEW, ENTITY_TYPE, null,
                    AuditDetail.builder()
                            .code("scope", "list")
                            .code("patientId", patientId.toString())
                            .build());
        }

        return repository
                .findByPatientIdAndDeletedAtIsNullOrderByEncounteredAtDesc(patientId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ---------- clinical schemas ----------

    /** The form a new record for this specialty is written against. */
    @Transactional(readOnly = true)
    public ClinicalDataSchema currentSchema(String specialtyCode) {
        String code = specialtyCode == null || specialtyCode.isBlank()
                ? properties.defaultSpecialtyCode()
                : specialtyCode;

        return schemaRepository.findCurrent(code)
                .orElseThrow(() -> new BadRequestException(
                        "Chưa có schema dữ liệu lâm sàng nào được công bố cho chuyên khoa "
                                + code + ". Quản trị viên cần công bố schema trước khi "
                                + "ghi bản ghi khám."));
    }

    // ---------- helpers ----------

    /**
     * Rewrites the diagnosis list from scratch.
     *
     * <p>Delete and re-insert rather than a diff. The list is short, the rows
     * carry no history of their own, and a diff would have to decide what to do
     * about a diagnosis whose primary flag moved — which is the case that a
     * partial unique index on "one primary per encounter" rejects if the order of
     * operations is wrong. Clearing first means it never is.
     */
    private void replaceDiagnoses(UUID encounterId, List<EncounterRequests.DiagnosisEntry> entries) {
        diagnosisRepository.deleteByEncounterId(encounterId);
        diagnosisRepository.flush();

        if (entries == null || entries.isEmpty()) {
            return;
        }

        long primaries = entries.stream().filter(EncounterRequests.DiagnosisEntry::primary).count();
        if (primaries > 1) {
            throw new BadRequestException(
                    "Chỉ được một chẩn đoán chính. Đang có " + primaries + ".");
        }

        List<EncounterDiagnosis> rows = new ArrayList<>(entries.size());

        for (EncounterRequests.DiagnosisEntry entry : entries) {
            CatalogIcd10 code = icd10Repository.findByCode(entry.icd10Code())
                    .orElseThrow(() -> new NotFoundException(
                            "Mã ICD-10 không có trong danh mục: " + entry.icd10Code()));

            EncounterDiagnosis diagnosis = new EncounterDiagnosis();
            diagnosis.setEncounterId(encounterId);
            diagnosis.setIcd10Id(code.getId());
            // Frozen at recording time, so a later catalogue correction does not
            // rewrite what the doctor diagnosed.
            diagnosis.setIcd10Code(code.getCode());
            diagnosis.setIcd10Name(code.getName());
            diagnosis.setPrimary(entry.primary());
            diagnosis.setNote(entry.note());
            rows.add(diagnosis);
        }

        diagnosisRepository.saveAll(rows);
    }

    /**
     * Moves the appointment to IN_PROGRESS. Requirement 13.6.
     *
     * <p>Only when the transition is legal. An appointment already DONE, or
     * cancelled, must not be dragged back: the state machine owns that rule, and
     * a record being written against a cancelled appointment is a thing to notice
     * rather than to tidy up silently.
     */
    private void startAppointment(UUID appointmentId, UUID doctorId) {
        if (appointmentId == null) {
            return;
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException(
                        "Appointment not found: " + appointmentId));

        if (appointment.getStatus() == AppointmentStatus.IN_PROGRESS) {
            return;
        }

        statusMachine.validateTransition(appointment.getStatus(), AppointmentStatus.IN_PROGRESS);
        appointment.setStatus(AppointmentStatus.IN_PROGRESS);
        appointmentRepository.save(appointment);
    }

    private Encounter find(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("Encounter not found: " + id));
    }

    private void requirePatient(UUID patientId) {
        patientRepository.findByIdAndDeletedAtIsNull(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));
    }

    private EncounterResponse toResponse(Encounter encounter) {
        List<EncounterDiagnosis> diagnoses = diagnosisRepository
                .findByEncounterIdOrderByPrimaryDescCreatedAtAsc(encounter.getId());

        // The schema this record names, not the current one. Sent so the client
        // can render the form the record was written against.
        ClinicalDataSchema schema = encounter.getClinicalDataSchemaId() == null
                ? null
                : schemaRepository.findById(encounter.getClinicalDataSchemaId()).orElse(null);

        return EncounterResponse.from(encounter, diagnoses,
                schema == null ? null : schema.getSpecialtyCode(),
                schema == null ? null : schema.getVersion());
    }
}
