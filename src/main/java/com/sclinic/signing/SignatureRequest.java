package com.sclinic.signing;

import java.util.UUID;

/**
 * What the provider is being asked to sign.
 *
 * @param signerStaffId    who is signing, for the clinic's own records
 * @param credentialId     the signer's credential at the provider. Resolved from
 *                         the staff row by the service, so an implementation
 *                         never has to know how the clinic stores it
 * @param hashBase64       the digest to sign, base64. Not the document: the
 *                         record stays in the clinic, and a digest is all a
 *                         signature needs
 * @param hashAlgorithm    how the digest was produced, so the provider signs it
 *                         under the right algorithm identifier and a verifier
 *                         years later can reproduce it
 * @param displayName      what to show the signer on their phone while they
 *                         decide. Must not identify the patient: a push
 *                         notification is read on a lock screen, often in public,
 *                         and "Nguyễn Thị A - viêm da" on a lock screen is a
 *                         disclosure the clinic did not intend
 */
public record SignatureRequest(
        UUID signerStaffId,
        String credentialId,
        String hashBase64,
        String hashAlgorithm,
        String displayName
) {
}
