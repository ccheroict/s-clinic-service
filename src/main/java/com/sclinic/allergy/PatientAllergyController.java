package com.sclinic.allergy;

import com.sclinic.access.PatientAccessGuard;
import com.sclinic.allergy.dto.AllergyRecordRequest;
import com.sclinic.allergy.dto.AllergyResponse;
import com.sclinic.allergy.dto.AllergyRetirementRequest;
import com.sclinic.audit.AuditAction;
import com.sclinic.audit.AuditService;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A patient's allergies.
 *
 * <p>Doctors and admins only, declared once for the whole controller. Everything
 * here is clinical: unlike the patient record, which splits into a clinical and
 * an administrative view, an allergy list has no administrative half to show a
 * receptionist. {@code PatientAccessGuard.requireAccess} would let one through —
 * it returns early for every role except DOCTOR, because withholding clinical
 * fields is {@code viewFor}'s job — so the restriction has to be stated here.
 * Putting it on the class rather than on each method means an endpoint added
 * later is covered by default instead of being open until someone notices.
 *
 * <p>On top of the role check, the guard still applies: a doctor reaches the
 * allergies of patients they treat, or of one they hold a break-glass grant over.
 */
@RestController
@RequestMapping("/api/patients/{patientId}/allergies")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
public class PatientAllergyController {

    private final PatientAllergyService service;
    private final PatientAccessGuard accessGuard;
    private final StaffRepository staffRepository;
    private final AuditService auditService;

    /**
     * @param includeRetired the full history rather than the live list. Off by
     *                       default: a prescribing screen wants what applies now,
     *                       and showing retired allergies alongside live ones is
     *                       how a retired one gets acted on
     */
    @GetMapping
    public List<AllergyResponse> list(@PathVariable UUID patientId,
                                      @RequestParam(defaultValue = "false") boolean includeRetired,
                                      Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        // Requirement 8.7, the same rule PatientService applies to the record
        // itself: an admin reading clinical data is rarely clinically necessary,
        // so it is recorded, while a doctor reading their own patient is the
        // system working and recording it would bury the entries that matter.
        if (accessGuard.alwaysAudit(staff.getRole())) {
            auditService.record(AuditAction.VIEW, "patient_allergy", patientId);
        }

        return includeRetired ? service.listAll(patientId) : service.listActive(patientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AllergyResponse record(@PathVariable UUID patientId,
                                  @Valid @RequestBody AllergyRecordRequest request,
                                  Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        return service.record(patientId, request, staff.getId());
    }

    /**
     * Retires a record that turned out to be wrong. Not a delete: that the
     * allergy was believed, and prescribing decisions were made around it, is
     * part of the record.
     */
    @PostMapping("/{allergyId}/retire")
    public AllergyResponse retire(@PathVariable UUID patientId,
                                  @PathVariable UUID allergyId,
                                  @Valid @RequestBody AllergyRetirementRequest request,
                                  Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        return service.retire(patientId, allergyId, request, staff.getId());
    }

    /**
     * Confirms that the migrated free-text allergies have been read.
     *
     * <p>Only a clinician can clear this, and nothing clears it automatically:
     * the flag exists because the migration moved sentences across without
     * interpreting them, so the only thing that can resolve it is a person having
     * looked.
     */
    @PostMapping("/mark-reviewed")
    public ResponseEntity<Void> markReviewed(@PathVariable UUID patientId,
                                             Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        service.markReviewed(patientId, staff.getId());
        return ResponseEntity.noContent().build();
    }

    private Staff resolveStaff(Authentication authentication) {
        String username = authentication.getName();

        return staffRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new UsernameNotFoundException("Staff not found: " + username));
    }
}
