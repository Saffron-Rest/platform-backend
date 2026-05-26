package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A reusable checklist that staff fill in every shift.
 *
 * <p>Items are stored as a JSON array in {@link #items} — the schema for a
 * single item is:</p>
 * <pre>{
 *   "id": "uuid",
 *   "label": "Wipe down stations",
 *   "requiresPhoto": false,
 *   "requiresTemperature": false
 * }</pre>
 *
 * <p>We picked JSON over a separate {@code checklist_item} table because
 * (a) items always move together with the template, (b) reordering is
 * trivial, (c) the average template has 5–15 items so query cost isn't a
 * concern. If we ever need to attribute completion per-item per-run we
 * already store responses keyed by {@code id}, see {@link ChecklistRun}.</p>
 */
@Entity
@Table(name = "checklist_template", indexes = {
        @Index(name = "ix_checklist_template_type", columnList = "type")
})
public class ChecklistTemplate {

    @Id
    private String id;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChecklistType type = ChecklistType.OPENING;

    /** Optional role hint (e.g. CASHIER, MANAGER) — purely for filtering
     *  the daily-run UI; access control still goes through Spring Security. */
    @Column(length = 40)
    private String role;

    @Column(columnDefinition = "text")
    private String description;

    /** JSON-encoded list of items. Always populated, never null. */
    @Column(nullable = false, columnDefinition = "text")
    private String items = "[]";

    @Column(nullable = false)
    private boolean active = true;

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
    public ChecklistType getType() { return type; }
    public void setType(ChecklistType type) { this.type = type; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
