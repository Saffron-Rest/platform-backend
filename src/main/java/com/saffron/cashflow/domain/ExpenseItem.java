package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "expense_item")
public class ExpenseItem {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id")
    private DailyEntry entry;

    /** Business date for reporting (shift date or post-close purchase date). */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseCategory category = ExpenseCategory.OTHER;

    @Column(nullable = false)
    private String description = "";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_source", nullable = false)
    private PaymentSource paymentSource = PaymentSource.CASH;

    @OneToMany(mappedBy = "expenseItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @BatchSize(size = 32)
    private List<ReceiptFile> invoices = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
        if (effectiveDate == null && entry != null) {
            effectiveDate = entry.getDate();
        }
    }

    public String getId() { return id; }
    public DailyEntry getEntry() { return entry; }
    public void setEntry(DailyEntry entry) { this.entry = entry; }
    public String getEntryId() { return entry != null ? entry.getId() : null; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
    public boolean isStandalone() { return entry == null; }
    public ExpenseCategory getCategory() { return category; }
    public void setCategory(ExpenseCategory category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public PaymentSource getPaymentSource() { return paymentSource; }
    public void setPaymentSource(PaymentSource paymentSource) { this.paymentSource = paymentSource; }
    public List<ReceiptFile> getInvoices() { return invoices; }

    public void addInvoice(ReceiptFile invoice) {
        invoices.add(invoice);
        invoice.setExpenseItem(this);
    }

    public Instant getCreatedAt() { return createdAt; }
}
