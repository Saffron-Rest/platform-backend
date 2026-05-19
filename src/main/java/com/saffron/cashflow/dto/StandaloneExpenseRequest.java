package com.saffron.cashflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class StandaloneExpenseRequest {

    @NotBlank
    private String effectiveDate;

    @NotNull
    private String category;

    private String description = "";

    @NotNull
    @DecimalMin("0")
    private BigDecimal amount;

    private String paymentSource = "CASH";

    public String getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(String effectiveDate) { this.effectiveDate = effectiveDate; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getPaymentSource() { return paymentSource; }
    public void setPaymentSource(String paymentSource) { this.paymentSource = paymentSource; }
}
