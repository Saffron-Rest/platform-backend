package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A calendar day on which the restaurant was deliberately closed
 * (public holiday, scheduled break, renovation, etc.).
 *
 * <p>The closure list is consulted by the shift-report flow to bypass
 * the "previous shift not submitted" gate — operators don't have to file
 * an empty report for a day the restaurant wasn't open. The date itself
 * is the natural primary key since at most one closure is allowed per
 * day.</p>
 */
@Entity
@Table(name = "restaurant_closure")
public class RestaurantClosure {

    /** The closed calendar day. PK because there is at most one closure per day. */
    @Id
    @Column(name = "closure_date", nullable = false)
    private LocalDate date;

    /** Why the restaurant was closed. Free-form, shown in the calendar UI. */
    @Column(nullable = false, length = 200)
    private String reason;

    /** User id of the admin who recorded the closure. */
    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
