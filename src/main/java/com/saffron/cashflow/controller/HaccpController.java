package com.saffron.cashflow.controller;

import com.saffron.cashflow.domain.HaccpKind;
import com.saffron.cashflow.report.HaccpPdfBuilder;
import com.saffron.cashflow.service.FileStorageService;
import com.saffron.cashflow.service.HaccpService;
import com.saffron.cashflow.security.AuthHelper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/haccp")
public class HaccpController {

    private final HaccpService haccpService;
    private final FileStorageService fileStorageService;

    public HaccpController(HaccpService haccpService, FileStorageService fileStorageService) {
        this.haccpService = haccpService;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String kind) {
        LocalDate fromDate = from == null || from.isBlank() ? null : LocalDate.parse(from);
        LocalDate toDate = to == null || to.isBlank() ? null : LocalDate.parse(to);
        HaccpKind k = kind == null || kind.isBlank() ? null : HaccpKind.valueOf(kind.toUpperCase());
        return haccpService.list(fromDate, toDate, k);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return haccpService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return haccpService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return haccpService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        haccpService.delete(id);
        return Map.of("deleted", id);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String path = fileStorageService.storeUnderPrefix(file, "haccp");
        return Map.of("path", path);
    }

    /**
     * PDF export for sanepid inspections. Returns a portrait A4 document
     * with one row per log entry. Date range is required so admins decide
     * what window the inspector asked for.
     */
    @GetMapping(value = "/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> export(
            @RequestParam("from") String from,
            @RequestParam("to") String to) throws IOException {
        AuthHelper.requireOperations();
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        List<Map<String, Object>> rows = haccpService.list(fromDate, toDate, null);
        byte[] pdf = HaccpPdfBuilder.build(rows, fromDate, toDate);
        String fname = "haccp-" + fromDate + "_" + toDate + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
