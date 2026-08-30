package com.sclinic.security.mfa;

/**
 * Base32 codec (RFC 4648, no padding on encode).
 *
 * <p>Authenticator apps expect the TOTP shared secret in base32, and the JDK has
 * no base32 implementation, so a small one lives here.
 */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS_PER_CHAR = 5;
    private static final int BITS_PER_BYTE = 8;

    private Base32() {
    }

    static String encode(byte[] data) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bitsInBuffer = 0;

        for (byte b : data) {
            buffer = (buffer << BITS_PER_BYTE) | (b & 0xFF);
            bitsInBuffer += BITS_PER_BYTE;

            while (bitsInBuffer >= BITS_PER_CHAR) {
                int index = (buffer >>> (bitsInBuffer - BITS_PER_CHAR)) & 0x1F;
                encoded.append(ALPHABET.charAt(index));
                bitsInBuffer -= BITS_PER_CHAR;
            }
        }

        if (bitsInBuffer > 0) {
            int index = (buffer << (BITS_PER_CHAR - bitsInBuffer)) & 0x1F;
            encoded.append(ALPHABET.charAt(index));
        }

        return encoded.toString();
    }

    /**
     * @throws IllegalArgumentException if the input contains a character outside
     *                                  the base32 alphabet
     */
    static byte[] decode(String encoded) {
        String normalised = encoded
                .trim()
                .replace("=", "")
                .replace(" ", "")
                .toUpperCase();

        int buffer = 0;
        int bitsInBuffer = 0;
        byte[] decoded = new byte[normalised.length() * BITS_PER_CHAR / BITS_PER_BYTE];
        int written = 0;

        for (char c : normalised.toCharArray()) {
            int index = ALPHABET.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("Not a base32 character: " + c);
            }
            buffer = (buffer << BITS_PER_CHAR) | index;
            bitsInBuffer += BITS_PER_CHAR;

            if (bitsInBuffer >= BITS_PER_BYTE) {
                decoded[written++] = (byte) ((buffer >>> (bitsInBuffer - BITS_PER_BYTE)) & 0xFF);
                bitsInBuffer -= BITS_PER_BYTE;
            }
        }

        return decoded;
    }
}
