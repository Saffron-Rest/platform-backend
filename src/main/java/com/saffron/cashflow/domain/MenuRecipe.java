package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Recipe / cost-card for a sellable item.
 *
 * <p>A recipe lists the ingredients consumed to produce a given
 * {@link #yieldQuantity} of finished output (e.g. "1 kg meat + 1 kg
 * dough → 71 pieces"). The unit costs of the ingredients live on
 * {@link StockItem}, so the recipe's effective food cost recomputes
 * automatically whenever a stock item's {@code unitCost} is updated.</p>
 *
 * <p>The optional {@link #menuItemId} link lets us push the computed
 * cost (and an admin-approved suggested sales price) onto a real
 * {@link MenuItem}. Standalone recipes (no link) are fine — useful
 * for prep components shared across dishes (a sauce base, a dough,
 * etc.). We store {@code menuItemId} as a plain string rather than a
 * JPA relation so archiving a menu item doesn't cascade-delete the
 * recipe.</p>
 */
@Entity
@Table(name = "menu_recipe", indexes = {
        @Index(name = "ix_menu_recipe_menu_item", columnList = "menu_item_id"),
        @Index(name = "ix_menu_recipe_active", columnList = "active")
})
public class MenuRecipe {

    @Id
    private String id;

    /** Display name. Falls back to the linked menu item's name in the
     *  UI when blank, but stored explicitly so standalone recipes
     *  (with no menu link) still have a label. */
    @Column(nullable = false, length = 160)
    private String name;

    /** Optional link to the {@link MenuItem} this recipe costs. Null
     *  means "draft" / "sub-recipe" / "prep". */
    @Column(name = "menu_item_id", length = 36)
    private String menuItemId;

    /** Number of finished units produced by the recipe — e.g. 71
     *  pieces, 8 portions, 12 jars. Used to divide total cost into a
     *  per-unit number. */
    @Column(name = "yield_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal yieldQuantity = BigDecimal.ONE;

    /** Free-form unit of the yield (piece, portion, jar, kg). */
    @Column(name = "yield_unit", nullable = false, length = 24)
    private String yieldUnit = "piece";

    /** Desired food-cost percentage out of the gross sales price.
     *  30 % is a typical restaurant default; lets us suggest a price
     *  via {@code suggestedPrice = costPerUnit / (target / 100)}. */
    @Column(name = "target_food_cost_pct", precision = 5, scale = 2)
    private BigDecimal targetFoodCostPct = new BigDecimal("30.00");

    /** VAT rate applied to the suggested sales price — Polish
     *  restaurants typically charge 8 % on food / 23 % on alcohol.
     *  Stored so we can quote both gross and net suggestions. */
    @Column(name = "vat_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRatePct = new BigDecimal("8.00");

    /** Optional waste / shrinkage allowance applied to every
     *  ingredient (e.g. 5 % to cover trimming, evaporation). Per-line
     *  overrides live on {@link MenuRecipeIngredient#getWastePct()}. */
    @Column(name = "waste_pct", precision = 5, scale = 2)
    private BigDecimal wastePct;

    /** Optional notes — prep instructions, allergen call-outs, etc. */
    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }
    public BigDecimal getYieldQuantity() { return yieldQuantity; }
    public void setYieldQuantity(BigDecimal yieldQuantity) { this.yieldQuantity = yieldQuantity; }
    public String getYieldUnit() { return yieldUnit; }
    public void setYieldUnit(String yieldUnit) { this.yieldUnit = yieldUnit; }
    public BigDecimal getTargetFoodCostPct() { return targetFoodCostPct; }
    public void setTargetFoodCostPct(BigDecimal targetFoodCostPct) { this.targetFoodCostPct = targetFoodCostPct; }
    public BigDecimal getVatRatePct() { return vatRatePct; }
    public void setVatRatePct(BigDecimal vatRatePct) { this.vatRatePct = vatRatePct; }
    public BigDecimal getWastePct() { return wastePct; }
    public void setWastePct(BigDecimal wastePct) { this.wastePct = wastePct; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
