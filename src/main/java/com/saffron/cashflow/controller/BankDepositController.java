package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.BankDepositRequest;
import com.saffron.cashflow.service.BankDepositService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bank-deposits")
public class BankDepositController {

    private final BankDepositService service;

    public BankDepositController(BankDepositService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam String from,
            @RequestParam String to) {
        return service.list(from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody BankDepositRequest req) {
        return service.create(req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
