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
 * One cash-out event paying back an {@link OwnerExpense}.
 *
 * <p>{@code paidDate} is what affects the cash position. Multiple
 * reimbursements per expense are allowed: an owner might be reimbursed
 * weekly while spending continuously.</p>
 *
 * <p>Reuses {@link SupplierInvoicePayment.PaymentMethod} so the picker
 * UI is consistent across "money paid out". The default is {@code CASH}
 * because owner reimbursements are historically tinted "petty cash"
 * (often paid from the till at end of week).</p>
 */
@Entity
@Table(name = "owner_expense_reimbursement")
public class OwnerExpenseReimbursement {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_expense_id", nullable = false)
    private OwnerExpense ownerExpense;

    @Column(name = "paid_date", nullable = false)
    private LocalDate paidDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierInvoicePayment.PaymentMethod method = SupplierInvoicePayment.PaymentMethod.CASH;

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
    public OwnerExpense getOwnerExpense() { return ownerExpense; }
    public void setOwnerExpense(OwnerExpense ownerExpense) { this.ownerExpense = ownerExpense; }
    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate paidDate) { this.paidDate = paidDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public SupplierInvoicePayment.PaymentMethod getMethod() { return method; }
    public void setMethod(SupplierInvoicePayment.PaymentMethod method) { this.method = method; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
