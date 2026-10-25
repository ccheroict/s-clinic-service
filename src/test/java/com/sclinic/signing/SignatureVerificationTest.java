package com.sclinic.signing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checking a signature, and the two different ways it can fail.
 *
 * <p>Requirement 14.9 asks whether a signature is still valid <em>and</em>
 * whether the data has changed since it was made. Those are separate findings
 * with separate owners: an altered record is an incident, a broken signature or
 * an expired certificate is a technical problem. One boolean would show both as
 * the same red cross and only one of them is worth waking somebody for.
 *
 * <p>Verification is local by design. These tests use a throwaway key pair for
 * exactly that reason: no provider is contacted, because a record signed five
 * years ago must stay verifiable after the clinic has changed provider or the
 * provider has stopped answering.
 */
class SignatureVerificationTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private static String hashOf(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(
                digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Signs a hash the way a provider would: over the digest bytes, because the
     * record itself never leaves the clinic.
     */
    private static String signHash(String hashBase64) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(Base64.getDecoder().decode(hashBase64));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    @Nested
    class DataChange {

        @Test
        void aRecordThatNoLongerMatchesItsHashIsReportedAsAltered() throws Exception {
            String signedHash = hashOf("Chẩn đoán: viêm da cơ địa");
            String nowHash = hashOf("Chẩn đoán: vảy nến");

            SignatureVerification result = LocalSignatureVerifier.verify(
                    new SignatureVerificationRequest("irrelevant", "SHA256withRSA",
                            null, signedHash, nowHash));

            // Checked before anything cryptographic, and reported on its own. The
            // signature may be perfectly sound; what changed is the record.
            assertThat(result.dataUnchanged()).isFalse();
            assertThat(result.isValid()).isFalse();
            assertThat(result.problem()).contains("đã thay đổi");
        }

        @Test
        void anUnchangedRecordWithNoCertificateIsNotCalledAltered() throws Exception {
            String hash = hashOf("Nội dung");

            SignatureVerification result = LocalSignatureVerifier.verify(
                    new SignatureVerificationRequest("sig", "SHA256withRSA",
                            null, hash, hash));

            // The distinction that matters: this installation cannot check the
            // signature, which is not the same as the record having been tampered
            // with, and saying so would start an investigation into nothing.
            assertThat(result.dataUnchanged()).isTrue();
            assertThat(result.signatureSound()).isFalse();
        }
    }

    @Nested
    class Cryptography {

        @Test
        void aSoundSignatureOverAnUnchangedRecordVerifies() throws Exception {
            String hash = hashOf("Chẩn đoán: viêm da cơ địa");
            String signature = signHash(hash);
            String certificate = selfSignedCertificateBase64();

            SignatureVerification result = LocalSignatureVerifier.verify(
                    new SignatureVerificationRequest(signature, "SHA256withRSA",
                            certificate, hash, hash));

            assertThat(result.isValid()).isTrue();
        }

        @Test
        void aSignatureFromADifferentKeyDoesNotVerify() throws Exception {
            String hash = hashOf("Nội dung");

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair other = generator.generateKeyPair();

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(other.getPrivate());
            signer.update(Base64.getDecoder().decode(hash));
            String foreignSignature = Base64.getEncoder().encodeToString(signer.sign());

            SignatureVerification result = LocalSignatureVerifier.verify(
                    new SignatureVerificationRequest(foreignSignature, "SHA256withRSA",
                            selfSignedCertificateBase64(), hash, hash));

            assertThat(result.signatureSound()).isFalse();
            // And crucially not reported as the data having changed, because it
            // has not: the hashes match.
            assertThat(result.dataUnchanged()).isTrue();
        }

        @Test
        void aMalformedCertificateIsAFailureToCheckRatherThanAnAccusation()
                throws Exception {
            String hash = hashOf("Nội dung");

            SignatureVerification result = LocalSignatureVerifier.verify(
                    new SignatureVerificationRequest(signHash(hash), "SHA256withRSA",
                            "bm90LWEtY2VydGlmaWNhdGU=", hash, hash));

            assertThat(result.isValid()).isFalse();
            assertThat(result.dataUnchanged()).isTrue();
        }
    }

    @Nested
    class WhenNoProviderIsConfigured {

        private final UnconfiguredSignatureProvider provider =
                new UnconfiguredSignatureProvider();

        @Test
        void signingIsRefusedWithAnExplanation() {
            assertThatThrownBy(() -> provider.sign(
                    new SignatureRequest(null, null, "aGFzaA==", "SHA-256", "Bản ghi khám")))
                    .isInstanceOf(SignatureFailedException.class)
                    .hasMessageContaining("Chưa cấu hình nhà cung cấp ký số");
        }

        @Test
        void itDoesNotSignWithALocalKey() {
            // The most dangerous thing this codebase could have done. Signing
            // with a key on the server would satisfy every test, mark records
            // SIGNED, produce a technically valid signature, and have no legal
            // standing at all - and nobody would find out until a record was
            // challenged.
            assertThatThrownBy(() -> provider.sign(
                    new SignatureRequest(null, "any", "aGFzaA==", "SHA-256", "x")))
                    .isInstanceOf(SignatureFailedException.class);

            assertThat(provider.providerCode()).isEqualTo("NONE");
        }

        @Test
        void verificationSaysItCannotCheckRatherThanThatSomethingIsWrong() {
            SignatureVerification result = provider.verify(
                    new SignatureVerificationRequest("s", "SHA256withRSA", null, "h", "h"));

            // Returning "valid" would be a lie; returning a bare "invalid" would
            // suggest tampering. Neither is true, and the message says which.
            assertThat(result.isValid()).isFalse();
            assertThat(result.problem()).contains("Không kiểm tra được");
        }
    }

    /**
     * A self-signed certificate wrapping the test key.
     *
     * <p>Built with the JDK's own certificate tooling rather than a library,
     * because the only thing needed is a public key in X.509 clothing so the
     * verifier has somewhere to read it from.
     */
    private static String selfSignedCertificateBase64() throws Exception {
        // Generated once per JVM run via keytool-equivalent APIs would pull in a
        // dependency; instead, a certificate is produced by signing a minimal
        // TBS structure is not something to hand-roll either. The verifier only
        // needs the public key, so the test supplies a certificate generated by
        // the JDK's CertAndKeyGen equivalent through KeyStore.
        return TestCertificates.selfSigned(keyPair);
    }
}
