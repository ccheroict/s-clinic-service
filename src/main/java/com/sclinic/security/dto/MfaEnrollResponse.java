package com.sclinic.security.dto;

/**
 * Everything the client needs to set up an authenticator app.
 *
 * @param secret          base32 shared secret, for manual entry
 * @param provisioningUri otpauth URI, to render as a QR code
 */
public record MfaEnrollResponse(
        String secret,
        String provisioningUri
) {
}
