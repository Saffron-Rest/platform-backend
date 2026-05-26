package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One trackable thing in inventory.
 *
 * <p>A stock item can be either:</p>
 * <ul>
 *   <li><b>Linked to a {@link MenuItem}</b> (via {@code menuItemId}): every
 *       POS sale of that item decrements {@code onHand} by the sold quantity.</li>
 *   <li><b>Standalone</b> (e.g. raw ingredients like flour, lamb shoulder):
 *       movement is purely manual — purchases up, waste/usage down.</li>
 * </ul>
 *
 * <p>{@code onHand} is the authoritative current balance. Every change to it
 * goes through {@link StockMovement} so the history is fully auditable and
 * reversible. The {@code unit} field is free-form ("pcs", "kg", "l", "portion")
 * and only used for display.</p>
 */
@Entity
@Table(name = "stock_item", indexes = {
        @Index(name = "ix_stock_item_active", columnList = "active"),
        @Index(name = "ix_stock_item_menu", columnList = "menu_item_id")
})
public class StockItem {

    @Id
    private String id;

    @Column(nullable = false, length = 160)
    private String name;

    /** Display SKU / barcode, matched against POS {@link PosSale#getSku()}
     *  as a fallback when {@code menuItemId} isn't set. Unique (case-insensitive)
     *  when present. */
    @Column(length = 64)
    private String sku;

    /** Unit of measurement, free-form: pcs, kg, l, portion, etc. */
    @Column(nullable = false, length = 16)
    private String unit = "pcs";

    /** Optional link to the menu so POS sales auto-decrement. Stored as a
     *  raw string (not a FK) so a deleted/archived menu item doesn't cascade
     *  into the inventory history. */
    @Column(name = "menu_item_id", length = 36)
    private String menuItemId;

    /** Free-form category label for grouping in the UI (Mains, Drinks,
     *  Ingredient, Cleaning, etc.). */
    @Column(length = 40)
    private String category;

    @Column(name = "on_hand", nullable = false, precision = 14, scale = 3)
    private BigDecimal onHand = BigDecimal.ZERO;

    /** Trigger a "low stock" badge when {@code onHand <= lowStockThreshold}.
     *  Null disables the warning. */
    @Column(name = "low_stock_threshold", precision = 14, scale = 3)
    private BigDecimal lowStockThreshold;

    /** Target on-hand after restock — the qty the buyer aims to reach when
     *  reordering. Cosmetic for now; surfaced in the UI. */
    @Column(name = "par_level", precision = 14, scale = 3)
    private BigDecimal parLevel;

    /** Optional per-unit cost — fed into a future inventory-value report. */
    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    /** Cached timestamp of the most recent movement — saves a join when
     *  building the list view. Updated by {@link StockMovement} writes. */
    @Column(name = "last_movement_at")
    private Instant lastMovementAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (onHand == null) onHand = BigDecimal.ZERO;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getOnHand() { return onHand; }
    public void setOnHand(BigDecimal onHand) { this.onHand = onHand; }
    public BigDecimal getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(BigDecimal lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }
    public BigDecimal getParLevel() { return parLevel; }
    public void setParLevel(BigDecimal parLevel) { this.parLevel = parLevel; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getLastMovementAt() { return lastMovementAt; }
    public void setLastMovementAt(Instant lastMovementAt) { this.lastMovementAt = lastMovementAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
