package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single line on a {@link SupplierInvoice}.
 *
 * <p>Two flavours:</p>
 * <ul>
 *   <li><b>Stock-linked</b> — {@code stockItemId} set. On invoice
 *       creation we post a {@link StockMovementType#PURCHASE} for
 *       {@code quantity} units and stash the resulting movement id on
 *       {@code stockMovementId} so a later void can revert exactly
 *       that one.</li>
 *   <li><b>Description-only</b> — services or one-off items that don't
 *       hit inventory (e.g. "Cleaning January", "Plumber call-out").
 *       {@code stockItemId} is null and no movement is posted.</li>
 * </ul>
 */
@Entity
@Table(name = "supplier_invoice_line")
public class SupplierInvoiceLine {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SupplierInvoice invoice;

    /** Optional FK to {@link StockItem#getId()}. Stored as a raw string
     *  (no SQL FK) so archiving a stock item doesn't cascade-delete
     *  historical line items. */
    @Column(name = "stock_item_id", length = 36)
    private String stockItemId;

    @Column(nullable = false, length = 300)
    private String description = "";

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(nullable = false, length = 16)
    private String unit = "pcs";

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    /** Set when a {@link StockMovement} was posted for this line.
     *  Used by void to revert exactly that one movement. */
    @Column(name = "stock_movement_id", length = 36)
    private String stockMovementId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public SupplierInvoice getInvoice() { return invoice; }
    public void setInvoice(SupplierInvoice invoice) { this.invoice = invoice; }
    public String getStockItemId() { return stockItemId; }
    public void setStockItemId(String stockItemId) { this.stockItemId = stockItemId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
    public String getStockMovementId() { return stockMovementId; }
    public void setStockMovementId(String stockMovementId) { this.stockMovementId = stockMovementId; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
