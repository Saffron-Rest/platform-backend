package com.saffron.cashflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public class BankDepositRequest {

    @NotBlank
    private String bankDate;

    @NotNull
    @DecimalMin("0")
    private BigDecimal totalSettled;

    private String notes;

    @NotEmpty
    @Valid
    private List<LinkPayload> links;

    public static class LinkPayload {
        @NotBlank
        private String linkedKind;

        @NotBlank
        private String linkedRefId;

        @NotBlank
        private String linkedDate;

        @NotNull
        @DecimalMin("0")
        private BigDecimal grossAmount;

        public String getLinkedKind() { return linkedKind; }
        public void setLinkedKind(String linkedKind) { this.linkedKind = linkedKind; }

        public String getLinkedRefId() { return linkedRefId; }
        public void setLinkedRefId(String linkedRefId) { this.linkedRefId = linkedRefId; }

        public String getLinkedDate() { return linkedDate; }
        public void setLinkedDate(String linkedDate) { this.linkedDate = linkedDate; }

        public BigDecimal getGrossAmount() { return grossAmount; }
        public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    }

    public String getBankDate() { return bankDate; }
    public void setBankDate(String bankDate) { this.bankDate = bankDate; }

    public BigDecimal getTotalSettled() { return totalSettled; }
    public void setTotalSettled(BigDecimal totalSettled) { this.totalSettled = totalSettled; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public List<LinkPayload> getLinks() { return links; }
    public void setLinks(List<LinkPayload> links) { this.links = links; }
}
