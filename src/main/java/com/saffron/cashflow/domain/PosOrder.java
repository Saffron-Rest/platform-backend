package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An order created via the Saffron POS. Tracks the full lifecycle from
 * open → paying → paid (or voided). Lines carry the fiscal VAT breakdown
 * so the paragon fiskalny command can be built from this record alone.
 */
@Entity
@Table(name = "pos_order",
        indexes = {
                @Index(name = "ix_pos_order_cashier", columnList = "cashier_id"),
                @Index(name = "ix_pos_order_table", columnList = "table_id"),
                @Index(name = "ix_pos_order_opened", columnList = "opened_at"),
                @Index(name = "ix_pos_order_status", columnList = "status")
        })
public class PosOrder {

    public enum Status { OPEN, PARKED, PAYING, PAID, VOIDED }

    @Id
    private String id;

    @Column(name = "table_id", length = 36)
    private String tableId;

    @Column(name = "cashier_id", nullable = false, length = 36)
    private String cashierId;

    /** The PosIntegration.id for source attribution — always "saffron-pos" for native orders. */
    @Column(name = "integration_id", nullable = false, length = 36)
    private String integrationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.OPEN;

    /** Number of covers (guests) — used for per-cover analytics. */
    @Column(name = "covers")
    private Integer covers;

    /** Optional guest note shown on the KDS ("allergen: nuts, extra spicy"). */
    @Column(name = "order_note", length = 500)
    private String orderNote;

    /** Gross total across all lines (after discounts). */
    @Column(name = "total_gross", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalGross = BigDecimal.ZERO;

    /** Sum of VAT amounts across all lines. */
    @Column(name = "total_vat", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalVat = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private SupplierInvoicePayment.PaymentMethod paymentMethod;

    /** Amount tendered (for cash-change calculation). */
    @Column(name = "amount_tendered", precision = 12, scale = 2)
    private BigDecimal amountTendered;

    /** Fiscal receipt number assigned by the kasa fiskalna after printing. */
    @Column(name = "fiscal_receipt_number", length = 64)
    private String fiscalReceiptNumber;

    /** Buyer's NIP — captured before printing if the customer requests a B2B receipt. */
    @Column(name = "buyer_nip", length = 20)
    private String buyerNip;

    /** Tip amount added on top of the bill (not included in VAT calculation). */
    @Column(name = "tip_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal tipAmount = BigDecimal.ZERO;

    /** When the order was parked (held mid-meal). Null unless status = PARKED. */
    @Column(name = "parked_at")
    private Instant parkedAt;

    /** Optional cashier note set when parking ("waiting for dessert"). */
    @Column(name = "parked_note", length = 200)
    private String parkedNote;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PosOrderLine> lines = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (openedAt == null) openedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }
    public String getCashierId() { return cashierId; }
    public void setCashierId(String cashierId) { this.cashierId = cashierId; }
    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String integrationId) { this.integrationId = integrationId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Integer getCovers() { return covers; }
    public void setCovers(Integer covers) { this.covers = covers; }
    public String getOrderNote() { return orderNote; }
    public void setOrderNote(String orderNote) { this.orderNote = orderNote; }
    public BigDecimal getTotalGross() { return totalGross; }
    public void setTotalGross(BigDecimal totalGross) { this.totalGross = totalGross; }
    public BigDecimal getTotalVat() { return totalVat; }
    public void setTotalVat(BigDecimal totalVat) { this.totalVat = totalVat; }
    public SupplierInvoicePayment.PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(SupplierInvoicePayment.PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getAmountTendered() { return amountTendered; }
    public void setAmountTendered(BigDecimal amountTendered) { this.amountTendered = amountTendered; }
    public String getFiscalReceiptNumber() { return fiscalReceiptNumber; }
    public void setFiscalReceiptNumber(String fiscalReceiptNumber) { this.fiscalReceiptNumber = fiscalReceiptNumber; }
    public String getBuyerNip() { return buyerNip; }
    public void setBuyerNip(String buyerNip) { this.buyerNip = buyerNip; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public BigDecimal getTipAmount() { return tipAmount; }
    public void setTipAmount(BigDecimal tipAmount) { this.tipAmount = tipAmount; }
    public Instant getParkedAt() { return parkedAt; }
    public void setParkedAt(Instant parkedAt) { this.parkedAt = parkedAt; }
    public String getParkedNote() { return parkedNote; }
    public void setParkedNote(String parkedNote) { this.parkedNote = parkedNote; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public List<PosOrderLine> getLines() { return lines; }
}
