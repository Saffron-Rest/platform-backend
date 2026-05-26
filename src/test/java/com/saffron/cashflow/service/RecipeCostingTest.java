package com.saffron.cashflow.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-style tests for {@link RecipeCosting} — the pure math
 * behind the recipe cost card. The numbers here are what an admin
 * filling in the modal will see, so every formula gets a worked
 * example.
 */
class RecipeCostingTest {

    @Test
    void worked_example_1kg_meat_plus_1kg_dough_for_71_pieces() {
        // The user-supplied scenario:
        //   1 kg meat at 80.00 PLN/kg  → 80.00
        //   1 kg dough at 12.00 PLN/kg → 12.00
        //   Total cost           → 92.00 PLN
        //   Yield                → 71 pieces
        //   Cost per piece       → 92.00 / 71 ≈ 1.296 PLN
        //   Target food-cost     → 30 %
        //   Suggested sell price → 1.296 / 0.30 ≈ 4.32 → round up to 4.50

        BigDecimal lineMeat = RecipeCosting.lineCost(
                RecipeCosting.effectiveQuantity(new BigDecimal("1.000"), null, null),
                new BigDecimal("80.00"));
        BigDecimal lineDough = RecipeCosting.lineCost(
                RecipeCosting.effectiveQuantity(new BigDecimal("1.000"), null, null),
                new BigDecimal("12.00"));
        BigDecimal total = lineMeat.add(lineDough);
        assertEquals(0, total.compareTo(new BigDecimal("92.00")),
                "Total cost should be 92.00 PLN");

        BigDecimal yield = new BigDecimal("71");
        BigDecimal costPerPiece = total.divide(yield, 6, java.math.RoundingMode.HALF_UP);
        // ≈ 1.295775 PLN
        assertTrue(costPerPiece.compareTo(new BigDecimal("1.29")) > 0);
        assertTrue(costPerPiece.compareTo(new BigDecimal("1.30")) < 0);

        BigDecimal suggested = RecipeCosting.suggestedSellPrice(
                costPerPiece, new BigDecimal("30"));
        // 1.295775 / 0.30 = 4.319 → round up to nearest 0.50 = 4.50
        assertEquals(0, suggested.compareTo(new BigDecimal("4.50")),
                "Should suggest 4.50 PLN/piece");

        BigDecimal achieved = RecipeCosting.achievedFoodCostPct(costPerPiece, suggested);
        // 1.296 / 4.50 = 28.79 % — slightly under target because we
        // rounded the price UP.
        assertTrue(achieved.compareTo(new BigDecimal("30")) <= 0,
                "Rounded-up price should land at-or-below target food cost %");
    }

    @Test
    void waste_pct_scales_quantity_up() {
        // 0.500 kg with 5 % waste consumes 0.525 kg.
        BigDecimal q = RecipeCosting.effectiveQuantity(
                new BigDecimal("0.500"), new BigDecimal("5"), null);
        assertEquals(0, q.compareTo(new BigDecimal("0.525")),
                "5 % waste on 0.500 kg should yield 0.525 kg consumed");
    }

    @Test
    void line_waste_overrides_recipe_waste() {
        BigDecimal q = RecipeCosting.effectiveQuantity(
                new BigDecimal("1.000"),
                new BigDecimal("10"),  // line-level
                new BigDecimal("5"));  // recipe-level fallback
        assertEquals(0, q.compareTo(new BigDecimal("1.100")),
                "Line-level waste should win over recipe-level");
    }

    @Test
    void zero_or_null_waste_is_a_no_op() {
        BigDecimal qNull = RecipeCosting.effectiveQuantity(
                new BigDecimal("2.000"), null, null);
        BigDecimal qZero = RecipeCosting.effectiveQuantity(
                new BigDecimal("2.000"), BigDecimal.ZERO, null);
        assertEquals(0, qNull.compareTo(new BigDecimal("2.000")));
        assertEquals(0, qZero.compareTo(new BigDecimal("2.000")));
    }

