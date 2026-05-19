package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Role;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateUserRequest(
        String name,
        String email,
        Boolean active,
        String password,
        Role role,
        PayType payType,
        BigDecimal payAmount,
        BigDecimal hourlyRate,
        LocalDate startDate
) {}
