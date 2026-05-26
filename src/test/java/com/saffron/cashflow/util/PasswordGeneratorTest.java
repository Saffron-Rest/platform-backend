package com.saffron.cashflow.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property tests for {@link PasswordGenerator}.
 *
 * <p>We don't pin to specific strings (output is random) — instead we
 * assert the invariants the rest of the system relies on.</p>
 */
class PasswordGeneratorTest {

    @Test
    void defaultLengthIs12() {
        for (int i = 0; i < 50; i++) {
            assertEquals(12, PasswordGenerator.generate().length());
        }
    }

    @Test
    void respectsRequestedLength() {
        for (int len = 6; len <= 32; len++) {
            assertEquals(len, PasswordGenerator.generate(len).length());
        }
    }

    @Test
    void rejectsLengthsBelowQuotaMinimum() {
        assertThrows(IllegalArgumentException.class, () -> PasswordGenerator.generate(5));
        assertThrows(IllegalArgumentException.class, () -> PasswordGenerator.generate(0));
        assertThrows(IllegalArgumentException.class, () -> PasswordGenerator.generate(-1));
    }

    @Test
    void containsAtLeastOneUpperLowerAndTwoDigits() {
        // 200 trials — the quota guarantee is hard, so 100% should pass.
        for (int i = 0; i < 200; i++) {
            String pw = PasswordGenerator.generate();
            assertTrue(pw.chars().anyMatch(Character::isLowerCase),
                    "missing lowercase in: " + pw);
            assertTrue(pw.chars().anyMatch(Character::isUpperCase),
                    "missing uppercase in: " + pw);
            long digits = pw.chars().filter(Character::isDigit).count();
            assertTrue(digits >= 2, "expected ≥2 digits in: " + pw);
        }
    }

    @Test
    void avoidsAmbiguousGlyphs() {
        // Characters that get confused over the phone or in a screen
        // share: 0/O, 1/l/I. The generator must never emit these.
        String banned = "0OIl1";
        for (int i = 0; i < 500; i++) {
            String pw = PasswordGenerator.generate();
            for (char c : pw.toCharArray()) {
                assertTrue(banned.indexOf(c) < 0,
                        "produced ambiguous glyph '" + c + "' in: " + pw);
            }
        }
    }

    @Test
    void hasReasonableEntropy() {
        // Two consecutive calls colliding would indicate a deterministic
        // PRNG. 1000 generations with a 12-char alphabet of ~60 symbols
        // should never repeat.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(PasswordGenerator.generate()),
                    "duplicate password generated — entropy regression?");
        }
    }
}
