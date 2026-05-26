package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Certification or training record attached to a {@link User}.
 *
 * <p>Common Polish examples:</p>
 * <ul>
 *   <li>{@code KSIAZECZKA_SANEPID} — food handler health book (required for all
 *       kitchen + service staff).</li>
 *   <li>{@code BHP} — workplace safety induction.</li>
 *   <li>{@code ALKOHOL} — alcohol licence holder.</li>
 *   <li>{@code FIRST_AID}, {@code HACCP_TRAINING}, … — free-form labels are
 *       allowed so admins don't beg for enum values.</li>
 * </ul>
 *
 * <p>A daily scheduled job (see {@link com.saffron.cashflow.service.CertExpiryReminderJob})
 * scans this table and creates a notification 30 / 14 / 1 days before
 * {@code expiresOn}, then a final overdue notification on the day. We track
 * {@link #lastWarningAt} so the same user doesn't get spammed every day.</p>
 */
@Entity
@Table(name = "employee_cert", indexes = {
        @Index(name = "ix_employee_cert_user", columnList = "user_id"),
        @Index(name = "ix_employee_cert_expires", columnList = "expires_on")
})
public class EmployeeCert {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** Free-form type label so the admin can add new ones without a code
     *  change (e.g. "książeczka sanepidowska", "BHP", "alcohol licence"). */
    @Column(nullable = false, length = 60)
    private String type;

    /** Optional certificate / book / licence number for record-keeping. */
    @Column(length = 120)
    private String number;

    /** Who issued it (Sanepid, training company, etc.). */
    @Column(length = 160)
    private String issuer;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(columnDefinition = "text")
    private String notes;

    /** Optional scan of the cert (PDF or photo). Same upload prefix as
     *  incidents and HACCP — {@code cert/...}. */
    @Column(name = "file_path", length = 255)
    private String filePath;

    /** Timestamp of the most recent expiry-warning notification we
     *  emitted. Used by the daily job to avoid re-notifying every day. */
    @Column(name = "last_warning_at")
    private Instant lastWarningAt;

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
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public void setIssuedOn(LocalDate issuedOn) { this.issuedOn = issuedOn; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Instant getLastWarningAt() { return lastWarningAt; }
    public void setLastWarningAt(Instant lastWarningAt) { this.lastWarningAt = lastWarningAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
