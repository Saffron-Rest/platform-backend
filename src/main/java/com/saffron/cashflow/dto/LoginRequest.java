package com.saffron.cashflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Invalid username")
        String username,
        @NotBlank String password
) {}
