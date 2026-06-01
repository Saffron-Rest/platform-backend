package com.saffron.cashflow.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * One supplier delivery / bill recorded as accounts payable.
 *
 * <p>Hits the P&amp;L on {@code invoiceDate} (accrual). The cash event
 * happens later, recorded as one or more {@link SupplierInvoicePayment}
 * rows whose {@code paymentDate} drives the treasury balance — never
 * the {@code invoiceDate}.</p>
 *
 * <p>{@code amountPaid} is denormalised: every payment write recomputes
 * it so the list view stays cheap. The {@link SupplierInvoiceStatus} is
 * derived from {@code amountPaid} vs {@code total} but persisted so
 * "give me everything outstanding" stays an indexed lookup.</p>
 *
 * <p>Lines are cascade-deleted with the invoice ({@code orphanRemoval =
 * true}). Payments cascade-delete too — undoing a void wouldn't make
 * accounting sense anyway, so we don't try to preserve them.</p>
 */
@Entity
@Table(name = "supplier_invoice", indexes = {
        @Index(name = "ix_supplier_invoice_supplier", columnList = "supplier_id, invoice_date DESC"),
        @Index(name = "ix_supplier_invoice_status", columnList = "status, due_date"),
        @Index(name = "ix_supplier_invoice_invoice_date", columnList = "invoice_date")
})
public class SupplierInvoice {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    /** Free-form invoice number from the supplier (e.g. "INV-2026-042").
     *  Optional — handwritten deliveries don't always have one. */
    @Column(name = "invoice_number", length = 80)
    private String invoiceNumber;

    /** When the goods/services were delivered. Drives COGS recognition
     *  on the P&L and the start of the credit window. */
    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    /** When payment is due. Pre-filled from {@link Supplier#getPaymentTermsDays()}
     *  but freely editable per invoice. */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** Bucketed under the same expense category system as
     *  {@link ExpenseItem} so the P&L can roll them up consistently.
     *  Defaults to {@link ExpenseCategory#SUPPLIER}; a non-stock
     *  service invoice can be re-categorised (e.g. CLEANING). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExpenseCategory category = ExpenseCategory.SUPPLIER;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal vat = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierInvoiceStatus status = SupplierInvoiceStatus.UNPAID;

    @Column(columnDefinition = "text")
    private String notes;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<SupplierInvoiceLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("paymentDate DESC, createdAt DESC")
    private List<SupplierInvoicePayment> payments = new ArrayList<>();

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

    /** Convenience helper: how much is still owed to the supplier. */
    public BigDecimal outstanding() {
        BigDecimal o = (total == null ? BigDecimal.ZERO : total)
                .subtract(amountPaid == null ? BigDecimal.ZERO : amountPaid);
        return o.signum() < 0 ? BigDecimal.ZERO : o;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier supplier) { this.supplier = supplier; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public ExpenseCategory getCategory() { return category; }
    public void setCategory(ExpenseCategory category) { this.category = category; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getVat() { return vat; }
    public void setVat(BigDecimal vat) { this.vat = vat; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public void setAmountPaid(BigDecimal amountPaid) { this.amountPaid = amountPaid; }
    public SupplierInvoiceStatus getStatus() { return status; }
    public void setStatus(SupplierInvoiceStatus status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<SupplierInvoiceLine> getLines() { return lines; }
    public List<SupplierInvoicePayment> getPayments() { return payments; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Instant voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
}
