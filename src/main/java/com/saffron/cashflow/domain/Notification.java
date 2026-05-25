package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * In-app notification for a single user. Mirrors what push notifications
 * carry but persists across sessions so web admins can catch up on
 * mentions even if they were AFK when the push arrived.
 *
 * Polymorphic target so a notification can deep-link straight to whatever
 * triggered it (comment, audit alert, schedule reminder, …).
 */
@Entity
@Table(
        name = "notification",
        indexes = {
                @Index(name = "idx_notif_user", columnList = "user_id,created_at"),
                @Index(name = "idx_notif_user_unread", columnList = "user_id,read_at")
        })
public class Notification {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** Free-form classifier used by the UI to pick an icon — values like
     *  "mention", "alert", "schedule", "system". */
    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 600)
    private String body;

    /** Where clicking the notification should take the user. */
    @Column(length = 400)
    private String url;

    /** Optional ref to the originating entity for traceability. */
    @Column(name = "entity_type", length = 32)
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "actor_user_id")
    private String actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until the user opens the dropdown / clicks it. */
    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
}
