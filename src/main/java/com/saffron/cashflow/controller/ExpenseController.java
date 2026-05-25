package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.ExpenseItemRequest;
import com.saffron.cashflow.dto.StandaloneExpenseRequest;
import com.saffron.cashflow.service.ExpenseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @GetMapping
    public List<Map<String, Object>> listAll(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(value = "tagId", required = false) List<String> tagIds) {
        return expenseService.listAll(from, to, tagIds);
    }

    @GetMapping("/entry/{entryId}")
    public List<Map<String, Object>> list(@PathVariable String entryId) {
        return expenseService.listForEntry(entryId);
    }

    @PostMapping(value = "/standalone", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createStandalone(
            @RequestPart("data") @Valid StandaloneExpenseRequest data,
            @RequestPart(value = "invoice", required = false) MultipartFile invoice) throws Exception {
        return expenseService.createStandalone(data, invoice);
    }

    @PostMapping(value = "/standalone/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createStandaloneJson(@Valid @RequestBody StandaloneExpenseRequest data)
            throws Exception {
        return expenseService.createStandalone(data, null);
    }

    @PutMapping(value = "/standalone/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> updateStandalone(
            @PathVariable String id,
            @RequestPart("data") @Valid StandaloneExpenseRequest data,
            @RequestPart(value = "invoice", required = false) MultipartFile invoice) throws Exception {
        return expenseService.updateStandalone(id, data, invoice);
    }

    @PutMapping(value = "/standalone/{id}/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateStandaloneJson(
            @PathVariable String id,
            @Valid @RequestBody StandaloneExpenseRequest data) throws Exception {
        return expenseService.updateStandalone(id, data, null);
    }

    @DeleteMapping("/standalone/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStandalone(@PathVariable String id) {
        expenseService.deleteStandalone(id);
    }

    /** Bulk-delete: POST so the body can carry the id list cleanly (DELETE
     *  with a request body is ambiguous across HTTP clients). */
    @PostMapping("/standalone/bulk-delete")
    public Map<String, Object> bulkDeleteStandalone(@RequestBody BulkIdsRequest req) {
        return expenseService.bulkDeleteStandalone(req.ids());
    }

    public record BulkIdsRequest(java.util.List<String> ids) {}

    @PostMapping(value = "/entry/{entryId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(
            @PathVariable String entryId,
            @RequestPart("data") @Valid ExpenseItemRequest data,
            @RequestPart(value = "invoice", required = false) MultipartFile invoice) throws Exception {
        return expenseService.create(entryId, data, invoice);
    }

    @PostMapping(value = "/entry/{entryId}/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createJson(
            @PathVariable String entryId,
            @Valid @RequestBody ExpenseItemRequest data) throws Exception {
        return expenseService.create(entryId, data, null);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestPart("data") @Valid ExpenseItemRequest data,
            @RequestPart(value = "invoice", required = false) MultipartFile invoice) throws Exception {
        return expenseService.update(id, data, invoice);
    }

    @PutMapping(value = "/entry/{entryId}/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> sync(
            @PathVariable String entryId,
            @Valid @RequestBody List<ExpenseItemRequest> items) {
        return expenseService.sync(entryId, items);
    }

    @PostMapping(value = "/{id}/invoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadInvoice(
            @PathVariable String id,
            @RequestPart("invoice") MultipartFile invoice) throws Exception {
        return expenseService.uploadInvoice(id, invoice);
    }

    @DeleteMapping("/{expenseId}/invoices/{fileId}")
    public Map<String, Object> deleteInvoice(
            @PathVariable String expenseId,
            @PathVariable String fileId) {
        return expenseService.deleteInvoice(expenseId, fileId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        expenseService.delete(id);
    }
}
