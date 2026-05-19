package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.PayrollSettingsRequest;
import com.saffron.cashflow.service.SettingsService;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Map<String, Object> get() {
        return settingsService.getPlatforms();
    }

    @PutMapping("/platforms")
    public Map<String, Object> updatePlatforms(@RequestBody Map<String, Boolean> platforms) {
        return settingsService.updatePlatforms(platforms);
    }

    @GetMapping("/payroll")
    public Map<String, Object> getPayroll() {
        return settingsService.getPayrollSettings();
    }

    @PutMapping("/payroll")
    public Map<String, Object> updatePayroll(@RequestBody PayrollSettingsRequest body) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (body.weeklyHours() != null) {
            map.put("weeklyHours", body.weeklyHours());
        }
        if (body.storeCloseTime() != null) {
            map.put("storeCloseTime", body.storeCloseTime());
        }
        return settingsService.updatePayrollSettings(map);
    }
}
