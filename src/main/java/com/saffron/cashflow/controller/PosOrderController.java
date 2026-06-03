package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.PosOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the Saffron POS tablet app.
 *
 * All routes require authentication. Table and order management is open to
 * any authenticated user (cashiers take orders); table configuration (POST /tables)
 * is restricted inside the service layer to operations-role users.
 */
@RestController
@RequestMapping("/api/pos")
public class PosOrderController {

    private final PosOrderService posOrderService;

    public PosOrderController(PosOrderService posOrderService) {
        this.posOrderService = posOrderService;
    }

    // ── Menu ─────────────────────────────────────────────────────────────────

    /** Returns all POS-available menu items, sorted by category then posDisplayOrder. */
    @GetMapping("/menu")
    public List<Map<String, Object>> menu() {
        return posOrderService.posMenu();
    }

    // ── Tables ────────────────────────────────────────────────────────────────

    @GetMapping("/tables")
    public List<Map<String, Object>> tables() {
        return posOrderService.listTables();
    }

    @PostMapping("/tables")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> saveTable(@RequestBody Map<String, Object> req) {
        return posOrderService.saveTable(req);
    }

    @PutMapping("/tables/{id}")
    public Map<String, Object> updateTable(@PathVariable String id, @RequestBody Map<String, Object> req) {
        req.put("id", id);
        return posOrderService.saveTable(req);
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    @GetMapping("/orders")
    public List<Map<String, Object>> openOrders() {
        return posOrderService.listOpenOrders();
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable String id) {
        return posOrderService.getOrder(id);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> req) {
        return posOrderService.createOrder(req);
    }

    @PostMapping("/orders/{id}/lines")
    public Map<String, Object> addLine(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return posOrderService.addLine(id, req);
    }

    @DeleteMapping("/orders/{orderId}/lines/{lineId}")
    public Map<String, Object> removeLine(@PathVariable String orderId, @PathVariable String lineId) {
        return posOrderService.removeLine(orderId, lineId);
    }

    /** Record payment. Pass paymentMethod, optionally amountTendered, buyerNip, fiscalReceiptNumber. */
    @PostMapping("/orders/{id}/pay")
    public Map<String, Object> payOrder(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return posOrderService.payOrder(id, req);
    }

    @PostMapping("/orders/{id}/void")
    public Map<String, Object> voidOrder(@PathVariable String id) {
        return posOrderService.voidOrder(id);
    }
}
