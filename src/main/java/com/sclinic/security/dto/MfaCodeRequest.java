package com.sclinic.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A second-factor answer: either a 6-digit TOTP code or a recovery code.
 */
public record MfaCodeRequest(
        @NotBlank(message = "code is required")
        String code
) {
}
