package com.sclinic.signing;

/**
 * The provider did not sign.
 *
 * <p>A declined confirmation, a timeout while the signer did not look at their
 * phone, an expired credential, the provider being down. Deliberately one
 * exception rather than a hierarchy: the clinical answer is the same in every
 * case — the record is still a draft and nothing was signed — and the differences
 * belong in the message and the log rather than in the control flow.
 *
 * <p>What must never happen is a failure here leaving a record marked SIGNED. The
 * status change and the signature row are written in the same transaction as this
 * call's success, so there is no state in which one exists without the other.
 */
public class SignatureFailedException extends RuntimeException {

    public SignatureFailedException(String message) {
        super(message);
    }

    public SignatureFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
