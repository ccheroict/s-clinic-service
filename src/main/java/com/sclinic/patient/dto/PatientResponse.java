package com.sclinic.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient view returned by the API.
 */
public record PatientResponse(
        UUID id,
        String code,
        String fullName,
        LocalDate dob,
        String sex,
        String phone,
        String address,
        String medicalHistory,
        String allergies,
        String note,
        String nationalId,
        String insuranceNo,
        String taxCode,
        Instant createdAt,
        Instant updatedAt,

        LocalDate deceasedOn,

        /**
         * Earliest date this record may be considered for destruction, or null
         * when no retention policy is configured for patient records. Null means
         * "keep": a record with no retention date never becomes eligible.
         */
        LocalDate retentionUntil,

        /** Blocks destruction regardless of the retention date. */
        boolean legalHold,

        /**
         * This patient has allergy information recorded only as prose, which no
         * automated check can read, and no clinician has yet turned it into
         * coded records.
         *
         * <p>Exposed so the chart can say so. A record whose allergies are a
         * sentence looks identical to one with no allergies at all unless the UI
         * is told the difference.
         */
        boolean allergyReviewRequired,

        /**
         * The free-text allergies, kept verbatim, for whoever reviews them.
         *
         * <p>Clinical data, so it is on this response and not on
         * {@link PatientAdministrativeResponse}.
         */
        String allergyLegacyNote
) {
}
