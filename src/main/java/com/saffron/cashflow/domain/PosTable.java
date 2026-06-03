package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A physical table (or counter seat) on the restaurant floor plan.
 * Tables are shared across all cashiers; the POS shows them on a grid.
 */
@Entity
@Table(name = "pos_table")
public class PosTable {

    @Id
    private String id;

    @Column(nullable = false, length = 40)
    private String name;

    /** Room or section label shown as a filter on the floor map. */
    @Column(length = 60)
    private String area;

    /** Grid column (0-based) for the visual floor map. */
    @Column(name = "grid_x", nullable = false)
    private int gridX = 0;

    /** Grid row (0-based) for the visual floor map. */
    @Column(name = "grid_y", nullable = false)
    private int gridY = 0;

    @Column(nullable = false)
    private int seats = 4;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

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
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public int getGridX() { return gridX; }
    public void setGridX(int gridX) { this.gridX = gridX; }
    public int getGridY() { return gridY; }
    public void setGridY(int gridY) { this.gridY = gridY; }
    public int getSeats() { return seats; }
    public void setSeats(int seats) { this.seats = seats; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
