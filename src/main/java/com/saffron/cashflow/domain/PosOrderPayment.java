package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payment leg in a split-payment transaction.
 * A single order may be settled with multiple methods (cash + card + voucher).
 */
@Entity
@Table(name = "pos_order_payment",
        indexes = @Index(name = "ix_pos_order_payment_order", columnList = "order_id"))
public class PosOrderPayment {

    @Id
    private String id;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierInvoicePayment.PaymentMethod method;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** External reference — card auth code, BLIK confirmation, voucher serial, etc. */
    @Column(length = 64)
    private String reference;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (processedAt == null) processedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public SupplierInvoicePayment.PaymentMethod getMethod() { return method; }
    public void setMethod(SupplierInvoicePayment.PaymentMethod method) { this.method = method; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public Instant getProcessedAt() { return processedAt; }
}
