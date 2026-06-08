package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.AnalyticsService;
import com.saffron.cashflow.service.ProfitLossService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final ProfitLossService profitLossService;

    public AnalyticsController(AnalyticsService analyticsService, ProfitLossService profitLossService) {
        this.analyticsService = analyticsService;
        this.profitLossService = profitLossService;
    }

    @GetMapping("/cashflow")
    public Map<String, Object> cashflow(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cashierId,
            @RequestParam(required = false) String status) {
        return analyticsService.cashflow(from, to, cashierId, status);
    }

    @GetMapping("/forecast")
    public Map<String, Object> forecast(
            @RequestParam(required = false, defaultValue = "7") int days) {
        return analyticsService.forecastDays(days);
    }

    @GetMapping("/profit-loss")
    public Map<String, Object> profitLoss(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String template,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "true") boolean includeLabor) {
        return profitLossService.profitAndLoss(from, to, template, status, includeLabor);
    }
}
