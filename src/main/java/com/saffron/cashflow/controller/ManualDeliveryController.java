package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.ManualDeliveryIncomeRequest;
import com.saffron.cashflow.service.ManualDeliveryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/delivery-income")
public class ManualDeliveryController {

    private final ManualDeliveryService manualDeliveryService;

    public ManualDeliveryController(ManualDeliveryService manualDeliveryService) {
        this.manualDeliveryService = manualDeliveryService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(value = "tagId", required = false) List<String> tagIds) {
        return manualDeliveryService.list(from, to, tagIds);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ManualDeliveryIncomeRequest req) {
        return manualDeliveryService.create(req);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @Valid @RequestBody ManualDeliveryIncomeRequest req) {
        return manualDeliveryService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        manualDeliveryService.delete(id);
    }
}
