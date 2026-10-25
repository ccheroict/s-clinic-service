package com.sclinic.encounter;

import com.sclinic.encounter.dto.EncounterRequests;
import com.sclinic.encounter.dto.EncounterResponse;
import com.sclinic.encounter.dto.EncounterSignatureStatus;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The encounter record.
 *
 * <p>Doctors and admins, declared once for the class. An encounter is clinical
 * from end to end — there is no administrative half of an examination to show a
 * receptionist — and {@code PatientAccessGuard.requireAccess} returns early for
 * every role except DOCTOR, so relying on the guard alone would let a
 * receptionist read examination findings. The same trap the allergy endpoints
 * fell into.
 */
@RestController
@RequestMapping("/api/encounters")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
public class EncounterController {

    private final EncounterService service;
    private final EncounterSigningService signingService;
    private final StaffRepository staffRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterResponse create(@Valid @RequestBody EncounterRequests.Create request,
                                    Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return service.create(request, staff.getId(), staff.getRole());
    }

    @GetMapping("/{id}")
    public EncounterResponse getById(@PathVariable UUID id, Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return service.getById(id, staff.getId(), staff.getRole());
    }

    /**
     * Replaces the content of a draft.
     *
     * <p>A PUT, because the body is the whole record. A PATCH would have to give
     * null a meaning, and whichever meaning it got would make one of "clear this
     * field" or "leave this field alone" impossible to say.
     */
    @PutMapping("/{id}")
    public EncounterResponse update(@PathVariable UUID id,
                                    @Valid @RequestBody EncounterRequests.Update request,
                                    Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return service.update(id, request, staff.getId(), staff.getRole());
    }

    @GetMapping
    public List<EncounterResponse> listForPatient(@RequestParam UUID patientId,
                                                  Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return service.listForPatient(patientId, staff.getId(), staff.getRole());
    }

    /**
     * The clinical form a new record would be written against.
     *
     * <p>The client renders the form from this rather than from anything compiled
     * into it, which is what makes publishing a new schema version change the
     * screen without a release.
     */
    @GetMapping("/clinical-schema")
    public Map<String, Object> currentSchema(
            @RequestParam(required = false) String specialtyCode) {
        ClinicalDataSchema schema = service.currentSchema(specialtyCode);

        return Map.of(
                "id", schema.getId(),
                "specialtyCode", schema.getSpecialtyCode(),
                "version", schema.getVersion(),
                "title", schema.getTitle(),
                "schema", schema.getSchema());
    }

    // ---------- signing, correcting, checking ----------

    /**
     * Signs the record and locks it. Requirement 14.5.
     *
     * <p>Slow by nature: it waits for the doctor to approve on their phone, which
     * takes as long as it takes them to pick it up. A client must not treat tens
     * of seconds here as a failure, and must not retry — a second call would ask
     * for a second approval of a record that is already signed.
     */
    @PostMapping("/{id}/sign")
    public EncounterResponse sign(@PathVariable UUID id, Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return signingService.sign(id, staff.getId(), staff.getRole());
    }

    /**
     * Opens a correction to a signed record. Requirement 14.7.
     *
     * <p>Returns a new draft that starts as a copy of the original. It has no
     * effect until it is signed in its own right; until then the original is
     * still the record in force.
     */
    @PostMapping("/{id}/amendments")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterResponse openAmendment(@PathVariable UUID id,
                                           @Valid @RequestBody AmendmentRequest request,
                                           Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return signingService.openAmendment(id, request.reason(), staff.getId(),
                staff.getRole());
    }

    /**
     * The original and every correction, oldest first. Requirement 14.8.
     *
     * <p>Answers the same way whichever link in the chain is asked about, so a
     * reader who arrives in the middle still sees the whole history.
     */
    @GetMapping("/{id}/chain")
    public List<EncounterResponse> amendmentChain(@PathVariable UUID id,
                                                  Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return signingService.amendmentChain(id, staff.getId(), staff.getRole());
    }

    /** Whether the signature still holds and the record still matches it. */
    @GetMapping("/{id}/signature")
    public EncounterSignatureStatus signatureStatus(@PathVariable UUID id,
                                                    Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return signingService.verifySignature(id, staff.getId(), staff.getRole());
    }

    /** @param reason why the signed record needs correcting. Must not be blank. */
    public record AmendmentRequest(
            @jakarta.validation.constraints.NotBlank(
                    message = "Đính chính bản ghi đã ký bắt buộc có lý do")
            @jakarta.validation.constraints.Size(max = 2000)
            String reason
    ) {
    }

    private Staff resolveStaff(Authentication authentication) {
        String username = authentication.getName();

        return staffRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new UsernameNotFoundException("Staff not found: " + username));
    }
}
