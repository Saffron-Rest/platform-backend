package com.saffron.cashflow.controller;

import com.saffron.cashflow.domain.StockMovementType;
import com.saffron.cashflow.service.StockService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the stock-management feature. Mounted at
 * {@code /api/stock}. All routes require operations-level access (admin
 * or manager) — enforced by {@link com.saffron.cashflow.service.StockService}.
 */
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return stockService.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return stockService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return stockService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return stockService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> archive(@PathVariable String id) {
        stockService.archive(id);
        return Map.of("archived", id);
    }

    /**
     * Permanently remove an archived stock item and its movement ledger.
     * Admin-only; the item must already be archived (see
     * {@link StockService#deletePermanently}). Pass an optional
     * {@code reason} in the body to annotate the audit log.
     */
    @DeleteMapping("/{id}/permanent")
    public Map<String, Object> deletePermanent(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : asString(body.get("reason"));
        stockService.deletePermanently(id, reason);
        return Map.of("deleted", id);
    }

    @GetMapping("/{id}/movements")
    public List<Map<String, Object>> movements(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return stockService.history(id, limit);
    }

    /**
     * Apply a manual movement (purchase / waste / transfer / internal use /
     * generic adjustment). Body fields:
     * <ul>
     *   <li>{@code delta} (required) — signed change, e.g. {@code 5} for a
     *       purchase or {@code -2} for waste.</li>
     *   <li>{@code type} (optional, default {@code ADJUST}) — one of
     *       {@code PURCHASE | WASTE | TRANSFER | INTERNAL_USE | ADJUST}.</li>
     *   <li>{@code reason} (required) — human-readable explanation.</li>
     * </ul>
     */
    @PostMapping("/{id}/adjust")
    public Map<String, Object> adjust(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        BigDecimal delta = asBig(body.get("delta"));
        StockMovementType type = parseType(body.get("type"));
        String reason = asString(body.get("reason"));
        String referenceType = asString(body.get("referenceType"));
        String referenceId = asString(body.get("referenceId"));
        String notes = asString(body.get("notes"));
        return stockService.adjust(id, delta, type, reason, referenceType, referenceId, notes);
    }

    /**
     * Set the on-hand balance to an exact value. Equivalent to {@code adjust}
     * with {@code type=ADJUST} and a computed delta. Useful for "I just did
     * a physical count, balance is now 14".
     *
     * <p>Body: {@code { "onHand": 14, "reason": "Physical count Tue evening" }}</p>
     */
    @PostMapping("/{id}/set-on-hand")
    public Map<String, Object> setOnHand(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        BigDecimal target = asBig(body.get("onHand"));
        String reason = asString(body.get("reason"));
        return stockService.setOnHand(id, target, reason);
    }

    @PostMapping("/movements/{movementId}/revert")
    public Map<String, Object> revertMovement(
            @PathVariable String movementId,
            @RequestBody Map<String, Object> body) {
        return stockService.revertMovement(movementId, asString(body.get("reason")));
    }

    // ------------------------------------------------------------------------

    private static BigDecimal asBig(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        return new BigDecimal(s);
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static StockMovementType parseType(Object v) {
        if (v == null) return StockMovementType.ADJUST;
        try { return StockMovementType.valueOf(v.toString().trim().toUpperCase()); }
        catch (IllegalArgumentException ex) {
            return StockMovementType.ADJUST;
        }
    }
}
