package com.sclinic.signing;

/**
 * Everything needed to re-check one stored signature.
 *
 * @param signatureValueBase64   as stored
 * @param signatureAlgorithm     as stored
 * @param certificateChainBase64 the certificate used at signing time, as stored
 * @param storedHashBase64       the hash that was signed
 * @param recomputedHashBase64   the hash of the record as it stands now. Computed
 *                               by the caller, which is the only party that knows
 *                               how to canonicalise the record it owns
 */
public record SignatureVerificationRequest(
        String signatureValueBase64,
        String signatureAlgorithm,
        String certificateChainBase64,
        String storedHashBase64,
        String recomputedHashBase64
) {
}
