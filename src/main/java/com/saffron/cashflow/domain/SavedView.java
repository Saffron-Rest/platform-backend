package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-user pinned filter snapshot for a specific page (finance ledger,
 * shift reports, payouts, …). The filter state is stored as the raw JSON
 * string the UI uses to reconstruct itself — keeps the backend agnostic
 * to page-specific filter shapes.
 *
 * Unique on (user_id, page, name) so a user can have e.g. "March 2026"
 * twice across different pages but not duplicated within one page.
 */
@Entity
@Table(
        name = "saved_view",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_saved_view",
                columnNames = {"user_id", "page", "name"}),
        indexes = @Index(name = "idx_saved_view_user", columnList = "user_id,page"))
public class SavedView {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** Slug identifying the page — eg. "finance.expenses", "reports". */
    @Column(nullable = false, length = 64)
    private String page;

    @Column(nullable = false, length = 80)
    private String name;

    /** Serialised filter state. We use a TEXT column rather than JSON to
     *  keep migrations simple; payloads are tiny. */
    @Lob
    @Column(name = "filters_json", nullable = false, columnDefinition = "TEXT")
    private String filtersJson;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

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
    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
