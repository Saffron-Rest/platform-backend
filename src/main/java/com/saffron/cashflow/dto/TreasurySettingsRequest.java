package com.saffron.cashflow.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;

public record TreasurySettingsRequest(
        @NotNull @DecimalMin("0") BigDecimal initialCashBalance,
        @NotNull @DecimalMin("0") BigDecimal initialCardBalance,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal cardSalesSettlementRate,
        @NotNull Map<String, BigDecimal> platformSettlementRates
) {}
