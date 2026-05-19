package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.RegisterPushTokenRequest;
import com.saffron.cashflow.service.CashierNotificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final CashierNotificationService cashierNotificationService;

    public NotificationController(CashierNotificationService cashierNotificationService) {
        this.cashierNotificationService = cashierNotificationService;
    }

    @PostMapping("/register-token")
    public Map<String, Object> registerToken(@Valid @RequestBody RegisterPushTokenRequest request) {
        return cashierNotificationService.registerPushToken(request);
    }

    @GetMapping("/inbox")
    public List<Map<String, Object>> inbox() {
        return cashierNotificationService.listInbox();
    }
}
