package com.sclinic.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a clinician needs a record they have no treating relationship with.
 *
 * <p>Requirement 8.4: a blank reason is refused. The reason is the entire price
 * of the access, so accepting an empty one would make the grant free.
 */
public record BreakGlassRequest(

        @NotBlank(message = "reason is required")
        @Size(min = 10, max = 500,
                message = "reason must be between 10 and 500 characters")
        String reason
) {
}
