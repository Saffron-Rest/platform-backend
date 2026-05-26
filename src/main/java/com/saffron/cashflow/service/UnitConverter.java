package com.saffron.cashflow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tiny declarative unit-conversion registry shared by recipe costing.
 *
 * <p>The data is intentionally small: kitchens deal with a handful of
 * mass and volume units, plus the loose imperial measures sometimes
 * found on supplier sheets. Each unit declares its <i>base unit</i>
 * (g for mass, ml for volume) and a conversion factor — anything else
 * is computed by transitive composition.</p>
 *
 * <p>Unknown units (e.g. {@code "piece"}, {@code "portion"},
 * {@code "tray"}) only compare to themselves: the caller is told via
 * {@link #convert(BigDecimal, String, String)} returning empty, and
 * the cost path falls back to a 1:1 assumption with a warning surfaced
 * to the UI. We intentionally do <b>not</b> guess — converting "5 pcs"
 * to grams would be dangerously fake.</p>
 *
 * <p>Aliases (case-insensitive) catch the common typo variants:
 * "kg" vs "KG", "lt" for litre, "g" vs "gram(s)", etc.</p>
 */
public final class UnitConverter {

    /** Family of measurement — units only convert within their family. */
    public enum Family { MASS, VOLUME, COUNT, UNKNOWN }

    /** Internal canonical record of one unit. */
    private record UnitInfo(String canonical, Family family, BigDecimal factorToBase) {}

    /** Lookup keyed by lowercased alias. Built once at class load. */
    private static final Map<String, UnitInfo> UNITS = buildRegistry();

    private UnitConverter() {}

    private static Map<String, UnitInfo> buildRegistry() {
        java.util.Map<String, UnitInfo> m = new java.util.HashMap<>();

        // ─── Mass (base = g) ──────────────────────────────────────
        UnitInfo gram = new UnitInfo("g", Family.MASS, new BigDecimal("1"));
        UnitInfo kilogram = new UnitInfo("kg", Family.MASS, new BigDecimal("1000"));
        UnitInfo milligram = new UnitInfo("mg", Family.MASS, new BigDecimal("0.001"));
        UnitInfo ounce = new UnitInfo("oz", Family.MASS, new BigDecimal("28.3495"));
        UnitInfo pound = new UnitInfo("lb", Family.MASS, new BigDecimal("453.592"));
        register(m, gram, "g", "gram", "grams", "gr");
        register(m, kilogram, "kg", "kilo", "kilos", "kilogram", "kilograms");
        register(m, milligram, "mg", "milligram", "milligrams");
        register(m, ounce, "oz", "ounce", "ounces");
        register(m, pound, "lb", "lbs", "pound", "pounds");

        // ─── Volume (base = ml) ───────────────────────────────────
        UnitInfo millilitre = new UnitInfo("ml", Family.VOLUME, new BigDecimal("1"));
        UnitInfo litre = new UnitInfo("l", Family.VOLUME, new BigDecimal("1000"));
        UnitInfo centilitre = new UnitInfo("cl", Family.VOLUME, new BigDecimal("10"));
        UnitInfo decilitre = new UnitInfo("dl", Family.VOLUME, new BigDecimal("100"));
        UnitInfo teaspoon = new UnitInfo("tsp", Family.VOLUME, new BigDecimal("5"));
        UnitInfo tablespoon = new UnitInfo("tbsp", Family.VOLUME, new BigDecimal("15"));
        UnitInfo cup = new UnitInfo("cup", Family.VOLUME, new BigDecimal("240"));
        UnitInfo flOz = new UnitInfo("floz", Family.VOLUME, new BigDecimal("29.5735"));
        register(m, millilitre, "ml", "millilitre", "millilitres", "milliliter", "milliliters");
        register(m, litre, "l", "lt", "litre", "litres", "liter", "liters");
        register(m, centilitre, "cl", "centilitre", "centilitres");
        register(m, decilitre, "dl", "decilitre", "decilitres");
        register(m, teaspoon, "tsp", "teaspoon", "teaspoons");
        register(m, tablespoon, "tbsp", "tablespoon", "tablespoons");
        register(m, cup, "cup", "cups");
        register(m, flOz, "floz", "fl oz", "fluid ounce", "fluid ounces");

        // ─── Count units (mutually non-convertible) ───────────────
        // Each count "kind" has its own canonical so that pcs ≠ portion
        // ≠ serving ≠ unit. They all share the COUNT family, but
        // {@link #convert} refuses to cross between distinct canonicals
        // because a portion of soup is not the same as a piece of soup.
        UnitInfo piece = new UnitInfo("pcs", Family.COUNT, BigDecimal.ONE);
        UnitInfo portion = new UnitInfo("portion", Family.COUNT, BigDecimal.ONE);
        UnitInfo serving = new UnitInfo("serving", Family.COUNT, BigDecimal.ONE);
        UnitInfo unit = new UnitInfo("unit", Family.COUNT, BigDecimal.ONE);
        register(m, piece, "pcs", "pc", "piece", "pieces");
        register(m, portion, "portion", "portions");
        register(m, serving, "serving", "servings");
        register(m, unit, "unit", "units");

        return java.util.Collections.unmodifiableMap(m);
    }

    private static void register(Map<String, UnitInfo> m, UnitInfo info, String... aliases) {
        for (String alias : aliases) m.put(alias.toLowerCase(Locale.ROOT), info);
    }

    /** Family that a unit belongs to (MASS, VOLUME, COUNT, UNKNOWN). */
    public static Family familyOf(String unit) {
        UnitInfo info = lookup(unit);
        return info == null ? Family.UNKNOWN : info.family;
    }

    /**
     * Convert {@code quantity} from {@code fromUnit} to {@code toUnit}.
     *
     * <p>Returns {@code Optional.empty()} when the two units don't
     * share a family (e.g. kg → ml) or either is unknown. Calls with
     * identical or aliased units short-circuit to the input value.</p>
     */
    public static Optional<BigDecimal> convert(BigDecimal quantity, String fromUnit, String toUnit) {
        if (quantity == null) return Optional.empty();
        if (fromUnit == null || toUnit == null) return Optional.empty();
        if (fromUnit.equalsIgnoreCase(toUnit)) return Optional.of(quantity);
        UnitInfo from = lookup(fromUnit);
        UnitInfo to = lookup(toUnit);
        if (from == null || to == null) return Optional.empty();
        if (from.family != to.family) return Optional.empty();
        if (from.family == Family.COUNT) {
            // All "count" units (pcs, portion, serving) only equal
            // themselves under their canonical name; we deliberately
            // refuse to fake any conversion between them because we
            // don't know how many grams a portion is without recipe
            // context.
            return from.canonical.equalsIgnoreCase(to.canonical)
                    ? Optional.of(quantity)
                    : Optional.empty();
        }
        // base = quantity * factorToBase(from)
        BigDecimal base = quantity.multiply(from.factorToBase);
        BigDecimal out = base.divide(to.factorToBase, 8, RoundingMode.HALF_UP);
        // Tidy obvious zeros: 0.300000000 → 0.30
        return Optional.of(out.stripTrailingZeros().scale() < 0
                ? out.setScale(0, RoundingMode.HALF_UP)
                : out);
    }

    /** True when the two units convert into each other. */
    public static boolean isConvertible(String fromUnit, String toUnit) {
        return convert(BigDecimal.ONE, fromUnit, toUnit).isPresent();
    }

    /** Canonical alias for a given unit string (or the input lowercased
     *  when unknown). Mainly handy for de-duping display labels. */
    public static String canonical(String unit) {
        UnitInfo info = lookup(unit);
        return info == null
                ? (unit == null ? null : unit.toLowerCase(Locale.ROOT))
                : info.canonical;
    }

    private static UnitInfo lookup(String unit) {
        if (unit == null || unit.isBlank()) return null;
        return UNITS.get(unit.trim().toLowerCase(Locale.ROOT));
    }
}
