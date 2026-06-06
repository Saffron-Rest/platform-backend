package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Stores the raw JSON body of every incoming POS webhook call so admins can
 * inspect exactly what the POS sent — useful for discovering available fields
 * before mapping them to menu / stock items.
 *
 * Only the webhook path writes here (not the simulator or manual sync),
 * so the log reflects real POS payloads only.
 */
@Entity
@Table(name = "pos_webhook_log",
        indexes = @Index(name = "ix_pos_webhook_log_integration", columnList = "integration_id, received_at"))
public class PosWebhookLog {

    @Id
    private String id;

    @Column(name = "integration_id", nullable = false, length = 36)
    private String integrationId;

    /** Base receipt / order id extracted from externalId (strips the #N suffix). */
    @Column(name = "external_id", length = 128)
    private String externalId;

    /** The complete raw request body exactly as the POS sent it. */
    @Column(name = "raw_body", nullable = false, columnDefinition = "text")
    private String rawBody;

    /** How many new sale lines were inserted from this payload. */
    @Column
    private int inserted;

    /** How many lines were skipped (duplicate externalId). */
    @Column
    private int skipped;

    /** How many lines had no matching MenuItem. */
    @Column
    private int unmatched;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (receivedAt == null) receivedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String integrationId) { this.integrationId = integrationId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getRawBody() { return rawBody; }
    public void setRawBody(String rawBody) { this.rawBody = rawBody; }
    public int getInserted() { return inserted; }
    public void setInserted(int inserted) { this.inserted = inserted; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public int getUnmatched() { return unmatched; }
    public void setUnmatched(int unmatched) { this.unmatched = unmatched; }
    public Instant getReceivedAt() { return receivedAt; }
}
