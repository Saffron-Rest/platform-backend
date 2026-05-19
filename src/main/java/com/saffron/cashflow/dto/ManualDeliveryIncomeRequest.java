package com.saffron.cashflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class ManualDeliveryIncomeRequest {

    @NotBlank
    private String effectiveDate;

    @NotBlank
    private String platform;

    @NotNull
    @DecimalMin("0")
    private BigDecimal grossAmount;

    @DecimalMin("0")
    private BigDecimal settledToCard;

    private String notes;

    public String getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(String effectiveDate) { this.effectiveDate = effectiveDate; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getSettledToCard() { return settledToCard; }
    public void setSettledToCard(BigDecimal settledToCard) { this.settledToCard = settledToCard; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
