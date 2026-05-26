package com.saffron.cashflow.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates short, human-readable temporary passwords for admin-initiated
 * password resets.
 *
 * <p>Design goals:
 * <ul>
 *   <li><b>Easy to read aloud.</b> No ambiguous glyphs (0/O, 1/l/I) so an
 *       admin can dictate the temp password over the phone without "is
 *       that a one or a lowercase L" back-and-forth.</li>
 *   <li><b>Mixed character classes.</b> The generator guarantees at least
 *       one lowercase letter, one uppercase letter, and two digits so the
 *       result satisfies typical password-policy checks even if the
 *       user's own password rules differ from ours.</li>
 *   <li><b>Cryptographically random.</b> Uses {@link SecureRandom}, never
 *       {@link java.util.Random}.</li>
 *   <li><b>Single-use surface.</b> The plaintext is returned to the
 *       caller exactly once; the hash is what's persisted, and the
 *       caller is responsible for never logging the plaintext.</li>
 * </ul></p>
 *
 * <p>The output is 12 characters by default — long enough to resist a
 * brute-force attempt during the short window before the user signs in
 * and rotates it via the {@code mustChangePassword} flow.</p>
 */
public final class PasswordGenerator {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz"; // no 'l'
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";  // no 'I', 'O'
    private static final String DIGIT = "23456789";                  // no '0', '1'

    private PasswordGenerator() {}

    /** Convenience overload — 12 characters. */
    public static String generate() {
        return generate(12);
    }

    /**
     * @param length total length; must be ≥ 6 to fit the minimum class
     *               quota (1 upper + 1 lower + 2 digits + 2 random).
     */
    public static String generate(int length) {
        if (length < 6) {
            throw new IllegalArgumentException("Password length must be >= 6");
        }
        List<Character> chars = new ArrayList<>(length);
        // Seed the minimum quota first, then fill the rest from the
        // combined alphabet. Shuffling at the end means the structural
        // positions of the quota chars aren't predictable.
        chars.add(pick(LOWER));
        chars.add(pick(UPPER));
        chars.add(pick(DIGIT));
        chars.add(pick(DIGIT));
        String all = LOWER + UPPER + DIGIT;
        while (chars.size() < length) chars.add(pick(all));
        Collections.shuffle(chars, RNG);
        StringBuilder sb = new StringBuilder(length);
        for (char c : chars) sb.append(c);
        return sb.toString();
    }

    private static Character pick(String pool) {
        return pool.charAt(RNG.nextInt(pool.length()));
    }
}
