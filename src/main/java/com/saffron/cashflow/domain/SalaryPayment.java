package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "salary_payment")
public class SalaryPayment {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_date", nullable = false)
    private LocalDate paidDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_source", nullable = false)
    private PaymentSource paymentSource;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    private String notes;

    /** When true the payment is recorded for payroll bookkeeping but does NOT
     *  reduce treasury balances (e.g. off-the-books bonus paid from owner's
     *  personal pocket, or any reconciliation we don't want to affect cash/card
     *  on hand). Nullable for historical rows; null is treated as false. */
    @Column(name = "exclude_from_treasury")
    private Boolean excludeFromTreasury;

    @Column(name = "created_by", nullable = false)
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
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate paidDate) { this.paidDate = paidDate; }
    public PaymentSource getPaymentSource() { return paymentSource; }
    public void setPaymentSource(PaymentSource paymentSource) { this.paymentSource = paymentSource; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(LocalDate periodFrom) { this.periodFrom = periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public void setPeriodTo(LocalDate periodTo) { this.periodTo = periodTo; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isExcludeFromTreasury() { return Boolean.TRUE.equals(excludeFromTreasury); }
    public void setExcludeFromTreasury(Boolean excludeFromTreasury) { this.excludeFromTreasury = excludeFromTreasury; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
