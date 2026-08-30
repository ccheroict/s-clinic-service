package com.sclinic.security.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords (RFC 6238) over HMAC-SHA1, the variant every
 * mainstream authenticator app implements.
 *
 * <p>Pure and stateless so it can be checked against the RFC test vectors.
 */
public final class TotpGenerator {

    /** RFC 6238 default: a new code every 30 seconds. */
    public static final int TIME_STEP_SECONDS = 30;

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int CODE_DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final int[] POWERS_OF_TEN = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpGenerator() {
    }

    /** A fresh 160-bit secret, base32-encoded for authenticator apps. */
    public static String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return Base32.encode(secret);
    }

    /** The code for a given instant. */
    public static String codeAt(String base32Secret, Instant instant) {
        return codeForStep(base32Secret, timeStep(instant));
    }

    /**
     * Verifies a code, accepting neighbouring time steps to tolerate clock drift
     * between the server and the user's phone.
     *
     * @param windowSteps how many steps either side to accept; 1 means the
     *                    previous, current and next code are valid
     */
    public static boolean verify(String base32Secret, String candidate, Instant instant, int windowSteps) {
        if (base32Secret == null || candidate == null) {
            return false;
        }
        String normalised = candidate.trim();
        if (normalised.length() != CODE_DIGITS) {
            return false;
        }

        long currentStep = timeStep(instant);
        boolean matched = false;

        // Checks every step in the window even after a match, so the work done
        // does not reveal which step matched.
        for (long step = currentStep - windowSteps; step <= currentStep + windowSteps; step++) {
            if (constantTimeEquals(codeForStep(base32Secret, step), normalised)) {
                matched = true;
            }
        }
        return matched;
    }

    /** The otpauth URI an authenticator app consumes, usually via QR code. */
    public static String provisioningUri(String base32Secret, String accountName, String issuer) {
        String encodedIssuer = urlEncode(issuer);
        String label = encodedIssuer + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1"
                + "&digits=" + CODE_DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    static long timeStep(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), TIME_STEP_SECONDS);
    }

    private static String codeForStep(String base32Secret, long step) {
        byte[] key = Base32.decode(base32Secret);
        byte[] counter = ByteBuffer.allocate(Long.BYTES).putLong(step).array();

        byte[] digest;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            digest = mac.doFinal(counter);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA1 is mandated by the JCA spec; unreachable on a valid JVM.
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }

        // Dynamic truncation, RFC 4226 section 5.4.
        int offset = digest[digest.length - 1] & 0x0F;
        int binary = ((digest[offset] & 0x7F) << 24)
                | ((digest[offset + 1] & 0xFF) << 16)
                | ((digest[offset + 2] & 0xFF) << 8)
                | (digest[offset + 3] & 0xFF);

        int code = binary % POWERS_OF_TEN[CODE_DIGITS];
        return String.format("%0" + CODE_DIGITS + "d", code);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int differences = 0;
        for (int i = 0; i < a.length(); i++) {
            differences |= a.charAt(i) ^ b.charAt(i);
        }
        return differences == 0;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
