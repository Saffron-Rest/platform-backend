package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.ReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String cashierId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status) {
        return reportService.summary(period, date, cashierId, from, to, status);
    }

    @GetMapping("/export/pdf/entry/{entryId}")
    public ResponseEntity<byte[]> exportEntryPdf(@PathVariable String entryId) {
        byte[] data = reportService.exportEntryPdf(entryId);
        String filename = "saffron-report-" + entryId;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(data);
    }

    @GetMapping("/export/{format}")
    public ResponseEntity<byte[]> export(
            @PathVariable String format,
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String cashierId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status) {
        byte[] data = reportService.export(format, period, date, cashierId, from, to, status);
        String fromLabel = from != null ? from : (date != null ? date : java.time.LocalDate.now().toString());
        String toLabel = to != null ? to : fromLabel;
        String filename = "saffron-cashflow-" + fromLabel + (fromLabel.equals(toLabel) ? "" : "_to_" + toLabel);
        return switch (format.toLowerCase()) {
            case "csv" -> ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(data);
            case "excel" -> ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
            case "pdf" -> ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(data);
            default -> ResponseEntity.badRequest().build();
        };
    }
}
