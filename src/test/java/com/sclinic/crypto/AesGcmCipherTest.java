package com.sclinic.crypto;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String INDEX_KEY = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private static final String CCCD = "001199012345";

    private AesGcmCipher cipher(String key) {
        return new AesGcmCipher(new DataEncryptionProperties(key, INDEX_KEY));
    }

    private final AesGcmCipher cipher = cipher(KEY);

    @Test
    void decryptsWhatItEncrypted() {
        assertThat(cipher.decrypt(cipher.encrypt(CCCD))).isEqualTo(CCCD);
    }

    @Test
    void leavesNullAlone() {
        // An absent identifier must stay absent rather than becoming an
        // encrypted empty string, which would read as "present but blank".
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void handlesAnEmptyString() {
        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
    }

    @Test
    void handlesNonAsciiText() {
        String value = "Nguyễn Văn Ứng 0012-ĐỊNH";

        assertThat(cipher.decrypt(cipher.encrypt(value))).isEqualTo(value);
    }

    @Test
    void doesNotContainThePlaintext() {
        assertThat(cipher.encrypt(CCCD)).doesNotContain(CCCD);
    }

    @Test
    void carriesAVersionPrefixSoKeysCanBeRotatedLater() {
        assertThat(cipher.encrypt(CCCD)).startsWith("v1:");
        assertThat(AesGcmCipher.isEncrypted(cipher.encrypt(CCCD))).isTrue();
        assertThat(AesGcmCipher.isEncrypted(CCCD)).isFalse();
    }

    @Nested
    class SameValueTwice {

        /**
         * A fresh IV each time, so the column cannot be read to find out which
         * patients share an identity number. This is the property that rules out
         * searching the column directly, which is what BlindIndex is for.
         */
        @Test
        void producesDifferentCiphertext() {
            assertThat(cipher.encrypt(CCCD)).isNotEqualTo(cipher.encrypt(CCCD));
        }

        @Test
        void bothStillDecryptToTheSameValue() {
            assertThat(cipher.decrypt(cipher.encrypt(CCCD)))
                    .isEqualTo(cipher.decrypt(cipher.encrypt(CCCD)));
        }
    }

    @Nested
    class Tampering {

        /**
         * GCM authenticates as well as encrypts. Without that, an altered
         * ciphertext would decrypt to plausible rubbish, and a patient would
         * quietly acquire a different identity number.
         */
        /**
         * Flips a byte of the decoded payload rather than a base64 character:
         * the final base64 character carries unused bits, so changing it does not
         * reliably change the bytes underneath.
         */
        @Test
        void anAlteredCiphertextIsRejected() {
            String stored = cipher.encrypt(CCCD);
            byte[] payload = Base64.getDecoder().decode(stored.substring("v1:".length()));

            // Past the 12-byte IV, so this alters the ciphertext itself.
            payload[payload.length - 2] ^= 0x01;
            String tampered = "v1:" + Base64.getEncoder().encodeToString(payload);

            assertThatThrownBy(() -> cipher.decrypt(tampered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("altered");
        }

        /** Altering the IV has to be caught too, not just the ciphertext. */
        @Test
        void anAlteredInitialisationVectorIsRejected() {
            String stored = cipher.encrypt(CCCD);
            byte[] payload = Base64.getDecoder().decode(stored.substring("v1:".length()));

            payload[0] ^= 0x01;
            String tampered = "v1:" + Base64.getEncoder().encodeToString(payload);

            assertThatThrownBy(() -> cipher.decrypt(tampered))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aTruncatedValueIsRejected() {
            String stored = cipher.encrypt(CCCD);

            assertThatThrownBy(() -> cipher.decrypt(stored.substring(0, 10)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aValueFromADifferentKeyIsRejected() {
            String otherKey = Base64.getEncoder()
                    .encodeToString("ffffffffffffffffffffffffffffffff".getBytes());
            String stored = cipher(otherKey).encrypt(CCCD);

            assertThatThrownBy(() -> cipher.decrypt(stored))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different key");
        }

        @Test
        void aPlaintextValueIsNotMistakenForCiphertext() {
            assertThatThrownBy(() -> cipher.decrypt(CCCD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("predate encryption");
        }
    }

    @Nested
    class KeyConfiguration {

        /**
         * No default and no silent fallback. A default key would be published in
         * this repository; falling back to plaintext would leave the system
         * looking compliant while storing identity numbers in the clear.
         */
        @Test
        void refusesToStartWithoutAKey() {
            assertThatThrownBy(() -> cipher(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sclinic.crypto.key");
        }

        @Test
        void refusesToStartWithABlankKey() {
            assertThatThrownBy(() -> cipher("   "))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not set");
        }

        @Test
        void refusesAKeyThatIsNotBase64() {
            assertThatThrownBy(() -> cipher("not base64 !!!"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base64");
        }

        @Test
        void refusesAKeyThatIsTooShortToBeUseful() {
            String tooShort = Base64.getEncoder().encodeToString("short".getBytes());

            assertThatThrownBy(() -> cipher(tooShort))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least");
        }

        /**
         * AES takes exactly 128, 192 or 256 bits. A key of any other length has
         * to be refused at startup: left to the cipher it fails at the first
         * attempt to write an identifier, which in production means the first
         * patient someone registers rather than the deployment.
         */
        @Test
        void refusesAKeyThatIsNotAValidAesLength() {
            String twentyBytes = Base64.getEncoder()
                    .encodeToString("12345678901234567890".getBytes());

            assertThatThrownBy(() -> cipher(twentyBytes))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly 16, 24 or 32");
        }

        @Test
        void acceptsEachValidAesLength() {
            for (int length : new int[]{16, 24, 32}) {
                String key = Base64.getEncoder().encodeToString("x".repeat(length).getBytes());

                assertThat(cipher(key).decrypt(cipher(key).encrypt(CCCD))).isEqualTo(CCCD);
            }
        }
    }

    @Nested
    class Index {

        private BlindIndex index(String key) {
            return new BlindIndex(new DataEncryptionProperties(KEY, key));
        }

        private final BlindIndex index = index(INDEX_KEY);

        @Test
        void isStableForTheSameValue() {
            assertThat(index.of(CCCD)).isEqualTo(index.of(CCCD));
        }

        @Test
        void differsForDifferentValues() {
            assertThat(index.of(CCCD)).isNotEqualTo(index.of("001199012346"));
        }

        @Test
        void ignoresSurroundingWhitespaceAndCase() {
            assertThat(index.of("  ab123  ")).isEqualTo(index.of("AB123"));
        }

        @Test
        void doesNotContainThePlaintext() {
            assertThat(index.of(CCCD)).doesNotContain(CCCD);
        }

        /**
         * Null for an absent identifier, so the many patients with no CCCD on
         * file do not all collide on one index value -- which would also break
         * the unique constraint on the column.
         */
        @Test
        void isAbsentForAnAbsentValue() {
            assertThat(index.of(null)).isNull();
            assertThat(index.of("   ")).isNull();
        }

        /**
         * A keyed hash, not a bare digest. An identity number has a small and
         * highly structured search space, so a plain SHA-256 of one is reversible
         * by brute force in seconds.
         */
        @Test
        void dependsOnTheKey() {
            String otherKey = Base64.getEncoder()
                    .encodeToString("11111111111111111111111111111111".getBytes());

            assertThat(index.of(CCCD)).isNotEqualTo(index(otherKey).of(CCCD));
        }

        @Test
        void usesADifferentKeyFromTheCipher() {
            // Sharing one key would mean a single compromise breaks both
            // confidentiality and the lookup index.
            assertThatThrownBy(() -> index(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("index-key");
        }
    }
}
