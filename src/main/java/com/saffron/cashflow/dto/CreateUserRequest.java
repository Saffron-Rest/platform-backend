package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateUserRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Invalid username")
        String username,
        @Email String email,
        @NotBlank @Size(min = 6) String password,
        @NotBlank String name,
        Role role,
        PayType payType,
        BigDecimal payAmount,
        @NotNull LocalDate startDate
) {}