    @Test
    void line_cost_returns_zero_when_unit_cost_unknown() {
        // Stock items can exist without a captured cost — the line
        // contributes nothing to the total in that case so the cost
        // card doesn't lie about how expensive a dish is.
        BigDecimal cost = RecipeCosting.lineCost(new BigDecimal("3"), null);
        assertEquals(0, cost.compareTo(BigDecimal.ZERO));
    }

    @Test
    void suggested_price_rounds_up_to_protect_food_cost_target() {
        // raw 7.81 should not round to 7.50 (which would undershoot the
        // food-cost target). It rounds up to 8.00.
        BigDecimal suggested = RecipeCosting.suggestedSellPrice(
                new BigDecimal("2.343"),  // costPerUnit
                new BigDecimal("30"));    // target
        // raw = 7.81 → ceiling to 8.00 (next 0.50 step)
        assertEquals(0, suggested.compareTo(new BigDecimal("8.00")));
    }

    @Test
    void suggested_price_is_null_when_target_or_cost_missing() {
        assertNull(RecipeCosting.suggestedSellPrice(null, new BigDecimal("30")));
        assertNull(RecipeCosting.suggestedSellPrice(BigDecimal.ZERO, new BigDecimal("30")));
        assertNull(RecipeCosting.suggestedSellPrice(new BigDecimal("1"), null));
        assertNull(RecipeCosting.suggestedSellPrice(new BigDecimal("1"), BigDecimal.ZERO));
    }

    @Test
    void achieved_food_cost_and_margin_round_to_two_dp() {
        BigDecimal achieved = RecipeCosting.achievedFoodCostPct(
                new BigDecimal("3.50"), new BigDecimal("12.00"));
        // 3.50 / 12.00 = 0.29166… → 29.17 %
        assertEquals(0, achieved.compareTo(new BigDecimal("29.17")));

        BigDecimal margin = RecipeCosting.contributionMargin(
                new BigDecimal("12.00"), new BigDecimal("3.50"));
        assertEquals(0, margin.compareTo(new BigDecimal("8.50")));

        BigDecimal marginPct = RecipeCosting.marginPct(
                new BigDecimal("12.00"), new BigDecimal("3.50"));
        // 8.50 / 12.00 = 0.7083… → 70.83 %
        assertEquals(0, marginPct.compareTo(new BigDecimal("70.83")));
    }

    @Test
    void net_of_vat_backs_out_polish_food_rate() {
        // Polish food VAT is 8 %. Gross 10.80 → net 10.00.
        BigDecimal net = RecipeCosting.netOfVat(
                new BigDecimal("10.80"), new BigDecimal("8"));
        assertEquals(0, net.compareTo(new BigDecimal("10.00")));
    }

    @Test
    void net_of_vat_with_zero_or_null_passes_through() {
        BigDecimal gross = new BigDecimal("9.99");
        assertEquals(0, RecipeCosting.netOfVat(gross, null).compareTo(new BigDecimal("9.99")));
        assertEquals(0, RecipeCosting.netOfVat(gross, BigDecimal.ZERO).compareTo(new BigDecimal("9.99")));
    }

    @Test
    void round_helpers_snap_to_currency_step() {
        // Round to nearest 0.50
        assertEquals(0, RecipeCosting.roundToNearest(
                new BigDecimal("4.32"), new BigDecimal("0.50")).compareTo(new BigDecimal("4.50")));
        assertEquals(0, RecipeCosting.roundToNearest(
                new BigDecimal("4.24"), new BigDecimal("0.50")).compareTo(new BigDecimal("4.00")));
        // Round up always pushes to the next step
        assertEquals(0, RecipeCosting.roundUpToNearest(
                new BigDecimal("4.01"), new BigDecimal("0.50")).compareTo(new BigDecimal("4.50")));
    }

    @Test
    void zero_yield_skips_suggestion_without_throwing() {
        // Yields are computed in the service, not here, but the public
        // suggested-price helper must tolerate a non-positive cost.
        BigDecimal s = RecipeCosting.suggestedSellPrice(
                new BigDecimal("-1"), new BigDecimal("30"));
        assertNull(s);
    }

