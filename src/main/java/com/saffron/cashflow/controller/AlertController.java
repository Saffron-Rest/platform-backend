package com.saffron.cashflow.controller;

import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.service.AlertService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return alertService.list();
    }

    @PostMapping("/check-missing")
    public Map<String, Boolean> checkMissing() {
        AuthHelper.requireAdmin();
        alertService.checkMissingSubmissions();
        return Map.of("ok", true);
    }

    @PatchMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable String id) {
        return alertService.markRead(id);
    }
}
