package com.sclinic.consent.dto;

import com.sclinic.consent.ConsentCollectionMethod;
import com.sclinic.consent.ConsentRecord;
import com.sclinic.consent.ConsentType;
import com.sclinic.consent.ConsentTypeVersion;

import java.time.Instant;
import java.util.UUID;

/**
 * One consent record.
 *
 * <p>Carries the version number rather than only the version id: "agreed to v2 of
 * the photography consent" is the sentence a compliance review needs, and an id
 * does not say it.
 */
public record ConsentRecordResponse(
        UUID id,
        UUID patientId,
        String typeCode,
        String typeName,
        int version,
        String purpose,
        Instant grantedAt,
        ConsentCollectionMethod collectionMethod,
        String evidence,

        /** Current, withdrawn, or superseded by a later consent. */
        boolean live,
        Instant withdrawnAt,
        String withdrawalNote,
        Instant supersededAt
) {

    public static ConsentRecordResponse from(ConsentRecord record, ConsentType type,
                                             ConsentTypeVersion version) {
        return new ConsentRecordResponse(
                record.getId(),
                record.getPatientId(),
                type.getCode(),
                type.getName(),
                version.getVersion(),
                record.getPurpose(),
                record.getGrantedAt(),
                record.getCollectionMethod(),
                record.getEvidence(),
                record.isLive(),
                record.getWithdrawnAt(),
                record.getWithdrawalNote(),
                record.getSupersededAt());
    }
}
