package com.sclinic.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A patient record without the clinical parts.
 *
 * <p>What a receptionist needs to do their job: book an appointment, call the
 * patient, raise an invoice. Requirement 8.6.
 *
 * <p>A separate record rather than the full response with fields nulled out. A
 * nulled field is indistinguishable from an empty one, so a caller cannot tell
 * "this patient has no recorded allergies" from "you are not allowed to know" —
 * and in a clinical setting those two must never be confused. A type that has no
 * such field at all cannot be misread, and it also means a clinical field added
 * to the record later does not silently start appearing here.
 *
 * <p>Deliberately absent: {@code medicalHistory}, {@code allergies}, {@code note}.
 * The insurance and tax identifiers are present because billing needs them.
 */
public record PatientAdministrativeResponse(
        UUID id,
        String code,
        String fullName,
        LocalDate dob,
        String sex,
        String phone,
        String address,
        String insuranceNo,
        String taxCode,
        Instant createdAt,
        Instant updatedAt,
        LocalDate deceasedOn
) {
}
