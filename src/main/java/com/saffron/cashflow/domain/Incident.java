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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Operational incident — anything worth a "this happened" entry.
 *
 * <p>Designed to be the single place admins and managers go when something
 * goes wrong: broken oven, customer complaint, slip-and-fall, missing
 * delivery, cash short, etc. We deliberately keep the schema lean and lean
 * on the existing polymorphic <code>Comment</code> + <code>Tag</code>
 * tables for everything that doesn't fit a single column.</p>
 *
 * <p>Photo evidence is stored via the regular {@link com.saffron.cashflow.service.FileStorageService}
 * upload flow — the relative path is kept in {@link #photoPath}. Multiple
 * photos can be attached as comments with image attachments.</p>
 */
@Entity
@Table(name = "incident", indexes = {
        @Index(name = "ix_incident_status", columnList = "status"),
        @Index(name = "ix_incident_occurred", columnList = "occurred_on"),
        @Index(name = "ix_incident_assignee", columnList = "assignee_id")
})
public class Incident {

    @Id
    private String id;

    @Column(nullable = false, length = 200)
    private String title;

    /** Free-form category label so the catalogue stays open and admins
     *  don't beg for new enum values. Suggestions surface in the UI. */
    @Column(length = 60)
    private String category;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentSeverity severity = IncidentSeverity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Column(columnDefinition = "text")
    private String description;

    /** Optional financial impact in zł — replacement cost, refund issued,
     *  cash short. Aggregated in the analytics PDF as "Incident losses". */
    @Column(name = "estimated_cost", precision = 12, scale = 2)
    private BigDecimal estimatedCost;

    /** Relative path under {@code app.upload-dir} (e.g. {@code incident/abc.jpg}). */
    @Column(name = "photo_path", length = 255)
    private String photoPath;

    @Column(name = "reported_by_id", length = 36, nullable = false)
    private String reportedById;

    @Column(name = "assignee_id", length = 36)
    private String assigneeId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_id", length = 36)
    private String resolvedById;

    @Column(name = "resolution_notes", columnDefinition = "text")
    private String resolutionNotes;

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
        if (occurredOn == null) occurredOn = LocalDate.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public LocalDate getOccurredOn() { return occurredOn; }
    public void setOccurredOn(LocalDate occurredOn) { this.occurredOn = occurredOn; }
    public IncidentSeverity getSeverity() { return severity; }
    public void setSeverity(IncidentSeverity severity) { this.severity = severity; }
    public IncidentStatus getStatus() { return status; }
    public void setStatus(IncidentStatus status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }
    public String getReportedById() { return reportedById; }
    public void setReportedById(String reportedById) { this.reportedById = reportedById; }
    public String getAssigneeId() { return assigneeId; }
    public void setAssigneeId(String assigneeId) { this.assigneeId = assigneeId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedById() { return resolvedById; }
    public void setResolvedById(String resolvedById) { this.resolvedById = resolvedById; }
    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
