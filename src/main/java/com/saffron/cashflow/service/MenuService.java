package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.MenuCategory;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.repository.MenuCategoryRepository;
import com.saffron.cashflow.repository.MenuItemRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages the restaurant menu — categories and items.
 *
 * Item sell price and food cost are stored gross of VAT to match POS receipts.
 * Margin calculations elsewhere can back out net amounts using {@code vatRatePct}.
 *
 * All mutations require an operations role (admin/manager). Cashiers see a
 * read-only view.
 */
@Service
public class MenuService {

    private static final BigDecimal DEFAULT_VAT = new BigDecimal("8.00");

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final AuditService auditService;

    public MenuService(
            MenuCategoryRepository categoryRepository,
            MenuItemRepository itemRepository,
            AuditService auditService) {
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
        this.auditService = auditService;
    }

    // ---------- Categories ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCategories(boolean includeArchived) {
        List<MenuCategory> cats = includeArchived
                ? categoryRepository.findAllByOrderBySortOrderAscNameAsc()
                : categoryRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc();
        // Compute per-category item count for the UI.
        Map<String, Long> counts = new HashMap<>();
        for (MenuItem item : itemRepository.findAll()) {
            counts.merge(item.getCategoryId(), 1L, Long::sum);
        }
        return cats.stream().map(c -> categoryToMap(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    @Transactional
    public Map<String, Object> createCategory(String name, Integer sortOrder) {
        AuthHelper.requireOperations();
        String clean = requireName(name, 80, "Category name");
        categoryRepository.findFirstByNameIgnoreCase(clean).ifPresent(existing -> {
            throw new BadRequestException("Category \"" + existing.getName() + "\" already exists");
        });
        MenuCategory c = new MenuCategory();
        c.setName(clean);
        c.setSortOrder(sortOrder != null ? sortOrder : nextSortOrder());
        c.setActive(true);
        c = categoryRepository.save(c);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "MenuCategory", c.getId(),
                Map.of(), Map.of("name", c.getName(), "sortOrder", c.getSortOrder()), null);
        return categoryToMap(c, 0L);
    }

    @Transactional
    public Map<String, Object> updateCategory(String id, String name, Integer sortOrder, Boolean active) {
        AuthHelper.requireOperations();
        MenuCategory c = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found"));
        Map<String, Object> before = Map.of(
                "name", c.getName(),
                "sortOrder", c.getSortOrder(),
                "active", c.isActive());
        if (name != null) {
            String clean = requireName(name, 80, "Category name");
            categoryRepository.findFirstByNameIgnoreCase(clean).ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    throw new BadRequestException("Category \"" + existing.getName() + "\" already exists");
                }
            });
            c.setName(clean);
        }
        if (sortOrder != null) c.setSortOrder(sortOrder);
        if (active != null) c.setActive(active);
        c = categoryRepository.save(c);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "MenuCategory", c.getId(),
                before, Map.of("name", c.getName(), "sortOrder", c.getSortOrder(), "active", c.isActive()), null);
        long itemCount = itemRepository.findAllByCategoryIdOrderByNameAsc(c.getId()).size();
        return categoryToMap(c, itemCount);
    }

    @Transactional
    public void deleteCategory(String id) {
        AuthHelper.requireOperations();
        MenuCategory c = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found"));
        long items = itemRepository.findAllByCategoryIdOrderByNameAsc(id).size();
        if (items > 0) {
            throw new BadRequestException(
                    "Category has " + items + " menu item" + (items == 1 ? "" : "s")
                            + ". Move or archive them first.");
        }
        categoryRepository.delete(c);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "MenuCategory", id,
                Map.of("name", c.getName()), Map.of(), null);
    }

    // ---------- Items ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listItems(String categoryId, boolean includeArchived) {
        List<MenuItem> items;
        if (categoryId != null && !categoryId.isBlank()) {
            items = itemRepository.findAllByCategoryIdOrderByNameAsc(categoryId);
        } else if (!includeArchived) {
            items = itemRepository.findAllByActiveTrueOrderByNameAsc();
        } else {
            items = itemRepository.findAllByOrderByNameAsc();
        }
        Map<String, MenuCategory> catsById = categoriesById();
        return items.stream().map(i -> itemToMap(i, catsById.get(i.getCategoryId()))).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getItem(String id) {
        MenuItem item = itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Menu item not found"));
        MenuCategory cat = item.getCategoryId() != null
                ? categoryRepository.findById(item.getCategoryId()).orElse(null)
                : null;
        return itemToMap(item, cat);
    }

    @Transactional
    public Map<String, Object> createItem(MenuItemRequest req) {
        AuthHelper.requireOperations();
        String name = requireName(req.name(), 160, "Item name");
        MenuCategory cat = resolveCategory(req.categoryId());
        BigDecimal sellPrice = requirePrice(req.sellPrice(), "Sell price");
        BigDecimal foodCost = req.foodCost() != null ? requireNonNegative(req.foodCost(), "Food cost") : null;
        BigDecimal vat = req.vatRatePct() != null ? requireNonNegative(req.vatRatePct(), "VAT rate") : DEFAULT_VAT;
        String sku = req.sku() != null ? req.sku().trim() : null;
        if (sku != null && !sku.isEmpty()) {
            itemRepository.findFirstBySkuIgnoreCase(sku).ifPresent(existing -> {
                throw new BadRequestException("SKU \"" + existing.getSku() + "\" is already used by " + existing.getName());
            });
        } else {
            sku = null;
        }

        MenuItem item = new MenuItem();
        item.setName(name);
        item.setCategoryId(cat.getId());
        item.setSku(sku);
        item.setDescription(trimToNull(req.description(), 500));
        item.setLongDescription(trimToNull(req.longDescription(), 1000));
        item.setDietaryTags(normaliseTags(req.dietaryTags()));
        item.setAllergens(normaliseTags(req.allergens()));
        if (req.featured() != null) item.setFeatured(req.featured());
        item.setSellPrice(sellPrice);
        item.setFoodCost(foodCost);
        item.setVatRatePct(vat);
        item.setActive(true);
        item = itemRepository.save(item);

        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "MenuItem", item.getId(),
                Map.of(),
                Map.of("name", item.getName(), "sellPrice", item.getSellPrice(),
                        "foodCost", String.valueOf(item.getFoodCost())),
                Map.of("categoryId", item.getCategoryId()));
        return itemToMap(item, cat);
    }

    @Transactional
    public Map<String, Object> updateItem(String id, MenuItemRequest req) {
        AuthHelper.requireOperations();
        MenuItem item = itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Menu item not found"));
        Map<String, Object> before = Map.of(
                "name", item.getName(),
                "categoryId", item.getCategoryId(),
                "sellPrice", item.getSellPrice(),
                "foodCost", String.valueOf(item.getFoodCost()),
                "active", item.isActive(),
                "sku", String.valueOf(item.getSku()));

        if (req.name() != null) item.setName(requireName(req.name(), 160, "Item name"));
        MenuCategory cat = req.categoryId() != null
                ? resolveCategory(req.categoryId())
                : categoryRepository.findById(item.getCategoryId()).orElse(null);
        if (cat != null) item.setCategoryId(cat.getId());
        if (req.sellPrice() != null) item.setSellPrice(requirePrice(req.sellPrice(), "Sell price"));
        if (req.foodCost() != null) {
            item.setFoodCost(requireNonNegative(req.foodCost(), "Food cost"));
        }
        if (req.vatRatePct() != null) item.setVatRatePct(requireNonNegative(req.vatRatePct(), "VAT rate"));
        if (req.description() != null) item.setDescription(trimToNull(req.description(), 500));
        if (req.longDescription() != null) item.setLongDescription(trimToNull(req.longDescription(), 1000));
        if (req.dietaryTags() != null) item.setDietaryTags(normaliseTags(req.dietaryTags()));
        if (req.allergens() != null) item.setAllergens(normaliseTags(req.allergens()));
        if (req.featured() != null) item.setFeatured(req.featured());
        if (req.sku() != null) {
            String sku = req.sku().trim();
            if (sku.isEmpty()) {
                item.setSku(null);
            } else {
                itemRepository.findFirstBySkuIgnoreCase(sku).ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw new BadRequestException(
                                "SKU \"" + existing.getSku() + "\" is already used by " + existing.getName());
                    }
                });
                item.setSku(sku);
            }
        }
        if (req.active() != null) {
            boolean wasActive = item.isActive();
            item.setActive(req.active());
            if (!req.active() && wasActive) item.setArchivedAt(Instant.now());
            if (req.active() && !wasActive) item.setArchivedAt(null);
        }
        item = itemRepository.save(item);

        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "MenuItem", item.getId(),
                before,
                Map.of("name", item.getName(), "sellPrice", item.getSellPrice(),
                        "foodCost", String.valueOf(item.getFoodCost()),
                        "active", item.isActive()),
                null);
        return itemToMap(item, cat);
    }

    @Transactional
    public void deleteItem(String id) {
        AuthHelper.requireOperations();
        MenuItem item = itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Menu item not found"));
        // Keep item rows after archive to preserve POS sale joins; only delete
        // items that have never been used. The frontend should prefer archive.
        itemRepository.delete(item);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "MenuItem", id,
                Map.of("name", item.getName()), Map.of(), null);
    }

    // ---------- CSV import ----------

    /**
     * Bulk import items from a CSV file. Expected columns (any order, case insensitive):
     *   name, category, sku, sell_price, food_cost, vat, description
     *
     * Behaviour:
     *  - Categories are auto-created if missing.
     *  - Existing items matched by SKU (when provided) or by exact name are updated.
     *  - Rows with empty required fields are reported in the response and skipped.
     */
    @Transactional
    public Map<String, Object> importCsv(InputStream csv) {
        AuthHelper.requireOperations();
        List<Map<String, Object>> errors = new ArrayList<>();
        int created = 0, updated = 0;
        Map<String, MenuCategory> catCache = new HashMap<>();
        for (MenuCategory c : categoryRepository.findAll()) catCache.put(c.getName().toLowerCase(), c);

        try (BufferedReader r = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String headerLine = r.readLine();
            if (headerLine == null) {
                throw new BadRequestException("CSV is empty");
            }
            String[] headers = splitCsvLine(headerLine);
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                col.put(headers[i].trim().toLowerCase().replace(" ", "_"), i);
            }
            requireHeader(col, "name");
            requireHeader(col, "sell_price");

            String line;
            int lineNo = 1;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                String[] cells = splitCsvLine(line);
                try {
                    String name = cellAt(cells, col, "name");
                    if (name == null || name.isBlank()) {
                        errors.add(Map.of("line", lineNo, "error", "Missing name"));
                        continue;
                    }
                    String rawCategory = cellAt(cells, col, "category");
                    final String categoryName = (rawCategory == null || rawCategory.isBlank())
                            ? "Uncategorised"
                            : rawCategory.trim();
                    MenuCategory cat = catCache.computeIfAbsent(categoryName.toLowerCase(), k -> {
                        MenuCategory nc = new MenuCategory();
                        nc.setName(categoryName);
                        nc.setSortOrder(nextSortOrder());
                        return categoryRepository.save(nc);
                    });

                    String sku = cellAt(cells, col, "sku");
                    BigDecimal sellPrice = parseDecimal(cellAt(cells, col, "sell_price"));
                    if (sellPrice == null) {
                        errors.add(Map.of("line", lineNo, "error", "Invalid sell_price"));
                        continue;
                    }
                    BigDecimal foodCost = parseDecimal(cellAt(cells, col, "food_cost"));
                    BigDecimal vat = parseDecimal(cellAt(cells, col, "vat"));
                    if (vat == null) vat = DEFAULT_VAT;
                    String description = cellAt(cells, col, "description");

                    Optional<MenuItem> existing = (sku != null && !sku.isBlank())
                            ? itemRepository.findFirstBySkuIgnoreCase(sku.trim())
                            : itemRepository.findFirstByNameIgnoreCase(name.trim());

                    MenuItem item = existing.orElseGet(MenuItem::new);
                    boolean isNew = item.getId() == null;
                    item.setName(name.trim());
                    item.setCategoryId(cat.getId());
                    item.setSku(sku == null || sku.isBlank() ? null : sku.trim());
                    item.setDescription(trimToNull(description, 500));
                    item.setSellPrice(sellPrice.setScale(2, RoundingMode.HALF_UP));
                    if (foodCost != null) item.setFoodCost(foodCost.setScale(2, RoundingMode.HALF_UP));
                    item.setVatRatePct(vat.setScale(2, RoundingMode.HALF_UP));
                    if (isNew) {
                        item.setActive(true);
                    }
                    itemRepository.save(item);
                    if (isNew) created++; else updated++;
                } catch (Exception ex) {
                    errors.add(Map.of("line", lineNo, "error", ex.getMessage() != null
                            ? ex.getMessage() : "Failed to parse row"));
                }
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception ex) {
            throw new BadRequestException("Failed to read CSV: " + ex.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("errors", errors);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "MenuItem", "csv-import",
                Map.of(), Map.of("created", created, "updated", updated, "errorCount", errors.size()), null);
        return result;
    }

    // ---------- Internal lookup helpers used by other services ----------

    @Transactional(readOnly = true)
    public Optional<MenuItem> findBySku(String sku) {
        if (sku == null || sku.isBlank()) return Optional.empty();
        return itemRepository.findFirstBySkuIgnoreCase(sku.trim());
    }

    @Transactional(readOnly = true)
    public Optional<MenuItem> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return itemRepository.findFirstByNameIgnoreCase(name.trim());
    }

    @Transactional(readOnly = true)
    public Optional<MenuItem> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return itemRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Map<String, MenuItem> mapByIds(List<String> ids) {
        Map<String, MenuItem> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) return map;
        for (MenuItem i : itemRepository.findAllById(ids)) map.put(i.getId(), i);
        return map;
    }

    // ---------- Helpers ----------

    private MenuCategory resolveCategory(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            throw new BadRequestException("Category is required");
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Category not found: " + categoryId));
    }

    private int nextSortOrder() {
        return categoryRepository.findAll().stream()
                .mapToInt(MenuCategory::getSortOrder)
                .max()
                .orElse(0) + 10;
    }

    private Map<String, MenuCategory> categoriesById() {
        Map<String, MenuCategory> m = new HashMap<>();
        for (MenuCategory c : categoryRepository.findAll()) m.put(c.getId(), c);
        return m;
    }

    private static String requireName(String name, int max, String label) {
        if (name == null) throw new BadRequestException(label + " is required");
        String t = name.trim();
        if (t.isEmpty()) throw new BadRequestException(label + " is required");
        if (t.length() > max) throw new BadRequestException(label + " too long (max " + max + ")");
        return t;
    }

    private static BigDecimal requirePrice(BigDecimal v, String label) {
        if (v == null) throw new BadRequestException(label + " is required");
        if (v.signum() < 0) throw new BadRequestException(label + " must be 0 or positive");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal requireNonNegative(BigDecimal v, String label) {
        if (v.signum() < 0) throw new BadRequestException(label + " must be 0 or positive");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimToNull(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static void requireHeader(Map<String, Integer> col, String required) {
        if (!col.containsKey(required)) {
            throw new BadRequestException("CSV header is missing required column: " + required);
        }
    }

    private static String cellAt(String[] cells, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null) return null;
        if (idx >= cells.length) return null;
        String v = cells[idx];
        return v == null ? null : v.trim();
    }

    /** Minimal CSV splitter that handles quoted fields with embedded commas. */
    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (ch == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t.replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> categoryToMap(MenuCategory c, long itemCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("sortOrder", c.getSortOrder());
        m.put("active", c.isActive());
        m.put("itemCount", itemCount);
        return m;
    }

    private static Map<String, Object> itemToMap(MenuItem item, MenuCategory cat) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("name", item.getName());
        m.put("sku", item.getSku());
        m.put("description", item.getDescription());
        m.put("longDescription", item.getLongDescription());
        m.put("imagePath", item.getImagePath());
        m.put("imageUrl", item.getImagePath() != null && item.getImagePath().startsWith("menu/")
                ? "/api/files/" + item.getImagePath()
                : null);
        m.put("dietaryTags", item.getDietaryTags());
        m.put("allergens", item.getAllergens());
        m.put("featured", item.isFeatured());
        m.put("categoryId", item.getCategoryId());
        m.put("categoryName", cat != null ? cat.getName() : null);
        m.put("sellPrice", item.getSellPrice());
        m.put("foodCost", item.getFoodCost());
        m.put("vatRatePct", item.getVatRatePct());
        m.put("active", item.isActive());
        // Display-only margin so the UI doesn't need to recompute.
        if (item.getFoodCost() != null && item.getSellPrice() != null
                && item.getSellPrice().signum() > 0) {
            BigDecimal margin = item.getSellPrice().subtract(item.getFoodCost());
            BigDecimal marginPct = margin
                    .divide(item.getSellPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
            BigDecimal foodCostPct = item.getFoodCost()
                    .divide(item.getSellPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
            m.put("marginAmount", margin.setScale(2, RoundingMode.HALF_UP));
            m.put("marginPct", marginPct);
            m.put("foodCostPct", foodCostPct);
        }
        m.put("createdAt", item.getCreatedAt() != null ? item.getCreatedAt().toString() : null);
        m.put("updatedAt", item.getUpdatedAt() != null ? item.getUpdatedAt().toString() : null);
        return m;
    }

    private static String normaliseTags(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        // Normalise to comma-separated, lowercase, no duplicates, no spaces around
        // commas. Tags are short kebab-case slugs so they're easy to render as
        // chips and look consistent on the printed menu.
        List<String> parts = new ArrayList<>();
        for (String part : t.split("[,;]")) {
            String p = part.trim().toLowerCase().replaceAll("\\s+", "-");
            if (!p.isEmpty() && !parts.contains(p)) parts.add(p);
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    // ---------- Photo upload ----------

    /** Set the image path after the upload controller has saved the file. */
    @Transactional
    public Map<String, Object> setItemImage(String id, String relativePath) {
        AuthHelper.requireOperations();
        MenuItem item = itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Menu item not found"));
        item.setImagePath(relativePath);
        item = itemRepository.save(item);
        MenuCategory cat = categoryRepository.findById(item.getCategoryId()).orElse(null);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "MenuItem", item.getId(),
                Map.of(), Map.of("imagePath", relativePath), null);
        return itemToMap(item, cat);
    }

    @Transactional
    public Map<String, Object> clearItemImage(String id) {
        AuthHelper.requireOperations();
        MenuItem item = itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Menu item not found"));
        String before = item.getImagePath();
        item.setImagePath(null);
        item = itemRepository.save(item);
        MenuCategory cat = categoryRepository.findById(item.getCategoryId()).orElse(null);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "MenuItem", item.getId(),
                Map.of("imagePath", String.valueOf(before)), Map.of("imagePath", "null"), null);
        return itemToMap(item, cat);
    }

    /** Used by the menu PDF builder — active items, grouped by category. */
    @Transactional(readOnly = true)
    public List<MenuCategory> activeCategoriesInOrder() {
        return categoryRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<MenuItem> activeItemsForCategory(String categoryId) {
        return itemRepository.findAllByCategoryIdOrderByNameAsc(categoryId).stream()
                .filter(MenuItem::isActive)
                .toList();
    }

    /** Request payload for create / update — used by controller. */
    public record MenuItemRequest(
            String name,
            String sku,
            String description,
            String longDescription,
            String dietaryTags,
            String allergens,
            String categoryId,
            BigDecimal sellPrice,
            BigDecimal foodCost,
            BigDecimal vatRatePct,
            Boolean featured,
            Boolean active) {
        public MenuItemRequest {
            Objects.requireNonNullElse(name, "");
        }
    }
}
