package com.saffron.cashflow.controller;

import com.saffron.cashflow.domain.PaymentSource;
import com.saffron.cashflow.service.PayoutRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/earnings")
public class PayoutRequestController {

    private final PayoutRequestService service;

    public PayoutRequestController(PayoutRequestService service) {
        this.service = service;
    }

    // ── Cashier self-service ──────────────────────────────────────────────────

    @GetMapping("/me")
    public Map<String, Object> myEarnings(
            @RequestParam String from,
            @RequestParam String to) {
        return service.getMyEarnings(from, to);
    }

    @PostMapping("/me/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRequest(@RequestBody Map<String, Object> body) {
        BigDecimal amount = new BigDecimal(String.valueOf(body.get("amount")));
        String notes = body.get("notes") != null ? String.valueOf(body.get("notes")) : null;
        return service.createRequest(amount, notes);
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    @GetMapping("/requests")
    public List<Map<String, Object>> listRequests(
            @RequestParam(required = false) String status) {
        return service.listRequests(status);
    }

    @PostMapping("/requests/{id}/approve")
    public Map<String, Object> approve(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String sourceStr = body != null && body.get("source") != null
                ? String.valueOf(body.get("source")) : "CASH";
        PaymentSource source;
        try { source = PaymentSource.valueOf(sourceStr.toUpperCase()); }
        catch (IllegalArgumentException e) { source = PaymentSource.CASH; }
        String adminNotes = body != null && body.get("adminNotes") != null
                ? String.valueOf(body.get("adminNotes")) : null;
        return service.approve(id, source, adminNotes);
    }

    @PostMapping("/requests/{id}/decline")
    public Map<String, Object> decline(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String adminNotes = body != null && body.get("adminNotes") != null
                ? String.valueOf(body.get("adminNotes")) : null;
        return service.decline(id, adminNotes);
    }

    @PostMapping("/access/{userId}")
    public Map<String, Object> setAccess(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return service.setEarningsAccess(userId, enabled);
    }
}
