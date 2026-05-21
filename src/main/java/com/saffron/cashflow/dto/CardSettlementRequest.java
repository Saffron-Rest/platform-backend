package com.saffron.cashflow.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class CardSettlementRequest {

    @NotBlank
    private String effectiveDate;

    /** Gross sold on card (informational / used to compute variance). */
    @DecimalMin("0")
    private BigDecimal grossAmount;

    @NotNull
    @DecimalMin("0")
    private BigDecimal settledAmount;

    /** Optional link to the ledger row this settlement overrides. */
    private String linkedKind;
    private String linkedRefId;

    private String notes;

    public String getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(String effectiveDate) { this.effectiveDate = effectiveDate; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }

    public String getLinkedKind() { return linkedKind; }
    public void setLinkedKind(String linkedKind) { this.linkedKind = linkedKind; }

    public String getLinkedRefId() { return linkedRefId; }
    public void setLinkedRefId(String linkedRefId) { this.linkedRefId = linkedRefId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
