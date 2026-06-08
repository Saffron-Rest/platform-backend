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

    /** Discount type: {@code "PERCENTAGE"} or {@code "AMOUNT"}. Null = no discount. */
    @Column(name = "discount_type", length = 12)
    private String discountType;

    /** The discount rate (%) or flat amount (PLN) entered by the user. */
    @Column(name = "discount_value", precision = 12, scale = 4)
    private BigDecimal discountValue;

    /** Computed discount in PLN = lineGross × discountPct/100 or = discountValue.
     *  Stored so the UI can show the saving without recomputing. */
    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    /** VAT rate for this line in percent (e.g. 8.00 for 8%, 23.00 for 23%).
     *  VAT is applied on the post-discount net. Null = not tracked per-line. */
    @Column(name = "vat_pct", precision = 5, scale = 2)
    private BigDecimal vatPct;

    /** VAT amount = lineTotal × vatPct / 100. Stored so the invoice-level
     *  VAT total is just a sum — no need to recompute. */
    @Column(name = "vat_amount", precision = 12, scale = 2)
    private BigDecimal vatAmount;

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
    public String getDiscountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }
    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getVatPct() { return vatPct; }
    public void setVatPct(BigDecimal vatPct) { this.vatPct = vatPct; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public void setVatAmount(BigDecimal vatAmount) { this.vatAmount = vatAmount; }
    public String getStockMovementId() { return stockMovementId; }
    public void setStockMovementId(String stockMovementId) { this.stockMovementId = stockMovementId; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
