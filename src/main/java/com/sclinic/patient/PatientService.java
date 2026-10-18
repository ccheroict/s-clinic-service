package com.sclinic.patient;

import com.sclinic.access.BreakGlassGrantRepository;
import com.sclinic.access.PatientAccessGuard;
import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditDetail;
import com.sclinic.audit.AuditService;
import com.sclinic.common.exception.NotFoundException;
import com.sclinic.crypto.BlindIndex;
import com.sclinic.patient.dto.PatientDeleteRequest;
import com.sclinic.patient.dto.PatientRequest;
import com.sclinic.patient.dto.PatientResponse;
import com.sclinic.retention.RetentionCalculator;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientService {

    private static final String ENTITY_TYPE = "patient";

    /**
     * Retention dates are calendar dates, so turning an instant into one needs a
     * zone. The clinic's own zone is the right one: a record withdrawn late on
     * the 31st was withdrawn on the 31st locally, and a retention period counted
     * from the 1st because UTC had rolled over would be a day wrong in the
     * direction of destroying things sooner.
     */
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final PatientRepository repository;
    private final PatientMapper mapper;
    private final AuditService auditService;
    private final RetentionCalculator retentionCalculator;
    private final StaffRepository staffRepository;
    private final BlindIndex blindIndex;
    private final PatientAccessGuard accessGuard;
    private final BreakGlassGrantRepository breakGlassRepository;
    private final Clock clock;

    /**
     * Lists patients the caller is entitled to see.
     *
     * <p>A doctor gets only the patients they treat, plus any they hold a
     * break-glass grant over. Receptionists and admins get everyone, though a
     * receptionist's rows carry no clinical fields.
     */
    @Transactional(readOnly = true)
    public Page<?> search(String keyword, Pageable pageable, UUID staffId, String role) {
        String term = StringUtils.hasText(keyword) ? keyword : null;

        Page<Patient> page;
        if (accessGuard.isDoctor(role)) {
            Collection<UUID> granted = grantedPatientIds(staffId);
            page = term != null
                    ? repository.searchActiveForDoctor(term, staffId, granted, pageable)
                    : repository.findActiveForDoctor(staffId, granted, pageable);
        } else if (term != null) {
            page = repository.searchActive(term, pageable);
        } else {
            page = repository.findByDeletedAtIsNull(pageable);
        }

        return accessGuard.viewFor(role) == PatientAccessGuard.View.ADMINISTRATIVE
                ? page.map(mapper::toAdministrativeResponse)
                : page.map(mapper::toResponse);
    }

    /**
     * @throws com.sclinic.access.PatientAccessDeniedException if a doctor has no
     *         treating relationship with this patient and no live grant
     */
    @Transactional(readOnly = true)
    public Object getById(UUID id, UUID staffId, String role) {
        Patient patient = find(id);
        accessGuard.requireAccess(id, staffId, role);

        // A doctor reading their own patient is the system working, and recording
        // every such read would bury the entries that matter. An admin reading a
        // patient record is rarely clinically necessary, so requirement 8.7 asks
        // for those specifically.
        if (accessGuard.alwaysAudit(role) || hasBreakGlassOnly(id, staffId, role)) {
            auditService.record(AuditAction.VIEW, ENTITY_TYPE, id);
        }

        return accessGuard.viewFor(role) == PatientAccessGuard.View.ADMINISTRATIVE
                ? mapper.toAdministrativeResponse(patient)
                : mapper.toResponse(patient);
    }

    /** A read reached only through a grant is worth recording on its own. */
    private boolean hasBreakGlassOnly(UUID patientId, UUID staffId, String role) {
        return accessGuard.isDoctor(role)
                && !accessGuard.hasTreatingRelationship(patientId, staffId);
    }

    /**
     * Patients the doctor holds a live grant over.
     *
     * <p>Never empty: an empty collection in an SQL {@code IN} list is a portability
     * hazard, and getting it wrong here means a doctor silently sees no patients
     * at all. The nil UUID can never be a patient id, so it is an inert filler.
     */
    private Collection<UUID> grantedPatientIds(UUID staffId) {
        List<UUID> granted = breakGlassRepository
                .findPatientIdsWithActiveGrant(staffId, clock.instant());
        return granted.isEmpty() ? List.of(new UUID(0, 0)) : granted;
    }

    @Transactional
    public PatientResponse create(PatientRequest request) {
        Patient patient = mapper.toEntity(request);
        patient.setNationalIdHash(blindIndex.of(patient.getNationalId()));
        // flush so DB-/Hibernate-generated values (timestamps) are populated in the response
        Patient saved = repository.saveAndFlush(patient);

        // An active record gets a retention date too, so the field is never empty
        // merely because the record has not been withdrawn yet.
        applyRetention(saved);
        saved = repository.saveAndFlush(saved);

        auditService.record(AuditAction.CREATE, ENTITY_TYPE, saved.getId());
        return mapper.toResponse(saved);
    }

    @Transactional
    public PatientResponse update(UUID id, PatientRequest request) {
        Patient patient = find(id);
        LocalDate deceasedBefore = patient.getDeceasedOn();

        mapper.updateEntity(request, patient);
        rejectFutureDateOfDeath(patient.getDeceasedOn());

        // The encrypted column cannot be searched, so the lookup index has to be
        // rewritten whenever the number behind it changes.
        patient.setNationalIdHash(blindIndex.of(patient.getNationalId()));

        // A newly recorded date of death starts a different retention clock where
        // the policy defines one, so the date has to be recomputed rather than
        // left at whatever the ordinary rule produced.
        if (!Objects.equals(deceasedBefore, patient.getDeceasedOn())) {
            applyRetention(patient);
        }

        Patient saved = repository.saveAndFlush(patient);
        auditService.record(AuditAction.UPDATE, ENTITY_TYPE, id);
        return mapper.toResponse(saved);
    }

    /**
     * Withdraws a record from use. The row is never removed.
     *
     * <p>A clinic must keep medical records for a statutory minimum, so there is
     * no code path that deletes one; what this does is mark it withdrawn, record
     * who did it and why, and recompute the retention date from the withdrawal.
     * The reason is mandatory, and the database enforces that independently.
     */
    @Transactional
    public void delete(UUID id, PatientDeleteRequest request) {
        Patient patient = find(id);

        patient.setDeletedAt(clock.instant());
        patient.setDeletedBy(currentStaffId());
        patient.setDeleteReason(request.reason().trim());
        applyRetention(patient);

        repository.saveAndFlush(patient);

        // The reason is the audit-worthy fact here and is recorded on the patient
        // row itself; the trail keeps only that a withdrawal happened, so a reason
        // mentioning the patient's circumstances is not copied into a second place.
        auditService.record(AuditAction.DELETE, ENTITY_TYPE, id,
                AuditDetail.builder().changed("deletedAt").build());
    }

    /**
     * Records eligible for destruction: withdrawn, past their retention date and
     * not on legal hold.
     *
     * <p>Destruction itself is not implemented here. This exists so the rule can
     * be stated and tested now, in particular that a legal hold and a missing
     * retention date both keep a record out of the list.
     */
    @Transactional(readOnly = true)
    public List<UUID> findEligibleForDestruction() {
        return repository
                .findByDeletedAtIsNotNullAndLegalHoldFalseAndRetentionUntilLessThan(today())
                .stream()
                .map(Patient::getId)
                .toList();
    }

    /**
     * Sets the retention date from the configured policy, or leaves it null when
     * no policy exists.
     *
     * <p>Leaving it null is the deliberate behaviour: with no policy, no record
     * can be judged past its retention period, so an unconfigured system keeps
     * everything rather than applying a guessed period.
     */
    private void applyRetention(Patient patient) {
        LocalDate createdOn = toLocalDate(patient.getCreatedAt());
        LocalDate withdrawnOn = toLocalDate(patient.getDeletedAt());

        retentionCalculator
                .retentionUntil(ENTITY_TYPE, createdOn, withdrawnOn, patient.getDeceasedOn())
                .ifPresent(patient::setRetentionUntil);
    }

    private void rejectFutureDateOfDeath(LocalDate deceasedOn) {
        if (deceasedOn != null && deceasedOn.isAfter(today())) {
            throw new IllegalArgumentException("deceasedOn must not be in the future");
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), CLINIC_ZONE);
    }

    private LocalDate toLocalDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, CLINIC_ZONE);
    }

    private UUID currentStaffId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return staffRepository.findByUsernameAndActiveTrue(auth.getName())
                .map(Staff::getId)
                .orElse(null);
    }

    /** Withdrawn records are not reachable through the business API. */
    private Patient find(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + id));
    }
}
