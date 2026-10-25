package com.sclinic.encounter;

import com.sclinic.signing.DigitalSignatureProvider;
import com.sclinic.signing.SignatureRequest;
import com.sclinic.signing.SignatureResult;
import com.sclinic.signing.SignatureVerification;
import com.sclinic.signing.SignatureVerificationRequest;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * A signing provider for tests, holding a key generated in this JVM.
 *
 * <p>In the test sources, and only there. The production code deliberately
 * refuses to sign with a locally held key — doing so would mark records SIGNED,
 * produce technically valid signatures and carry no legal weight, with nothing to
 * reveal the problem until a record was challenged. This class is exactly that
 * dangerous thing, which is why it cannot be reached from anything shippable.
 *
 * <p>It signs for real, so the tests exercise the real verification path rather
 * than a stub that returns true. What they cannot exercise is the part that needs
 * a provider: whether VNPT's API is called correctly.
 */
public class TestSignatureProvider implements DigitalSignatureProvider {

    private final KeyPair keyPair;
    private boolean refuse;

    public TestSignatureProvider() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keyPair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test key", e);
        }
    }

    /** Makes the next signing attempt fail, standing in for a declined approval. */
    public void refuseNextSignature(boolean refuse) {
        this.refuse = refuse;
    }

    @Override
    public String providerCode() {
        return "TEST";
    }

    @Override
    public SignatureResult sign(SignatureRequest request) {
        if (refuse) {
            throw new com.sclinic.signing.SignatureFailedException(
                    "Người ký đã từ chối (giả lập trong kiểm thử).");
        }

        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(Base64.getDecoder().decode(request.hashBase64()));

            return new SignatureResult(
                    Base64.getEncoder().encodeToString(signer.sign()),
                    "SHA256withRSA",
                    certificateBase64(),
                    "CN=S-Clinic Test Doctor",
                    "1",
                    Instant.now(),
                    "test-" + System.nanoTime());
        } catch (Exception e) {
            throw new com.sclinic.signing.SignatureFailedException("Test signing failed", e);
        }
    }

    @Override
    public SignatureVerification verify(SignatureVerificationRequest request) {
        if (!Objects.equals(request.storedHashBase64(), request.recomputedHashBase64())) {
            return SignatureVerification.dataAltered();
        }

        try {
            Signature verifier = Signature.getInstance(request.signatureAlgorithm());
            verifier.initVerify(keyPair.getPublic());
            verifier.update(Base64.getDecoder().decode(request.storedHashBase64()));

            boolean sound = verifier.verify(
                    Base64.getDecoder().decode(request.signatureValueBase64()));

            return sound
                    ? SignatureVerification.valid()
                    : SignatureVerification.signatureBroken("Chữ ký không khớp.");
        } catch (Exception e) {
            return SignatureVerification.signatureBroken(e.getClass().getSimpleName());
        }
    }

    private String certificateBase64() {
        // The encounter tests verify through this class, which holds the public
        // key directly, so no certificate parsing is involved. A real
        // certificate is exercised in SignatureVerificationTest against
        // LocalSignatureVerifier.
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
