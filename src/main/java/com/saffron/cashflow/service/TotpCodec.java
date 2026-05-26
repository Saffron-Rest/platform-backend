package com.saffron.cashflow.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Minimal RFC 6238 (TOTP) + RFC 4648 (Base32) implementation.
 *
 * <p>We don't pull in an external library because the spec is small,
 * stable, and a single file is easier to audit. Implements:</p>
 * <ul>
 *   <li>Base32 encode / decode (no padding, uppercase, ignored whitespace).</li>
 *   <li>HMAC-SHA1 TOTP with 30-second period and 6-digit codes.</li>
 *   <li>A small acceptance window (±1 step) for clock skew, matching what
 *       Google Authenticator, Microsoft Authenticator, and 1Password do.</li>
 * </ul>
 */
public final class TotpCodec {

    private TotpCodec() {}

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;          // 160 bits, RFC 4226 recommendation
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;                  // accept code from the previous + current + next step

    public static String generateSecretBase32() {
        SecureRandom rnd = new SecureRandom();
        byte[] buf = new byte[SECRET_BYTES];
        rnd.nextBytes(buf);
        return base32Encode(buf);
    }

    /** Build an {@code otpauth://totp/...} URI suitable for QR encoding. */
    public static String buildOtpAuthUri(String issuer, String account, String secretB32) {
        String label = urlEncode(issuer + ":" + account);
        return "otpauth://totp/" + label
                + "?secret=" + secretB32
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** Verify a 6-digit code at the current time, allowing ±1 step skew. */
    public static boolean verify(String secretB32, String userCode) {
        if (secretB32 == null || userCode == null) return false;
        String trimmed = userCode.replaceAll("\\D", "");
        if (trimmed.length() != DIGITS) return false;
        int target;
        try { target = Integer.parseInt(trimmed); }
        catch (NumberFormatException e) { return false; }
        long step = Instant.now().getEpochSecond() / PERIOD_SECONDS;
        byte[] secret;
        try { secret = base32Decode(secretB32); }
        catch (IllegalArgumentException e) { return false; }
        for (int w = -WINDOW; w <= WINDOW; w++) {
            int code = generate(secret, step + w);
            if (constantTimeEquals(code, target)) return true;
        }
        return false;
    }

    // ---------- TOTP / HOTP core ----------

    private static int generate(byte[] key, long counter) {
        try {
            byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] hash = mac.doFinal(msg);
            // Dynamic truncation per RFC 4226 § 5.3
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return binary % (int) Math.pow(10, DIGITS);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP failure", e);
        }
    }

    private static boolean constantTimeEquals(int a, int b) {
        // Branch-free comparison so we don't leak match position via timing.
        return ((a ^ b) | -(a ^ b)) == 0;
    }

    // ---------- Base32 ----------

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                int idx = (buffer >> (bits - 5)) & 0x1f;
                sb.append(BASE32_ALPHABET.charAt(idx));
                bits -= 5;
            }
        }
        if (bits > 0) {
            int idx = (buffer << (5 - bits)) & 0x1f;
            sb.append(BASE32_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        String clean = s.trim().replaceAll("[\\s=]", "").toUpperCase();
        if (clean.isEmpty()) throw new IllegalArgumentException("empty secret");
        int buffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : clean.toCharArray()) {
            int idx = BASE32_ALPHABET.indexOf(c);
            if (idx < 0) throw new IllegalArgumentException("Invalid base32 char: " + c);
            buffer = (buffer << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
