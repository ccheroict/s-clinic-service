package com.sclinic.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a patient record is being withdrawn from use.
 *
 * <p>Mandatory. Withdrawing a medical record is a decision someone has to answer
 * for later, and a withdrawal with no stated reason cannot be reviewed. The
 * database carries the same constraint, so the rule holds even for a caller that
 * bypasses this validation.
 */
public record PatientDeleteRequest(

        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason
) {
}
