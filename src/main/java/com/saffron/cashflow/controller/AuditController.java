package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.AuditService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> search(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q) {
        return auditService.search(limit, offset, action, entityType, userId, entityId, from, to, q);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return auditService.getById(id);
    }

    /** Legacy: recent logs without filters. */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "100") int limit) {
        return auditService.listRecent(limit);
    }
}
