package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.domain.StockMovement;
import com.saffron.cashflow.domain.StockMovementType;
import com.saffron.cashflow.repository.MenuItemRepository;
import com.saffron.cashflow.repository.StockItemRepository;
import com.saffron.cashflow.repository.StockMovementRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Business logic for the stock-management feature.
 *
 * <p>Every mutation goes through one of three entry points so the audit
 * trail is consistent:</p>
 * <ul>
 *   <li>{@link #adjust(String, java.math.BigDecimal, StockMovementType, String, String, String, String)}
 *       — admin "set on hand to X" / record a purchase / record waste, etc.</li>
 *   <li>{@link #recordSale} — system-driven decrement from a POS sale.</li>
 *   <li>{@link #revertMovement} — undo any prior movement (creates a new
 *       reverse movement, never deletes).</li>
 * </ul>
 *
 * <p>Each mutation writes one {@link StockMovement} row and updates the
 * {@link StockItem#getOnHand()} balance plus its {@code lastMovementAt}
 * timestamp. The pair lives inside a single {@code @Transactional} so a
 * crash mid-update never leaves the ledger out of sync with on-hand.</p>
 */
@Service
public class StockService {

    private final StockItemRepository itemRepository;
    private final StockMovementRepository movementRepository;
    private final MenuItemRepository menuItemRepository;
    private final AuditService auditService;

    public StockService(
            StockItemRepository itemRepository,
            StockMovementRepository movementRepository,
            MenuItemRepository menuItemRepository,
            AuditService auditService) {
        this.itemRepository = itemRepository;
        this.movementRepository = movementRepository;
        this.menuItemRepository = menuItemRepository;
        this.auditService = auditService;
    }

    // ========================================================================
    // Read paths
    // ========================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AuthHelper.requireOperations();
        Map<String, String> menuNames = menuItemRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(MenuItem::getId, MenuItem::getName,
                        (a, b) -> a));
        return itemRepository.findAllOrdered().stream()
                .map(s -> toMap(s, menuNames))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requireOperations();
        StockItem item = require(id);
        return toMap(item, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String itemId, int limit) {
        AuthHelper.requireOperations();
        require(itemId);
        List<StockMovement> movements = movementRepository.findByStockItemIdOrderByCreatedAtDesc(itemId);
        if (limit > 0 && movements.size() > limit) movements = movements.subList(0, limit);
        return movements.stream().map(StockService::movementToMap).toList();
    }

    // ========================================================================
    // CRUD
    // ========================================================================

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        StockItem item = new StockItem();
        applyMutable(item, body, /* allowOnHand */ true);
        if (item.getName() == null || item.getName().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        if (item.getOnHand() == null) item.setOnHand(BigDecimal.ZERO);

        BigDecimal openingCount = item.getOnHand();
        item.setOnHand(BigDecimal.ZERO);
        item = itemRepository.save(item);

        // If the admin entered a starting balance, record it as an
        // OPENING_COUNT movement so the history starts with a row instead
        // of an unexplained "magically appeared at 12 units".
        if (openingCount != null && openingCount.compareTo(BigDecimal.ZERO) != 0) {
            applyDelta(item, openingCount, StockMovementType.OPENING_COUNT,
                    "MANUAL", null, "Opening count", user.id());
        }
        auditService.logChange(user.id(), AuditAction.CREATE, "StockItem", item.getId(),
                null, snapshot(item), Map.of("openingCount", openingCount));

        return toMap(item, null);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        StockItem item = require(id);
        Map<String, Object> before = snapshot(item);
        applyMutable(item, body, /* allowOnHand */ false);
        item = itemRepository.save(item);
        auditService.logChange(user.id(), AuditAction.UPDATE, "StockItem", item.getId(),
                before, snapshot(item), null);
        return toMap(item, null);
    }

    @Transactional
    public void archive(String id) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        StockItem item = require(id);
        if (!item.isActive()) return;
        Map<String, Object> before = snapshot(item);
        item.setActive(false);
        itemRepository.save(item);
        auditService.logChange(user.id(), AuditAction.DELETE, "StockItem", item.getId(),
                before, snapshot(item), null);
    }

    /**
     * Permanently remove a stock item and its movement ledger.
     *
     * <p>This is the only path that truly deletes data — the regular
     * {@link #archive} path keeps the row around with {@code active=false}
     * so movement history (and any reports drawn from it) stays intact.
     * Two safety gates protect against accidents:
     * <ol>
     *   <li>Admin role required. Managers/cashiers can archive but cannot
     *       hard-delete.</li>
     *   <li>The item must already be archived. Forcing the two-step
     *       "Archive → Delete" workflow means a mis-click in the row UI
     *       can't nuke an active product.</li>
     * </ol>
     * The {@code stock_movement.stock_item_id} foreign key has
     * {@code ON DELETE CASCADE}, so the database removes the ledger rows
     * atomically alongside the parent. We snapshot the item into the
     * audit log so the trail survives the row itself.</p>
     */
    @Transactional
    public void deletePermanently(String id, String reason) {
        AuthHelper.requireAdminOr(Permission.STOCK_DELETE);
        AuthUser user = AuthHelper.currentUser();
        StockItem item = require(id);
        if (item.isActive()) {
            throw new BadRequestException(
                    "Archive the item first. Permanent delete is only allowed on archived items.");
        }
        Map<String, Object> before = snapshot(item);
        // Capture the movement count for the audit trail so reviewers
        // can see how much ledger data was wiped at the same time.
        long movementCount = movementRepository.countByStockItemId(id);
        Map<String, Object> after = Map.of(
                "deleted", true,
                "movementsRemoved", movementCount);
        itemRepository.delete(item);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("permanent", true);
        if (reason != null && !reason.isBlank()) extra.put("reason", reason.trim());
        auditService.logChange(user.id(), AuditAction.DELETE, "StockItem", id,
                before, after, extra);
    }

    // ========================================================================
    // Movement entry points
    // ========================================================================

    /**
     * Apply a manual movement. Use this for purchases, waste, transfers,
     * or "set on hand to exactly X" (which we model as an {@link StockMovementType#ADJUST}
     * with a computed delta).
     *
     * @param itemId       stock item id
     * @param delta        signed change to apply (or absolute target when
     *                     {@code type == ADJUST} and {@code targetMode == true})
     * @param type         movement type
     * @param reason       human-readable reason, required for non-system types
     * @param referenceType optional source tag (e.g. "PURCHASE_ORDER")
     * @param referenceId   optional source row id
     * @param notes         additional notes — currently unused but reserved
     *                      for future receipt-attachment flow
     */
    @Transactional
    public Map<String, Object> adjust(
            String itemId,
            BigDecimal delta,
            StockMovementType type,
            String reason,
            String referenceType,
            String referenceId,
            String notes) {
        AuthHelper.requireOperations();
        if (delta == null) throw new BadRequestException("Delta required");
        if (type == null) type = StockMovementType.ADJUST;
        if (type == StockMovementType.SALE || type == StockMovementType.REVERT) {
            throw new BadRequestException("Use the dedicated endpoint for " + type);
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Reason is required");
        }
        AuthUser user = AuthHelper.currentUser();
        StockItem item = require(itemId);
        StockMovement m = applyDelta(item, delta, type, referenceType, referenceId, reason.trim(), user.id());
        auditService.logChange(user.id(), AuditAction.STOCK_ADJUST, "StockItem", item.getId(),
                null, null,
                Map.of(
                        "delta", delta,
                        "type", type.name(),
                        "balanceAfter", item.getOnHand(),
                        "reason", reason.trim(),
                        "movementId", m.getId()));
        return Map.of("item", toMap(item, null), "movement", movementToMap(m));
    }

    /**
     * Set the on-hand balance to an exact target value. Implemented as an
     * {@link StockMovementType#ADJUST} with delta computed from the current
     * balance — surfaces the same auditable row format as every other change.
     */
    @Transactional
    public Map<String, Object> setOnHand(String itemId, BigDecimal target, String reason) {
        if (target == null) throw new BadRequestException("Target required");
        if (target.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("On-hand cannot be negative");
        }
        StockItem item = require(itemId);
        BigDecimal current = item.getOnHand() == null ? BigDecimal.ZERO : item.getOnHand();
        BigDecimal delta = target.subtract(current);
        return adjust(itemId, delta, StockMovementType.ADJUST, reason, "MANUAL", null, null);
    }

    /**
     * Record a sale-driven decrement. Called by the POS ingest listener.
     * Idempotent on (referenceType=POS_SALE, referenceId=posSaleId) so a
     * webhook retry never double-decrements.
     *
     * @return {@code true} when the movement was actually applied (new),
     *         {@code false} when it was already recorded by an earlier
     *         delivery.
     */
    @Transactional
    public boolean recordSale(String stockItemId, BigDecimal quantitySold, String posSaleId, String posDescription) {
        if (quantitySold == null || quantitySold.compareTo(BigDecimal.ZERO) <= 0) return false;
        if (posSaleId == null || posSaleId.isBlank()) return false;
        Optional<StockMovement> existing =
                movementRepository.findFirstByReferenceTypeAndReferenceId("POS_SALE", posSaleId);
        if (existing.isPresent()) return false;
        StockItem item = itemRepository.findById(stockItemId).orElse(null);
        if (item == null || !item.isActive()) return false;

        BigDecimal delta = quantitySold.negate();
        String reason = "POS sale" + (posDescription == null || posDescription.isBlank()
                ? "" : " · " + posDescription);
        applyDelta(item, delta, StockMovementType.SALE, "POS_SALE", posSaleId,
                reason, null);
        return true;
    }

    @Transactional
    public Map<String, Object> revertMovement(String movementId, String reason) {
        AuthHelper.requireOperations();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Reason is required");
        }
        AuthUser user = AuthHelper.currentUser();
        StockMovement orig = movementRepository.findById(movementId)
                .orElseThrow(() -> new NotFoundException("Movement not found"));
        if (orig.isReverted()) {
            throw new BadRequestException("This movement was already reverted");
        }
        if (orig.getType() == StockMovementType.REVERT) {
            throw new BadRequestException("Cannot revert a REVERT row");
        }
        StockItem item = require(orig.getStockItemId());

        // The compensating delta is -delta of the original. So if we sold
        // 2 (delta=-2) and want to undo, we apply +2 with type=REVERT.
        BigDecimal compensating = orig.getDelta().negate();
        StockMovement revertRow = applyDelta(item, compensating, StockMovementType.REVERT,
                "REVERT", orig.getId(), reason.trim(), user.id());
        orig.setReverted(true);
        orig.setRevertedById(user.id());
        orig.setRevertedAt(Instant.now());
        movementRepository.save(orig);

        auditService.logChange(user.id(), AuditAction.STOCK_REVERT, "StockItem", item.getId(),
                null, null,
                Map.of(
                        "originalMovementId", orig.getId(),
                        "originalType", orig.getType().name(),
                        "originalDelta", orig.getDelta(),
                        "revertMovementId", revertRow.getId(),
                        "balanceAfter", item.getOnHand(),
                        "reason", reason.trim()));
        return Map.of("item", toMap(item, null), "movement", movementToMap(revertRow));
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /** Persist one movement row and update on-hand + lastMovementAt. */
    private StockMovement applyDelta(StockItem item, BigDecimal delta, StockMovementType type,
                                      String refType, String refId, String reason, String userId) {
        BigDecimal current = item.getOnHand() == null ? BigDecimal.ZERO : item.getOnHand();
        BigDecimal next = current.add(delta).setScale(3, RoundingMode.HALF_UP);
        // Allow negative balances — restaurants sometimes oversell during
        // a count outage. The UI will flag it red so it's not silently lost.
        item.setOnHand(next);
        item.setLastMovementAt(Instant.now());
        itemRepository.save(item);

        StockMovement m = new StockMovement();
        m.setStockItemId(item.getId());
        m.setType(type);
        m.setDelta(delta.setScale(3, RoundingMode.HALF_UP));
        m.setBalanceAfter(next);
        m.setReferenceType(refType);
        m.setReferenceId(refId);
        m.setReason(reason);
        m.setUserId(userId);
        return movementRepository.save(m);
    }

    private StockItem require(String id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Stock item not found"));
    }

    @SuppressWarnings("unchecked")
    private void applyMutable(StockItem item, Map<String, Object> body, boolean allowOnHand) {
        if (body.containsKey("name")) item.setName(asString(body.get("name")));
        if (body.containsKey("sku")) item.setSku(asString(body.get("sku")));
        if (body.containsKey("unit")) {
            String u = asString(body.get("unit"));
            item.setUnit(u == null || u.isBlank() ? "pcs" : u.trim());
        }
        if (body.containsKey("menuItemId")) {
            String mid = asString(body.get("menuItemId"));
            item.setMenuItemId(mid == null || mid.isBlank() ? null : mid);
        }
        if (body.containsKey("category")) item.setCategory(asString(body.get("category")));
        if (body.containsKey("lowStockThreshold")) item.setLowStockThreshold(asBig(body.get("lowStockThreshold")));
        if (body.containsKey("parLevel")) item.setParLevel(asBig(body.get("parLevel")));
        if (body.containsKey("unitCost")) item.setUnitCost(asBig(body.get("unitCost")));
        if (body.containsKey("notes")) item.setNotes(asString(body.get("notes")));
        if (body.containsKey("active")) {
            Object v = body.get("active");
            if (v instanceof Boolean b) item.setActive(b);
        }
        if (allowOnHand && body.containsKey("onHand")) {
            BigDecimal v = asBig(body.get("onHand"));
            item.setOnHand(v == null ? BigDecimal.ZERO : v);
        }
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal asBig(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException ex) { throw new BadRequestException("Invalid number: " + s); }
    }

    // ========================================================================
    // Serialization
    // ========================================================================

    private static Map<String, Object> toMap(StockItem s, Map<String, String> menuNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("sku", s.getSku());
        m.put("unit", s.getUnit());
        m.put("menuItemId", s.getMenuItemId());
        if (menuNames != null && s.getMenuItemId() != null) {
            m.put("menuItemName", menuNames.get(s.getMenuItemId()));
        }
        m.put("category", s.getCategory());
        m.put("onHand", s.getOnHand());
        m.put("lowStockThreshold", s.getLowStockThreshold());
        m.put("parLevel", s.getParLevel());
        m.put("unitCost", s.getUnitCost());
        m.put("notes", s.getNotes());
        m.put("active", s.isActive());
        m.put("lastMovementAt", s.getLastMovementAt());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        // Derived: status badge label so the frontend doesn't need to
        // duplicate the rule.
        m.put("status", statusOf(s));
        m.put("inventoryValue", inventoryValue(s));
        return m;
    }

    private static String statusOf(StockItem s) {
        if (!s.isActive()) return "ARCHIVED";
        BigDecimal onHand = s.getOnHand() == null ? BigDecimal.ZERO : s.getOnHand();
        if (onHand.compareTo(BigDecimal.ZERO) <= 0) return "OUT";
        BigDecimal threshold = s.getLowStockThreshold();
        if (threshold != null && threshold.compareTo(BigDecimal.ZERO) > 0
                && onHand.compareTo(threshold) <= 0) {
            return "LOW";
        }
        return "OK";
    }

    private static BigDecimal inventoryValue(StockItem s) {
        if (s.getUnitCost() == null || s.getOnHand() == null) return null;
        return s.getOnHand().multiply(s.getUnitCost()).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> snapshot(StockItem s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("sku", s.getSku());
        m.put("unit", s.getUnit());
        m.put("menuItemId", s.getMenuItemId());
        m.put("category", s.getCategory());
        m.put("onHand", s.getOnHand());
        m.put("lowStockThreshold", s.getLowStockThreshold());
        m.put("parLevel", s.getParLevel());
        m.put("unitCost", s.getUnitCost());
        m.put("active", s.isActive());
        return m;
    }

    private static Map<String, Object> movementToMap(StockMovement m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("stockItemId", m.getStockItemId());
        map.put("type", m.getType() == null ? null : m.getType().name());
        map.put("delta", m.getDelta());
        map.put("balanceAfter", m.getBalanceAfter());
        map.put("referenceType", m.getReferenceType());
        map.put("referenceId", m.getReferenceId());
        map.put("reason", m.getReason());
        map.put("userId", m.getUserId());
        map.put("reverted", m.isReverted());
        map.put("revertedAt", m.getRevertedAt());
        map.put("createdAt", m.getCreatedAt());
        return map;
    }
}
