package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateUserRequest(
        String name,
        @Email String email,
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Invalid username")
        String username,
        Boolean active,
        String password,
        Role role,
        PayType payType,
        BigDecimal payAmount,
        BigDecimal hourlyRate,
        /** First day the new pay applies (required when pay type or amount changes). */
        LocalDate payEffectiveFrom,
        String payChangeNote,
        LocalDate startDate
) {}
