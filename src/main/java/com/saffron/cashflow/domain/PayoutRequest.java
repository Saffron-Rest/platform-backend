package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cashier's self-service request to be paid out some or all of their
 * accrued but unpaid earnings.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Cashier submits → status = {@code PENDING}.</li>
 *   <li>Admin approves → a {@link SalaryPayment} is created and
 *       {@code salaryPaymentId} is set; status = {@code APPROVED}.</li>
 *   <li>Admin declines → status = {@code DECLINED}, {@code adminNotes}
 *       carries the reason. No salary payment is created and no report
 *       numbers change.</li>
 * </ol>
 */
@Entity
@Table(name = "payout_request", indexes = {
        @Index(name = "ix_payout_request_user", columnList = "user_id, created_at DESC"),
        @Index(name = "ix_payout_request_status", columnList = "status, created_at DESC")
})
public class PayoutRequest {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "requested_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "requested_date", nullable = false)
    private LocalDate requestedDate;

    @Column(length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PayoutRequestStatus status = PayoutRequestStatus.PENDING;

    @Column(name = "admin_notes", length = 500)
    private String adminNotes;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Set when the request is approved — links to the created SalaryPayment. */
    @Column(name = "salary_payment_id", length = 36)
    private String salaryPaymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (requestedDate == null) requestedDate = LocalDate.now();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public void setRequestedAmount(BigDecimal requestedAmount) { this.requestedAmount = requestedAmount; }
    public LocalDate getRequestedDate() { return requestedDate; }
    public void setRequestedDate(LocalDate requestedDate) { this.requestedDate = requestedDate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public PayoutRequestStatus getStatus() { return status; }
    public void setStatus(PayoutRequestStatus status) { this.status = status; }
    public String getAdminNotes() { return adminNotes; }
    public void setAdminNotes(String adminNotes) { this.adminNotes = adminNotes; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getSalaryPaymentId() { return salaryPaymentId; }
    public void setSalaryPaymentId(String salaryPaymentId) { this.salaryPaymentId = salaryPaymentId; }
    public Instant getCreatedAt() { return createdAt; }
}
