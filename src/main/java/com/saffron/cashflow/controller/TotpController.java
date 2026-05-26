package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.TotpService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/security/totp")
public class TotpController {

    private final TotpService totpService;

    public TotpController(TotpService totpService) {
        this.totpService = totpService;
    }

    @GetMapping
    public Map<String, Object> status() {
        return totpService.status();
    }

    @PostMapping("/enroll")
    public Map<String, Object> beginEnrollment() {
        return totpService.beginEnrollment();
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirmEnrollment(@RequestBody Map<String, String> body) {
        return totpService.confirmEnrollment(body.getOrDefault("code", ""));
    }

    @PostMapping("/disable")
    public Map<String, Object> selfDisable(@RequestBody Map<String, String> body) {
        return totpService.selfDisable(body.getOrDefault("code", ""));
    }

    @GetMapping("/{userId}")
    public Map<String, Object> statusFor(@PathVariable String userId) {
        return totpService.statusFor(userId);
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> adminDisable(@PathVariable String userId) {
        return totpService.adminDisable(userId);
    }
}
