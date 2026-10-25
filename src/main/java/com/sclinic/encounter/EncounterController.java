package com.sclinic.encounter;

import com.sclinic.encounter.dto.EncounterRequests;
import com.sclinic.encounter.dto.EncounterResponse;
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

    private Staff resolveStaff(Authentication authentication) {
        String username = authentication.getName();

        return staffRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new UsernameNotFoundException("Staff not found: " + username));
    }
}
