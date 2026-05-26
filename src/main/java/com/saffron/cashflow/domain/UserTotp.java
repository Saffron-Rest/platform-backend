package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-user TOTP (time-based one-time password) secret used for two-factor
 * authentication. Lives in its own table so we don't bloat the {@code
 * users} table and so we can revoke without touching other user fields.
 *
 * <p>The secret is stored as a base32 string — the same encoding used in
 * {@code otpauth://} URIs. We don't encrypt it at rest because the entire
 * database is already protected by VPS-level encryption + restricted
 * access; encrypting in the app layer would require a key-management
 * story bigger than the rest of the app put together. Future enhancement
 * if needed.</p>
 */
@Entity
@Table(name = "user_totp")
public class UserTotp {

    @Id
    @Column(name = "user_id", length = 36)
    private String userId;

    /** Base32-encoded secret (typically 32 characters / 160 bits). */
    @Column(name = "secret_b32", nullable = false, length = 64)
    private String secretB32;

    /** False during enrollment: the secret exists but isn't required at
     *  login until the user proves they scanned the QR code. */
    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Reserved for backup-codes JSON. Not used in v1. */
    @Column(name = "backup_codes_hash", length = 2000)
    private String backupCodesHash;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSecretB32() { return secretB32; }
    public void setSecretB32(String secretB32) { this.secretB32 = secretB32; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getEnabledAt() { return enabledAt; }
    public void setEnabledAt(Instant enabledAt) { this.enabledAt = enabledAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getBackupCodesHash() { return backupCodesHash; }
    public void setBackupCodesHash(String backupCodesHash) { this.backupCodesHash = backupCodesHash; }
}
