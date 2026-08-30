package com.sclinic.facility;

import com.sclinic.facility.dto.FacilityRequest;
import com.sclinic.facility.dto.FacilityResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/facility")
@RequiredArgsConstructor
public class FacilityController {

    private final FacilityService service;

    /** Any authenticated user needs facility data to render printed documents. */
    @GetMapping
    public FacilityResponse get() {
        return service.getCurrent();
    }

    /** Changing facility identity affects prescriptions and invoices, so admins only. */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public FacilityResponse update(@Valid @RequestBody FacilityRequest request) {
        return service.update(request);
    }
}
