package com.sclinic.security.dto;

import java.util.List;

/**
 * Result of completing enrolment.
 *
 * <p>The recovery codes are returned exactly once and are not recoverable
 * afterwards; only their hashes are stored.
 *
 * @param session     token and identity for the next step
 * @param backupCodes single-use recovery codes to be written down
 */
public record MfaConfirmResponse(
        LoginResponse session,
        List<String> backupCodes
) {
}
