package com.saffron.cashflow.service;

import com.saffron.cashflow.security.AuthHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menu engineering — the classical Kasavana &amp; Smith 2×2 classification:
 *
 * <pre>
 *                 ┌──────────────────────────┐
 *  high margin →  │  Puzzle      │   Star    │
 *                 │  (high $)    │ (winner)  │
 *                 │              │           │
 *                 │              │           │
 *                 │  Dog         │ Plowhorse │
 *  low margin  →  │  (loser)     │ (popular) │
 *                 └──────────────────────────┘
 *                   low qty        high qty
 * </pre>
 *
 * The cut-offs are computed dynamically from medians of the active item set —
 * this works whether the menu has 20 dishes or 200.
 *
 * Suggestions are deterministic rules layered on top of the classification
 * plus a few absolute thresholds (food-cost &gt; 38 %, share &lt; 2 %, etc.).
 * Designed to be readable to the owner — no AI required.
 */
@Service
public class MenuEngineService {

    /** Items below this revenue share are too small to surface in suggestions. */
    private static final BigDecimal MIN_SHARE_PCT = new BigDecimal("0.5");
    /** Food-cost ceiling — above this we flag the dish even if it sells well. */
    private static final BigDecimal HIGH_FOOD_COST_PCT = new BigDecimal("38.0");
    /** Stars / Plowhorses below this margin % get a "review pricing" suggestion. */
    private static final BigDecimal LOW_MARGIN_PCT = new BigDecimal("55.0");

    private final MenuAnalyticsService analytics;

    public MenuEngineService(MenuAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> compute(LocalDate from, LocalDate to) {
        AuthHelper.requireOperations();
        Map<String, Object> data = analytics.compute(from, to);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) data.getOrDefault("items", List.of());

        // Only items resolved to the menu participate in the matrix; unmatched
        // and zero-margin items get filtered out (food cost unknown ⇒ classify
        // safely as "unknown" rather than poisoning the medians).
        List<Map<String, Object>> classifiable = new ArrayList<>();
        for (Map<String, Object> row : rawItems) {
            boolean unmatched = Boolean.TRUE.equals(row.get("unmatched"));
            BigDecimal qty = asDecimal(row.get("quantity"));
            BigDecimal foodCost = asDecimal(row.get("foodCost"));
            BigDecimal revenue = asDecimal(row.get("revenue"));
            if (unmatched) continue;
            if (qty.signum() <= 0 || revenue.signum() <= 0) continue;
            // Items with no food cost can't be classified — pass through as
            // unclassified so the owner sees them but they don't skew medians.
            if (foodCost.signum() <= 0) {
                row.put("class", "UNCLASSIFIED");
                continue;
            }
            classifiable.add(row);
        }

        if (classifiable.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("from", data.get("from"));
            empty.put("to", data.get("to"));
            empty.put("totals", data.get("totals"));
            empty.put("items", rawItems);
            empty.put("categoryMix", data.get("categoryMix"));
            empty.put("medianQty", BigDecimal.ZERO);
            empty.put("medianMarginPct", BigDecimal.ZERO);
            empty.put("classified", Map.of("star", List.of(), "plowhorse", List.of(),
                    "puzzle", List.of(), "dog", List.of(), "unclassified", List.of()));
            empty.put("suggestions", List.of());
            return empty;
        }

        BigDecimal medianQty = median(classifiable.stream()
                .map(r -> asDecimal(r.get("quantity")))
                .sorted()
                .toList());
        BigDecimal medianMarginPct = median(classifiable.stream()
                .map(r -> asDecimal(r.get("marginPct")))
                .sorted()
                .toList());

        List<Map<String, Object>> stars = new ArrayList<>();
        List<Map<String, Object>> plowhorses = new ArrayList<>();
        List<Map<String, Object>> puzzles = new ArrayList<>();
        List<Map<String, Object>> dogs = new ArrayList<>();

        for (Map<String, Object> row : classifiable) {
            BigDecimal qty = asDecimal(row.get("quantity"));
            BigDecimal marginPct = asDecimal(row.get("marginPct"));
            boolean highQty = qty.compareTo(medianQty) >= 0;
            boolean highMargin = marginPct.compareTo(medianMarginPct) >= 0;
            String cls;
            if (highQty && highMargin) { cls = "STAR"; stars.add(row); }
            else if (highQty)          { cls = "PLOWHORSE"; plowhorses.add(row); }
            else if (highMargin)       { cls = "PUZZLE"; puzzles.add(row); }
            else                       { cls = "DOG"; dogs.add(row); }
            row.put("class", cls);
        }

        // Build suggestions, ranked by expected impact.
        List<Map<String, Object>> suggestions = buildSuggestions(classifiable);

        // Unclassified rows (no food cost) — surface them so the owner can fix.
        List<Map<String, Object>> unclassified = rawItems.stream()
                .filter(r -> "UNCLASSIFIED".equals(r.get("class")))
                .toList();

        Map<String, Object> classified = new LinkedHashMap<>();
        classified.put("star", stars);
        classified.put("plowhorse", plowhorses);
        classified.put("puzzle", puzzles);
        classified.put("dog", dogs);
        classified.put("unclassified", unclassified);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", data.get("from"));
        out.put("to", data.get("to"));
        out.put("totals", data.get("totals"));
        out.put("items", rawItems);
        out.put("categoryMix", data.get("categoryMix"));
        out.put("medianQty", medianQty);
        out.put("medianMarginPct", medianMarginPct);
        out.put("classified", classified);
        out.put("suggestions", suggestions);
        return out;
    }

