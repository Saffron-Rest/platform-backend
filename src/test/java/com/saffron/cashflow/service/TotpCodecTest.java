package com.saffron.cashflow.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Smoke tests for {@link TotpCodec}.
 *
 * <p>We can't easily test "verify a code from Google Authenticator" in a
 * unit test (clocks, secrets), but we can verify the round-trip invariants
 * that everything else relies on.</p>
 */
class TotpCodecTest {

    @Test
    void base32RoundTrip() {
        // RFC 4648 § 10 test vectors (without padding because we strip it
        // during decode anyway).
        assertEquals("", TotpCodec.base32Encode(new byte[0]));
        assertEquals("MY", TotpCodec.base32Encode("f".getBytes()));
        assertEquals("MZXQ", TotpCodec.base32Encode("fo".getBytes()));
        assertEquals("MZXW6", TotpCodec.base32Encode("foo".getBytes()));
        assertEquals("MZXW6YQ", TotpCodec.base32Encode("foob".getBytes()));
        assertEquals("MZXW6YTB", TotpCodec.base32Encode("fooba".getBytes()));

        // Decode round-trips a generated secret.
        String secret = TotpCodec.generateSecretBase32();
        assertNotNull(secret);
        assertEquals(32, secret.length(), "20-byte secret encodes to 32 chars in base32");
        byte[] decoded = TotpCodec.base32Decode(secret);
        assertEquals(20, decoded.length, "decoded length should be the original 20 bytes");
        // Re-encoding should give us the same string back.
        assertEquals(secret, TotpCodec.base32Encode(decoded));
    }

    @Test
    void base32IgnoresWhitespaceAndCase() {
        byte[] a = TotpCodec.base32Decode("JBSWY3DPEHPK3PXP");
        byte[] b = TotpCodec.base32Decode("jbswy3dpehpk3pxp");
        byte[] c = TotpCodec.base32Decode("JBSW Y3DP EHPK 3PXP");
        assertArrayEquals(a, b);
        assertArrayEquals(a, c);
    }

    @Test
    void verifyRejectsObviouslyBadInput() {
        String secret = TotpCodec.generateSecretBase32();
        assertFalse(TotpCodec.verify(secret, null));
        assertFalse(TotpCodec.verify(secret, ""));
        assertFalse(TotpCodec.verify(secret, "abc"));
        assertFalse(TotpCodec.verify(secret, "12345"));        // too short
        assertFalse(TotpCodec.verify(secret, "1234567"));      // too long
        assertFalse(TotpCodec.verify(secret, "000000"));        // overwhelmingly likely to be wrong
    }

    @Test
    void otpAuthUriEscapesSpacesAndContainsKeyParams() {
        String uri = TotpCodec.buildOtpAuthUri("Saffron Co", "kasia@example.com", "ABCDEFGHIJKLMNOP");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("issuer=Saffron+Co"));
        assertTrue(uri.contains("secret=ABCDEFGHIJKLMNOP"));
        assertTrue(uri.contains("algorithm=SHA1"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }

    @Test
    void differentCallsToGenerateProduceDifferentSecrets() {
        String s1 = TotpCodec.generateSecretBase32();
        String s2 = TotpCodec.generateSecretBase32();
        assertNotEquals(s1, s2,
                "SecureRandom collisions are astronomically unlikely; if this fires the RNG is broken");
    }
}
