package com.sclinic.signing;

/**
 * The answer to requirement 14.9, which is two questions rather than one.
 *
 * @param signatureSound whether the signature is cryptographically valid for the
 *        certificate it names
 * @param dataUnchanged whether the record still hashes to what was signed
 * @param certificateValidAtSigningTime whether the certificate was within its
 *        validity period when the signature was made. Separate from
 *        {@code signatureSound} because a certificate that has since expired does
 *        not retroactively invalidate a signature made while it was good — and
 *        conflating them would make every older record look broken
 * @param problem what was wrong, null when everything holds
 *
 * <p>Reported apart because the two failures mean opposite things. A signature
 * that does not verify against an unchanged record points at the signature or
 * the certificate; a record that no longer matches its hash points at the record
 * having been altered. One boolean would collapse "this document was tampered
 * with" and "this certificate has expired" into the same red cross, and only one
 * of those is an incident.
 */
public record SignatureVerification(
        boolean signatureSound,
        boolean dataUnchanged,
        boolean certificateValidAtSigningTime,
        String problem
) {

    public boolean isValid() {
        return signatureSound && dataUnchanged && certificateValidAtSigningTime;
    }

    public static SignatureVerification valid() {
        return new SignatureVerification(true, true, true, null);
    }

    public static SignatureVerification dataAltered() {
        return new SignatureVerification(true, false, true,
                "Nội dung bản ghi đã thay đổi so với thời điểm ký. "
                        + "Chữ ký không còn chứng thực được nội dung hiện tại.");
    }

    public static SignatureVerification signatureBroken(String detail) {
        return new SignatureVerification(false, true, true, detail);
    }
}
