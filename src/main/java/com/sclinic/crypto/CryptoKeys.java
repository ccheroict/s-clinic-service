package com.sclinic.crypto;

import java.util.Base64;

/**
 * Reads keys from configuration, and refuses to start without them.
 *
 * <p>There is no default and no fallback. A default key would be published in
 * the repository, which makes the encryption decorative, and a silent fallback
 * to writing plaintext would be worse still: the system would look compliant
 * while storing identity numbers in the clear. Failing at startup is the only
 * behaviour that cannot be missed.
 */
public final class CryptoKeys {

    /** 128 bits is the floor for an HMAC key here; 256 is what to deploy. */
    private static final int MIN_KEY_BYTES = 16;

    private CryptoKeys() {
    }

    /**
     * Decodes a key that AES will accept.
     *
     * <p>AES takes exactly 128, 192 or 256 bits and rejects anything else. Left to
     * the cipher, a key of the wrong length fails at the first attempt to write an
     * identifier rather than at startup — which means in production it fails on
     * the first patient someone registers, not on deployment. Checked here so the
     * application refuses to start instead.
     */
    static byte[] decodeAesKey(String base64Key, String propertyName) {
        byte[] key = decode(base64Key, propertyName, "Patient identifiers are encrypted at rest");

        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalStateException(propertyName + " must decode to exactly 16, 24 or 32 "
                    + "bytes for AES (128, 192 or 256 bits); got " + key.length
                    + " bytes. Generate one with: openssl rand -base64 32");
        }
        return key;
    }

    /**
     * Decodes and length-checks a base64 key, or refuses to let the application
     * start.
     *
     * <p>Public because the audit chain's HMAC key needs the same treatment and
     * the same wording. Two copies of "there is no default, fail at startup"
     * would drift, and the copy that drifted would be the one that quietly
     * grew a fallback.
     *
     * @param purpose what breaks without this key, for the error message
     */
    public static byte[] decode(String base64Key, String propertyName, String purpose) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " is not set. " + purpose + ", so the application will not "
                            + "start without a key. Generate one with: openssl rand -base64 32");
        }

        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " is not valid base64", e);
        }

        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(propertyName + " must be at least "
                    + MIN_KEY_BYTES + " bytes (" + MIN_KEY_BYTES * 8 + " bits); got "
                    + key.length + " bytes");
        }
        return key;
    }
}
