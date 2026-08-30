package com.sclinic.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Password change payload.
 *
 * <p>The current password is required even when the account is already holding a
 * change-password token, so that a stolen interim token alone cannot take over
 * the account.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "currentPassword is required")
        String currentPassword,

        @NotBlank(message = "newPassword is required")
        String newPassword
) {
}
