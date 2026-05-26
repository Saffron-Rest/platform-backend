package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single HACCP record — fridge temperature, cooking probe, delivery
 * acceptance, cleaning sign-off, etc.
 *
 * <p>The schema is deliberately denormalised: instead of a separate
 * template/run pair (as checklists have), each entry is a self-contained
 * "what / where / when / how / who". Inspectors read these one row at a
 * time. Free-form JSON in {@link #data} accommodates kind-specific extras
 * (e.g. supplier name on a delivery row, areas cleaned on a cleaning row).</p>
 */
@Entity
@Table(name = "haccp_log", indexes = {
        @Index(name = "ix_haccp_log_kind", columnList = "kind, recorded_on"),
        @Index(name = "ix_haccp_log_date", columnList = "recorded_on")
})
public class HaccpLog {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private HaccpKind kind;

    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by_id", nullable = false, length = 36)
    private String recordedById;

    /** What was measured — "Walk-in 1", "Display fridge", "Wok station". */
    @Column(length = 120)
    private String location;

    /** Numeric reading in °C if applicable. We allow negatives for freezers. */
    @Column(name = "temperature_c", precision = 5, scale = 2)
    private BigDecimal temperatureC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HaccpStatus status = HaccpStatus.OK;

    @Column(columnDefinition = "text")
    private String notes;

    /** Relative path to attached photo / proof (prefix {@code haccp/}). */
    @Column(name = "photo_path", length = 255)
    private String photoPath;

    /** Free-form JSON for kind-specific extras. */
    @Column(columnDefinition = "text")
    private String data;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (recordedAt == null) recordedAt = now;
        if (recordedOn == null) recordedOn = LocalDate.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public HaccpKind getKind() { return kind; }
    public void setKind(HaccpKind kind) { this.kind = kind; }
    public LocalDate getRecordedOn() { return recordedOn; }
    public void setRecordedOn(LocalDate recordedOn) { this.recordedOn = recordedOn; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
    public String getRecordedById() { return recordedById; }
    public void setRecordedById(String recordedById) { this.recordedById = recordedById; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public BigDecimal getTemperatureC() { return temperatureC; }
    public void setTemperatureC(BigDecimal temperatureC) { this.temperatureC = temperatureC; }
    public HaccpStatus getStatus() { return status; }
    public void setStatus(HaccpStatus status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public Instant getCreatedAt() { return createdAt; }
}
