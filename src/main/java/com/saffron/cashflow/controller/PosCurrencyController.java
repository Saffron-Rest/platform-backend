package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.PosExchangeRateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/pos/currency")
public class PosCurrencyController {

    private final PosExchangeRateService rateService;

    public PosCurrencyController(PosExchangeRateService rateService) {
        this.rateService = rateService;
    }

    /** Returns current NBP exchange rates (PLN mid rates). Cached for 1 hour. */
    @GetMapping("/rates")
    public Map<String, Object> rates() {
        return rateService.getRates();
    }
}
