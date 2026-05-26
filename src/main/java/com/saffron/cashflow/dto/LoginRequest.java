package com.saffron.cashflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Invalid username")
        String username,
        @NotBlank String password,
        /** Optional 6-digit TOTP code when the user has 2FA enabled.
         *  Backwards-compatible: existing clients without 2FA can omit. */
        String totpCode
) {
    public LoginRequest(String username, String password) {
        this(username, password, null);
    }
}
