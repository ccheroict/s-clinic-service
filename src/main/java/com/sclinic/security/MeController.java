package com.sclinic.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns identity information about the currently authenticated user.
 * Used by the frontend (s-clinic-web) to determine username and role after login.
 */
@RestController
@RequestMapping("/api")
public class MeController {

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        String username = authentication.getName();

        // Extract role from authorities (format: ROLE_DOCTOR → DOCTOR)
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);

        return new MeResponse(username, role);
    }

    public record MeResponse(String username, String role) {}
}
