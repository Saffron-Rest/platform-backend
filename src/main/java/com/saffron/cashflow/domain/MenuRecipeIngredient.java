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
 * A single line on a {@link MenuRecipe}.
 *
 * <p>An ingredient line consumes <i>either</i> a {@link StockItem} or
 * another {@link MenuRecipe} (a "sub-recipe" — e.g. a dough or a
 * sauce whose cost is itself computed from ingredients). Exactly one
 * of {@link #stockItemId} / {@link #subRecipeId} must be set; the
 * service layer enforces this and detects cycles before persisting.</p>
 *
 * <p>{@link #quantity} is captured in the recipe-author's chosen
 * {@link #unit}. The costing service uses
 * {@code UnitConverter} to translate to the source's native unit
 * (stock item unit, or sub-recipe yield unit) and surfaces a warning
 * when conversion isn't possible.</p>
 *
 * <p>{@link #wastePct} lets an admin model trimming/evaporation on a
 * per-ingredient basis. A 5 % waste on a 0.500 kg line costs as if
 * 0.525 kg were consumed.</p>
 */
@Entity
@Table(name = "menu_recipe_ingredient", indexes = {
        @Index(name = "ix_recipe_ingredient_recipe", columnList = "recipe_id"),
        @Index(name = "ix_recipe_ingredient_stock", columnList = "stock_item_id"),
        @Index(name = "ix_recipe_ingredient_sub", columnList = "sub_recipe_id")
})
public class MenuRecipeIngredient {

    @Id
    private String id;

    @Column(name = "recipe_id", nullable = false, length = 36)
    private String recipeId;

    /** Stock-item-backed ingredient. Nullable — when set,
     *  {@link #subRecipeId} must be null. */
    @Column(name = "stock_item_id", length = 36)
    private String stockItemId;

    /** Sub-recipe-backed ingredient (e.g. "dough" as a reused prep).
     *  Nullable — when set, {@link #stockItemId} must be null. The
     *  service builds a DAG and rejects cycles. */
    @Column(name = "sub_recipe_id", length = 36)
    private String subRecipeId;

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
    public String getSubRecipeId() { return subRecipeId; }
    public void setSubRecipeId(String subRecipeId) { this.subRecipeId = subRecipeId; }
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
