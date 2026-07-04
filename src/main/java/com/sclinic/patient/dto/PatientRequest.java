package com.sclinic.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Create/update payload for a patient.
 */
public record PatientRequest(
        String code,

        @NotBlank(message = "full_name is required")
        String fullName,

        LocalDate dob,

        @Pattern(regexp = "M|F|U", message = "sex must be M, F or U")
        String sex,

        String phone,
        String address,
        String medicalHistory,
        String allergies,
        String note,
        String nationalId,
        String insuranceNo,
        String taxCode
) {
}
