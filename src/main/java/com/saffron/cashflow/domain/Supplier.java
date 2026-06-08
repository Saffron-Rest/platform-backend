package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Vendor master record for accounts-payable / supplier credit.
 *
 * <p>Each row represents a third party we buy from on credit (food
 * suppliers, beverage distributors, cleaning services, etc.). Stored
 * separately from {@link User}s because suppliers don't log in and
 * require fields that don't fit the user model (VAT id, default
 * payment terms, address).</p>
 *
 * <p>{@code paymentTermsDays} is used to pre-fill the due date when
 * recording a {@link SupplierInvoice}. {@code 0} means "due on
 * delivery" (cash sale on a tab settled at end of shift); positive
 * values count working/calendar days from the invoice date.</p>
 */
@Entity
@Table(name = "supplier", indexes = {
        @Index(name = "ix_supplier_active", columnList = "active")
})
public class Supplier {

    @Id
    private String id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "contact_name", length = 160)
    private String contactName;

    @Column(length = 64)
    private String phone;

    @Column(length = 160)
    private String email;

    @Column(name = "vat_id", length = 40)
    private String vatId;

    @Column(length = 400)
    private String address;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays = 7;

    /** IBAN or local account number for payment transfers. */
    @Column(name = "bank_account_number", length = 40)
    private String bankAccountNumber;

    /** Bank name shown on the payment confirmation screen. */
    @Column(name = "bank_name", length = 100)
    private String bankName;

    /** BIC / SWIFT code for international transfers. */
    @Column(name = "bank_bic_swift", length = 16)
    private String bankBicSwift;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getVatId() { return vatId; }
    public void setVatId(String vatId) { this.vatId = vatId; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public int getPaymentTermsDays() { return paymentTermsDays; }
    public void setPaymentTermsDays(int paymentTermsDays) { this.paymentTermsDays = paymentTermsDays; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBankBicSwift() { return bankBicSwift; }
    public void setBankBicSwift(String bankBicSwift) { this.bankBicSwift = bankBicSwift; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
