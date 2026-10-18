package com.sclinic.consent;

import com.sclinic.consent.dto.ConsentVersionRequest;
import com.sclinic.consent.dto.ConsentVersionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The wording patients consent to.
 *
 * <p>Separate from {@link ConsentController} because it is a different job with a
 * different risk: that one records what a patient decided, this one decides what
 * patients are asked. Publishing is restricted to administrators.
 *
 * <p>V14 ships the seven types with no wording at all, so this is not optional
 * setup — until an administrator publishes content, every action gated on a
 * consent type reports that type as unconfigured. That is intentional: the text a
 * patient reads is a legal instrument the clinic answers for, and shipping
 * generated legal prose would mean a clinic going live having collected consent
 * against words nobody there approved.
 */
@RestController
@RequestMapping("/api/consent-types")
@RequiredArgsConstructor
public class ConsentAdminController {

    private final ConsentService service;

    /**
     * The wording in force for a type, for showing to a patient before they
     * agree.
     *
     * <p>Readable by any signed-in staff member: it is what the patient is about
     * to be shown, and whoever is collecting the consent has to be able to show
     * it.
     */
    @GetMapping("/{type}/current-version")
    public ConsentVersionResponse currentVersion(@PathVariable ConsentTypeCode type) {
        return service.currentVersion(type);
    }

    /**
     * Publishes a new version.
     *
     * <p>Previous versions are left in place and keep applying to the consents
     * recorded against them. Editing wording in place would rewrite what past
     * patients agreed to, which is the failure this module is built to make
     * impossible.
     */
    @PostMapping("/{type}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ConsentVersionResponse publish(@PathVariable ConsentTypeCode type,
                                          @Valid @RequestBody ConsentVersionRequest request) {
        return service.publishVersion(type, request);
    }
}
