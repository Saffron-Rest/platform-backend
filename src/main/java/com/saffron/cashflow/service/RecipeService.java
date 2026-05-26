package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.MenuRecipe;
import com.saffron.cashflow.domain.MenuRecipeCostSnapshot;
import com.saffron.cashflow.domain.MenuRecipeIngredient;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.repository.MenuItemRepository;
import com.saffron.cashflow.repository.MenuRecipeCostSnapshotRepository;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recipe / cost-card management — v2.
 *
 * <p>This service is the engine room of menu engineering. Compared
 * with the original cost card it adds:
 * <ul>
 *   <li><b>Sub-recipes</b> — an ingredient line can point at another
 *       recipe (e.g. a "dough" base used by 5 dishes); cost is
 *       computed recursively with cycle protection.</li>
 *   <li><b>Unit conversion</b> — a line in g is correctly priced
 *       from a stock item costed in kg (and vice versa) via
 *       {@link UnitConverter}. Incompatible units fall back to a 1:1
 *       multiply with a warning surfaced to the UI.</li>
 *   <li><b>Prime cost</b> — optional labor minutes, packaging
 *       cost-per-unit and overhead % join food cost in a fully
 *       loaded price model.</li>
 *   <li><b>Price scenarios</b> — five target food-cost ratios
 *       (25/28/30/33/35 %) priced side-by-side so the admin can pick
 *       a margin band quickly.</li>
 *   <li><b>Health flags</b> — GOOD / WARNING / BAD computed from
 *       missing data, dominant ingredients, low margins, etc.</li>
 *   <li><b>Cost snapshots</b> — append-only history rows captured
 *       on save and apply so the admin can see how the food cost has
 *       drifted over time.</li>
 * </ul></p>
 */
@Service
public class RecipeService {

    /** Internal default minimum margin used by the health badge when
     *  the recipe doesn't carry its own threshold. */
    private static final BigDecimal DEFAULT_MIN_MARGIN_PCT = new BigDecimal("60");
    /** Single ingredient share over this is flagged as "concentration
     *  risk" — if the supplier raises the price, the dish flips. */
    private static final BigDecimal SINGLE_LINE_RISK_PCT = new BigDecimal("70");
    /** Recipe is flagged when achieved FC% exceeds the target by this
     *  much (lets numeric noise around the rounding boundary slide). */
    private static final BigDecimal FOOD_COST_OVERRUN_TOLERANCE = new BigDecimal("3");
    /** Targets considered in the multi-scenario suggestion. Picked to
     *  cover the common operator brackets: 25 % aggressive,
     *  28–30 % normal, 33–35 % shoulder. */
    private static final BigDecimal[] SCENARIO_TARGETS = {
            new BigDecimal("25"), new BigDecimal("28"),
            new BigDecimal("30"), new BigDecimal("33"),
            new BigDecimal("35"),
    };

    private final MenuRecipeRepository recipeRepository;
    private final MenuRecipeIngredientRepository ingredientRepository;
    private final MenuRecipeCostSnapshotRepository snapshotRepository;
    private final StockItemRepository stockItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final AuditService auditService;

