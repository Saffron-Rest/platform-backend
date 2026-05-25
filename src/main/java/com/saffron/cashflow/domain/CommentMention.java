package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One-per-mention row. Populated by CommentService when it parses @handles
 * out of the body. Mentioned users get an in-app notification via the
 * existing NotificationDispatch system.
 */
@Entity
@Table(
        name = "comment_mention",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_comment_mention",
                columnNames = {"comment_id", "user_id"}),
        indexes = @Index(name = "idx_comment_mention_user", columnList = "user_id"))
public class CommentMention {

    @Id
    private String id;

    @Column(name = "comment_id", nullable = false)
    private String commentId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCommentId() { return commentId; }
    public void setCommentId(String commentId) { this.commentId = commentId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Instant getCreatedAt() { return createdAt; }
}
