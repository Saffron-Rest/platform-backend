package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.PayType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PayRateEntryRequest(
        @NotNull PayType payType,
        @NotNull BigDecimal payAmount,
        @NotNull LocalDate effectiveFrom,
        String notes
) {}
