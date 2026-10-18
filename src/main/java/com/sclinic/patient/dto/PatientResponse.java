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
        boolean legalHold
) {
}
