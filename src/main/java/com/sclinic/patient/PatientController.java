package com.sclinic.patient;

import com.sclinic.access.BreakGlassGrant;
import com.sclinic.access.BreakGlassService;
import com.sclinic.access.dto.BreakGlassRequest;
import com.sclinic.access.dto.BreakGlassResponse;
import com.sclinic.patient.dto.PatientDeleteRequest;
import com.sclinic.patient.dto.PatientRequest;
import com.sclinic.patient.dto.PatientResponse;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService service;
    private final BreakGlassService breakGlassService;
    private final StaffRepository staffRepository;

    /**
     * Patients the caller may see. A doctor gets the patients they treat; a
     * receptionist gets everyone without the clinical fields.
     *
     * <p>The response type varies by role, which is why it is not declared as
     * {@code Page<PatientResponse>}: a receptionist's rows are a different shape,
     * not the same shape with holes in it.
     */
    @GetMapping
    public Page<?> list(@RequestParam(required = false) String q,
                        Pageable pageable,
                        Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return service.search(q, pageable, staff.getId(), staff.getRole());
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable UUID id, Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        return service.getById(id, staff.getId(), staff.getRole());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponse create(@Valid @RequestBody PatientRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public PatientResponse update(@PathVariable UUID id, @Valid @RequestBody PatientRequest request) {
        return service.update(id, request);
    }

    /**
     * Withdraws a patient record from use. Admin only, and a reason is required.
     *
     * <p>This does not delete anything. A clinic must keep medical records for a
     * statutory minimum period, so the record is marked withdrawn and disappears
     * from the business API while the row and its history remain. There is no
     * endpoint anywhere that removes a patient record permanently.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @Valid @RequestBody PatientDeleteRequest request) {
        service.delete(id, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Grants the calling clinician temporary access to a patient they do not
     * treat, in exchange for a stated reason.
     *
     * <p>Doctors only. A receptionist already reaches every patient's
     * administrative record and has no clinical access to escalate to, and an
     * admin already reaches everything, so for either of them this would be a way
     * to generate noise rather than access.
     */
    @PostMapping("/{id}/break-glass")
    @PreAuthorize("hasRole('DOCTOR')")
    public BreakGlassResponse breakGlass(@PathVariable UUID id,
                                         @Valid @RequestBody BreakGlassRequest request,
                                         Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        BreakGlassGrant grant = breakGlassService.grant(
                id, staff.getId(), staff.getUsername(), request.reason());

        return new BreakGlassResponse(grant.getId(), id, grant.getExpiresAt(),
                "Temporary access granted and recorded. Administrators have been notified.");
    }

    /**
     * Resolve the currently authenticated Staff entity from Spring Security's
     * Authentication.
     */
    private Staff resolveStaff(Authentication authentication) {
        String username = authentication.getName();

        return staffRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new UsernameNotFoundException("Staff not found: " + username));
    }
}
