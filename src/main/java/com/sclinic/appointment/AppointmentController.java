package com.sclinic.appointment;

import com.sclinic.appointment.dto.AppointmentCreateRequest;
import com.sclinic.appointment.dto.AppointmentResponse;
import com.sclinic.appointment.dto.AppointmentUpdateRequest;
import com.sclinic.appointment.dto.StatusUpdateRequest;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService service;
    private final StaffRepository staffRepository;

    @GetMapping
    public Page<AppointmentResponse> list(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) AppointmentStatus status,
            Pageable pageable,
            Authentication authentication) {

        Staff staff = resolveStaff(authentication);
        return service.list(date, doctorId, status, pageable, staff.getId(), staff.getRole());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public AppointmentResponse create(@Valid @RequestBody AppointmentCreateRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public AppointmentResponse update(@PathVariable UUID id,
                                      @Valid @RequestBody AppointmentUpdateRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public AppointmentResponse updateStatus(@PathVariable UUID id,
                                            @Valid @RequestBody StatusUpdateRequest request,
                                            Authentication authentication) {

        Staff staff = resolveStaff(authentication);
        return service.updateStatus(id, request, staff.getId(), staff.getRole());
    }

    /**
     * Resolve the currently authenticated Staff entity from Spring Security's Authentication.
     */
    private Staff resolveStaff(Authentication authentication) {
        String username = authentication.getName();
        return staffRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new UsernameNotFoundException("Staff not found: " + username));
    }
}
