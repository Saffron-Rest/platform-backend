package com.saffron.cashflow.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A restaurant expense paid out of the owner's personal pocket.
 *
 * <p>Models the inverse of an {@link DailyEntry#getOwnerWithdrawal()
 * owner withdrawal}: instead of the owner taking money <em>out</em> of
 * the till, they put their own money <em>in</em> by paying for an
 * expense the restaurant should have covered. The result is a debt
 * <em>from</em> the restaurant <em>to</em> the owner — settled by one
 * or more {@link OwnerExpenseReimbursement} rows whose {@code paidDate}
 * drives the cash account.</p>
 *
 * <p>Hits the P&amp;L on {@code expenseDate} (accrual). The eventual
 * reimbursement is purely a cash event, never a P&amp;L event — that's
 * the same accountant-friendly split we adopted for supplier
 * payables.</p>
 *
 * <p>{@code ownerUserId} is stored as a raw string (no JPA FK) so a
 * deactivated user doesn't cascade-delete their reimbursement history.
 * The service layer joins back to {@link User} lazily for display.</p>
 */
@Entity
@Table(name = "owner_expense", indexes = {
        @Index(name = "ix_owner_expense_owner", columnList = "owner_user_id, expense_date DESC"),
        @Index(name = "ix_owner_expense_status", columnList = "status, expense_date"),
        @Index(name = "ix_owner_expense_category", columnList = "category, expense_date")
})
public class OwnerExpense {

    @Id
    private String id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExpenseCategory category = ExpenseCategory.OTHER;

    /** Free-form description ("Cleaning supplies at Carrefour", "Plumber
     *  emergency call"). Required so the audit trail is self-explanatory. */
    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "amount_reimbursed", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountReimbursed = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerExpenseStatus status = OwnerExpenseStatus.PENDING;

    /** Optional external reference — receipt number, bank statement id,
     *  etc. Useful when the owner uploads a photo elsewhere and just
     *  wants to cross-reference here. */
    @Column(length = 120)
    private String reference;

    @Column(columnDefinition = "text")
    private String notes;

    @OneToMany(mappedBy = "ownerExpense", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("paidDate DESC, createdAt DESC")
    private List<OwnerExpenseReimbursement> reimbursements = new ArrayList<>();

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 36)
    private String voidedBy;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** How much the restaurant still owes the owner. */
    public BigDecimal outstanding() {
        BigDecimal o = (total == null ? BigDecimal.ZERO : total)
                .subtract(amountReimbursed == null ? BigDecimal.ZERO : amountReimbursed);
        return o.signum() < 0 ? BigDecimal.ZERO : o;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
    public ExpenseCategory getCategory() { return category; }
    public void setCategory(ExpenseCategory category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public BigDecimal getAmountReimbursed() { return amountReimbursed; }
    public void setAmountReimbursed(BigDecimal amountReimbursed) { this.amountReimbursed = amountReimbursed; }
    public OwnerExpenseStatus getStatus() { return status; }
    public void setStatus(OwnerExpenseStatus status) { this.status = status; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<OwnerExpenseReimbursement> getReimbursements() { return reimbursements; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Instant voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
}
