package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.PayType;
import java.math.BigDecimal;
import java.time.LocalDate;

/** PATCH-style: every field optional, only non-null fields are applied. */
public record UpdatePayRateRequest(
        PayType payType,
        BigDecimal payAmount,
        LocalDate effectiveFrom,
        String notes
) {}
