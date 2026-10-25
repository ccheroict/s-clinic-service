package com.sclinic.signing;

import java.time.Instant;

/**
 * What came back from the provider.
 *
 * @param signatureValueBase64 the signature itself
 * @param signatureAlgorithm   the algorithm the provider used. Recorded rather
 *                             than assumed: a verifier in ten years cannot guess
 * @param certificateChainBase64 the signer's certificate chain at this moment,
 *                             leaf first. Stored per signature because
 *                             certificates get renewed, and a signature made
 *                             under a certificate that has since expired is
 *                             still a valid signature made while it was good —
 *                             which can only be shown if that certificate is
 *                             kept alongside it
 * @param certificateSubject   pulled out so a reviewer can read who signed
 *                             without parsing DER
 * @param signedAt             when the provider says it signed. Their timestamp,
 *                             not the clinic's: the provider is the one whose
 *                             clock a dispute would turn on
 * @param providerTransactionId the provider's own reference, for reconciling
 *                             against their records
 */
public record SignatureResult(
        String signatureValueBase64,
        String signatureAlgorithm,
        String certificateChainBase64,
        String certificateSubject,
        String certificateSerial,
        Instant signedAt,
        String providerTransactionId
) {
}
