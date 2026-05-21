package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Manual reconciliation entry for direct POS card sales. Records what was sold on card
 * vs what the bank actually credited so the difference (variance) can be applied to the
 * card / bank balance after the fact (e.g. POS fees, holdbacks).
 *
 * <p>Treasury contribution = {@code settledAmount - grossAmount}. Use a zero
 * {@code grossAmount} to record a standalone deposit, or set both to record a variance.
 */
@Entity
@Table(
        name = "card_settlement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_card_settlement_link",
                columnNames = {"linked_kind", "linked_ref_id"}))
public class CardSettlement {

    @Id
    private String id;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** Snapshot of the source row's amount at the time of reconciliation. */
    @Column(name = "gross_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    /** What the bank actually credited to the card/bank balance. */
    @Column(name = "settled_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal settledAmount = BigDecimal.ZERO;

    /** Optional link to the ledger row this settlement overrides (e.g. SHIFT_CARD_SALES_SETTLED). */
    @Column(name = "linked_kind")
    private String linkedKind;

    @Column(name = "linked_ref_id")
    private String linkedRefId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getId() { return id; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount != null ? grossAmount : BigDecimal.ZERO;
    }

    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) {
        this.settledAmount = settledAmount != null ? settledAmount : BigDecimal.ZERO;
    }

    public String getLinkedKind() { return linkedKind; }
    public void setLinkedKind(String linkedKind) { this.linkedKind = linkedKind; }

    public String getLinkedRefId() { return linkedRefId; }
    public void setLinkedRefId(String linkedRefId) { this.linkedRefId = linkedRefId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    /** Net contribution to the card / bank balance: {@code settledAmount − grossAmount}. */
    public BigDecimal delta() {
        return settledAmount.subtract(grossAmount).setScale(2, RoundingMode.HALF_UP);
    }
}
