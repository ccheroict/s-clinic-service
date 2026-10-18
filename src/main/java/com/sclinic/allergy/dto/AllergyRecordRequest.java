package com.sclinic.allergy.dto;

import com.sclinic.allergy.AllergyCertainty;
import com.sclinic.allergy.AllergySeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Recording an allergy.
 *
 * @param allergenCode code from the allergen catalogue. A code rather than a
 *                     name, because the point of the catalogue is that the check
 *                     at prescribing time fires however the substance was spelled
 * @param severity     required: it decides whether a prescription is warned
 *                     about or stopped, so there is no sensible default
 * @param certainty    defaults to REPORTED, which is what most allergies are
 * @param recordedOn   defaults to today
 */
public record AllergyRecordRequest(

        @NotBlank(message = "allergenCode is required")
        String allergenCode,

        @Size(max = 500, message = "reaction must be at most 500 characters")
        String reaction,

        @NotNull(message = "severity is required")
        AllergySeverity severity,

        AllergyCertainty certainty,

        LocalDate recordedOn,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {
}
