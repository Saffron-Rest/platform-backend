package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    private String id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.CASHIER;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean mustChangePassword = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_type")
    private PayType payType = PayType.HOURLY;

    /** Stored in legacy column hourly_rate — amount meaning depends on payType. */
    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal payAmount;

    @Column(name = "start_date")
    private LocalDate startDate;

    /**
     * CSV of {@link Permission#name()} values an admin has granted to
     * this user on top of {@link #role}'s defaults. Nullable — a NULL
     * column means "no extras". See {@link Permission#parseCsv} for the
     * encoding contract. Never edited by users themselves; only by an
     * admin via {@code UserService.setPermissions}.
     */
    /** BCrypt hash of this cashier's 4-digit POS PIN. Null if no PIN set. */
    @Column(name = "pos_pin")
    private String posPin;

    @Column(name = "extra_permissions", columnDefinition = "TEXT")
    private String extraPermissions;

    /**
     * CSV of {@link Permission#name()} values an admin has revoked from
     * this user. Only keys that are normally implied by {@link #role}'s
     * default meaningfully subtract anything — others are stored
     * harmlessly. Nullable; same encoding contract as
     * {@link #extraPermissions}.
     */
    @Column(name = "revoked_permissions", columnDefinition = "TEXT")
    private String revokedPermissions;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public PayType getPayType() { return payType; }
    public void setPayType(PayType payType) { this.payType = payType != null ? payType : PayType.HOURLY; }
    public BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public String getPosPin() { return posPin; }
    public void setPosPin(String posPin) { this.posPin = posPin; }
    public String getExtraPermissions() { return extraPermissions; }
    public void setExtraPermissions(String extraPermissions) { this.extraPermissions = extraPermissions; }
    public String getRevokedPermissions() { return revokedPermissions; }
    public void setRevokedPermissions(String revokedPermissions) { this.revokedPermissions = revokedPermissions; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
