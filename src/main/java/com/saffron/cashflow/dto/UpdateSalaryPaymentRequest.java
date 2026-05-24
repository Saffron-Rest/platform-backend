package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.PaymentSource;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDate;

/** All fields optional — only provided fields are applied. */
public record UpdateSalaryPaymentRequest(
        @DecimalMin("0.01") BigDecimal amount,
        LocalDate paidDate,
        PaymentSource source,
        LocalDate periodFrom,
        LocalDate periodTo,
        String notes,
        /** Set true to explicitly clear an optional field (paidDate cannot be cleared). */
        Boolean clearPeriod,
        Boolean clearNotes
) {}