    private List<Map<String, Object>> buildSuggestions(List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : items) {
            String name = String.valueOf(row.get("name"));
            String cls = String.valueOf(row.get("class"));
            BigDecimal share = asDecimal(row.get("share"));
            if (share.compareTo(MIN_SHARE_PCT) < 0 && !"DOG".equals(cls)) continue;

            BigDecimal qty = asDecimal(row.get("quantity"));
            BigDecimal revenue = asDecimal(row.get("revenue"));
            BigDecimal marginPct = asDecimal(row.get("marginPct"));
            BigDecimal foodCostPct = asDecimal(row.get("foodCostPct"));
            BigDecimal sellPrice = asDecimal(row.get("sellPrice"));

            switch (cls) {
                case "PLOWHORSE" -> {
                    // High volume + low margin: tiny price bump is a force-multiplier.
                    BigDecimal bump = sellPrice.multiply(new BigDecimal("0.05"))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal monthlyImpact = bump.multiply(qty);
                    out.add(suggestion(
                            row,
                            "RAISE_PRICE",
                            "high",
                            "Raise price of " + name + " by ~5%",
                            "It sells well but margin is only " + pct(marginPct) +
                                    ". A " + money(bump) + " bump adds ~" + money(monthlyImpact) +
                                    " over the same volume. Monitor demand for 2 weeks."
                    ));
                }
                case "DOG" -> {
                    out.add(suggestion(
                            row,
                            "REMOVE_OR_REPLACE",
                            "medium",
                            "Consider removing or reworking " + name,
                            "Low volume (" + qty.toPlainString() + " sold) and low margin (" + pct(marginPct) +
                                    "). Either drop it, reprice aggressively, or replace it with a higher-margin dish."
                    ));
                }
                case "PUZZLE" -> {
                    out.add(suggestion(
                            row,
                            "PROMOTE",
                            "medium",
                            "Push " + name + " — strong margin, weak volume",
                            "Margin is " + pct(marginPct) + " but only " + qty.toPlainString() +
                                    " sold. Try menu placement, server recommendations, or a paired-deal."
                    ));
                }
                case "STAR" -> {
                    if (foodCostPct.compareTo(HIGH_FOOD_COST_PCT) > 0) {
                        out.add(suggestion(
                                row,
                                "REVIEW_FOOD_COST",
                                "high",
                                "Review portion / supplier on " + name,
                                "Star item, but food cost is " + pct(foodCostPct) +
                                        " — above the 35–38% safe band. Shaving 2% across this volume" +
                                        " adds about " + money(revenue.multiply(new BigDecimal("0.02"))) + "/period."
                        ));
                    }
                }
                default -> { /* UNCLASSIFIED */ }
            }
        }

        // Absolute thresholds — runs across all items, regardless of class.
        for (Map<String, Object> row : items) {
            BigDecimal foodCostPct = asDecimal(row.get("foodCostPct"));
            if (foodCostPct.compareTo(new BigDecimal("42")) > 0) {
                out.add(suggestion(
                        row,
                        "FOOD_COST_OVER_42",
                        "high",
                        "Food cost on " + row.get("name") + " is " + pct(foodCostPct),
                        "That's well above industry norms. Check supplier price, recipe, and portion size."
                ));
            }
        }

        // Rank: high first, then medium.
        out.sort(Comparator.comparingInt((Map<String, Object> s) -> rank(String.valueOf(s.get("severity")))));
        // Deduplicate by (itemId, type) — a single dish should only show one recommendation per type.
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Map<String, Object>> dedup = new ArrayList<>();
        for (Map<String, Object> s : out) {
            String key = String.valueOf(s.get("itemId")) + "|" + s.get("type");
            if (seen.add(key)) dedup.add(s);
        }
        return dedup;
    }

    private static Map<String, Object> suggestion(
            Map<String, Object> row, String type, String severity, String title, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("severity", severity);
        m.put("title", title);
        m.put("detail", detail);
        m.put("itemId", row.get("itemId"));
        m.put("itemName", row.get("name"));
        m.put("categoryName", row.get("categoryName"));
        return m;
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        if (sorted.isEmpty()) return BigDecimal.ZERO;
        int n = sorted.size();
        if ((n & 1) == 1) return sorted.get(n / 2);
        BigDecimal lo = sorted.get(n / 2 - 1);
        BigDecimal hi = sorted.get(n / 2);
        return lo.add(hi).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal asDecimal(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (o instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s); } catch (Exception ignored) {}
        }
        return BigDecimal.ZERO;
    }

    private static String pct(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + " PLN";
    }
}
