package com.sclinic.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Lets an encrypted column still be looked up by exact value.
 *
 * <p>Encryption uses a fresh IV each time, so the same identity number produces
 * different ciphertext and {@code WHERE national_id = ?} can never match. The
 * usual answer is to make the encryption deterministic, which trades the problem
 * for a worse one: equal plaintexts then produce equal ciphertexts, so the column
 * reveals which patients share a number to anyone who can read it.
 *
 * <p>This stores a keyed hash of the value in a separate column instead. Exact
 * lookup works, and the hash gives nothing away without the key — unlike a plain
 * SHA-256, which an identity number's small and highly structured search space
 * makes trivially reversible by brute force.
 */
@Component
public class BlindIndex {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public BlindIndex(DataEncryptionProperties properties) {
        this.key = CryptoKeys.decode(properties.indexKey(), "sclinic.crypto.index-key");
    }

    /**
     * @return lowercase hex HMAC of the normalised value, or null for a null or
     *         blank input so records without the identifier do not all collide on
     *         one index value
     */
    public String of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        // Normalised so that the same number written with stray spacing or in a
        // different case is found. Identity numbers are digits, but insurance and
        // tax codes are not always.
        String normalised = value.trim().toUpperCase();

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by the JCA spec; unreachable on a valid JVM.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
