package com.sclinic.facility.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Facility view returned by the API.
 */
public record FacilityResponse(
        UUID id,
        String name,
        String kcbCode,
        String interopCode,
        String taxCode,
        String address,
        String phone,
        String email,
        String licenseNo,
        LocalDate licenseIssuedAt,
        String technicalDirector,
        String einvoiceTemplateCode,
        String einvoiceSerial,
        String einvoiceUnitCode,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
