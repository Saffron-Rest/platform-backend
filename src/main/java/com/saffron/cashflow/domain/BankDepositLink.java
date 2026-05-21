package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One source row reconciled by a {@link BankDeposit}. A given (linkedKind, linkedRefId)
 * pair can only belong to a single deposit at a time.
 */
@Entity
@Table(
        name = "bank_deposit_link",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bank_deposit_link_ref",
                columnNames = {"linked_kind", "linked_ref_id"}))
public class BankDepositLink {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_deposit_id", nullable = false)
    private BankDeposit bankDeposit;

    @Column(name = "linked_kind", nullable = false)
    private String linkedKind;

    @Column(name = "linked_ref_id", nullable = false)
    private String linkedRefId;

    @Column(name = "linked_date", nullable = false)
    private LocalDate linkedDate;

    /** Snapshot of the source row's amount at the time the link was created. */
    @Column(name = "gross_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }

    public BankDeposit getBankDeposit() { return bankDeposit; }
    public void setBankDeposit(BankDeposit bankDeposit) { this.bankDeposit = bankDeposit; }

    public String getLinkedKind() { return linkedKind; }
    public void setLinkedKind(String linkedKind) { this.linkedKind = linkedKind; }

    public String getLinkedRefId() { return linkedRefId; }
    public void setLinkedRefId(String linkedRefId) { this.linkedRefId = linkedRefId; }

    public LocalDate getLinkedDate() { return linkedDate; }
    public void setLinkedDate(LocalDate linkedDate) { this.linkedDate = linkedDate; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount != null ? grossAmount : BigDecimal.ZERO;
    }
}
