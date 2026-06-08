package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.AccountingHubService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/accounting")
public class AccountingHubController {

    private final AccountingHubService hubService;

    public AccountingHubController(AccountingHubService hubService) {
        this.hubService = hubService;
    }

    @GetMapping("/hub")
    public Map<String, Object> hub() {
        return hubService.hub();
    }
}
