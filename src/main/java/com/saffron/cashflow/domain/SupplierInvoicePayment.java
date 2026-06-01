package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One cash settlement against a {@link SupplierInvoice}.
 *
 * <p>{@code paymentDate} is the cash date — what affects treasury and
 * the Finance ledger. Multiple payments per invoice are allowed
 * (partial settlements are common with monthly suppliers).</p>
 *
 * <p>This row never hits the P&amp;L: COGS was recognised when the
 * invoice was first booked. Only the cash account moves on payment.</p>
 */
@Entity
@Table(name = "supplier_invoice_payment")
public class SupplierInvoicePayment {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SupplierInvoice invoice;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method = PaymentMethod.BANK_TRANSFER;

    /** Bank reference, transaction id, cheque number, etc. */
    @Column(length = 120)
    private String reference;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_by", length = 36)
    private String createdBy;

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
    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public PaymentMethod getMethod() { return method; }
    public void setMethod(PaymentMethod method) { this.method = method; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    /** Payment channels we support. Cash and CARD overlap with the
     *  POS/expense {@link PaymentSource} but we want a richer set
     *  (bank transfer, cheque) so this is its own enum. */
    public enum PaymentMethod {
        CASH,
        CARD,
        BANK_TRANSFER,
        CHEQUE,
        OTHER
    }
}
