package com.sclinic.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Publishing a version of a consent type's wording.
 *
 * <p>No version number: the service assigns it, so two administrators cannot both
 * create version 3. No published flag either — publishing is what this call does.
 *
 * @param sensitivityNotice the statement that the data processed is sensitive
 *                          personal data. Required for sensitive types
 *                          (requirement 11.4) and rejected as a missing field
 *                          rather than as a database error
 * @param effectiveFrom     defaults to today. A future date is allowed and is how
 *                          a planned change is staged: the version is not served
 *                          until the date arrives
 */
public record ConsentVersionRequest(

        @NotBlank(message = "content is required")
        @Size(max = 20000, message = "content must be at most 20000 characters")
        String content,

        @NotBlank(message = "purpose is required")
        @Size(max = 1000, message = "purpose must be at most 1000 characters")
        String purpose,

        @Size(max = 2000, message = "sensitivityNotice must be at most 2000 characters")
        String sensitivityNotice,

        LocalDate effectiveFrom
) {
}
