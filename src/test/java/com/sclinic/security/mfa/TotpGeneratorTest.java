package com.sclinic.security.mfa;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpGeneratorTest {

    /**
     * The RFC 6238 appendix B seed is the ASCII string "12345678901234567890".
     * The published vectors are 8-digit; this implementation emits 6, so the
     * expectations below are the last six digits of each published value.
     */
    private static final String RFC_SECRET =
            Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Nested
    class RfcTestVectors {

        @ParameterizedTest(name = "t={0}s expects {1}")
        @CsvSource({
                "59,287082",
                "1111111109,081804",
                "1111111111,050471",
                "1234567890,005924",
                "2000000000,279037",
                "20000000000,353130"
        })
        void matchesRfc6238Vectors(long epochSeconds, String expectedCode) {
            String code = TotpGenerator.codeAt(RFC_SECRET, Instant.ofEpochSecond(epochSeconds));

            assertThat(code).isEqualTo(expectedCode);
        }
    }

    @Nested
    class Base32Codec {

        @Test
        void roundTripsArbitraryBytes() {
            byte[] original = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

            byte[] roundTripped = Base32.decode(Base32.encode(original));

            assertThat(roundTripped).startsWith(original);
        }

        @Test
        void encodesKnownValue() {
            // RFC 4648 test vector.
            assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)))
                    .isEqualTo("MZXW6YTBOI");
        }

        @Test
        void toleratesLowercaseAndSpacesAndPadding() {
            byte[] expected = Base32.decode("MZXW6YTBOI");

            assertThat(Base32.decode("mzxw6ytboi")).isEqualTo(expected);
            assertThat(Base32.decode("MZXW 6YTB OI")).isEqualTo(expected);
            assertThat(Base32.decode("MZXW6YTBOI======")).isEqualTo(expected);
        }
    }

    @Nested
    class Verification {

        private static final Instant NOW = Instant.parse("2026-08-29T10:00:00Z");

        @Test
        void acceptsTheCurrentCode() {
            String secret = TotpGenerator.generateSecret();
            String code = TotpGenerator.codeAt(secret, NOW);

            assertThat(TotpGenerator.verify(secret, code, NOW, 1)).isTrue();
        }

        @Test
        void acceptsTheAdjacentCodesWithinTheWindow() {
            String secret = TotpGenerator.generateSecret();
            Instant previousStep = NOW.minusSeconds(TotpGenerator.TIME_STEP_SECONDS);
            Instant nextStep = NOW.plusSeconds(TotpGenerator.TIME_STEP_SECONDS);

            assertThat(TotpGenerator.verify(secret, TotpGenerator.codeAt(secret, previousStep), NOW, 1))
                    .as("previous code tolerated for clock drift")
                    .isTrue();
            assertThat(TotpGenerator.verify(secret, TotpGenerator.codeAt(secret, nextStep), NOW, 1))
                    .as("next code tolerated for clock drift")
                    .isTrue();
        }

        @Test
        void rejectsCodesOutsideTheWindow() {
            String secret = TotpGenerator.generateSecret();
            Instant farPast = NOW.minusSeconds(TotpGenerator.TIME_STEP_SECONDS * 5L);

            assertThat(TotpGenerator.verify(secret, TotpGenerator.codeAt(secret, farPast), NOW, 1))
                    .isFalse();
        }

        @Test
        void rejectsWrongCode() {
            String secret = TotpGenerator.generateSecret();

            assertThat(TotpGenerator.verify(secret, "000000", NOW, 1)).isFalse();
        }

        @Test
        void rejectsCodeOfTheWrongLength() {
            String secret = TotpGenerator.generateSecret();
            String valid = TotpGenerator.codeAt(secret, NOW);

            assertThat(TotpGenerator.verify(secret, valid.substring(1), NOW, 1)).isFalse();
            assertThat(TotpGenerator.verify(secret, "0" + valid, NOW, 1)).isFalse();
        }

        @Test
        void rejectsNulls() {
            assertThat(TotpGenerator.verify(null, "123456", NOW, 1)).isFalse();
            assertThat(TotpGenerator.verify(TotpGenerator.generateSecret(), null, NOW, 1)).isFalse();
        }

        @Test
        void toleratesSurroundingWhitespaceTypedByUsers() {
            String secret = TotpGenerator.generateSecret();
            String code = TotpGenerator.codeAt(secret, NOW);

            assertThat(TotpGenerator.verify(secret, "  " + code + " ", NOW, 1)).isTrue();
        }

        @Test
        void codeFromOneSecretDoesNotValidateAgainstAnother() {
            String secretA = TotpGenerator.generateSecret();
            String secretB = TotpGenerator.generateSecret();

            assertThat(TotpGenerator.verify(secretB, TotpGenerator.codeAt(secretA, NOW), NOW, 1))
                    .isFalse();
        }
    }

    @Nested
    class Secrets {

        @Test
        void generatesDistinct160BitSecrets() {
            String first = TotpGenerator.generateSecret();
            String second = TotpGenerator.generateSecret();

            assertThat(first).isNotEqualTo(second);
            // 20 bytes -> 32 base32 characters.
            assertThat(first).hasSize(32);
            assertThat(Base32.decode(first)).hasSize(20);
        }

        @Test
        void codeIsAlwaysSixDigits() {
            String secret = TotpGenerator.generateSecret();

            for (int i = 0; i < 500; i++) {
                String code = TotpGenerator.codeAt(secret, Instant.ofEpochSecond(i * 31L));
                assertThat(code).hasSize(6).containsOnlyDigits();
            }
        }
    }

    @Nested
    class ProvisioningUri {

        @Test
        void buildsUriAuthenticatorAppsUnderstand() {
            String uri = TotpGenerator.provisioningUri("ABCDEFGH", "bacsi", "S-Clinic");

            assertThat(uri)
                    .startsWith("otpauth://totp/S-Clinic:bacsi")
                    .contains("secret=ABCDEFGH")
                    .contains("issuer=S-Clinic")
                    .contains("algorithm=SHA1")
                    .contains("digits=6")
                    .contains("period=30");
        }

        @Test
        void escapesSpacesInIssuerAndAccount() {
            String uri = TotpGenerator.provisioningUri("ABC", "bac si", "Phong kham S-Clinic");

            assertThat(uri).doesNotContain(" ").contains("%20");
        }
    }
}
