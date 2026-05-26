package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit row recording every change to a {@link StockItem}'s
 * on-hand balance.
 *
 * <p>The set of rows for a stock item forms a ledger: the most recent
 * {@code balanceAfter} equals {@link StockItem#getOnHand()}. We snapshot
 * the post-trade balance on every movement so reverts and history
 * displays don't have to replay the whole timeline.</p>
 *
 * <p>To <b>revert</b> a movement, we create a new {@link StockMovementType#REVERT}
 * movement with {@code delta = -original.delta} and set
 * {@code original.reverted = true}. The original row is never deleted —
 * preserves traceability even after a revert.</p>
 */
@Entity
@Table(name = "stock_movement", indexes = {
        @Index(name = "ix_stock_movement_item", columnList = "stock_item_id, created_at DESC"),
        @Index(name = "ix_stock_movement_ref", columnList = "reference_type, reference_id")
})
public class StockMovement {

    @Id
    private String id;

    @Column(name = "stock_item_id", nullable = false, length = 36)
    private String stockItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private StockMovementType type;

    /** Signed change to {@link StockItem#getOnHand()}. Negative for sales /
     *  waste / transfers; positive for purchases / corrections up. */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal delta;

    @Column(name = "balance_after", nullable = false, precision = 14, scale = 3)
    private BigDecimal balanceAfter;

    /** What caused the movement, e.g. {@code "POS_SALE"} / {@code "EXPENSE"}
     *  / {@code "MANUAL"} / {@code "REVERT"}. Used to find the source row
     *  later (e.g. to undo a sale-decrement when a POS sale is voided). */
    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id", length = 64)
    private String referenceId;

    @Column(length = 500)
    private String reason;

    /** User who triggered the movement. Null for system-generated rows
     *  (e.g. POS sale ingest). */
    @Column(name = "user_id", length = 36)
    private String userId;

    /** True when a later REVERT movement undid this one. */
    @Column(nullable = false)
    private boolean reverted = false;

    @Column(name = "reverted_by_id", length = 36)
    private String revertedById;

    @Column(name = "reverted_at")
    private Instant revertedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStockItemId() { return stockItemId; }
    public void setStockItemId(String stockItemId) { this.stockItemId = stockItemId; }
    public StockMovementType getType() { return type; }
    public void setType(StockMovementType type) { this.type = type; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal delta) { this.delta = delta; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }
    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public boolean isReverted() { return reverted; }
    public void setReverted(boolean reverted) { this.reverted = reverted; }
    public String getRevertedById() { return revertedById; }
    public void setRevertedById(String revertedById) { this.revertedById = revertedById; }
    public Instant getRevertedAt() { return revertedAt; }
    public void setRevertedAt(Instant revertedAt) { this.revertedAt = revertedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
