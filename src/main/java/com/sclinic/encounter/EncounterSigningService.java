package com.sclinic.encounter;

import com.sclinic.access.PatientAccessGuard;
import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.BadRequestException;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.encounter.dto.EncounterResponse;
import com.sclinic.encounter.dto.EncounterSignatureStatus;
import com.sclinic.signing.DigitalSignatureProvider;
import com.sclinic.signing.SignatureFailedException;
import com.sclinic.signing.SignatureRecord;
import com.sclinic.signing.SignatureRecordRepository;
import com.sclinic.signing.SignatureRequest;
import com.sclinic.signing.SignatureResult;
import com.sclinic.signing.SignatureVerification;
import com.sclinic.signing.SignatureVerificationRequest;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Signing an encounter, correcting a signed one, and checking either.
 *
 * <p>Separate from {@link EncounterService} because the two have opposite jobs.
 * That one lets a doctor change a record; this one makes a record unchangeable.
 * Keeping them in one class would put the code that writes content next to the
 * code that freezes it, which is the arrangement most likely to grow a method
 * that does a little of both.
 *
 * <p>What is <b>not</b> here yet, pending TT 13/2025 and CV 365/TTYQG: who may
 * amend somebody else's record, whether a correction has a time limit, and
 * whether the regulation prescribes a form. The structure requirements 14.6 to
 * 14.8 state outright is implemented; the rules that depend on the wording of
 * those documents are deliberately absent rather than guessed, because a guessed
 * rule in this area looks authoritative and governs whether a correction is
 * legally effective.
 */
@Service
@RequiredArgsConstructor
public class EncounterSigningService {

    static final String ENTITY_TYPE = "encounter";

    private final EncounterRepository repository;
    private final EncounterDiagnosisRepository diagnosisRepository;
    private final SignatureRecordRepository signatureRepository;
    private final DigitalSignatureProvider signatureProvider;
    private final StaffRepository staffRepository;
    private final PatientAccessGuard accessGuard;
    private final AuditService auditService;
    private final Clock clock;

    // ---------- signing ----------

    /**
     * Signs a draft and locks it. Requirement 14.5.
     *
     * <p>The order here is the whole of the safety argument. The hash is computed
     * from the record as it stands, the provider is asked to sign that hash, and
     * only once it has done so is the status changed and the signature row
     * written — all inside one transaction. So there is no state in which a
     * record is marked SIGNED without a signature, and none in which a signature
     * exists for a record still open for editing.
     *
     * <p>Signing blocks while the doctor approves on their phone, which can take
     * tens of seconds. That is remote signing working as intended rather than a
     * timeout.
     */
    @Transactional
    public EncounterResponse sign(UUID encounterId, UUID staffId, String role) {
        Encounter encounter = find(encounterId);
        accessGuard.requireAccess(encounter.getPatientId(), staffId, role);

        if (encounter.getStatus() != EncounterStatus.DRAFT) {
            throw new EncounterNotEditableException(encounter.getStatus());
        }

        Staff signer = staffRepository.findById(staffId)
                .orElseThrow(() -> new NotFoundException("Staff not found: " + staffId));

        requireSignableContent(encounter, signer);

        List<EncounterDiagnosis> diagnoses = diagnosisRepository
                .findByEncounterIdOrderByPrimaryDescCreatedAtAsc(encounterId);
        String hash = EncounterCanonicalForm.hash(encounter, diagnoses);

        // Nothing identifying goes in the text the signer sees on their phone.
        // A push notification is read on a lock screen, often in public, so
        // "Nguyễn Thị A — viêm da cơ địa" there is a disclosure the clinic never
        // intended to make.
        SignatureResult result = signatureProvider.sign(new SignatureRequest(
                staffId,
                signer.getSigningCredentialId(),
                hash,
                EncounterCanonicalForm.HASH_ALGORITHM,
                "Bản ghi khám - S-Clinic"));

        SignatureRecord signature = new SignatureRecord();
        signature.setEntityType(ENTITY_TYPE);
        signature.setEntityId(encounterId);
        signature.setSignerStaffId(staffId);
        signature.setSignedHash(hash);
        signature.setHashAlgorithm(EncounterCanonicalForm.HASH_ALGORITHM);
        signature.setSignatureValue(result.signatureValueBase64());
        signature.setSignatureAlgorithm(result.signatureAlgorithm());
        signature.setCertificateChain(result.certificateChainBase64());
        signature.setCertificateSubject(result.certificateSubject());
        signature.setCertificateSerial(result.certificateSerial());
        signature.setProviderCode(signatureProvider.providerCode());
        signature.setProviderTransactionId(result.providerTransactionId());
        signature.setSignedAt(result.signedAt());
        signatureRepository.saveAndFlush(signature);

        encounter.setStatus(EncounterStatus.SIGNED);
        encounter.setUpdatedAt(clock.instant());
        Encounter saved = repository.saveAndFlush(encounter);

        // If this is a signed amendment, the record it corrects now says so. The
        // original's content is untouched: this is why status is excluded from
        // the canonical form, since otherwise correcting a record properly would
        // break the original's own signature.
        markOriginalAmended(saved);

        auditService.record(AuditAction.SIGN, ENTITY_TYPE, encounterId,
                AuditDetail.builder()
                        .code("provider", signatureProvider.providerCode())
                        .code("hashAlgorithm", EncounterCanonicalForm.HASH_ALGORITHM)
                        .build());

        return toResponse(saved, diagnoses);
    }

