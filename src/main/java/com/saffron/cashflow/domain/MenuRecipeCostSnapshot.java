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
import java.util.UUID;

/**
 * Point-in-time cost snapshot for a {@link MenuRecipe}.
 *
 * <p>Captured whenever the recipe is saved or its computed numbers
 * pushed to a linked {@link MenuItem}. Snapshots are append-only —
 * the recipe edit history doubles as a cost-drift timeline, so
 * operators can see how a dish's food cost or suggested price moved
 * after a supplier hike.</p>
 *
 * <p>We store the gross numbers (food cost, prime cost, fully loaded,
 * suggested gross price) rather than re-running the computation later,
 * because the computation depends on the unit costs of stock items
 * that may themselves have changed in the meantime. Recording the
 * numbers as they were is the whole point.</p>
 */
@Entity
@Table(name = "menu_recipe_cost_snapshot", indexes = {
        @Index(name = "ix_recipe_snapshot_recipe", columnList = "recipe_id,taken_at"),
        @Index(name = "ix_recipe_snapshot_source", columnList = "source")
})
public class MenuRecipeCostSnapshot {

    public enum Source { SAVE, APPLY, MANUAL }

    @Id
    private String id;

    @Column(name = "recipe_id", nullable = false, length = 36)
    private String recipeId;

    @Column(name = "food_cost", precision = 14, scale = 4)
    private BigDecimal foodCost;

    @Column(name = "prime_cost", precision = 14, scale = 4)
    private BigDecimal primeCost;

    @Column(name = "fully_loaded_cost", precision = 14, scale = 4)
    private BigDecimal fullyLoadedCost;

    @Column(name = "cost_per_unit", precision = 14, scale = 4)
    private BigDecimal costPerUnit;

    @Column(name = "suggested_price", precision = 14, scale = 4)
    private BigDecimal suggestedPrice;

    @Column(name = "achieved_food_cost_pct", precision = 6, scale = 2)
    private BigDecimal achievedFoodCostPct;

    @Column(name = "margin_pct", precision = 6, scale = 2)
    private BigDecimal marginPct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.SAVE;

    /** Optional admin comment ("supplier raised meat 12 %"). */
    @Column(length = 240)
    private String note;

    @Column(name = "taken_at", nullable = false, updatable = false)
    private Instant takenAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (takenAt == null) takenAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String recipeId) { this.recipeId = recipeId; }
    public BigDecimal getFoodCost() { return foodCost; }
    public void setFoodCost(BigDecimal foodCost) { this.foodCost = foodCost; }
    public BigDecimal getPrimeCost() { return primeCost; }
    public void setPrimeCost(BigDecimal primeCost) { this.primeCost = primeCost; }
    public BigDecimal getFullyLoadedCost() { return fullyLoadedCost; }
    public void setFullyLoadedCost(BigDecimal fullyLoadedCost) { this.fullyLoadedCost = fullyLoadedCost; }
    public BigDecimal getCostPerUnit() { return costPerUnit; }
    public void setCostPerUnit(BigDecimal costPerUnit) { this.costPerUnit = costPerUnit; }
    public BigDecimal getSuggestedPrice() { return suggestedPrice; }
    public void setSuggestedPrice(BigDecimal suggestedPrice) { this.suggestedPrice = suggestedPrice; }
    public BigDecimal getAchievedFoodCostPct() { return achievedFoodCostPct; }
    public void setAchievedFoodCostPct(BigDecimal achievedFoodCostPct) { this.achievedFoodCostPct = achievedFoodCostPct; }
    public BigDecimal getMarginPct() { return marginPct; }
    public void setMarginPct(BigDecimal marginPct) { this.marginPct = marginPct; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getTakenAt() { return takenAt; }
    public void setTakenAt(Instant takenAt) { this.takenAt = takenAt; }
}
