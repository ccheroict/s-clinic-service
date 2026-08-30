package com.sclinic.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials submitted to obtain a session token.
 */
public record LoginRequest(
        @NotBlank(message = "username is required")
        String username,

        @NotBlank(message = "password is required")
        String password
) {
}
