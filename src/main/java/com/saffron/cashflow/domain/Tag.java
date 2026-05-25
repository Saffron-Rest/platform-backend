package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Free-form label that can be attached to entries, expenses, salary payments
 * and manual delivery rows. Designed as a building block for global search,
 * data-health filtering ("show me everything tagged 'investigate'") and
 * export-center grouping.
 */
@Entity
@Table(name = "tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_tag_name", columnNames = "name"))
public class Tag {

    @Id
    private String id;

    /** Display name. Stored case-preserving but compared case-insensitively
     *  for uniqueness (see TagService). */
    @Column(nullable = false, length = 64)
    private String name;

    /** Optional 7-char hex colour ("#RRGGBB"). Frontend falls back to a
     *  deterministic palette when null. */
    @Column(length = 9)
    private String color;

    @Column(length = 200)
    private String description;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
