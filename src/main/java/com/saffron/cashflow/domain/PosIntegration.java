package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A configured POS source that can push sales into our system via webhook.
 *
 * Each integration carries its own HMAC secret so we can verify inbound
 * payloads and rotate keys without affecting other sources.
 */
@Entity
@Table(name = "pos_integration",
        uniqueConstraints = @UniqueConstraint(name = "uk_pos_integration_name", columnNames = "name"))
public class PosIntegration {

    @Id
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    /** Free-form vendor tag (e.g. "wolt-pos", "syrve", "shoper"). */
    @Column(length = 40)
    private String vendor;

    /** Shared secret used to verify the HMAC-SHA256 signature on inbound
     *  webhooks. Stored in plain text — rotate via the admin UI. */
    @Column(name = "webhook_secret", nullable = false, length = 128)
    private String webhookSecret;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_external_id", length = 128)
    private String lastExternalId;

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
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getLastExternalId() { return lastExternalId; }
    public void setLastExternalId(String lastExternalId) { this.lastExternalId = lastExternalId; }
    public Instant getCreatedAt() { return createdAt; }
}
