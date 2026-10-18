package com.sclinic.allergy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an allergy record is being retired.
 *
 * <p>Mandatory. Removing an allergy from a patient's active list changes what
 * can be prescribed to them, so it is a clinical decision someone has to answer
 * for — and the database enforces the same rule.
 */
public record AllergyRetirementRequest(

        @NotBlank(message = "reason is required")
        @Size(min = 10, max = 500, message = "reason must be between 10 and 500 characters")
        String reason
) {
}
