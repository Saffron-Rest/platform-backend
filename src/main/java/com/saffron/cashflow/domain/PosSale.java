package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One sold line ingested from the POS — flattened so analytics queries are
 * trivially fast (no joins needed for top-N sellers and mix breakdowns).
 *
 * Idempotency is enforced via {@code externalId} so the POS can safely retry
 * webhook deliveries without creating duplicates.
 */
@Entity
@Table(name = "pos_sale",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pos_sale_external",
                columnNames = {"integration_id", "external_id"}),
        indexes = {
                @Index(name = "ix_pos_sale_occurred", columnList = "occurred_at"),
                @Index(name = "ix_pos_sale_business_day", columnList = "business_day"),
                @Index(name = "ix_pos_sale_item", columnList = "menu_item_id"),
                @Index(name = "ix_pos_sale_sku", columnList = "sku")
        })
public class PosSale {

    @Id
    private String id;

    /** Integration that delivered the sale — also used to scope the unique
     *  {@code externalId}. */
    @Column(name = "integration_id", nullable = false, length = 36)
    private String integrationId;

    /** POS-generated id used for idempotency (order id + line index, or
     *  receipt id). Treated as opaque. */
    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    /** Best-effort resolved item id. Null when the SKU/name doesn't match the
     *  menu — those sales are still ingested but show under "Unmatched". */
    @Column(name = "menu_item_id", length = 36)
    private String menuItemId;

    @Column(length = 64)
    private String sku;

    @Column(name = "item_name", length = 160)
    private String itemName;

    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Column(precision = 12, scale = 3, nullable = false)
    private BigDecimal quantity;

    /** Unit gross price as charged at the POS. */
    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    /** Per-unit discount (positive number). */
    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    /** Per-unit food cost snapshot. We snapshot at ingest time so future menu
     *  price changes don't retroactively change historical margin. */
    @Column(name = "food_cost", precision = 12, scale = 2)
    private BigDecimal foodCost;

    @Column(name = "payment_method", length = 32)
    private String paymentMethod;

    /** Server-local instant when the sale happened at the POS. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Business day in Europe/Warsaw — used for shift-aligned grouping. */
    @Column(name = "business_day", nullable = false)
    private LocalDate businessDay;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    /** VAT rate applied to this line (% e.g. 8.00 or 23.00).
     *  Snapshotted from MenuItem.vatRatePct at ingest time. */
    @Column(name = "vat_rate_pct", precision = 5, scale = 2)
    private BigDecimal vatRatePct;

    /** Net (ex-VAT) amount = quantity × unitPrice / (1 + vatRate). */
    @Column(name = "vat_net_amount", precision = 12, scale = 2)
    private BigDecimal vatNetAmount;

    /** VAT amount = gross - net. */
    @Column(name = "vat_amount", precision = 12, scale = 2)
    private BigDecimal vatAmount;

    /** Fiscal receipt number assigned by the kasa fiskalna after printing.
     *  Null until the receipt is physically printed and confirmed. */
    @Column(name = "fiscal_receipt_number", length = 64)
    private String fiscalReceiptNumber;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (receivedAt == null) receivedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String integrationId) { this.integrationId = integrationId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getFoodCost() { return foodCost; }
    public void setFoodCost(BigDecimal foodCost) { this.foodCost = foodCost; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public LocalDate getBusinessDay() { return businessDay; }
    public void setBusinessDay(LocalDate businessDay) { this.businessDay = businessDay; }
    public Instant getReceivedAt() { return receivedAt; }
    public BigDecimal getVatRatePct() { return vatRatePct; }
    public void setVatRatePct(BigDecimal vatRatePct) { this.vatRatePct = vatRatePct; }
    public BigDecimal getVatNetAmount() { return vatNetAmount; }
    public void setVatNetAmount(BigDecimal vatNetAmount) { this.vatNetAmount = vatNetAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public void setVatAmount(BigDecimal vatAmount) { this.vatAmount = vatAmount; }
    public String getFiscalReceiptNumber() { return fiscalReceiptNumber; }
    public void setFiscalReceiptNumber(String fiscalReceiptNumber) { this.fiscalReceiptNumber = fiscalReceiptNumber; }
}
