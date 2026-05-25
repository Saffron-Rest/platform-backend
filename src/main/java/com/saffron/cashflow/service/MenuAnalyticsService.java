package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.MenuCategory;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.repository.MenuCategoryRepository;
import com.saffron.cashflow.repository.MenuItemRepository;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.security.AuthHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the raw POS sale rows into the numbers used by the menu analytics
 * dashboard and the menu engineering matrix.
 *
 * Everything is computed in-memory from a single SELECT on {@code pos_sale}
 * so we stay simple — the volume is bounded (one row per item per receipt).
 *
 * The output is deliberately verbose to keep the frontend dumb:
 * <ul>
 *   <li>{@code totals} — period KPIs (revenue, qty, avg ticket, food cost %)</li>
 *   <li>{@code items}  — per-item aggregates with margin</li>
 *   <li>{@code categoryMix} — per-category revenue share</li>
 *   <li>{@code unmatched} — count of sales that didn't resolve to a menu item</li>
 * </ul>
 */
@Service
public class MenuAnalyticsService {

    private final PosSaleRepository saleRepository;
    private final MenuItemRepository itemRepository;
    private final MenuCategoryRepository categoryRepository;

    public MenuAnalyticsService(
            PosSaleRepository saleRepository,
            MenuItemRepository itemRepository,
            MenuCategoryRepository categoryRepository) {
        this.saleRepository = saleRepository;
        this.itemRepository = itemRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> compute(LocalDate from, LocalDate to) {
        AuthHelper.requireOperations();
        if (from == null) from = LocalDate.now().withDayOfMonth(1);
        if (to == null) to = LocalDate.now();
        if (from.isAfter(to)) {
            LocalDate tmp = from; from = to; to = tmp;
        }

        List<PosSale> sales = saleRepository.findInRange(from, to);
        Map<String, MenuItem> itemsById = new HashMap<>();
        for (MenuItem i : itemRepository.findAll()) itemsById.put(i.getId(), i);
        Map<String, MenuCategory> catsById = new HashMap<>();
        for (MenuCategory c : categoryRepository.findAll()) catsById.put(c.getId(), c);

        Map<String, ItemAgg> byItem = new HashMap<>();
        Map<String, CategoryAgg> byCategory = new HashMap<>();
        int totalQty = 0;
        int matchedQty = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalFoodCost = BigDecimal.ZERO;
        int unmatched = 0;

        // Avg-ticket is approximated as revenue ÷ distinct receipts. Our pos_sale
        // table stores one row per line, and externalId looks like "order-N#i".
        // Strip the "#i" suffix to count receipts.
        java.util.Set<String> receipts = new java.util.HashSet<>();

        for (PosSale s : sales) {
            BigDecimal qty = s.getQuantity() != null ? s.getQuantity() : BigDecimal.ZERO;
            BigDecimal unit = s.getUnitPrice() != null ? s.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal disc = s.getDiscountAmount() != null ? s.getDiscountAmount() : BigDecimal.ZERO;
            BigDecimal lineRevenue = unit.subtract(disc).max(BigDecimal.ZERO).multiply(qty);
            BigDecimal lineFoodCost = s.getFoodCost() != null
                    ? s.getFoodCost().multiply(qty)
                    : BigDecimal.ZERO;
            int qtyInt = qty.setScale(0, RoundingMode.HALF_UP).intValueExact();

            totalQty += qtyInt;
            totalRevenue = totalRevenue.add(lineRevenue);
            totalFoodCost = totalFoodCost.add(lineFoodCost);

            String key = s.getMenuItemId() != null ? s.getMenuItemId() : ("unmatched::" + (s.getSku() != null ? s.getSku() : (s.getItemName() != null ? s.getItemName() : "unknown")));
            ItemAgg agg = byItem.computeIfAbsent(key, k -> new ItemAgg());
            if (s.getMenuItemId() == null) {
                unmatched++;
                agg.unmatched = true;
                agg.displayName = s.getItemName() != null ? s.getItemName() :
                        (s.getSku() != null ? "SKU " + s.getSku() : "Unknown");
            } else {
                matchedQty += qtyInt;
                MenuItem mi = itemsById.get(s.getMenuItemId());
                if (mi != null) {
                    agg.itemId = mi.getId();
                    agg.displayName = mi.getName();
                    agg.sku = mi.getSku();
                    agg.categoryId = mi.getCategoryId();
                    if (mi.getFoodCost() != null) agg.unitFoodCost = mi.getFoodCost();
                    agg.sellPrice = mi.getSellPrice();
                }
            }
            agg.quantity = agg.quantity.add(qty);
            agg.revenue = agg.revenue.add(lineRevenue);
            agg.foodCost = agg.foodCost.add(lineFoodCost);

            if (s.getCategoryId() != null) {
                CategoryAgg cAgg = byCategory.computeIfAbsent(s.getCategoryId(), k -> new CategoryAgg());
                cAgg.revenue = cAgg.revenue.add(lineRevenue);
                cAgg.quantity = cAgg.quantity.add(qty);
            }

            String extId = s.getExternalId();
            if (extId != null) {
                int hash = extId.indexOf('#');
                receipts.add(hash > 0 ? extId.substring(0, hash) : extId);
            }
        }

        BigDecimal avgTicket = receipts.isEmpty()
                ? BigDecimal.ZERO
                : totalRevenue.divide(BigDecimal.valueOf(receipts.size()), 2, RoundingMode.HALF_UP);
        BigDecimal totalMargin = totalRevenue.subtract(totalFoodCost);
        BigDecimal foodCostPct = totalRevenue.signum() == 0
                ? BigDecimal.ZERO
                : totalFoodCost.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
        BigDecimal marginPct = totalRevenue.signum() == 0
                ? BigDecimal.ZERO
                : totalMargin.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);

        List<Map<String, Object>> itemRows = new ArrayList<>();
        for (ItemAgg agg : byItem.values()) {
            itemRows.add(agg.toMap(catsById, totalRevenue));
        }
        itemRows.sort(Comparator.comparing(
                (Map<String, Object> r) -> ((Number) r.get("revenue")).doubleValue()).reversed());

        List<Map<String, Object>> categoryRows = new ArrayList<>();
        for (Map.Entry<String, CategoryAgg> e : byCategory.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            MenuCategory cat = catsById.get(e.getKey());
            row.put("categoryId", e.getKey());
            row.put("name", cat != null ? cat.getName() : "(unknown)");
            row.put("revenue", e.getValue().revenue.setScale(2, RoundingMode.HALF_UP));
            row.put("quantity", e.getValue().quantity.setScale(0, RoundingMode.HALF_UP));
            row.put("share", totalRevenue.signum() == 0
                    ? BigDecimal.ZERO
                    : e.getValue().revenue.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
            categoryRows.add(row);
        }
        categoryRows.sort(Comparator.comparing(
                (Map<String, Object> r) -> ((Number) r.get("revenue")).doubleValue()).reversed());

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("quantity", totalQty);
        totals.put("matchedQuantity", matchedQty);
        totals.put("receipts", receipts.size());
        totals.put("revenue", totalRevenue.setScale(2, RoundingMode.HALF_UP));
        totals.put("foodCost", totalFoodCost.setScale(2, RoundingMode.HALF_UP));
        totals.put("margin", totalMargin.setScale(2, RoundingMode.HALF_UP));
        totals.put("marginPct", marginPct);
        totals.put("foodCostPct", foodCostPct);
        totals.put("avgTicket", avgTicket);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("totals", totals);
        out.put("items", itemRows);
        out.put("categoryMix", categoryRows);
        out.put("unmatched", unmatched);
        return out;
    }

    // ---------- Aggregates ----------

    static final class ItemAgg {
        String itemId;
        String displayName;
        String sku;
        String categoryId;
        BigDecimal sellPrice = BigDecimal.ZERO;
        BigDecimal unitFoodCost = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal foodCost = BigDecimal.ZERO;
        boolean unmatched;

        Map<String, Object> toMap(Map<String, MenuCategory> cats, BigDecimal totalRevenue) {
            Map<String, Object> m = new LinkedHashMap<>();
            BigDecimal margin = revenue.subtract(foodCost);
            BigDecimal marginPct = revenue.signum() == 0
                    ? BigDecimal.ZERO
                    : margin.divide(revenue, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
            BigDecimal foodCostPct = revenue.signum() == 0
                    ? BigDecimal.ZERO
                    : foodCost.divide(revenue, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
            BigDecimal share = totalRevenue.signum() == 0
                    ? BigDecimal.ZERO
                    : revenue.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);

            m.put("itemId", itemId);
            m.put("name", displayName);
            m.put("sku", sku);
            m.put("categoryId", categoryId);
            m.put("categoryName", categoryId != null && cats.get(categoryId) != null
                    ? cats.get(categoryId).getName() : null);
            m.put("sellPrice", sellPrice.setScale(2, RoundingMode.HALF_UP));
            m.put("unitFoodCost", unitFoodCost.setScale(2, RoundingMode.HALF_UP));
            m.put("quantity", quantity.setScale(0, RoundingMode.HALF_UP));
            m.put("revenue", revenue.setScale(2, RoundingMode.HALF_UP));
            m.put("foodCost", foodCost.setScale(2, RoundingMode.HALF_UP));
            m.put("margin", margin.setScale(2, RoundingMode.HALF_UP));
            m.put("marginPct", marginPct);
            m.put("foodCostPct", foodCostPct);
            m.put("share", share);
            m.put("unmatched", unmatched);
            return m;
        }
    }

    static final class CategoryAgg {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
    }
}
