package com.sclinic.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key used to encrypt patient identifiers at rest.
 *
 * @param key    base64-encoded AES key, 128 / 192 / 256 bits
 * @param indexKey base64-encoded HMAC key for the blind index. Separate from
 *                 {@code key} on purpose: the index is a keyed hash of the same
 *                 plaintext, so sharing one key would mean a single compromise
 *                 breaks both confidentiality and the lookup index at once
 */
@ConfigurationProperties(prefix = "sclinic.crypto")
public record DataEncryptionProperties(
        String key,
        String indexKey
) {
}
