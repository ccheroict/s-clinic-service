package com.sclinic.bootstrap;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a random password that satisfies the password policy by construction.
 *
 * <p>Used for the bootstrap admin account so no installation ever ships with a
 * password an attacker could guess from the source code.
 */
public final class InitialPasswordGenerator {

    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGIT = "23456789";
    private static final String SPECIAL = "!@#$%^&*-_=+?";
    private static final String ALL = LOWER + UPPER + DIGIT + SPECIAL;

    private static final int LENGTH = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private InitialPasswordGenerator() {
    }

    /**
     * Characters that are easy to confuse when read off a screen (l, I, 1, O, 0)
     * are excluded, because this password gets transcribed by a human once.
     */
    public static String generate() {
        List<Character> characters = new ArrayList<>(LENGTH);
        characters.add(pick(LOWER));
        characters.add(pick(UPPER));
        characters.add(pick(DIGIT));
        characters.add(pick(SPECIAL));
        while (characters.size() < LENGTH) {
            characters.add(pick(ALL));
        }
        Collections.shuffle(characters, RANDOM);

        StringBuilder password = new StringBuilder(LENGTH);
        characters.forEach(password::append);
        return password.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }
}
