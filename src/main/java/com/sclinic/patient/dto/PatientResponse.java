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
        Instant updatedAt
) {
}
