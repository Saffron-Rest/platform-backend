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
 * A single completion of a {@link ChecklistTemplate} for a given day.
 *
 * <p>{@link #responses} is a JSON object keyed by item id; each value
 * contains {@code checked}, {@code notes}, {@code photoPath}, and a
 * {@code checkedAt} timestamp. We keep totals on the entity itself
 * ({@link #totalItems}, {@link #completedItems}) so the list view doesn't
 * have to parse JSON to show "8 of 12 done".</p>
 */
@Entity
@Table(name = "checklist_run", indexes = {
        @Index(name = "ix_checklist_run_date", columnList = "run_date"),
        @Index(name = "ix_checklist_run_template", columnList = "template_id")
})
public class ChecklistRun {

    @Id
    private String id;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(name = "completed_by_id", length = 36)
    private String completedById;

    /** JSON object keyed by item id. */
    @Column(nullable = false, columnDefinition = "text")
    private String responses = "{}";

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "completed_items", nullable = false)
    private int completedItems;

    @Column(columnDefinition = "text")
    private String notes;

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
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public LocalDate getRunDate() { return runDate; }
    public void setRunDate(LocalDate runDate) { this.runDate = runDate; }
    public String getCompletedById() { return completedById; }
    public void setCompletedById(String completedById) { this.completedById = completedById; }
    public String getResponses() { return responses; }
    public void setResponses(String responses) { this.responses = responses; }
    public int getTotalItems() { return totalItems; }
    public void setTotalItems(int totalItems) { this.totalItems = totalItems; }
    public int getCompletedItems() { return completedItems; }
    public void setCompletedItems(int completedItems) { this.completedItems = completedItems; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
