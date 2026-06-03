package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A cash-in or cash-out event that is NOT tied to a sale.
 * Examples: bank drop (out), petty-cash top-up (in), supplier cash payment (out).
 *
 * <p>Tracked against a {@link PosSession} so the closing cash count
 * reconciliation is always: openingFloat + cashSales + deposits - withdrawals = closingCount.</p>
 */
@Entity
@Table(name = "cash_drawer_transaction",
        indexes = @Index(name = "ix_cash_drawer_session", columnList = "session_id"))
public class CashDrawerTransaction {

    public enum Type { IN, OUT }

    public enum Reason {
        BANK_DEPOSIT, SUPPLIER_PAYMENT, PETTY_CASH, CHANGE_FUND, OTHER
    }

    @Id
    private String id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "cashier_id", nullable = false, length = 36)
    private String cashierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Type type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason = Reason.OTHER;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getCashierId() { return cashierId; }
    public void setCashierId(String cashierId) { this.cashierId = cashierId; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Reason getReason() { return reason; }
    public void setReason(Reason reason) { this.reason = reason; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
}
