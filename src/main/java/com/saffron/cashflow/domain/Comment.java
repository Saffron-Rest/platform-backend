package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Polymorphic comment attached to any record type (entries, expenses,
 * salary payments, manual delivery). Body is plain text with @username
 * mentions which are parsed at write time into CommentMention rows.
 *
 * Soft-deleted (deletedAt) rather than removed so the audit log keeps
 * a stable handle. Reads filter out deleted rows.
 */
@Entity
@Table(
        name = "comment",
        indexes = {
                @Index(name = "idx_comment_entity", columnList = "entity_type,entity_id"),
                @Index(name = "idx_comment_author", columnList = "author_id")
        })
public class Comment {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private TaggedEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "author_id", nullable = false)
    private String authorId;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public TaggedEntityType getEntityType() { return entityType; }
    public void setEntityType(TaggedEntityType entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
