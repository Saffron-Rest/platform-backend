package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A QR / BLIK payment request for a POS order.
 *
 * <p>Lifecycle: PENDING → CONFIRMED (customer paid) or EXPIRED / CANCELLED.
 * The POS polls {@code GET /pos/qr/{id}/status} every 3 seconds until
 * CONFIRMED or expired.</p>
 *
 * <p>In production this connects to a BLIK provider (e.g. Payten mPay).
 * The stub implementation auto-confirms on the first poll after 5 seconds
 * so the UI can be built and tested without live credentials.</p>
 */
@Entity
@Table(name = "pos_qr_transaction",
        indexes = @Index(name = "ix_pos_qr_order", columnList = "order_id"))
public class PosQrTransaction {

    public enum Status { PENDING, CONFIRMED, EXPIRED, CANCELLED }

    @Id
    private String id;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    /** Opaque QR payload — encodes the transaction ID for the customer's banking app. */
    @Column(name = "qr_payload", nullable = false, length = 512)
    private String qrPayload;

    /** Reference returned by the payment provider on confirmation. */
    @Column(name = "provider_reference", length = 64)
    private String providerReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (expiresAt == null) expiresAt = createdAt.plusSeconds(300); // 5-minute window
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getQrPayload() { return qrPayload; }
    public void setQrPayload(String qrPayload) { this.qrPayload = qrPayload; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
}
