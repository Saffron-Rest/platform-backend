package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Prevents duplicate Telegram messages for the same event (e.g. daily missing-report digest). */
@Entity
@Table(
        name = "admin_telegram_dispatch",
        uniqueConstraints = @UniqueConstraint(columnNames = {"dedupe_key"}))
public class AdminTelegramDispatch {

    @Id
    private String id;

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 200)
    private String dedupeKey;

    @Column(nullable = false, length = 500)
    private String preview;

    @Column(nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (sentAt == null) sentAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
