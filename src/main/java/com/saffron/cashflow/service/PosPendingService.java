package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.domain.SystemSetting;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages the "POS pending items" approval queue.
 *
 * <p>When a POS sale arrives for an item that isn't catalogued yet (no
 * matching MenuItem by SKU or name), the sale is stored as-is with a null
 * menuItemId. This service aggregates those unmatched sales into a pending
 * list so an admin can choose to approve (create a MenuItem and/or StockItem)
 * or dismiss each one.</p>
 *
 * <p>Dismissed items are stored in a {@link SystemSetting} JSON blob under
 * the key {@value #DISMISSED_KEY} so no extra migration is needed.</p>
 */
@Service
public class PosPendingService {

    static final String DISMISSED_KEY = "pos.dismissed.items";

    private final PosSaleRepository saleRepository;
    private final SystemSettingRepository settingRepository;
    private final MenuService menuService;
    private final StockService stockService;

    public PosPendingService(
            PosSaleRepository saleRepository,
            SystemSettingRepository settingRepository,
            MenuService menuService,
            StockService stockService) {
        this.saleRepository = saleRepository;
        this.settingRepository = settingRepository;
        this.menuService = menuService;
        this.stockService = stockService;
    }

    // -------------------------------------------------------------------------
    // List pending items
    // -------------------------------------------------------------------------

    /**
     * Returns distinct unmatched POS items grouped by name, excluding any
     * that have been dismissed by an admin.
     *
     * <p>Each row in the result carries the aggregated sales count, the most
     * recent unit price, and the last time the item was seen — enough context
     * for the admin to decide whether to approve it.</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPending() {
        AuthHelper.requireOperations();
        Set<String> dismissed = loadDismissed();

        // Pull all unmatched sales (menuItemId IS NULL).
        // The PosSaleRepository already has findAll — we filter in-process
        // since volume is expected to be small and we avoid a new query method.
        List<PosSale> unmatched = saleRepository.findAll().stream()
                .filter(s -> s.getMenuItemId() == null && s.getItemName() != null)
                .collect(Collectors.toList());

        // Group by name (case-insensitive) to merge e.g. "Lamb Plov" and "lamb plov"
        Map<String, List<PosSale>> byName = new LinkedHashMap<>();
        for (PosSale s : unmatched) {
            String key = s.getItemName().trim().toLowerCase();
            byName.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<PosSale>> entry : byName.entrySet()) {
            List<PosSale> group = entry.getValue();
            PosSale representative = group.stream()
                    .max((a, b) -> {
                        Instant ta = a.getOccurredAt() != null ? a.getOccurredAt() : Instant.EPOCH;
                        Instant tb = b.getOccurredAt() != null ? b.getOccurredAt() : Instant.EPOCH;
                        return ta.compareTo(tb);
                    }).orElse(group.get(0));

            String canonicalName = representative.getItemName().trim();
            String dismissKey = dismissKey(canonicalName, representative.getSku());
            if (dismissed.contains(dismissKey)) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", canonicalName);
            row.put("sku", representative.getSku());
            row.put("salesCount", group.size());
            row.put("lastPrice", representative.getUnitPrice());
            row.put("lastSeen", representative.getOccurredAt() != null
                    ? representative.getOccurredAt().toString() : null);
            result.add(row);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Approve
    // -------------------------------------------------------------------------

    /**
     * Create a MenuItem and/or StockItem for the given pending item.
     *
     * <p>At least one of {@code addToMenu} or {@code addToStock} must be true.
     * If both are requested a StockItem is created linked to the new MenuItem
     * so future POS sales auto-decrement inventory.</p>
     */
    @Transactional
    public Map<String, Object> approve(String name, String sku, BigDecimal unitPrice,
                                       boolean addToMenu, boolean addToStock) {
        AuthHelper.requireOperations();
        if (name == null || name.isBlank()) throw new BadRequestException("name is required");
        if (!addToMenu && !addToStock) throw new BadRequestException("Select at least one of: addToMenu, addToStock");

        MenuItem menuItem = null;
        StockItem stockItem = null;

        if (addToMenu) {
            menuItem = menuService.autoCreateFromPos(name, sku, unitPrice);
        }

        if (addToStock) {
            String menuItemId = menuItem != null ? menuItem.getId() : null;
            String itemName = menuItem != null ? menuItem.getName() : name.trim();
            String itemSku = menuItem != null ? menuItem.getSku() : (sku != null && !sku.isBlank() ? sku.trim() : null);
            stockItem = stockService.autoCreateFromPos(menuItemId, itemName, itemSku);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        if (menuItem != null) result.put("menuItemId", menuItem.getId());
        if (stockItem != null) result.put("stockItemId", stockItem.getId());
        return result;
    }

    // -------------------------------------------------------------------------
    // Dismiss
    // -------------------------------------------------------------------------

    /** Mark an item as dismissed — it will no longer appear in the pending list. */
    @Transactional
    public void dismiss(String name, String sku) {
        AuthHelper.requireOperations();
        if (name == null || name.isBlank()) throw new BadRequestException("name is required");
        Set<String> dismissed = loadDismissed();
        dismissed.add(dismissKey(name.trim(), sku));
        saveDismissed(dismissed);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String dismissKey(String name, String sku) {
        return (name != null ? name.trim().toLowerCase() : "") + "|" + (sku != null ? sku.trim().toLowerCase() : "");
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadDismissed() {
        return settingRepository.findById(DISMISSED_KEY)
                .map(s -> {
                    Object raw = s.getValue().get("items");
                    if (raw instanceof List<?> list) {
                        return list.stream().map(Object::toString).collect(Collectors.toCollection(HashSet::new));
                    }
                    return new HashSet<String>();
                })
                .orElseGet(HashSet::new);
    }

    private void saveDismissed(Set<String> dismissed) {
        SystemSetting setting = settingRepository.findById(DISMISSED_KEY)
                .orElseGet(() -> {
                    SystemSetting s = new SystemSetting();
                    s.setKey(DISMISSED_KEY);
                    return s;
                });
        Map<String, Object> value = new HashMap<>();
        value.put("items", new ArrayList<>(dismissed));
        setting.setValue(value);
        settingRepository.save(setting);
    }
}
