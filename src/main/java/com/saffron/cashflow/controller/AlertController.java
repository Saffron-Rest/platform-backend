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

    @GetMapping("/telegram-status")
    public Map<String, Object> telegramStatus() {
        return alertService.telegramStatus();
    }

    @PostMapping("/telegram-test")
    public Map<String, Object> telegramTest() {
        AuthHelper.requireAdmin();
        return alertService.sendTelegramTest();
    }

    @PostMapping("/check-missing")
    public Map<String, Object> checkMissing() {
        AuthHelper.requireAdmin();
        return alertService.checkMissingSubmissions();
    }

    @PatchMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable String id) {
        return alertService.markRead(id);
    }
}
