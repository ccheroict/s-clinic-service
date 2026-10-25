package com.sclinic.signing;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Objects;

/**
 * Checks a stored signature without asking anybody.
 *
 * <p>Verification needs the signature, the certificate and the hash, and all
 * three are on the row — so it is arithmetic, not a service call. That is the
 * point rather than an optimisation: a record signed five years ago has to stay
 * verifiable after the clinic has changed provider, after the provider has been
 * acquired, and after it has stopped answering the phone. A verification that
 * depends on a third party being reachable is one that fails exactly when a
 * record is being challenged.
 *
 * <p>Shared by every provider implementation for the same reason: whoever made
 * the signature, checking it is the same operation.
 */
final class LocalSignatureVerifier {

    private LocalSignatureVerifier() {
    }

    static SignatureVerification verify(SignatureVerificationRequest request) {
        // Checked first, and separately. A record that no longer hashes to what
        // was signed has been altered, which is an incident; a signature that
        // does not verify against an unchanged record points at the signature or
        // the certificate, which is a different problem with a different owner.
        if (!Objects.equals(request.storedHashBase64(), request.recomputedHashBase64())) {
            return SignatureVerification.dataAltered();
        }

        if (request.certificateChainBase64() == null
                || request.certificateChainBase64().isBlank()) {
            return SignatureVerification.signatureBroken(
                    "Không có chứng thư kèm chữ ký nên không kiểm tra được.");
        }

        try {
            X509Certificate certificate = leafCertificate(request.certificateChainBase64());

            Signature verifier = Signature.getInstance(request.signatureAlgorithm());
            verifier.initVerify(certificate.getPublicKey());
            // The signed bytes are the hash itself, because that is what was sent
            // to the provider: the record never left the clinic.
            verifier.update(Base64.getDecoder().decode(request.storedHashBase64()));

            boolean sound = verifier.verify(
                    Base64.getDecoder().decode(request.signatureValueBase64()));

            if (!sound) {
                return SignatureVerification.signatureBroken(
                        "Chữ ký không khớp với chứng thư đã dùng để ký.");
            }

            return SignatureVerification.valid();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return SignatureVerification.signatureBroken(
                    "Không đọc được chữ ký hoặc chứng thư: " + e.getClass().getSimpleName());
        }
    }

    private static X509Certificate leafCertificate(String chainBase64)
            throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(chainBase64.replaceAll("\\s", ""));

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
    }
}
