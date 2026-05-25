package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.PaymentSource;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordSalaryPaymentRequest(
        @NotBlank String userId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate paidDate,
        @NotNull PaymentSource source,
        LocalDate periodFrom,
        LocalDate periodTo,
        String notes,
        /** When true the payment is recorded but does NOT reduce treasury
         *  balances and skips the cash/card availability check. */
        Boolean excludeFromTreasury
) {}
