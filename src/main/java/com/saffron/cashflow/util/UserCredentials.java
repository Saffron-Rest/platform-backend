package com.saffron.cashflow.util;

import com.saffron.cashflow.web.BadRequestException;
import java.util.Locale;

public final class UserCredentials {

    private UserCredentials() {}

    public static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new BadRequestException("Username is required");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]{3,32}")) {
            throw new BadRequestException("Username must be 3–32 characters: letters, numbers, . _ -");
        }
        return normalized;
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
