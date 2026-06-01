package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.PayableService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST surface for accounts-payable / supplier-credit invoices and
 * their payments. Mounted at {@code /api/payables}.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /api/payables} — list (default: outstanding only)</li>
 *   <li>{@code GET /api/payables/aging} — outstanding aging buckets</li>
 *   <li>{@code GET /api/payables/{id}} — invoice detail with lines + payments</li>
 *   <li>{@code POST /api/payables} — book a new credit invoice</li>
 *   <li>{@code PUT /api/payables/{id}} — edit dates / total / notes</li>
 *   <li>{@code POST /api/payables/{id}/void} — cancel an unpaid invoice</li>
 *   <li>{@code POST /api/payables/{id}/payments} — record a payment</li>
 *   <li>{@code DELETE /api/payables/{id}/payments/{paymentId}} — reverse a payment</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payables")
public class PayableController {

    private final PayableService payableService;

    public PayableController(PayableService payableService) {
        this.payableService = payableService;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String supplierId) {
        return payableService.list(status, supplierId);
    }

    @GetMapping("/aging")
    public Map<String, Object> aging() {
        return payableService.aging();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return payableService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return payableService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return payableService.update(id, body);
    }

    @PostMapping("/{id}/void")
    public Map<String, Object> voidInvoice(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null
                : (body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        return payableService.voidInvoice(id, reason);
    }

    @PostMapping("/{id}/payments")
    public Map<String, Object> recordPayment(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return payableService.recordPayment(id, body);
    }

    @DeleteMapping("/{id}/payments/{paymentId}")
    public Map<String, Object> deletePayment(
            @PathVariable String id,
            @PathVariable String paymentId) {
        return payableService.deletePayment(id, paymentId);
    }
}
