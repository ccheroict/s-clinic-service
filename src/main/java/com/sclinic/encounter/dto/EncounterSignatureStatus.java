package com.sclinic.encounter.dto;

import com.sclinic.encounter.EncounterStatus;
import com.sclinic.signing.SignatureRecord;
import com.sclinic.signing.SignatureVerification;

import java.time.Instant;
import java.util.UUID;

/**
 * What can be said about the signature on one record. Requirement 14.9.
 *
 * @param signed whether a signature exists at all. Distinct from a signature
 *        that fails to verify: an unsigned draft is not a problem, and showing
 *        the two the same way would have every draft in the clinic displaying a
 *        warning
 * @param dataUnchanged whether the record still hashes to what was signed. The
 *        finding that would be an incident
 * @param signatureSound whether the signature verifies against its certificate.
 *        A technical problem, reported apart from the one above because they
 *        have different owners and only one of them means somebody edited a
 *        medical record
 * @param problem what is wrong, null when nothing is
 */
public record EncounterSignatureStatus(
        EncounterStatus encounterStatus,
        boolean signed,
        boolean valid,
        boolean dataUnchanged,
        boolean signatureSound,

        UUID signerStaffId,
        String certificateSubject,
        Instant signedAt,
        String providerCode,

        String problem
) {

    public static EncounterSignatureStatus unsigned(EncounterStatus status) {
        return new EncounterSignatureStatus(status, false, false, true, true,
                null, null, null, null, null);
    }

    public static EncounterSignatureStatus of(EncounterStatus status,
                                              SignatureRecord signature,
                                              SignatureVerification verification) {
        return new EncounterSignatureStatus(
                status,
                true,
                verification.isValid(),
                verification.dataUnchanged(),
                verification.signatureSound(),
                signature.getSignerStaffId(),
                signature.getCertificateSubject(),
                signature.getSignedAt(),
                signature.getProviderCode(),
                verification.problem());
    }
}
