package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.MenuRecipe;
import com.saffron.cashflow.domain.MenuRecipeIngredient;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.repository.MenuItemRepository;
import com.saffron.cashflow.repository.MenuRecipeIngredientRepository;
import com.saffron.cashflow.repository.MenuRecipeRepository;
import com.saffron.cashflow.repository.StockItemRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe / cost-card management.
 *
 * <p>Holds the cost cards, computes their effective food cost from
 * the latest stock-item unit costs, and surfaces a suggested gross
 * sales price for the admin's target food-cost percentage. The pure
 * math lives in {@link RecipeCosting} so it's unit-testable without
 * spinning up Spring.</p>
 *
 * <p>All read paths are gated behind {@link Permission#MENU_VIEW};
 * mutations require {@link Permission#MENU_RECIPES_MANAGE}. Pushing a
 * suggested price onto the live menu also requires
 * {@link Permission#MENU_MANAGE} so an admin who can experiment with
 * recipes can't silently bump prices on the customer-facing menu.</p>
 */
@Service
public class RecipeService {

    private final MenuRecipeRepository recipeRepository;
    private final MenuRecipeIngredientRepository ingredientRepository;
    private final StockItemRepository stockItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final AuditService auditService;

    public RecipeService(
            MenuRecipeRepository recipeRepository,
            MenuRecipeIngredientRepository ingredientRepository,
            StockItemRepository stockItemRepository,
            MenuItemRepository menuItemRepository,
            AuditService auditService) {
        this.recipeRepository = recipeRepository;
        this.ingredientRepository = ingredientRepository;
        this.stockItemRepository = stockItemRepository;
        this.menuItemRepository = menuItemRepository;
        this.auditService = auditService;
    }

    // ==================================================================
    // Reads
    // ==================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean includeInactive) {
        AuthHelper.requirePermission(Permission.MENU_VIEW);
        List<MenuRecipe> recipes = includeInactive
                ? recipeRepository.findAllByOrderByNameAsc()
                : recipeRepository.findByActiveTrueOrderByNameAsc();
        // Restaurants typically run with a few dozen recipes; per-recipe
        // ingredient fetch is fine. The stock cache memoizes unit-cost
        // lookups so a shared ingredient (e.g. salt across 20 recipes)
        // hits the DB once.
        Map<String, StockItem> stockCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>(recipes.size());
        for (MenuRecipe r : recipes) {
            List<MenuRecipeIngredient> lines =
                    ingredientRepository.findByRecipeIdOrderBySortOrderAsc(r.getId());
            out.add(toMap(r, lines, stockCache, /* lite */ true));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requirePermission(Permission.MENU_VIEW);
        MenuRecipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found"));
        List<MenuRecipeIngredient> lines =
                ingredientRepository.findByRecipeIdOrderBySortOrderAsc(id);
        return toMap(recipe, lines, new HashMap<>(), /* lite */ false);
    }

    // ==================================================================
    // Writes
    // ==================================================================

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        AuthHelper.requirePermission(Permission.MENU_RECIPES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        MenuRecipe recipe = applyMutable(new MenuRecipe(), body);
        if (recipe.getName() == null || recipe.getName().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        recipe = recipeRepository.save(recipe);
        replaceIngredients(recipe.getId(), body.get("ingredients"));
        auditService.log(user.id(), AuditAction.CREATE, "MenuRecipe", recipe.getId(),
                Map.of("name", recipe.getName(),
                        "menuItemId", recipe.getMenuItemId() == null ? "" : recipe.getMenuItemId()));
        return get(recipe.getId());
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requirePermission(Permission.MENU_RECIPES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        MenuRecipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found"));
        Map<String, Object> before = snapshot(recipe);
        applyMutable(recipe, body);
        recipe = recipeRepository.save(recipe);
        if (body.containsKey("ingredients")) {
            replaceIngredients(recipe.getId(), body.get("ingredients"));
        }
        auditService.logChange(user.id(), AuditAction.UPDATE, "MenuRecipe", recipe.getId(),
                before, snapshot(recipe), null);
        return get(recipe.getId());
    }

    @Transactional
    public void archive(String id) {
        AuthHelper.requirePermission(Permission.MENU_RECIPES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        MenuRecipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found"));
        if (!recipe.isActive()) return;
        Map<String, Object> before = snapshot(recipe);
        recipe.setActive(false);
        recipeRepository.save(recipe);
        auditService.logChange(user.id(), AuditAction.DELETE, "MenuRecipe", recipe.getId(),
                before, snapshot(recipe), null);
    }

    /**
     * Push the recipe's computed food cost (and optionally a price
     * suggestion) onto the linked {@link MenuItem}.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If {@code applySuggestedPrice} is true and a suggested
     *       price is available, the menu item's {@code sellPrice} is
     *       replaced. Otherwise the existing price is left
     *       untouched.</li>
     *   <li>The menu item's {@code foodCost} is always synced to the
     *       recipe's per-unit cost — this is the whole point of
     *       wiring recipes to menu items.</li>
     *   <li>Recipes without a {@code menuItemId} link return an error;
     *       the admin has to attach one first.</li>
     * </ul></p>
     */
    @Transactional
    public Map<String, Object> applyToMenu(String id, boolean applySuggestedPrice) {
        AuthHelper.requirePermission(Permission.MENU_RECIPES_MANAGE);
        AuthHelper.requirePermission(Permission.MENU_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        MenuRecipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found"));
        if (recipe.getMenuItemId() == null || recipe.getMenuItemId().isBlank()) {
            throw new BadRequestException("Attach a menu item before applying the cost card");
        }
        MenuItem item = menuItemRepository.findById(recipe.getMenuItemId())
                .orElseThrow(() -> new NotFoundException("Linked menu item not found"));

        List<MenuRecipeIngredient> lines =
                ingredientRepository.findByRecipeIdOrderBySortOrderAsc(id);
        Map<String, StockItem> stockCache = new HashMap<>();
        CostBreakdown breakdown = computeBreakdown(recipe, lines, stockCache);

        Map<String, Object> before = Map.of(
                "sellPrice", item.getSellPrice(),
                "foodCost", item.getFoodCost());
        BigDecimal newFoodCost = breakdown.costPerUnit == null
                ? null
                : breakdown.costPerUnit.setScale(2, RoundingMode.HALF_UP);
        item.setFoodCost(newFoodCost);
        boolean priceChanged = false;
        if (applySuggestedPrice && breakdown.suggestedSellPrice != null) {
            item.setSellPrice(breakdown.suggestedSellPrice);
            priceChanged = true;
        }
        menuItemRepository.save(item);

        auditService.logChange(
                user.id(),
                AuditAction.UPDATE,
                "MenuItem",
                item.getId(),
                before,
                Map.of("sellPrice", item.getSellPrice(), "foodCost", item.getFoodCost()),
                Map.of("appliedFromRecipe", recipe.getId(), "priceChanged", priceChanged));
        return get(id);
    }

    // ==================================================================
    // Preview — same maths as a real save but no persistence
    // ==================================================================

    /**
     * Compute a cost breakdown for a draft recipe payload. Used by
     * the in-flight modal so admins see the cost & suggestion update
     * as they edit, without round-tripping a save.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Map<String, Object> body) {
        AuthHelper.requirePermission(Permission.MENU_VIEW);
        MenuRecipe scratch = applyMutable(new MenuRecipe(), body);
        List<MenuRecipeIngredient> lines = parseIngredientLines(body.get("ingredients"), "scratch");
        Map<String, StockItem> stockCache = new HashMap<>();
        CostBreakdown breakdown = computeBreakdown(scratch, lines, stockCache);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipe", recipeMeta(scratch));
        out.put("lines", lineMaps(lines, stockCache, breakdown));
        out.put("totals", breakdown.totalsMap());
        return out;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Bundle of computed numbers returned by {@link #computeBreakdown}.
     * Mutable on purpose — internal helper, never escapes.
     */
    private static class CostBreakdown {
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal costPerUnit;
        BigDecimal suggestedSellPrice;
        BigDecimal achievedFoodCostPct;
        BigDecimal margin;
        BigDecimal marginPct;
        boolean someIngredientsMissingCost = false;

        Map<String, Object> totalsMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalCost", totalCost.setScale(2, RoundingMode.HALF_UP));
            m.put("costPerUnit", costPerUnit == null
                    ? null
                    : costPerUnit.setScale(2, RoundingMode.HALF_UP));
            m.put("suggestedSellPrice", suggestedSellPrice);
            m.put("achievedFoodCostPct", achievedFoodCostPct);
            m.put("contributionMargin", margin);
            m.put("marginPct", marginPct);
            m.put("someIngredientsMissingCost", someIngredientsMissingCost);
            return m;
        }
    }

    /**
     * Multiply quantities × current stock unit costs, divide by yield,
     * and quote a price suggestion + margins for the recipe's target
     * food-cost %. Pure transformation — never mutates persistent
     * state.
     */
    private CostBreakdown computeBreakdown(
            MenuRecipe recipe,
            List<MenuRecipeIngredient> lines,
            Map<String, StockItem> stockCache) {
        CostBreakdown out = new CostBreakdown();
        for (MenuRecipeIngredient line : lines) {
            StockItem stock = stockCache.computeIfAbsent(
                    line.getStockItemId(),
                    id -> stockItemRepository.findById(id).orElse(null));
            BigDecimal unitCost = stock == null ? null : stock.getUnitCost();
            if (unitCost == null) {
                out.someIngredientsMissingCost = true;
            }
            BigDecimal effectiveQty = RecipeCosting.effectiveQuantity(
                    line.getQuantity(), line.getWastePct(), recipe.getWastePct());
            BigDecimal lineCost = RecipeCosting.lineCost(effectiveQty, unitCost);
            out.totalCost = out.totalCost.add(lineCost);
        }
        BigDecimal yield = recipe.getYieldQuantity();
        if (yield != null && yield.signum() > 0) {
            out.costPerUnit = out.totalCost.divide(yield, 6, RoundingMode.HALF_UP);
            out.suggestedSellPrice = RecipeCosting.suggestedSellPrice(
                    out.costPerUnit, recipe.getTargetFoodCostPct());
            if (out.suggestedSellPrice != null) {
                out.achievedFoodCostPct = RecipeCosting.achievedFoodCostPct(
                        out.costPerUnit, out.suggestedSellPrice);
                out.margin = RecipeCosting.contributionMargin(
                        out.suggestedSellPrice, out.costPerUnit);
                out.marginPct = RecipeCosting.marginPct(
                        out.suggestedSellPrice, out.costPerUnit);
            }
        }
        return out;
    }

    private Map<String, Object> toMap(
            MenuRecipe recipe,
            List<MenuRecipeIngredient> lines,
            Map<String, StockItem> stockCache,
            boolean lite) {
        CostBreakdown breakdown = computeBreakdown(recipe, lines, stockCache);
        Map<String, Object> out = recipeMeta(recipe);
        out.put("totals", breakdown.totalsMap());
        out.put("ingredientCount", lines.size());
        if (!lite) {
            out.put("lines", lineMaps(lines, stockCache, breakdown));
        }
        if (recipe.getMenuItemId() != null) {
            menuItemRepository.findById(recipe.getMenuItemId()).ifPresent(mi -> {
                out.put("menuItemName", mi.getName());
                out.put("menuItemSellPrice", mi.getSellPrice());
                out.put("menuItemFoodCost", mi.getFoodCost());
            });
        }
        return out;
    }

    private Map<String, Object> recipeMeta(MenuRecipe r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("menuItemId", r.getMenuItemId());
        m.put("yieldQuantity", r.getYieldQuantity());
        m.put("yieldUnit", r.getYieldUnit());
        m.put("targetFoodCostPct", r.getTargetFoodCostPct());
        m.put("vatRatePct", r.getVatRatePct());
        m.put("wastePct", r.getWastePct());
        m.put("notes", r.getNotes());
        m.put("active", r.isActive());
        if (r.getCreatedAt() != null) m.put("createdAt", r.getCreatedAt().toString());
        if (r.getUpdatedAt() != null) m.put("updatedAt", r.getUpdatedAt().toString());
        return m;
    }

    private List<Map<String, Object>> lineMaps(
            List<MenuRecipeIngredient> lines,
            Map<String, StockItem> stockCache,
            CostBreakdown totalsForShare) {
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal total = totalsForShare.totalCost;
        for (MenuRecipeIngredient line : lines) {
            StockItem stock = stockCache.computeIfAbsent(
                    line.getStockItemId(),
                    id -> stockItemRepository.findById(id).orElse(null));
            BigDecimal unitCost = stock == null ? null : stock.getUnitCost();
            BigDecimal effectiveQty = RecipeCosting.effectiveQuantity(
                    line.getQuantity(), line.getWastePct(), null);
            BigDecimal lineCost = RecipeCosting.lineCost(effectiveQty, unitCost);
            BigDecimal share = (total != null && total.signum() > 0 && lineCost != null)
                    ? lineCost.divide(total, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(1, RoundingMode.HALF_UP)
                    : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", line.getId());
            m.put("stockItemId", line.getStockItemId());
            m.put("stockItemName", stock == null ? null : stock.getName());
            m.put("stockItemUnit", stock == null ? null : stock.getUnit());
            m.put("stockOnHand", stock == null ? null : stock.getOnHand());
            m.put("quantity", line.getQuantity());
            m.put("unit", line.getUnit());
            m.put("wastePct", line.getWastePct());
            m.put("note", line.getNote());
            m.put("unitCost", unitCost);
            m.put("effectiveQuantity",
                    effectiveQty == null ? null : effectiveQty.setScale(4, RoundingMode.HALF_UP));
            m.put("lineCost", lineCost == null ? null : lineCost.setScale(2, RoundingMode.HALF_UP));
            m.put("shareOfTotalPct", share);
            m.put("missingCost", unitCost == null);
            out.add(m);
        }
        return out;
    }

    private MenuRecipe applyMutable(MenuRecipe recipe, Map<String, Object> body) {
        if (body == null) return recipe;
        if (body.containsKey("name")) {
            Object v = body.get("name");
            recipe.setName(v == null ? null : v.toString().trim());
        }
        if (body.containsKey("menuItemId")) {
            Object v = body.get("menuItemId");
            String s = v == null ? null : v.toString().trim();
            recipe.setMenuItemId(s == null || s.isEmpty() ? null : s);
        }
        if (body.containsKey("yieldQuantity")) {
            recipe.setYieldQuantity(parseBigDecimal(body.get("yieldQuantity"), BigDecimal.ONE));
        }
        if (body.containsKey("yieldUnit")) {
            Object v = body.get("yieldUnit");
            String s = v == null ? null : v.toString().trim();
            if (s != null && !s.isEmpty()) recipe.setYieldUnit(s);
        }
        if (body.containsKey("targetFoodCostPct")) {
            recipe.setTargetFoodCostPct(parseBigDecimal(body.get("targetFoodCostPct"), null));
        }
        if (body.containsKey("vatRatePct")) {
            BigDecimal v = parseBigDecimal(body.get("vatRatePct"), null);
            if (v != null) recipe.setVatRatePct(v);
        }
        if (body.containsKey("wastePct")) {
            recipe.setWastePct(parseBigDecimal(body.get("wastePct"), null));
        }
        if (body.containsKey("notes")) {
            Object v = body.get("notes");
            recipe.setNotes(v == null ? null : v.toString());
        }
        if (body.containsKey("active")) {
            Object v = body.get("active");
            recipe.setActive(v == null || Boolean.parseBoolean(v.toString()));
        }
        return recipe;
    }

    /**
     * Wipe and replace the ingredient list. Simpler than per-line
     * diffing, and recipe lines are cheap (a recipe rarely has more
     * than a dozen ingredients). The cascade keeps the IDs clean.
     */
    private void replaceIngredients(String recipeId, Object payload) {
        ingredientRepository.deleteByRecipeId(recipeId);
        if (!(payload instanceof List<?> list)) return;
        List<MenuRecipeIngredient> fresh = parseIngredientLines(list, recipeId);
        if (!fresh.isEmpty()) ingredientRepository.saveAll(fresh);
    }

    private List<MenuRecipeIngredient> parseIngredientLines(Object raw, String recipeId) {
        List<MenuRecipeIngredient> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int order = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String stockItemId = strOrNull(m.get("stockItemId"));
            if (stockItemId == null) continue;
            MenuRecipeIngredient line = new MenuRecipeIngredient();
            line.setRecipeId(recipeId);
            line.setStockItemId(stockItemId);
            line.setQuantity(parseBigDecimal(m.get("quantity"), BigDecimal.ZERO));
            String unit = strOrNull(m.get("unit"));
            if (unit != null && !unit.isBlank()) line.setUnit(unit);
            line.setWastePct(parseBigDecimal(m.get("wastePct"), null));
            line.setNote(strOrNull(m.get("note")));
            line.setSortOrder(order++);
            out.add(line);
        }
        return out;
    }

    private Map<String, Object> snapshot(MenuRecipe r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.getName());
        m.put("menuItemId", r.getMenuItemId());
        m.put("yieldQuantity", r.getYieldQuantity());
        m.put("yieldUnit", r.getYieldUnit());
        m.put("targetFoodCostPct", r.getTargetFoodCostPct());
        m.put("vatRatePct", r.getVatRatePct());
        m.put("wastePct", r.getWastePct());
        m.put("active", r.isActive());
        return m;
    }

    private static BigDecimal parseBigDecimal(Object raw, BigDecimal fallback) {
        if (raw == null) return fallback;
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        String s = raw.toString().trim();
        if (s.isEmpty()) return fallback;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String strOrNull(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
