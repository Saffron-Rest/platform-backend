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

    /** When this integration last successfully pulled data (vendor-pull
     *  mode only — Dotykačka, etc.). */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    // ---------- Dotykačka credentials (only used when vendor = "dotykacka") ----------

    /** Dotykačka API cloud ID — required for token exchange. */
    @Column(name = "dotykacka_cloud_id", length = 64)
    private String dotykackaCloudId;

    /** OAuth2 Client ID issued by Dotykačka to our application. */
    @Column(name = "dotykacka_client_id", length = 128)
    private String dotykackaClientId;

    /** OAuth2 Client Secret — paired with clientId. */
    @Column(name = "dotykacka_client_secret", length = 256)
    private String dotykackaClientSecret;

    /** Long-lived refresh token obtained via the browser-based connector
     *  flow. Stored plain text; rotate by re-running the connector. */
    @Column(name = "dotykacka_refresh_token", length = 512)
    private String dotykackaRefreshToken;

    /** Cursor for incremental sync — we ask Dotykačka for orders changed
     *  after this timestamp. */
    @Column(name = "dotykacka_sync_cursor")
    private Instant dotykackaSyncCursor;

    /** ID returned by Dotykačka when we register a webhook on their side
     *  (POST /v2/clouds/{cloudId}/webhooks). Stored so we can unregister or
     *  rotate it. */
    @Column(name = "dotykacka_webhook_id")
    private Long dotykackaWebhookId;

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
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getDotykackaCloudId() { return dotykackaCloudId; }
    public void setDotykackaCloudId(String dotykackaCloudId) { this.dotykackaCloudId = dotykackaCloudId; }
    public String getDotykackaClientId() { return dotykackaClientId; }
    public void setDotykackaClientId(String dotykackaClientId) { this.dotykackaClientId = dotykackaClientId; }
    public String getDotykackaClientSecret() { return dotykackaClientSecret; }
    public void setDotykackaClientSecret(String dotykackaClientSecret) { this.dotykackaClientSecret = dotykackaClientSecret; }
    public String getDotykackaRefreshToken() { return dotykackaRefreshToken; }
    public void setDotykackaRefreshToken(String dotykackaRefreshToken) { this.dotykackaRefreshToken = dotykackaRefreshToken; }
    public Instant getDotykackaSyncCursor() { return dotykackaSyncCursor; }
    public void setDotykackaSyncCursor(Instant dotykackaSyncCursor) { this.dotykackaSyncCursor = dotykackaSyncCursor; }
    public Long getDotykackaWebhookId() { return dotykackaWebhookId; }
    public void setDotykackaWebhookId(Long dotykackaWebhookId) { this.dotykackaWebhookId = dotykackaWebhookId; }
    public Instant getCreatedAt() { return createdAt; }
}
