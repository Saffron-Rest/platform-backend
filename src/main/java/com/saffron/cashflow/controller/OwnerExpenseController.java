package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.OwnerExpenseService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * REST surface for owner-paid expenses awaiting reimbursement. Mounted
 * at {@code /api/owner-expenses}.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /} — list (default: PENDING + PARTIAL)</li>
 *   <li>{@code GET /{id}} — detail with reimbursement history</li>
 *   <li>{@code POST /} — file an expense (current user, by default)</li>
 *   <li>{@code PUT /{id}} — edit fields (description, date, category, total)</li>
 *   <li>{@code POST /{id}/void} — cancel a non-reimbursed expense</li>
 *   <li>{@code POST /{id}/reimbursements} — record a reimbursement</li>
 *   <li>{@code DELETE /{id}/reimbursements/{reimbursementId}} — reverse a reimbursement</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/owner-expenses")
public class OwnerExpenseController {

    private final OwnerExpenseService service;

    public OwnerExpenseController(OwnerExpenseService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ownerId) {
        return service.list(status, ownerId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public Map<String, Object> file(@RequestBody Map<String, Object> body) {
        return service.file(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/void")
    public Map<String, Object> voidExpense(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null
                : (body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        return service.voidExpense(id, reason);
    }

    @PostMapping("/{id}/reimbursements")
    public Map<String, Object> recordReimbursement(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return service.recordReimbursement(id, body);
    }

    @PutMapping("/{id}/reimbursements/{reimbursementId}")
    public Map<String, Object> updateReimbursement(
            @PathVariable String id,
            @PathVariable String reimbursementId,
            @RequestBody Map<String, Object> body) {
        return service.updateReimbursement(id, reimbursementId, body);
    }

    @DeleteMapping("/{id}/reimbursements/{reimbursementId}")
    public Map<String, Object> deleteReimbursement(
            @PathVariable String id,
            @PathVariable String reimbursementId) {
        return service.deleteReimbursement(id, reimbursementId);
    }

    /** Attach a receipt photo / PDF as proof of the expense. */
    @PostMapping("/{id}/receipts")
    public Map<String, Object> uploadReceipt(
            @PathVariable String id,
            @RequestParam("receipt") MultipartFile file) throws IOException {
        return service.uploadReceipt(id, file);
    }

    @DeleteMapping("/{id}/receipts/{fileId}")
    public Map<String, Object> deleteReceipt(
            @PathVariable String id,
            @PathVariable String fileId) {
        return service.deleteReceipt(id, fileId);
    }
}
