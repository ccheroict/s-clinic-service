package com.sclinic.consent;

import com.sclinic.access.PatientAccessGuard;
import com.sclinic.consent.dto.ConsentGrantRequest;
import com.sclinic.consent.dto.ConsentRecordResponse;
import com.sclinic.consent.dto.ConsentStatusResponse;
import com.sclinic.consent.dto.ConsentWithdrawRequest;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Collecting and withdrawing a patient's consent.
 *
 * <p>Open to every role, deliberately. Consent is collected at reception, which
 * makes it a receptionist's job; and a withdrawal must be recordable by whoever
 * the patient happens to speak to. Refusing a withdrawal because the wrong person
 * is at the desk would make the system an obstacle to a right it exists to serve,
 * and the realistic outcome is not that the patient comes back later — it is that
 * the withdrawal goes unrecorded.
 *
 * <p>What is recorded in every case is who did it, and the patient access rule
 * still applies: a doctor with no treating relationship cannot reach the record.
 */
@RestController
@RequestMapping("/api/patients/{patientId}/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService service;
    private final PatientAccessGuard accessGuard;
    private final StaffRepository staffRepository;

    /**
     * Every consent type with this patient's standing on it, including the ones
     * with nothing recorded.
     *
     * <p>No clinical data here — which type of consent a patient has given is not
     * a diagnosis — so a receptionist sees it, and has to, since collecting the
     * missing ones is their job.
     */
    @GetMapping
    public List<ConsentStatusResponse> status(@PathVariable UUID patientId,
                                              Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        return service.statusFor(patientId);
    }

    /** Everything ever recorded, for proving consent after the fact. */
    @GetMapping("/history")
    public List<ConsentRecordResponse> history(@PathVariable UUID patientId,
                                               Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        return service.history(patientId);
    }

    /**
     * Records consent against whichever wording is currently in force.
     *
     * <p>The caller does not choose the version. Letting it would let a client
     * record consent against wording the patient was never shown.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentRecordResponse grant(@PathVariable UUID patientId,
                                       @Valid @RequestBody ConsentGrantRequest request,
                                       Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        return service.grant(patientId, request, staff.getId());
    }

    /**
     * Records a withdrawal, effective immediately.
     *
     * <p>A POST to its own path rather than a DELETE of the record: nothing is
     * deleted. The consent happened, and the evidence that it happened is the
     * reason this module exists.
     */
    @PostMapping("/{type}/withdraw")
    public ConsentRecordResponse withdraw(@PathVariable UUID patientId,
                                          @PathVariable ConsentTypeCode type,
                                          @Valid @RequestBody(required = false)
                                          ConsentWithdrawRequest request,
                                          Authentication authentication) {
        Staff staff = resolveStaff(authentication);
        accessGuard.requireAccess(patientId, staff.getId(), staff.getRole());

        // No body is a valid withdrawal. The note is optional because the right
        // is unconditional, so requiring the client to send an empty object would
        // be a trap and nothing more.
        String note = request == null ? null : request.note();

        return service.withdraw(patientId, type, note, staff.getId());
    }

    private Staff resolveStaff(Authentication authentication) {
        String username = authentication.getName();

        return staffRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new UsernameNotFoundException("Staff not found: " + username));
    }
}
