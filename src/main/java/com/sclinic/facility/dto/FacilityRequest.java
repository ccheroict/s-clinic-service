package com.sclinic.facility.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Update payload for the facility record.
 */
public record FacilityRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotBlank(message = "kcbCode is required")
        @Size(max = 50, message = "kcbCode must be at most 50 characters")
        String kcbCode,

        @Size(max = 50, message = "interopCode must be at most 50 characters")
        String interopCode,

        @Size(max = 20, message = "taxCode must be at most 20 characters")
        String taxCode,

        String address,

        @Size(max = 30, message = "phone must be at most 30 characters")
        String phone,

        @Email(message = "email must be a valid address")
        String email,

        @Size(max = 100, message = "licenseNo must be at most 100 characters")
        String licenseNo,

        LocalDate licenseIssuedAt,

        @Size(max = 255, message = "technicalDirector must be at most 255 characters")
        String technicalDirector,

        @Size(max = 20, message = "einvoiceTemplateCode must be at most 20 characters")
        String einvoiceTemplateCode,

        @Size(max = 20, message = "einvoiceSerial must be at most 20 characters")
        String einvoiceSerial,

        @Size(max = 50, message = "einvoiceUnitCode must be at most 50 characters")
        String einvoiceUnitCode
) {
}
