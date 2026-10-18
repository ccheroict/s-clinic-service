package com.sclinic.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts short strings with AES-GCM.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts: a
 * ciphertext altered in the database fails to decrypt instead of producing
 * plausible rubbish, which for a national identity number is the difference
 * between a visible error and a patient quietly acquiring someone else's number.
 *
 * <p>A fresh random IV per encryption, so the same identifier written twice
 * produces different ciphertext. That is what stops the column from leaking which
 * patients share a value, and it is why these columns cannot be searched; exact
 * lookup goes through {@link BlindIndex} instead.
 *
 * <p>Stored form is {@code v1:base64(iv || ciphertext || tag)}. The version
 * prefix is what makes key rotation possible later without guessing how old
 * values were written.
 */
@Component
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";

    /** 96 bits, the size GCM is specified for and the only one without extra hashing. */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final String VERSION_PREFIX = "v1:";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(DataEncryptionProperties properties) {
        this.key = new SecretKeySpec(
                CryptoKeys.decodeAesKey(properties.key(), "sclinic.crypto.key"), ALGORITHM);
    }

    /**
     * @return the stored form, or null for null input so an absent identifier
     *         stays absent rather than becoming an encrypted empty string
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }

        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // Misconfigured key or a JVM without AES-GCM. Either way the value
            // must not be written in the clear, so this has to fail loudly.
            throw new IllegalStateException("Could not encrypt value", e);
        }
    }

    /**
     * @throws IllegalStateException if the value was altered, truncated, or
     *                               written with a different key
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException(
                    "Value is not in the expected encrypted form; it may predate encryption");
        }

        byte[] combined = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
        if (combined.length <= IV_BYTES) {
            throw new IllegalStateException("Encrypted value is truncated");
        }

        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, IV_BYTES);
        byte[] ciphertext = new byte[combined.length - IV_BYTES];
        System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Could not decrypt value; it was altered or written with a different key", e);
        }
    }

    /** Whether a stored value is in the encrypted form this class writes. */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(VERSION_PREFIX);
    }
}
