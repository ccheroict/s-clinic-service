package com.sclinic.signing;

/**
 * The clinic's signing service, whoever provides it.
 *
 * <p>Requirement 14.1: changing provider must mean adding an implementation of
 * this, not touching the code that decides what gets signed. That matters more
 * here than the usual argument for an interface. A Vietnamese clinic's choice of
 * certificate authority is a commercial decision that changes, the national rules
 * about remote signing are still moving, and the signing step sits in the middle
 * of the one workflow that cannot be allowed to break — so the provider has to be
 * replaceable without reopening the medical-record logic.
 *
 * <p>Implementations must not hold a private key. Remote signing exists precisely
 * so the key stays with the provider and the signer approves each use on their own
 * device; an implementation that signs locally with a key on the server would have
 * the same interface and none of the legal standing.
 */
public interface DigitalSignatureProvider {

    /**
     * Identifies this implementation, and is stored on every signature it makes.
     *
     * <p>Kept on the row because a clinic that changes provider still has to
     * verify what the old one signed, and that starts with knowing which one it
     * was.
     */
    String providerCode();

    /**
     * Signs a hash on behalf of one signer.
     *
     * <p>Takes a hash rather than the document. The record never leaves the
     * clinic: what goes to the provider is a digest, which is all a signature
     * needs and avoids sending a patient's examination findings to a third party
     * in order to sign them.
     *
     * <p>Expected to block while the signer confirms on their phone. Remote
     * signing in Vietnam is a push notification and a PIN, so a call here can
     * take tens of seconds; callers must treat that as normal rather than as a
     * timeout.
     *
     * @throws SignatureFailedException if the provider refuses, the signer
     *         declines, or the confirmation is not given in time
     */
    SignatureResult sign(SignatureRequest request);

    /**
     * Checks a stored signature against a hash recomputed from the record now.
     *
     * <p>Requirement 14.9 asks two questions and this answers both: whether the
     * signature is cryptographically sound, and whether the data still matches
     * what was signed. They fail differently — an expired certificate is not the
     * same event as an altered record — so {@link SignatureVerification} reports
     * them apart rather than as one boolean.
     */
    SignatureVerification verify(SignatureVerificationRequest request);
}
