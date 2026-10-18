package com.sclinic.consent;

import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.consent.dto.ConsentGrantRequest;
import com.sclinic.consent.dto.ConsentRecordResponse;
import com.sclinic.consent.dto.ConsentStatusResponse;
import com.sclinic.consent.dto.ConsentVersionRequest;
import com.sclinic.consent.dto.ConsentVersionResponse;
import com.sclinic.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Recording consent, and refusing the actions that need one and do not have it.
 *
 * <p>The method other modules call is {@link #requireConsent}. Everything else
 * here exists to make that check mean something: wording that is published
 * rather than drafted, a record that points at the wording the patient actually
 * read, and a withdrawal that takes effect the moment it is recorded.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private static final String RECORD_ENTITY = "consent_record";
    private static final String VERSION_ENTITY = "consent_type_version";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ConsentTypeRepository types;
    private final ConsentTypeVersionRepository versions;
    private final ConsentRecordRepository records;
    private final PatientRepository patientRepository;
    private final AuditService auditService;
    private final Clock clock;

    // ---------- the check other modules call ----------

    /**
     * Refuses the caller unless this patient has a live consent of this type.
     *
     * <p>Requirement 11.5 and 11.6. Reads the current state rather than anything
     * cached: a withdrawal has to bite on the next action, and a consent decision
     * held in a session or a field would keep letting things through until
     * something refreshed it.
     *
     * @throws ConsentRequiredException      if there is no live consent, naming
     *                                       the type that is missing
     * @throws ConsentNotConfiguredException if the type has no published wording,
     *                                       which is an administrator's problem
     *                                       and not the patient's
     */
    @Transactional(readOnly = true)
    public void requireConsent(UUID patientId, ConsentTypeCode type) {
        ConsentType consentType = requireType(type);

        // Checked first, and separately: a type with no wording can have no
        // records, so without this every action gated on it would report the
        // patient as having refused. Those two failures need opposite fixes.
        if (versions.findCurrent(consentType.getId(), today()).isEmpty()) {
            throw new ConsentNotConfiguredException(type);
        }

        if (records.findLive(patientId, consentType.getId()).isEmpty()) {
            throw new ConsentRequiredException(type, consentType.getName());
        }
    }

    /**
     * Whether a live consent exists, without throwing.
     *
     * <p>For callers deciding what to offer rather than whether to proceed — a UI
     * that hides the photograph button, say. Anything that acts on patient data
     * uses {@link #requireConsent}: a boolean that a caller can forget to check
     * is a check that will be forgotten.
     */
    @Transactional(readOnly = true)
    public boolean hasConsent(UUID patientId, ConsentTypeCode type) {
        return types.findByCode(type.name())
                .flatMap(t -> records.findLive(patientId, t.getId()))
                .isPresent();
    }

    /**
     * Whether appointment reminders may be sent to this patient.
     *
     * <p>Requirement 11.7. Named for what it decides rather than left as a
     * {@code hasConsent} call at the call site, so that the thing which must
     * stop when consent is withdrawn is a single, findable function. There is no
     * reminder sender yet; when there is, this is what it asks, and it must ask
     * per send rather than building a recipient list once.
     */
    @Transactional(readOnly = true)
    public boolean mayReceiveReminders(UUID patientId) {
        return hasConsent(patientId, ConsentTypeCode.APPOINTMENT_REMINDER);
    }

    // ---------- collecting consent ----------

    /**
     * Records a patient's consent against the wording currently in force.
     *
     * <p>If a live consent of the same type already exists it is marked
     * superseded, not withdrawn: the patient agreed again, probably to new
     * wording, and recording that as a withdrawal would put a false event in the
     * trail. The old record stays as proof of what applied until now.
     */
    @Transactional
    public ConsentRecordResponse grant(UUID patientId, ConsentGrantRequest request,
                                       UUID staffId) {
        requirePatient(patientId);
        ConsentType type = requireType(request.type());

        ConsentTypeVersion version = versions.findCurrent(type.getId(), today())
                .orElseThrow(() -> new ConsentNotConfiguredException(request.type()));

        Instant now = clock.instant();
        Instant grantedAt = request.grantedAt() == null ? now : request.grantedAt();

        if (grantedAt.isAfter(now)) {
            throw new IllegalArgumentException("grantedAt must not be in the future");
        }

        ConsentRecord record = new ConsentRecord();
        record.setPatientId(patientId);
        record.setConsentTypeId(type.getId());
        record.setVersionId(version.getId());

        // Copied from the version now, so a later correction to the wording
        // cannot change what this patient agreed to.
        record.setPurpose(version.getPurpose());
        record.setGrantedAt(grantedAt);
        record.setCollectionMethod(request.collectionMethod());
        record.setEvidence(request.evidence());
        record.setCollectedBy(staffId);

        Optional<ConsentRecord> previous = records.findLive(patientId, type.getId());
        ConsentRecord saved;

        if (previous.isPresent()) {
            // Released before the new row is inserted: the partial unique index
            // allows one live record per patient per type, and inserting first
            // would hit it.
            ConsentRecord old = previous.get();
            old.setSupersededAt(now);
            records.saveAndFlush(old);

            saved = records.saveAndFlush(record);

            old.setSupersededBy(saved.getId());
            records.saveAndFlush(old);
        } else {
            saved = records.saveAndFlush(record);
        }

        auditService.record(AuditAction.CREATE, RECORD_ENTITY, saved.getId(),
                AuditDetail.builder()
                        .code("patientId", patientId.toString())
                        .code("consentType", type.getCode())
                        .code("version", String.valueOf(version.getVersion()))
                        .code("method", request.collectionMethod().name())
                        .build());

        return toResponse(saved, type, version);
    }

    /**
     * Records a withdrawal. Requirement 11.6.
     *
     * <p>Takes effect immediately, because {@link #requireConsent} reads the
     * record rather than a copy of its conclusion. No reason is required: the
     * right to withdraw is unconditional, and a mandatory justification field
     * would make the system an obstacle to a right it exists to serve.
     */
    @Transactional
    public ConsentRecordResponse withdraw(UUID patientId, ConsentTypeCode typeCode,
                                          String note, UUID staffId) {
        requirePatient(patientId);
        ConsentType type = requireType(typeCode);

        ConsentRecord record = records.findLive(patientId, type.getId())
                .orElseThrow(() -> new NotFoundException(
                        "Bệnh nhân không có sự đồng ý còn hiệu lực loại " + typeCode.name()
                                + " để rút."));

        record.setWithdrawnAt(clock.instant());
        record.setWithdrawnBy(staffId);
        record.setWithdrawalNote(note);

        ConsentRecord saved = records.saveAndFlush(record);

        auditService.record(AuditAction.UPDATE, RECORD_ENTITY, saved.getId(),
                AuditDetail.builder()
                        .code("patientId", patientId.toString())
                        .code("consentType", type.getCode())
                        .code("withdrawn", "true")
                        .build());

        ConsentTypeVersion version = versions.findById(saved.getVersionId()).orElseThrow();
        return toResponse(saved, type, version);
    }

    // ---------- what the clinic has and has not collected ----------

    /**
     * Every consent type with this patient's current standing on it.
     *
     * <p>Includes the types with nothing recorded, which is the point: a list of
     * what has been collected cannot show what is missing, and what is missing is
     * what the person at the desk needs to know.
     */
    @Transactional(readOnly = true)
    public List<ConsentStatusResponse> statusFor(UUID patientId) {
        requirePatient(patientId);

        List<ConsentStatusResponse> statuses = new ArrayList<>();

        for (ConsentType type : types.findByActiveTrueOrderByCode()) {
            Optional<ConsentTypeVersion> current = versions.findCurrent(type.getId(), today());
            Optional<ConsentRecord> live = records.findLive(patientId, type.getId());

            // The last record of this type, live or not, so a withdrawn consent
            // reads as withdrawn rather than as never given.
            List<ConsentRecord> history = records
                    .findByPatientIdAndConsentTypeIdOrderByGrantedAtDesc(
                            patientId, type.getId());

            statuses.add(ConsentStatusResponse.of(type, current.orElse(null),
                    live.orElse(null), history.isEmpty() ? null : history.get(0)));
        }

        return statuses;
    }

    @Transactional(readOnly = true)
    public List<ConsentRecordResponse> history(UUID patientId) {
        requirePatient(patientId);

        return records.findByPatientIdOrderByGrantedAtDesc(patientId).stream()
                .map(record -> {
                    ConsentType type = types.findById(record.getConsentTypeId()).orElseThrow();
                    ConsentTypeVersion version =
                            versions.findById(record.getVersionId()).orElseThrow();
                    return toResponse(record, type, version);
                })
                .toList();
    }

    // ---------- wording ----------

    /**
     * Publishes a new version of a type's wording.
     *
     * <p>Published on creation rather than drafted and published later: there is
     * no editing workflow here, and a draft state nothing can leave is a state
     * that only ever confuses. Version numbers are assigned by the service, not
     * supplied, so two administrators cannot both create version 3.
     *
     * @throws IllegalArgumentException if a sensitive type's wording carries no
     *                                  notice of sensitivity (requirement 11.4)
     */
    @Transactional
    public ConsentVersionResponse publishVersion(ConsentTypeCode typeCode,
                                                 ConsentVersionRequest request) {
        ConsentType type = requireType(typeCode);

        // The database enforces this too, by trigger. Checked here as well so the
        // caller gets an explanation naming the requirement rather than a SQL
        // error, and enforced there as well because versions also arrive from
        // migrations and from scripts run by hand.
        if (type.isSensitive()
                && (request.sensitivityNotice() == null
                    || request.sensitivityNotice().isBlank())) {
            throw new IllegalArgumentException(
                    "Loại đồng ý " + typeCode.name() + " liên quan dữ liệu cá nhân nhạy cảm, "
                            + "nên nội dung phải nêu rõ với bệnh nhân rằng dữ liệu được xử lý "
                            + "là dữ liệu cá nhân nhạy cảm (yêu cầu 11.4, NĐ 13/2023). "
                            + "Thiếu sensitivityNotice.");
        }

        ConsentTypeVersion version = new ConsentTypeVersion();
        version.setTypeId(type.getId());
        version.setVersion(versions.highestVersion(type.getId()) + 1);
        version.setContent(request.content());
        version.setSensitivityNotice(request.sensitivityNotice());
        version.setPurpose(request.purpose());
        version.setEffectiveFrom(
                request.effectiveFrom() == null ? today() : request.effectiveFrom());
        version.setPublishedAt(clock.instant());

        ConsentTypeVersion saved = versions.saveAndFlush(version);

        auditService.record(AuditAction.CREATE, VERSION_ENTITY, saved.getId(),
                AuditDetail.builder()
                        .code("consentType", type.getCode())
                        .code("version", String.valueOf(saved.getVersion()))
                        .build());

        return ConsentVersionResponse.from(saved, type);
    }

    /** The wording currently in force for a type, for showing to a patient. */
    @Transactional(readOnly = true)
    public ConsentVersionResponse currentVersion(ConsentTypeCode typeCode) {
        ConsentType type = requireType(typeCode);

        return versions.findCurrent(type.getId(), today())
                .map(version -> ConsentVersionResponse.from(version, type))
                .orElseThrow(() -> new ConsentNotConfiguredException(typeCode));
    }

    // ---------- helpers ----------

    private ConsentType requireType(ConsentTypeCode code) {
        return types.findByCode(code.name())
                .orElseThrow(() -> new IllegalStateException(
                        "Consent type missing from the database: " + code.name()
                                + ". V14 seeds all seven; this means the row was deleted."));
    }

    private void requirePatient(UUID patientId) {
        patientRepository.findByIdAndDeletedAtIsNull(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), CLINIC_ZONE);
    }

    private ConsentRecordResponse toResponse(ConsentRecord record, ConsentType type,
                                             ConsentTypeVersion version) {
        return ConsentRecordResponse.from(record, type, version);
    }
}
