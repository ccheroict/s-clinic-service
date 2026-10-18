package com.sclinic.access.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Confirmation that temporary access was granted.
 *
 * <p>Returns when it expires and says plainly that administrators were told, so
 * the clinician knows both that the access is time-limited and that it was not
 * anonymous.
 */
public record BreakGlassResponse(
        UUID grantId,
        UUID patientId,
        Instant expiresAt,
        String notice
) {
}
