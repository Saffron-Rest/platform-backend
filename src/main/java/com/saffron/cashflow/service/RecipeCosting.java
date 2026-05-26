package com.saffron.cashflow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure helpers for recipe cost / price math. Lifted out of the
 * persistence-aware {@code RecipeService} so they're trivially
 * unit-testable and reusable from the controller's "preview" path
 * (which never touches the DB).
 *
 * <p>All inputs are tolerant of null — a null unitCost means "no cost
 * captured yet" and contributes zero. A null target food-cost %
 * disables the suggested-price computation altogether (the caller
 * gets {@code null} back).</p>
 */
public final class RecipeCosting {

    /** Scale we keep cost & price math at internally. Final values are
     *  rounded to currency precision (2dp) at the boundary. */
    private static final int SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private RecipeCosting() {}

    /**
     * Effective consumed quantity for an ingredient line — quantity
     * scaled up by waste %. A 0.500 kg line at 5 % waste consumes
     * 0.525 kg.
     *
     * <p>Per-line {@code lineWastePct} wins over the recipe-level
     * fallback; either-null means "no waste applied".</p>
     */
    public static BigDecimal effectiveQuantity(
            BigDecimal quantity, BigDecimal lineWastePct, BigDecimal recipeWastePct) {
        BigDecimal q = quantity == null ? BigDecimal.ZERO : quantity;
        BigDecimal w = lineWastePct != null ? lineWastePct : recipeWastePct;
        if (w == null || w.signum() <= 0) return q;
        // q * (1 + w/100)
        BigDecimal factor = BigDecimal.ONE.add(w.divide(HUNDRED, SCALE, RoundingMode.HALF_UP));
        return q.multiply(factor);
    }

    /**
     * Cost of one ingredient line: effective quantity × unit cost.
     * Returns BigDecimal.ZERO if either side is null/zero.
     */
    public static BigDecimal lineCost(BigDecimal effectiveQty, BigDecimal unitCost) {
        if (effectiveQty == null || unitCost == null) return BigDecimal.ZERO;
        return effectiveQty.multiply(unitCost).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Suggested gross sales price for a target food-cost percentage.
     *
     * <p>{@code suggested = costPerUnit / (targetFoodCostPct / 100)} —
     * i.e. invert the food-cost ratio. Returns null when the target
     * is null, zero, negative, or when the cost is unknown.</p>
     *
     * <p>The result is rounded UP to the nearest currency unit (0.50)
     * because rounding down would silently push the actual food-cost
     * % above the admin's target. Callers wanting a different rounding
     * strategy can use {@link #roundToNearest} on the raw value.</p>
     */
    public static BigDecimal suggestedSellPrice(
            BigDecimal costPerUnit, BigDecimal targetFoodCostPct) {
        if (costPerUnit == null || costPerUnit.signum() <= 0) return null;
        if (targetFoodCostPct == null || targetFoodCostPct.signum() <= 0) return null;
        BigDecimal raw = costPerUnit.divide(
                targetFoodCostPct.divide(HUNDRED, SCALE, RoundingMode.HALF_UP),
                SCALE,
                RoundingMode.HALF_UP);
        return roundUpToNearest(raw, new BigDecimal("0.50"));
    }

    /**
     * Achieved food-cost percentage given a chosen sell price.
     * Returns null when either input is missing or non-positive.
     */
    public static BigDecimal achievedFoodCostPct(BigDecimal costPerUnit, BigDecimal sellPrice) {
        if (costPerUnit == null || sellPrice == null) return null;
        if (sellPrice.signum() <= 0) return null;
        return costPerUnit.divide(sellPrice, SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Contribution margin in absolute terms — what the kitchen keeps
     * after paying for raw ingredients.
     */
    public static BigDecimal contributionMargin(BigDecimal sellPrice, BigDecimal costPerUnit) {
        if (sellPrice == null || costPerUnit == null) return null;
        return sellPrice.subtract(costPerUnit).setScale(2, RoundingMode.HALF_UP);
    }

    /** Margin as a % of the sell price ({@code (price − cost) / price × 100}). */
    public static BigDecimal marginPct(BigDecimal sellPrice, BigDecimal costPerUnit) {
        if (sellPrice == null || sellPrice.signum() <= 0 || costPerUnit == null) return null;
        return sellPrice.subtract(costPerUnit)
                .divide(sellPrice, SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Strip VAT out of a gross price: {@code net = gross / (1 + vat/100)}. */
    public static BigDecimal netOfVat(BigDecimal gross, BigDecimal vatPct) {
        if (gross == null) return null;
        if (vatPct == null || vatPct.signum() <= 0) return gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal divisor = BigDecimal.ONE.add(vatPct.divide(HUNDRED, SCALE, RoundingMode.HALF_UP));
        return gross.divide(divisor, 2, RoundingMode.HALF_UP);
    }

    /** Round to the nearest multiple of {@code step}, half-up. */
    public static BigDecimal roundToNearest(BigDecimal value, BigDecimal step) {
        if (value == null || step == null || step.signum() <= 0) return value;
        return value.divide(step, 0, RoundingMode.HALF_UP).multiply(step)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Round UP to the nearest multiple of {@code step} (used when
     *  suggesting a sell price so we don't accidentally undershoot
     *  the food-cost target). */
    public static BigDecimal roundUpToNearest(BigDecimal value, BigDecimal step) {
        if (value == null || step == null || step.signum() <= 0) return value;
        return value.divide(step, 0, RoundingMode.CEILING).multiply(step)
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ─── v2: labor / packaging / overhead helpers ─────────────────

    /** Labor cost per yield unit, from a "minutes per portion" model.
     *  Returns ZERO when either input is missing or non-positive. */
    public static BigDecimal laborCostPerUnit(
            BigDecimal minutesPerUnit, BigDecimal ratePerHour) {
        if (minutesPerUnit == null || ratePerHour == null) return BigDecimal.ZERO;
        if (minutesPerUnit.signum() <= 0 || ratePerHour.signum() <= 0) return BigDecimal.ZERO;
        // (minutes / 60) * rate
        return minutesPerUnit
                .divide(new BigDecimal("60"), SCALE, RoundingMode.HALF_UP)
                .multiply(ratePerHour)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Apply an overhead percentage on top of a base cost ({@code base
     *  * (1 + pct/100)}). Tolerates null/non-positive pct (returns
     *  base unchanged). */
    public static BigDecimal applyOverhead(BigDecimal base, BigDecimal overheadPct) {
        if (base == null) return null;
        if (overheadPct == null || overheadPct.signum() <= 0) return base;
        BigDecimal factor = BigDecimal.ONE.add(
                overheadPct.divide(HUNDRED, SCALE, RoundingMode.HALF_UP));
        return base.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Achieved prime-cost % (food + labor + packaging) for a given
     *  sell price. */
    public static BigDecimal achievedPrimeCostPct(BigDecimal primeCostPerUnit, BigDecimal sellPrice) {
        return achievedFoodCostPct(primeCostPerUnit, sellPrice);
    }

    /** Break-even price: the minimum gross price that recovers the
     *  fully-loaded cost (food + labor + packaging + overhead). Used
     *  to flag suggested prices that don't even cover overhead. */
    public static BigDecimal breakEvenPrice(BigDecimal fullyLoadedCostPerUnit) {
        if (fullyLoadedCostPerUnit == null || fullyLoadedCostPerUnit.signum() <= 0) return null;
        return roundUpToNearest(fullyLoadedCostPerUnit, new BigDecimal("0.10"));
    }
}
