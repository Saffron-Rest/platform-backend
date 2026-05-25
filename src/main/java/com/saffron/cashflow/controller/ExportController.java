package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.ExportService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** GET so admins can deep-link to "this exact export" without juggling
     *  a POST/download workflow. Browsers handle Content-Disposition. */
    @GetMapping
    public ResponseEntity<ByteArrayResource> download(
            @RequestParam String type,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cashierId,
            @RequestParam(required = false) String paymentSource,
            @RequestParam(required = false) String platform) {

        ExportService.ExportFilters filters = new ExportService.ExportFilters(
                type,
                ExportService.Format.parse(format),
                parseDateOrNull(from),
                parseDateOrNull(to),
                cashierId,
                paymentSource,
                platform);
        ExportService.ExportResult result = exportService.render(filters);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(result.contentType()))
                .body(new ByteArrayResource(result.body()));
    }

    private static LocalDate parseDateOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s);
    }
}
