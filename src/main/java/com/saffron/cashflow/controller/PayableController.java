package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.FileStorageService;
import com.saffron.cashflow.service.PayableService;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final FileStorageService fileStorageService;

    public PayableController(PayableService payableService, FileStorageService fileStorageService) {
        this.payableService = payableService;
        this.fileStorageService = fileStorageService;
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

    @PutMapping("/{id}/lines")
    public Map<String, Object> updateLines(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return payableService.updateLines(id, body);
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

    /** Attach a scanned invoice PDF or image to an existing invoice. */
    @PostMapping("/{id}/attachment")
    public Map<String, Object> uploadAttachment(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file) throws IOException {
        return payableService.attachFile(id, file, fileStorageService);
    }

    /** Remove the attached scan (the invoice record itself is kept). */
    @DeleteMapping("/{id}/attachment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(@PathVariable String id) throws IOException {
        payableService.removeAttachment(id, fileStorageService);
    }

    /** Serve the attached file for inline viewing or download. */
    @GetMapping("/{id}/attachment")
    public void serveAttachment(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean download,
            HttpServletResponse response) throws IOException {
        Map<String, Object> meta = payableService.resolveAttachment(id);
        String relPath = (String) meta.get("filePath");
        String filename = (String) meta.get("filename");
        Path file = fileStorageService.resolveOperationsFile(relPath);
        if (file == null || !Files.exists(file)) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        String contentType = Files.probeContentType(file);
        if (contentType == null) contentType = "application/octet-stream";
        response.setContentType(contentType);
        String disposition = download ? "attachment" : "inline";
        response.setHeader("Content-Disposition",
                disposition + "; filename=\"" + filename.replace("\"", "'") + "\"");
        Files.copy(file, response.getOutputStream());
        response.flushBuffer();
    }
}