    /**
     * Opens a correction to a signed record. Requirement 14.7.
     *
     * <p>A new draft pointing at the original, with a reason that cannot be
     * blank. It has no effect until it is signed: until then the original remains
     * the record in force, which is the point of requiring a signature on the
     * correction rather than on the intention to correct.
     */
    @Transactional
    public EncounterResponse openAmendment(UUID originalId, String reason, UUID staffId,
                                           String role) {
        Encounter original = find(originalId);
        accessGuard.requireAccess(original.getPatientId(), staffId, role);

        if (original.getStatus() == EncounterStatus.DRAFT) {
            // Nothing to amend: a draft is editable, and sending someone down the
            // amendment path for one would leave two records where there should
            // be one.
            throw new BadRequestException(
                    "Bản ghi khám này vẫn là bản nháp nên sửa trực tiếp được, "
                            + "không cần đính chính.");
        }

        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(
                    "Đính chính bản ghi đã ký bắt buộc có lý do.");
        }

        List<EncounterDiagnosis> originalDiagnoses = diagnosisRepository
                .findByEncounterIdOrderByPrimaryDescCreatedAtAsc(originalId);

        // Starts as a copy. A correction is usually a small change to a long
        // record, and making the doctor retype the examination would mean the
        // amendment differed from the original in ways nobody chose.
        Encounter amendment = new Encounter();
        amendment.setPatientId(original.getPatientId());
        amendment.setDoctorId(staffId);
        // The appointment is deliberately not carried over: one visit has one
        // record of what happened at it, and the partial unique index would
        // reject a second row claiming the same appointment anyway.
        amendment.setEncounteredAt(original.getEncounteredAt());
        amendment.setReason(original.getReason());
        amendment.setTreatmentPlan(original.getTreatmentPlan());
        amendment.setAdvice(original.getAdvice());
        amendment.setFollowUpDate(original.getFollowUpDate());
        amendment.setReferralNote(original.getReferralNote());
        amendment.setClinicalData(original.getClinicalData());
        amendment.setClinicalDataSchemaId(original.getClinicalDataSchemaId());
        amendment.setStatus(EncounterStatus.DRAFT);
        amendment.setAmendsEncounterId(originalId);
        amendment.setAmendmentReason(reason.trim());
        amendment.setUpdatedAt(clock.instant());

        Encounter saved = repository.saveAndFlush(amendment);

        List<EncounterDiagnosis> copied = new ArrayList<>(originalDiagnoses.size());
        for (EncounterDiagnosis source : originalDiagnoses) {
            EncounterDiagnosis copy = new EncounterDiagnosis();
            copy.setEncounterId(saved.getId());
            copy.setIcd10Id(source.getIcd10Id());
            // The codes as the original froze them, not as the catalogue reads
            // today: the amendment starts from what was actually signed.
            copy.setIcd10Code(source.getIcd10Code());
            copy.setIcd10Name(source.getIcd10Name());
            copy.setPrimary(source.isPrimary());
            copy.setNote(source.getNote());
            copied.add(copy);
        }
        diagnosisRepository.saveAll(copied);

        auditService.record(AuditAction.CREATE, ENTITY_TYPE, saved.getId(),
                AuditDetail.builder()
                        .code("amends", originalId.toString())
                        .build());

