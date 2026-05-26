package com.saffron.cashflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single line on a {@link MenuRecipe}: how much of a given
 * {@link StockItem} the recipe consumes.
 *
 * <p>We capture {@link #quantity} in the recipe's chosen
 * {@link #unit}. If the unit matches the underlying stock item's
 * unit (the common case), cost computation is a straight multiply by
 * {@link StockItem#getUnitCost()}. If they differ, the costing
 * service still multiplies — admins are responsible for keeping the
 * units consistent. (A future iteration could add a small conversion
 * registry, but for the day-to-day case where ingredients ship in the
 * units they're consumed, plain math is enough and avoids subtle
 * conversion-factor bugs.)</p>
 *
 * <p>{@link #wastePct} lets an admin model trimming/evaporation on a
 * per-ingredient basis. A 5 % waste on a 0.500 kg line costs as if
 * 0.525 kg were consumed.</p>
 */
@Entity
@Table(name = "menu_recipe_ingredient", indexes = {
        @Index(name = "ix_recipe_ingredient_recipe", columnList = "recipe_id"),
        @Index(name = "ix_recipe_ingredient_stock", columnList = "stock_item_id")
})
public class MenuRecipeIngredient {

    @Id
    private String id;

    @Column(name = "recipe_id", nullable = false, length = 36)
    private String recipeId;

    @Column(name = "stock_item_id", nullable = false, length = 36)
    private String stockItemId;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity = BigDecimal.ZERO;

    /** Free-form, must be the same family of unit as the stock item's
     *  {@code unit} for the cost math to make sense. */
    @Column(nullable = false, length = 16)
    private String unit = "pcs";

    /** Optional per-line waste % override; takes precedence over the
     *  recipe-level {@link MenuRecipe#getWastePct()}. */
    @Column(name = "waste_pct", precision = 5, scale = 2)
    private BigDecimal wastePct;

    /** Display order in the recipe editor — keeps the ingredient list
     *  stable across edits even though JPA doesn't guarantee order. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** Optional free-form note ("dice fine", "use fresh only"). */
    @Column(length = 240)
    private String note;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String recipeId) { this.recipeId = recipeId; }
    public String getStockItemId() { return stockItemId; }
    public void setStockItemId(String stockItemId) { this.stockItemId = stockItemId; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getWastePct() { return wastePct; }
    public void setWastePct(BigDecimal wastePct) { this.wastePct = wastePct; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
