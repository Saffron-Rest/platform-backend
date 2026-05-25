package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Polymorphic link between a Tag and any taggable record. Kept as a flat
 * join table (entityType + entityId) rather than per-record FK tables so
 * adding a new taggable record type doesn't require schema migrations.
 *
 * The (tag_id, entity_type, entity_id) tuple is unique — assigning the
 * same tag twice is a no-op.
 */
@Entity
@Table(
        name = "tag_assignment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tag_assignment",
                columnNames = {"tag_id", "entity_type", "entity_id"}),
        indexes = {
                @Index(name = "idx_tag_assignment_entity", columnList = "entity_type,entity_id"),
                @Index(name = "idx_tag_assignment_tag", columnList = "tag_id")
        })
public class TagAssignment {

    @Id
    private String id;

    @Column(name = "tag_id", nullable = false)
    private String tagId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private TaggedEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "assigned_by", nullable = false)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (assignedAt == null) assignedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTagId() { return tagId; }
    public void setTagId(String tagId) { this.tagId = tagId; }
    public TaggedEntityType getEntityType() { return entityType; }
    public void setEntityType(TaggedEntityType entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getAssignedBy() { return assignedBy; }
    public void setAssignedBy(String assignedBy) { this.assignedBy = assignedBy; }
    public Instant getAssignedAt() { return assignedAt; }
}
