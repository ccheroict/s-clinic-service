package com.sclinic.consent.dto;

import com.sclinic.consent.ConsentRecord;
import com.sclinic.consent.ConsentType;
import com.sclinic.consent.ConsentTypeVersion;

import java.time.Instant;

/**
 * Where a patient stands on one consent type.
 *
 * <p>Returned for every type, including the ones with nothing recorded. A list of
 * what has been collected cannot show what is missing, and what is missing is
 * what the person at the desk needs to know.
 */
public record ConsentStatusResponse(
        String typeCode,
        String typeName,
        String description,
        boolean sensitive,

        /** Whether care may proceed without it. */
        boolean blocksCare,

        /**
         * The clinic has no published wording for this type, so consent for it
         * cannot be collected at all.
         *
         * <p>Reported separately from {@code granted} because it is an
         * administrator's problem, not the patient's, and the two are
         * indistinguishable otherwise: both leave the action refused.
         */
        boolean notConfigured,

        /** Version in force, or null when the type has no published wording. */
        Integer currentVersion,

        boolean granted,

        /**
         * The patient agreed to older wording than what is now in force.
         *
         * <p>Not treated as missing consent — the patient did consent, and
         * revoking that because the clinic edited a form would be wrong — but
         * worth showing, because fresh consent against the current wording is
         * usually what a compliance review wants to see.
         */
        boolean supersededWording,

        Instant grantedAt,
        Instant withdrawnAt
) {

    /**
     * @param current the version in force, or null if none is published
     * @param live    the consent now in force, or null
     * @param latest  the most recent record of this type whatever its state, so a
     *                withdrawn consent reads as withdrawn rather than as never given
     */
    public static ConsentStatusResponse of(ConsentType type, ConsentTypeVersion current,
                                           ConsentRecord live, ConsentRecord latest) {
        boolean staleWording = live != null && current != null
                && !live.getVersionId().equals(current.getId());

        return new ConsentStatusResponse(
                type.getCode(),
                type.getName(),
                type.getDescription(),
                type.isSensitive(),
                type.isBlocksCare(),
                current == null,
                current == null ? null : current.getVersion(),
                live != null,
                staleWording,
                live == null ? null : live.getGrantedAt(),
                // Only from the latest record, and only when it is the thing that
                // ended: a superseded record's timestamp here would read as the
                // patient having withdrawn when they actually re-consented.
                latest == null ? null : latest.getWithdrawnAt());
    }
}
