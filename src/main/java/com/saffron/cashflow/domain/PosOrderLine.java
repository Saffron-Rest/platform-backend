package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One line in a POS order. VAT fields are computed at line creation from
 * the menu item's current vatRatePct so the fiscal receipt command has all
 * the data it needs without back-calculating at print time.
 */
@Entity
@Table(name = "pos_order_line",
        indexes = @Index(name = "ix_pos_order_line_order", columnList = "order_id"))
public class PosOrderLine {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private PosOrder order;

    @Column(name = "menu_item_id", length = 36)
    private String menuItemId;

    /** Snapshot of the item name at order time — survives menu renames. */
    @Column(name = "item_name", nullable = false, length = 160)
    private String itemName;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Gross (VAT-inclusive) unit price at order time. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** VAT rate snapshotted from MenuItem.vatRatePct (e.g. 8.00 or 23.00). */
    @Column(name = "vat_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRatePct;

    /** Per-unit discount (positive number). Zero if none. */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** Line gross = quantity × (unitPrice − discount). */
    @Column(name = "line_gross", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineGross;

    /** Net amount = lineGross / (1 + vatRate/100). */
    @Column(name = "vat_net_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal vatNetAmount;

    /** VAT amount = lineGross − vatNetAmount. */
    @Column(name = "vat_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal vatAmount;

    /** Optional modifier note ("well done", "no onion"). */
    @Column(length = 200)
    private String note;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        recomputeVat();
    }

    /** Recomputes derived VAT fields from quantity, unitPrice, discountAmount, vatRatePct. */
    public void recomputeVat() {
        if (quantity == null || unitPrice == null || vatRatePct == null) return;
        BigDecimal disc = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        BigDecimal gross = quantity.multiply(unitPrice.subtract(disc)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal divisor = BigDecimal.ONE.add(vatRatePct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
        BigDecimal net = gross.divide(divisor, 2, RoundingMode.HALF_UP);
        this.lineGross = gross;
        this.vatNetAmount = net;
        this.vatAmount = gross.subtract(net);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public PosOrder getOrder() { return order; }
    public void setOrder(PosOrder order) { this.order = order; }
    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getVatRatePct() { return vatRatePct; }
    public void setVatRatePct(BigDecimal vatRatePct) { this.vatRatePct = vatRatePct; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getLineGross() { return lineGross; }
    public BigDecimal getVatNetAmount() { return vatNetAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