    @Test
    void line_cost_uses_6_dp_internal_scale() {
        BigDecimal c = RecipeCosting.lineCost(
                new BigDecimal("0.333"), new BigDecimal("9.99"));
        // 0.333 * 9.99 = 3.32667
        assertNotNull(c);
        assertEquals(0, c.compareTo(new BigDecimal("3.326670")));
    }

    // ─── v2 helpers ──────────────────────────────────────────────

    @Test
    void labor_cost_per_unit_for_4_minutes_at_30_per_hour() {
        // 4 min * (30 / 60) PLN/min = 4 * 0.5 = 2.00 PLN/unit
        BigDecimal c = RecipeCosting.laborCostPerUnit(
                new BigDecimal("4"), new BigDecimal("30"));
        assertEquals(0, c.setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("2.00")));
    }

    @Test
    void labor_cost_zero_when_either_input_missing_or_nonpositive() {
        assertEquals(0, RecipeCosting.laborCostPerUnit(null, new BigDecimal("30"))
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, RecipeCosting.laborCostPerUnit(new BigDecimal("4"), null)
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, RecipeCosting.laborCostPerUnit(BigDecimal.ZERO, new BigDecimal("30"))
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, RecipeCosting.laborCostPerUnit(new BigDecimal("-1"), new BigDecimal("30"))
                .compareTo(BigDecimal.ZERO));
    }

    @Test
    void apply_overhead_scales_base_cost() {
        // 10 PLN base with 12 % overhead = 11.20 PLN.
        BigDecimal v = RecipeCosting.applyOverhead(
                new BigDecimal("10.00"), new BigDecimal("12"));
        assertEquals(0, v.setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("11.20")));
    }

    @Test
    void apply_overhead_is_identity_when_pct_missing_or_zero() {
        BigDecimal base = new BigDecimal("7.50");
        assertEquals(0, RecipeCosting.applyOverhead(base, null).compareTo(base));
        assertEquals(0, RecipeCosting.applyOverhead(base, BigDecimal.ZERO).compareTo(base));
    }

    @Test
    void break_even_price_rounds_up_to_currency_step() {
        // 4.31 fully loaded → break-even price is 4.40 (next 0.10 step up)
        BigDecimal be = RecipeCosting.breakEvenPrice(new BigDecimal("4.31"));
        assertEquals(0, be.compareTo(new BigDecimal("4.40")));
    }

    @Test
    void full_cost_stack_meat_dough_with_labor_and_overhead() {
        // 1 kg meat at 80 + 1 kg dough at 12 = 92 PLN total / 71 = 1.296/piece
        // Labor: 0.5 min/piece at 36 PLN/h = 0.30/piece
        // Packaging: 0.05/piece
        // Prime: 1.296 + 0.30 + 0.05 = 1.646
        // Overhead 10 % → fully loaded ≈ 1.81
        // Suggested at 30 % FC: ceil(1.296 / 0.30) → 4.50 (still based on food cost)
        BigDecimal food = new BigDecimal("92.00").divide(
                new BigDecimal("71"), 6, java.math.RoundingMode.HALF_UP);
        BigDecimal labor = RecipeCosting.laborCostPerUnit(
                new BigDecimal("0.5"), new BigDecimal("36"));
        BigDecimal packaging = new BigDecimal("0.05");
        BigDecimal prime = food.add(labor).add(packaging);
        BigDecimal loaded = RecipeCosting.applyOverhead(prime, new BigDecimal("10"));
        assertTrue(loaded.compareTo(prime) > 0, "overhead must increase cost");
        BigDecimal suggested = RecipeCosting.suggestedSellPrice(food, new BigDecimal("30"));
        assertEquals(0, suggested.compareTo(new BigDecimal("4.50")));
        // Break-even respects the fully loaded number, never the food cost alone.
        BigDecimal be = RecipeCosting.breakEvenPrice(loaded);
        assertTrue(be.compareTo(loaded) >= 0);
    }
}
