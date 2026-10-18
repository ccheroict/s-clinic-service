package com.sclinic.consent.dto;

import com.sclinic.consent.ConsentCollectionMethod;
import com.sclinic.consent.ConsentTypeCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Recording a patient's consent.
 *
 * <p>No version field. The version is whichever wording is in force when the
 * consent is collected, resolved by the service: letting a caller name a version
 * would let it record consent against wording the patient was never shown, and
 * that is the one thing this whole module exists to prevent.
 *
 * @param grantedAt  when the patient agreed, for a paper form signed earlier
 *                   today and entered now. Defaults to now; a future time is
 *                   refused
 * @param evidence   where the proof is — a scan reference, a signature id, a note
 *                   of who witnessed a verbal consent
 */
public record ConsentGrantRequest(

        @NotNull(message = "type is required")
        ConsentTypeCode type,

        @NotNull(message = "collectionMethod is required")
        ConsentCollectionMethod collectionMethod,

        @Size(max = 500, message = "evidence must be at most 500 characters")
        String evidence,

        Instant grantedAt
) {
}