    public RecipeService(
            MenuRecipeRepository recipeRepository,
            MenuRecipeIngredientRepository ingredientRepository,
            MenuRecipeCostSnapshotRepository snapshotRepository,
            StockItemRepository stockItemRepository,
            MenuItemRepository menuItemRepository,
            AuditService auditService) {
        this.recipeRepository = recipeRepository;
        this.ingredientRepository = ingredientRepository;
        this.snapshotRepository = snapshotRepository;
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
        // Caches shared across the entire listing so a stock item or
        // sub-recipe used by 10 menu items is hydrated once.
        Map<String, StockItem> stockCache = new HashMap<>();
        Map<String, MenuRecipe> recipeCache = new HashMap<>();
        Map<String, List<MenuRecipeIngredient>> linesCache = new HashMap<>();
        for (MenuRecipe r : recipes) recipeCache.put(r.getId(), r);
        List<Map<String, Object>> out = new ArrayList<>(recipes.size());
        for (MenuRecipe r : recipes) {
            List<MenuRecipeIngredient> lines = loadLines(r.getId(), linesCache);
            out.add(toMap(r, lines, stockCache, recipeCache, linesCache, /* lite */ true));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requirePermission(Permission.MENU_VIEW);
        MenuRecipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found"));
        Map<String, List<MenuRecipeIngredient>> linesCache = new HashMap<>();
        List<MenuRecipeIngredient> lines = loadLines(id, linesCache);
        Map<String, StockItem> stockCache = new HashMap<>();
        Map<String, MenuRecipe> recipeCache = new HashMap<>();
        recipeCache.put(id, recipe);
        return toMap(recipe, lines, stockCache, recipeCache, linesCache, /* lite */ false);
    }

    /**
     * History of cost snapshots for a recipe, newest first.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String id) {
        AuthHelper.requirePermission(Permission.MENU_VIEW);
        if (!recipeRepository.existsById(id)) throw new NotFoundException("Recipe not found");
        return snapshotRepository.findByRecipeIdOrderByTakenAtDesc(id).stream()
                .map(RecipeService::snapshotToMap)
                .toList();
    }

    /**
     * Recipes that consume a given stock item — used by the stock
     * page to warn that "9 recipes will reprice when you change this
     * unit cost".
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> affectedByStockItem(String stockItemId) {
        AuthHelper.requirePermission(Permission.MENU_VIEW);
        Set<String> recipeIds = new HashSet<>();
        for (MenuRecipeIngredient line : ingredientRepository.findByStockItemId(stockItemId)) {
            recipeIds.add(line.getRecipeId());
        }
        if (recipeIds.isEmpty()) return List.of();
        List<MenuRecipe> recipes = recipeRepository.findAllById(recipeIds);
        List<Map<String, Object>> out = new ArrayList<>(recipes.size());
        for (MenuRecipe r : recipes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("menuItemId", r.getMenuItemId());
            m.put("active", r.isActive());
            out.add(m);
        }
        return out;
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
        recordSnapshot(recipe.getId(), MenuRecipeCostSnapshot.Source.SAVE, null);
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
        recordSnapshot(recipe.getId(), MenuRecipeCostSnapshot.Source.SAVE, null);
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
     * suggestion) onto the linked {@link MenuItem}. Captures a SAVE-
     * tagged snapshot so the history reflects the moment the menu
     * actually changed.
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

        Map<String, List<MenuRecipeIngredient>> linesCache = new HashMap<>();
        Map<String, StockItem> stockCache = new HashMap<>();
        Map<String, MenuRecipe> recipeCache = new HashMap<>();
        recipeCache.put(id, recipe);
        List<MenuRecipeIngredient> lines = loadLines(id, linesCache);
        CostBreakdown breakdown = computeBreakdown(recipe, lines, stockCache, recipeCache,
                linesCache, new HashSet<>());

        Map<String, Object> before = Map.of(
                "sellPrice", item.getSellPrice(),
                "foodCost", item.getFoodCost());
        BigDecimal newFoodCost = breakdown.foodCostPerUnit == null
                ? null
                : breakdown.foodCostPerUnit.setScale(2, RoundingMode.HALF_UP);
        item.setFoodCost(newFoodCost);
        boolean priceChanged = false;
        if (applySuggestedPrice && breakdown.suggestedSellPrice != null) {
            item.setSellPrice(breakdown.suggestedSellPrice);
            priceChanged = true;
        }
        menuItemRepository.save(item);

        recordSnapshot(id, MenuRecipeCostSnapshot.Source.APPLY,
                priceChanged ? "Pushed cost + suggested price to menu" : "Pushed food cost to menu");

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

    @Transactional(readOnly = true)
    public Map<String, Object> preview(Map<String, Object> body) {
        AuthHelper.requirePermission(Permission.MENU_VIEW);
        MenuRecipe scratch = applyMutable(new MenuRecipe(), body);
        // Pretend the scratch recipe has a stable id so the cycle
        // visitor below doesn't accidentally trip when a sub-recipe
        // line points back at this draft.
        if (scratch.getId() == null) scratch.setId("preview");
        List<MenuRecipeIngredient> lines = parseIngredientLines(
                body.get("ingredients"), scratch.getId(), /* permissive */ true);
        Map<String, StockItem> stockCache = new HashMap<>();
        Map<String, MenuRecipe> recipeCache = new HashMap<>();
        Map<String, List<MenuRecipeIngredient>> linesCache = new HashMap<>();
        recipeCache.put(scratch.getId(), scratch);
        linesCache.put(scratch.getId(), lines);
        CostBreakdown breakdown = computeBreakdown(scratch, lines, stockCache, recipeCache,
                linesCache, new HashSet<>());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipe", recipeMeta(scratch));
        out.put("lines", lineMaps(lines, stockCache, recipeCache, linesCache, breakdown));
        out.put("totals", breakdown.totalsMap());
        out.put("scenarios", scenariosFor(breakdown, scratch));
        out.put("health", healthFor(breakdown, scratch));
        return out;
    }

    // ==================================================================
    // Costing engine
    // ==================================================================

    /**
     * Bundle of computed numbers returned by {@link #computeBreakdown}.
     * Mutable on purpose — internal helper, never escapes as-is.
     */
    private static class CostBreakdown {
        BigDecimal foodTotalCost = BigDecimal.ZERO;
        BigDecimal foodCostPerUnit;
        BigDecimal laborCostPerUnit;
        BigDecimal packagingCostPerUnit;
        BigDecimal primeCostPerUnit;
        BigDecimal overheadCostPerUnit;
        BigDecimal fullyLoadedCostPerUnit;
        BigDecimal suggestedSellPrice;
        BigDecimal achievedFoodCostPct;
        BigDecimal achievedPrimeCostPct;
        BigDecimal margin;
        BigDecimal marginPct;
        BigDecimal breakEvenPrice;
        boolean someIngredientsMissingCost = false;
        boolean someConversionsMissing = false;
        boolean cycleDetected = false;
        BigDecimal dominantLineSharePct;

        // Per-line costs keyed by line id (or sortOrder for previews).
        Map<String, BigDecimal> perLineCost = new LinkedHashMap<>();
        Map<String, Boolean> perLineConversionWarning = new LinkedHashMap<>();

        Map<String, Object> totalsMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("foodCost", foodTotalCost.setScale(2, RoundingMode.HALF_UP));
            m.put("foodCostPerUnit", scale2(foodCostPerUnit));
            m.put("laborCostPerUnit", scale2(laborCostPerUnit));
            m.put("packagingCostPerUnit", scale2(packagingCostPerUnit));
            m.put("primeCostPerUnit", scale2(primeCostPerUnit));
            m.put("overheadCostPerUnit", scale2(overheadCostPerUnit));
            m.put("fullyLoadedCostPerUnit", scale2(fullyLoadedCostPerUnit));
            // "costPerUnit" kept for backwards compatibility = food cost / unit.
            m.put("costPerUnit", scale2(foodCostPerUnit));
            m.put("suggestedSellPrice", suggestedSellPrice);
            m.put("breakEvenPrice", breakEvenPrice);
            m.put("achievedFoodCostPct", achievedFoodCostPct);
            m.put("achievedPrimeCostPct", achievedPrimeCostPct);
            m.put("contributionMargin", margin);
            m.put("marginPct", marginPct);
            m.put("someIngredientsMissingCost", someIngredientsMissingCost);
            m.put("someConversionsMissing", someConversionsMissing);
            m.put("cycleDetected", cycleDetected);
            m.put("dominantLineSharePct", dominantLineSharePct);
            return m;
        }
    }

    /**
     * Compute a full cost breakdown for a recipe. Recursively resolves
     * sub-recipe lines via {@code visited} (a set of recipe ids
     * currently being expanded) — encountering a recipe id already
     * in the visited set means we hit a cycle and bail out with
     * {@code cycleDetected = true}.
     */
    private CostBreakdown computeBreakdown(
            MenuRecipe recipe,
            List<MenuRecipeIngredient> lines,
            Map<String, StockItem> stockCache,
            Map<String, MenuRecipe> recipeCache,
            Map<String, List<MenuRecipeIngredient>> linesCache,
            Set<String> visited) {
        CostBreakdown out = new CostBreakdown();
        if (recipe.getId() != null && !visited.add(recipe.getId())) {
            out.cycleDetected = true;
            return out;
        }
        try {
            BigDecimal maxLineCost = BigDecimal.ZERO;
            for (MenuRecipeIngredient line : lines) {
                CostOfLine col = costOfLine(line, recipe, stockCache, recipeCache, linesCache, visited);
                if (col.missingCost) out.someIngredientsMissingCost = true;
                if (col.conversionMissing) out.someConversionsMissing = true;
                if (col.cycleDetected) out.cycleDetected = true;
                BigDecimal lineCost = col.cost == null ? BigDecimal.ZERO : col.cost;
                String key = line.getId() != null ? line.getId() : "ix:" + line.getSortOrder();
                out.perLineCost.put(key, lineCost);
                out.perLineConversionWarning.put(key, col.conversionMissing);
                out.foodTotalCost = out.foodTotalCost.add(lineCost);
                if (lineCost.compareTo(maxLineCost) > 0) maxLineCost = lineCost;
            }
            BigDecimal yield = recipe.getYieldQuantity();
            if (yield != null && yield.signum() > 0) {
                out.foodCostPerUnit = out.foodTotalCost.divide(yield, 6, RoundingMode.HALF_UP);
            }
            // Labor / packaging / overhead — purely additive, all
            // optional. We compute the "prime cost" (food + labor +
            // packaging) and "fully loaded" (+ overhead) numbers so
            // the suggested price can use whichever target the admin
            // configured.
            out.laborCostPerUnit = RecipeCosting.laborCostPerUnit(
                    recipe.getLaborMinutesPerUnit(), recipe.getLaborRatePerHour());
            out.packagingCostPerUnit = recipe.getPackagingCostPerUnit() == null
                    ? BigDecimal.ZERO
                    : recipe.getPackagingCostPerUnit();
            BigDecimal primeBase = (out.foodCostPerUnit == null ? BigDecimal.ZERO : out.foodCostPerUnit)
                    .add(out.laborCostPerUnit)
                    .add(out.packagingCostPerUnit);
            out.primeCostPerUnit = primeBase;
            BigDecimal loaded = RecipeCosting.applyOverhead(primeBase, recipe.getOverheadPct());
            out.fullyLoadedCostPerUnit = loaded;
            out.overheadCostPerUnit = (loaded == null || primeBase == null)
                    ? BigDecimal.ZERO
                    : loaded.subtract(primeBase).setScale(6, RoundingMode.HALF_UP);

            // Suggested price: prefer the prime-cost target when set,
            // otherwise fall back to the food-cost target.
            BigDecimal targetPrime = recipe.getTargetPrimeCostPct();
            BigDecimal targetFood = recipe.getTargetFoodCostPct();
            if (targetPrime != null && targetPrime.signum() > 0
                    && out.primeCostPerUnit != null && out.primeCostPerUnit.signum() > 0) {
                out.suggestedSellPrice = RecipeCosting.suggestedSellPrice(
                        out.primeCostPerUnit, targetPrime);
            } else if (out.foodCostPerUnit != null) {
                out.suggestedSellPrice = RecipeCosting.suggestedSellPrice(
                        out.foodCostPerUnit, targetFood);
            }
            out.breakEvenPrice = RecipeCosting.breakEvenPrice(out.fullyLoadedCostPerUnit);

            if (out.suggestedSellPrice != null) {
                out.achievedFoodCostPct = RecipeCosting.achievedFoodCostPct(
                        out.foodCostPerUnit, out.suggestedSellPrice);
                out.achievedPrimeCostPct = RecipeCosting.achievedPrimeCostPct(
                        out.primeCostPerUnit, out.suggestedSellPrice);
                out.margin = RecipeCosting.contributionMargin(
                        out.suggestedSellPrice,
                        out.foodCostPerUnit == null ? BigDecimal.ZERO : out.foodCostPerUnit);
                out.marginPct = RecipeCosting.marginPct(
                        out.suggestedSellPrice,
                        out.foodCostPerUnit == null ? BigDecimal.ZERO : out.foodCostPerUnit);
            }
            // Dominant line share: max line cost as % of total food cost.
            if (out.foodTotalCost.signum() > 0) {
                out.dominantLineSharePct = maxLineCost
                        .divide(out.foodTotalCost, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(1, RoundingMode.HALF_UP);
            }
        } finally {
            if (recipe.getId() != null) visited.remove(recipe.getId());
        }
        return out;
    }

    /** Result of costing a single ingredient line — distinguishes
     *  cost-missing from conversion-missing from cycle problems so the
     *  UI can show a different warning for each. */
    private static class CostOfLine {
        BigDecimal cost;
        boolean missingCost;
        boolean conversionMissing;
        boolean cycleDetected;
    }

    private CostOfLine costOfLine(
            MenuRecipeIngredient line,
            MenuRecipe parent,
            Map<String, StockItem> stockCache,
            Map<String, MenuRecipe> recipeCache,
            Map<String, List<MenuRecipeIngredient>> linesCache,
            Set<String> visited) {
        CostOfLine out = new CostOfLine();
        BigDecimal effectiveQty = RecipeCosting.effectiveQuantity(
                line.getQuantity(), line.getWastePct(), parent.getWastePct());

        if (line.getSubRecipeId() != null) {
            MenuRecipe sub = recipeCache.computeIfAbsent(
                    line.getSubRecipeId(),
                    id -> recipeRepository.findById(id).orElse(null));
            if (sub == null) {
                out.missingCost = true;
                return out;
            }
            if (visited.contains(sub.getId())) {
                out.cycleDetected = true;
                out.missingCost = true;
                return out;
            }
            List<MenuRecipeIngredient> subLines = loadLines(sub.getId(), linesCache);
            CostBreakdown subBreakdown = computeBreakdown(
                    sub, subLines, stockCache, recipeCache, linesCache, visited);
            if (subBreakdown.cycleDetected) out.cycleDetected = true;
            if (subBreakdown.someIngredientsMissingCost) out.missingCost = true;
            BigDecimal subPerYieldUnit = subBreakdown.foodCostPerUnit;
            if (subPerYieldUnit == null) {
                out.missingCost = true;
                return out;
            }
            // Convert the line's quantity into the sub-recipe's yield unit.
            Optional<BigDecimal> converted = UnitConverter.convert(
                    effectiveQty, line.getUnit(), sub.getYieldUnit());
            BigDecimal qtyInSubUnits;
            if (converted.isPresent()) {
                qtyInSubUnits = converted.get();
            } else {
                // Fall back to 1:1 multiply and tell the UI we
                // couldn't verify the conversion. Better to make a
                // visible best-effort estimate than to silently zero
                // the line.
                qtyInSubUnits = effectiveQty;
                if (!UnitConverter.canonical(line.getUnit())
                        .equalsIgnoreCase(UnitConverter.canonical(sub.getYieldUnit()))) {
                    out.conversionMissing = true;
                }
            }
            out.cost = qtyInSubUnits.multiply(subPerYieldUnit)
                    .setScale(6, RoundingMode.HALF_UP);
            return out;
        }

        // Stock-item-backed line.
        if (line.getStockItemId() == null) {
            // No source — happens during preview when the user hasn't
            // chosen anything yet. Leave the line at zero, flag it
            // missing so the UI nudges the user.
            out.missingCost = true;
            return out;
        }
        StockItem stock = stockCache.computeIfAbsent(
                line.getStockItemId(),
                id -> stockItemRepository.findById(id).orElse(null));
        if (stock == null || stock.getUnitCost() == null) {
            out.missingCost = true;
            return out;
        }
        Optional<BigDecimal> converted = UnitConverter.convert(
                effectiveQty, line.getUnit(), stock.getUnit());
        BigDecimal qtyInStockUnits;
        if (converted.isPresent()) {
            qtyInStockUnits = converted.get();
        } else {
            qtyInStockUnits = effectiveQty;
            if (line.getUnit() != null && stock.getUnit() != null
                    && !UnitConverter.canonical(line.getUnit())
                            .equalsIgnoreCase(UnitConverter.canonical(stock.getUnit()))) {
                out.conversionMissing = true;
            }
        }
        out.cost = qtyInStockUnits.multiply(stock.getUnitCost())
                .setScale(6, RoundingMode.HALF_UP);
        return out;
    }

    // ==================================================================
    // Scenarios & health flags
    // ==================================================================

    /** Suggested price at five canonical target food-cost ratios. */
    private List<Map<String, Object>> scenariosFor(CostBreakdown breakdown, MenuRecipe recipe) {
        if (breakdown.foodCostPerUnit == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(SCENARIO_TARGETS.length);
        BigDecimal currentTarget = recipe.getTargetFoodCostPct();
        for (BigDecimal target : SCENARIO_TARGETS) {
            BigDecimal price = RecipeCosting.suggestedSellPrice(
                    breakdown.foodCostPerUnit, target);
            BigDecimal margin = RecipeCosting.marginPct(price, breakdown.foodCostPerUnit);
            BigDecimal achievedFc = RecipeCosting.achievedFoodCostPct(
                    breakdown.foodCostPerUnit, price);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetFoodCostPct", target);
            m.put("suggestedSellPrice", price);
            m.put("achievedFoodCostPct", achievedFc);
            m.put("marginPct", margin);
            m.put("isCurrent", currentTarget != null
                    && currentTarget.compareTo(target) == 0);
            out.add(m);
        }
        return out;
    }

    /**
     * Compute the recipe's health (GOOD / WARNING / BAD) along with
     * an ordered list of human-readable issues. Keep the rules in one
     * place so the UI doesn't accidentally drift from the API.
     */
    private Map<String, Object> healthFor(CostBreakdown breakdown, MenuRecipe recipe) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String status = "GOOD";

        if (breakdown.cycleDetected) {
            status = "BAD";
            issues.add(issue("error", "cycle",
                    "This recipe contains a sub-recipe loop. Break the cycle to compute a cost."));
        }
        if (breakdown.someIngredientsMissingCost) {
            status = "BAD";
            issues.add(issue("error", "missing_cost",
                    "One or more ingredients have no unit cost. The total is incomplete."));
        }
        if (breakdown.someConversionsMissing) {
            if (!"BAD".equals(status)) status = "WARNING";
            issues.add(issue("warning", "missing_conversion",
                    "Some line units don't match their source units and couldn't be converted. Numbers use a 1:1 fallback."));
        }
        BigDecimal sellPrice = breakdown.suggestedSellPrice;
        if (sellPrice != null && breakdown.fullyLoadedCostPerUnit != null
                && sellPrice.compareTo(breakdown.fullyLoadedCostPerUnit) < 0) {
            status = "BAD";
            issues.add(issue("error", "below_breakeven",
                    "Suggested price is below the fully-loaded cost — this recipe loses money after overhead."));
        }
        BigDecimal minMargin = recipe.getMinMarginPct() == null
                ? DEFAULT_MIN_MARGIN_PCT : recipe.getMinMarginPct();
        if (breakdown.marginPct != null && breakdown.marginPct.compareTo(minMargin) < 0) {
            if (!"BAD".equals(status)) status = "WARNING";
            issues.add(issue("warning", "low_margin",
                    "Suggested margin "
                            + breakdown.marginPct + "% is below the target minimum of "
                            + minMargin + "%."));
        }
        BigDecimal targetFc = recipe.getTargetFoodCostPct();
        if (targetFc != null && breakdown.achievedFoodCostPct != null
                && breakdown.achievedFoodCostPct.subtract(targetFc)
                        .compareTo(FOOD_COST_OVERRUN_TOLERANCE) > 0) {
            if (!"BAD".equals(status)) status = "WARNING";
            issues.add(issue("warning", "food_cost_overrun",
                    "Achieved food cost " + breakdown.achievedFoodCostPct
                            + "% is more than " + FOOD_COST_OVERRUN_TOLERANCE
                            + "% above the target of " + targetFc + "%."));
        }
        if (breakdown.dominantLineSharePct != null
                && breakdown.dominantLineSharePct.compareTo(SINGLE_LINE_RISK_PCT) > 0) {
            if (!"BAD".equals(status)) status = "WARNING";
            issues.add(issue("warning", "concentration_risk",
                    "One ingredient is " + breakdown.dominantLineSharePct
                            + "% of the food cost — supplier price moves will swing this dish hard."));
        }
        if (issues.isEmpty()) {
            issues.add(issue("info", "ok", "Recipe looks healthy."));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("issues", issues);
        return m;
    }

    private static Map<String, Object> issue(String severity, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>(3);
        m.put("severity", severity);
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    // ==================================================================
    // Snapshots
    // ==================================================================

    /** Append a snapshot row reflecting the recipe's current numbers. */
    private void recordSnapshot(String recipeId, MenuRecipeCostSnapshot.Source source, String note) {
        MenuRecipe recipe = recipeRepository.findById(recipeId).orElse(null);
        if (recipe == null) return;
        Map<String, List<MenuRecipeIngredient>> linesCache = new HashMap<>();
        Map<String, StockItem> stockCache = new HashMap<>();
        Map<String, MenuRecipe> recipeCache = new HashMap<>();
        recipeCache.put(recipeId, recipe);
        List<MenuRecipeIngredient> lines = loadLines(recipeId, linesCache);
        CostBreakdown breakdown = computeBreakdown(recipe, lines, stockCache, recipeCache,
                linesCache, new HashSet<>());
        MenuRecipeCostSnapshot snap = new MenuRecipeCostSnapshot();
        snap.setRecipeId(recipeId);
        snap.setFoodCost(scale4(breakdown.foodTotalCost));
        snap.setPrimeCost(scale4(breakdown.primeCostPerUnit));
        snap.setFullyLoadedCost(scale4(breakdown.fullyLoadedCostPerUnit));
        snap.setCostPerUnit(scale4(breakdown.foodCostPerUnit));
        snap.setSuggestedPrice(scale4(breakdown.suggestedSellPrice));
        snap.setAchievedFoodCostPct(breakdown.achievedFoodCostPct);
        snap.setMarginPct(breakdown.marginPct);
        snap.setSource(source);
        snap.setNote(note);
        snapshotRepository.save(snap);
    }

    private static Map<String, Object> snapshotToMap(MenuRecipeCostSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("recipeId", s.getRecipeId());
        m.put("foodCost", s.getFoodCost());
        m.put("primeCost", s.getPrimeCost());
        m.put("fullyLoadedCost", s.getFullyLoadedCost());
        m.put("costPerUnit", s.getCostPerUnit());
        m.put("suggestedPrice", s.getSuggestedPrice());
        m.put("achievedFoodCostPct", s.getAchievedFoodCostPct());
        m.put("marginPct", s.getMarginPct());
        m.put("source", s.getSource() == null ? null : s.getSource().name());
        m.put("note", s.getNote());
        m.put("takenAt", s.getTakenAt() == null ? null : s.getTakenAt().toString());
        return m;
    }

    // ==================================================================
    // Serialization
    // ==================================================================

    private Map<String, Object> toMap(
            MenuRecipe recipe,
            List<MenuRecipeIngredient> lines,
            Map<String, StockItem> stockCache,
            Map<String, MenuRecipe> recipeCache,
            Map<String, List<MenuRecipeIngredient>> linesCache,
            boolean lite) {
        CostBreakdown breakdown = computeBreakdown(recipe, lines, stockCache, recipeCache,
                linesCache, new HashSet<>());
        Map<String, Object> out = recipeMeta(recipe);
        out.put("totals", breakdown.totalsMap());
        out.put("ingredientCount", lines.size());
        out.put("health", healthFor(breakdown, recipe));
        if (!lite) {
            out.put("lines", lineMaps(lines, stockCache, recipeCache, linesCache, breakdown));
            out.put("scenarios", scenariosFor(breakdown, recipe));
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
        m.put("targetPrimeCostPct", r.getTargetPrimeCostPct());
        m.put("vatRatePct", r.getVatRatePct());
        m.put("wastePct", r.getWastePct());
        m.put("laborMinutesPerUnit", r.getLaborMinutesPerUnit());
        m.put("laborRatePerHour", r.getLaborRatePerHour());
        m.put("packagingCostPerUnit", r.getPackagingCostPerUnit());
        m.put("overheadPct", r.getOverheadPct());
        m.put("minMarginPct", r.getMinMarginPct());
        m.put("notes", r.getNotes());
        m.put("active", r.isActive());
        if (r.getCreatedAt() != null) m.put("createdAt", r.getCreatedAt().toString());
        if (r.getUpdatedAt() != null) m.put("updatedAt", r.getUpdatedAt().toString());
        return m;
    }

    private List<Map<String, Object>> lineMaps(
            List<MenuRecipeIngredient> lines,
            Map<String, StockItem> stockCache,
            Map<String, MenuRecipe> recipeCache,
            Map<String, List<MenuRecipeIngredient>> linesCache,
            CostBreakdown totals) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MenuRecipeIngredient line : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", line.getId());
            m.put("sortOrder", line.getSortOrder());
            m.put("quantity", line.getQuantity());
            m.put("unit", line.getUnit());
            m.put("wastePct", line.getWastePct());
            m.put("note", line.getNote());

            String key = line.getId() != null ? line.getId() : "ix:" + line.getSortOrder();
            BigDecimal lineCost = totals.perLineCost.getOrDefault(key, BigDecimal.ZERO);
            Boolean conversionWarn = totals.perLineConversionWarning.getOrDefault(key, false);
            BigDecimal share = (totals.foodTotalCost.signum() > 0)
                    ? lineCost.divide(totals.foodTotalCost, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(1, RoundingMode.HALF_UP)
                    : null;
            m.put("lineCost", scale2(lineCost));
            m.put("shareOfTotalPct", share);
            m.put("conversionWarning", conversionWarn);

            if (line.getSubRecipeId() != null) {
                m.put("source", "RECIPE");
                m.put("subRecipeId", line.getSubRecipeId());
                MenuRecipe sub = recipeCache.computeIfAbsent(
                        line.getSubRecipeId(),
                        id -> recipeRepository.findById(id).orElse(null));
                m.put("sourceName", sub == null ? null : sub.getName());
                m.put("sourceUnit", sub == null ? null : sub.getYieldUnit());
                BigDecimal subUnitCost = null;
                if (sub != null) {
                    List<MenuRecipeIngredient> subLines = loadLines(sub.getId(), linesCache);
                    CostBreakdown subCb = computeBreakdown(sub, subLines, stockCache,
                            recipeCache, linesCache, new HashSet<>());
                    subUnitCost = subCb.foodCostPerUnit;
                }
                m.put("sourceUnitCost", scale4(subUnitCost));
                m.put("missingCost", subUnitCost == null);
            } else {
                m.put("source", "STOCK");
                m.put("stockItemId", line.getStockItemId());
                StockItem stock = line.getStockItemId() == null ? null
                        : stockCache.computeIfAbsent(
                                line.getStockItemId(),
                                id -> stockItemRepository.findById(id).orElse(null));
                m.put("sourceName", stock == null ? null : stock.getName());
                m.put("sourceUnit", stock == null ? null : stock.getUnit());
                m.put("sourceUnitCost", stock == null ? null : stock.getUnitCost());
                m.put("stockOnHand", stock == null ? null : stock.getOnHand());
                m.put("missingCost", stock == null || stock.getUnitCost() == null);
            }
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    // Mutation helpers (parse body → entities)
    // ==================================================================

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
        if (body.containsKey("targetPrimeCostPct")) {
            recipe.setTargetPrimeCostPct(parseBigDecimal(body.get("targetPrimeCostPct"), null));
        }
        if (body.containsKey("vatRatePct")) {
            BigDecimal v = parseBigDecimal(body.get("vatRatePct"), null);
            if (v != null) recipe.setVatRatePct(v);
        }
        if (body.containsKey("wastePct")) {
            recipe.setWastePct(parseBigDecimal(body.get("wastePct"), null));
        }
        if (body.containsKey("laborMinutesPerUnit")) {
            recipe.setLaborMinutesPerUnit(parseBigDecimal(body.get("laborMinutesPerUnit"), null));
        }
        if (body.containsKey("laborRatePerHour")) {
            recipe.setLaborRatePerHour(parseBigDecimal(body.get("laborRatePerHour"), null));
        }
        if (body.containsKey("packagingCostPerUnit")) {
            recipe.setPackagingCostPerUnit(parseBigDecimal(body.get("packagingCostPerUnit"), null));
        }
        if (body.containsKey("overheadPct")) {
            recipe.setOverheadPct(parseBigDecimal(body.get("overheadPct"), null));
        }
        if (body.containsKey("minMarginPct")) {
            recipe.setMinMarginPct(parseBigDecimal(body.get("minMarginPct"), null));
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
     * Wipe and replace the ingredient list. Cycle protection: walking
     * the sub-recipe DAG from each line and checking we never come
     * back to {@code recipeId}.
     */
    private void replaceIngredients(String recipeId, Object payload) {
        ingredientRepository.deleteByRecipeId(recipeId);
        if (!(payload instanceof List<?> list)) return;
        List<MenuRecipeIngredient> fresh = parseIngredientLines(list, recipeId, false);
        // Cycle check (sub-recipes only).
        for (MenuRecipeIngredient line : fresh) {
            if (line.getSubRecipeId() != null) {
                if (Boolean.TRUE.equals(reachesSelf(line.getSubRecipeId(), recipeId,
                        new HashSet<>(Set.of(recipeId))))) {
                    throw new BadRequestException(
                            "Sub-recipe cycle detected — recipe cannot include itself directly or transitively.");
                }
            }
        }
        if (!fresh.isEmpty()) ingredientRepository.saveAll(fresh);
    }

    private Boolean reachesSelf(String fromRecipeId, String selfId, Set<String> seen) {
        if (fromRecipeId == null) return false;
        if (fromRecipeId.equals(selfId)) return true;
        if (!seen.add(fromRecipeId)) return false;
        for (MenuRecipeIngredient line : ingredientRepository.findByRecipeIdOrderBySortOrderAsc(fromRecipeId)) {
            if (line.getSubRecipeId() != null
                    && Boolean.TRUE.equals(reachesSelf(line.getSubRecipeId(), selfId, seen))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse ingredient lines from a JSON body.
     *
     * @param permissive when true (preview path) we accept lines with
     *     no source so the user sees their in-flight edits even before
     *     they pick a stock item.
     */
    private List<MenuRecipeIngredient> parseIngredientLines(
            Object raw, String recipeId, boolean permissive) {
        List<MenuRecipeIngredient> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int order = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String stockItemId = strOrNull(m.get("stockItemId"));
            String subRecipeId = strOrNull(m.get("subRecipeId"));
            if (stockItemId != null && subRecipeId != null) {
                throw new BadRequestException(
                        "Each ingredient line takes either a stock item or a sub-recipe, not both.");
            }
            if (stockItemId == null && subRecipeId == null && !permissive) {
                // Drop empty lines silently on real saves — the user
                // probably added a row and didn't fill it in.
                continue;
            }
            MenuRecipeIngredient line = new MenuRecipeIngredient();
            line.setRecipeId(recipeId);
            line.setStockItemId(stockItemId);
            line.setSubRecipeId(subRecipeId);
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

    private List<MenuRecipeIngredient> loadLines(
            String recipeId, Map<String, List<MenuRecipeIngredient>> cache) {
        return cache.computeIfAbsent(recipeId,
                id -> ingredientRepository.findByRecipeIdOrderBySortOrderAsc(id));
    }

    private Map<String, Object> snapshot(MenuRecipe r) {
        // Used by the audit log: keep this compact and stable across
        // updates so the diff stays readable.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.getName());
        m.put("menuItemId", r.getMenuItemId());
        m.put("yieldQuantity", r.getYieldQuantity());
        m.put("yieldUnit", r.getYieldUnit());
        m.put("targetFoodCostPct", r.getTargetFoodCostPct());
        m.put("targetPrimeCostPct", r.getTargetPrimeCostPct());
        m.put("vatRatePct", r.getVatRatePct());
        m.put("wastePct", r.getWastePct());
        m.put("laborMinutesPerUnit", r.getLaborMinutesPerUnit());
        m.put("laborRatePerHour", r.getLaborRatePerHour());
        m.put("packagingCostPerUnit", r.getPackagingCostPerUnit());
        m.put("overheadPct", r.getOverheadPct());
        m.put("minMarginPct", r.getMinMarginPct());
        m.put("active", r.isActive());
        return m;
    }

    // ==================================================================
    // Tiny utilities
    // ==================================================================

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v == null ? null : v.setScale(4, RoundingMode.HALF_UP);
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

    // For unit-tests that want to access the scenario list directly.
    public static List<BigDecimal> scenarioTargets() {
        return Arrays.asList(SCENARIO_TARGETS);
    }
}