        return toResponse(saved, copied);
    }

    // ---------- reading the chain ----------

    /**
     * The original and every correction to it, oldest first. Requirement 14.8.
     *
     * <p>Starts from the original even when given an amendment's id, so a reader
     * who arrives in the middle of a chain still sees the whole of it. A history
     * that reads differently depending on which link you opened would be worse
     * than no history.
     */
    @Transactional(readOnly = true)
    public List<EncounterResponse> amendmentChain(UUID encounterId, UUID staffId, String role) {
        Encounter encounter = find(encounterId);
        accessGuard.requireAccess(encounter.getPatientId(), staffId, role);

        Encounter root = encounter;
        while (root.getAmendsEncounterId() != null) {
            root = find(root.getAmendsEncounterId());
        }

        List<EncounterResponse> chain = new ArrayList<>();
        chain.add(toResponse(root, diagnosesOf(root.getId())));
        appendAmendments(root.getId(), chain);
        return chain;
    }

    /**
     * Re-checks the signature on a record. Requirement 14.9.
     *
     * <p>Recomputes the hash from the record as it stands now and asks the
     * provider implementation to verify the stored signature against it. The two
     * findings are kept apart: an altered record and a broken signature are
     * different events with different owners.
     */
    @Transactional(readOnly = true)
    public EncounterSignatureStatus verifySignature(UUID encounterId, UUID staffId, String role) {
        Encounter encounter = find(encounterId);
        accessGuard.requireAccess(encounter.getPatientId(), staffId, role);

        SignatureRecord signature = signatureRepository
                .findFirstByEntityTypeAndEntityIdOrderBySignedAtDesc(ENTITY_TYPE, encounterId)
                .orElse(null);

        if (signature == null) {
            return EncounterSignatureStatus.unsigned(encounter.getStatus());
        }

        String recomputed = EncounterCanonicalForm.hash(encounter, diagnosesOf(encounterId));

        SignatureVerification verification = signatureProvider.verify(
                new SignatureVerificationRequest(
                        signature.getSignatureValue(),
                        signature.getSignatureAlgorithm(),
                        signature.getCertificateChain(),
                        signature.getSignedHash(),
                        recomputed));

        return EncounterSignatureStatus.of(encounter.getStatus(), signature, verification);
    }

    // ---------- helpers ----------

    /**
     * Checks the things that must be true before a record can be signed.
     *
     * <p>Requirement 13.2's "exactly one primary diagnosis" is enforced here
     * rather than on every insert. A draft being typed legitimately passes
     * through having none, so the moment to insist is the moment the record stops
     * being a draft — which is also the moment it becomes a legal document.
     */
    private void requireSignableContent(Encounter encounter, Staff signer) {
        if (signer.getSigningCredentialId() == null
                || signer.getSigningCredentialId().isBlank()) {
            throw new SignatureFailedException(
                    "Tài khoản " + signer.getUsername() + " chưa gắn chứng thư số "
                            + "(signing_credential_id). Quản trị viên cần cấu hình "
                            + "trước khi bác sĩ này ký bản ghi khám.");
        }

        if (signer.getPracticeLicenceNo() == null
                || signer.getPracticeLicenceNo().isBlank()) {
            // Requirement 14.3. "Signed by Dr X" means little without the licence
            // that permitted Dr X to sign it, and the record carries that licence
            // precisely so it can be read later without asking anyone.
            throw new SignatureFailedException(
                    "Tài khoản " + signer.getUsername() + " chưa có số giấy phép "
                            + "hành nghề nên không ký được bản ghi khám.");
        }

        long primaries = diagnosisRepository.countByEncounterIdAndPrimaryTrue(encounter.getId());

        if (primaries != 1) {
            throw new BadRequestException(
                    "Bản ghi khám phải có đúng một chẩn đoán chính trước khi ký. "
                            + "Hiện có " + primaries + ".");
        }
    }

    private void markOriginalAmended(Encounter amendment) {
        if (amendment.getAmendsEncounterId() == null) {
            return;
        }

        Encounter original = find(amendment.getAmendsEncounterId());

        if (original.getStatus() == EncounterStatus.SIGNED) {
            original.setStatus(EncounterStatus.AMENDED);
            original.setUpdatedAt(clock.instant());
            repository.saveAndFlush(original);
        }
    }

    private void appendAmendments(UUID encounterId, List<EncounterResponse> chain) {
        for (Encounter amendment : repository
                .findByAmendsEncounterIdOrderByCreatedAtAsc(encounterId)) {
            chain.add(toResponse(amendment, diagnosesOf(amendment.getId())));
            // Recursive, because a correction can itself be corrected and the
            // chain has to read in order however deep it goes.
            appendAmendments(amendment.getId(), chain);
        }
    }

    private List<EncounterDiagnosis> diagnosesOf(UUID encounterId) {
        return diagnosisRepository.findByEncounterIdOrderByPrimaryDescCreatedAtAsc(encounterId);
    }

    private Encounter find(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("Encounter not found: " + id));
    }

    private EncounterResponse toResponse(Encounter encounter,
                                         List<EncounterDiagnosis> diagnoses) {
        return EncounterResponse.from(encounter, diagnoses, null, null);
    }
}
