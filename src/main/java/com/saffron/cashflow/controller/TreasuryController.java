package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.RecordSalaryPaymentRequest;
import com.saffron.cashflow.dto.TreasurySettingsRequest;
import com.saffron.cashflow.service.TreasuryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/treasury")
public class TreasuryController {

    private final TreasuryService treasuryService;

    public TreasuryController(TreasuryService treasuryService) {
        this.treasuryService = treasuryService;
    }

    @GetMapping
    public Map<String, Object> overview() {
        return treasuryService.overview();
    }

    @GetMapping("/settlement-defaults")
    public Map<String, Object> settlementDefaults() {
        return treasuryService.settlementDefaults();
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@Valid @RequestBody TreasurySettingsRequest request) {
        return treasuryService.updateSettings(request);
    }

    @GetMapping("/salary-payments")
    public List<Map<String, Object>> salaryPayments(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String matchPeriod) {
        return treasuryService.listSalaryPayments(from, to, userId, source, matchPeriod);
    }

    @PostMapping("/salary-payments")
    public Map<String, Object> recordSalaryPayment(@Valid @RequestBody RecordSalaryPaymentRequest request) {
        return treasuryService.recordSalaryPayment(request);
    }
}
