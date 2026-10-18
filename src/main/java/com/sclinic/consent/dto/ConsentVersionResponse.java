package com.sclinic.consent.dto;

import com.sclinic.consent.ConsentType;
import com.sclinic.consent.ConsentTypeVersion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A version of a consent type's wording, as shown to a patient.
 */
public record ConsentVersionResponse(
        UUID id,
        String typeCode,
        String typeName,
        int version,
        String content,

        /**
         * The notice that the data is sensitive personal data, where the type
         * calls for one. Sent as its own field rather than folded into the
         * content so the UI can present it where it will be read.
         */
        String sensitivityNotice,

        String purpose,
        boolean sensitive,
        LocalDate effectiveFrom,
        Instant publishedAt
) {

    public static ConsentVersionResponse from(ConsentTypeVersion version, ConsentType type) {
        return new ConsentVersionResponse(
                version.getId(),
                type.getCode(),
                type.getName(),
                version.getVersion(),
                version.getContent(),
                version.getSensitivityNotice(),
                version.getPurpose(),
                type.isSensitive(),
                version.getEffectiveFrom(),
                version.getPublishedAt());
    }
}
