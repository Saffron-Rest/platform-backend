package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.CardSettlementRequest;
import com.saffron.cashflow.service.CardSettlementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/card-settlements")
public class CardSettlementController {

    private final CardSettlementService service;

    public CardSettlementController(CardSettlementService service) {
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
    public Map<String, Object> create(@Valid @RequestBody CardSettlementRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @Valid @RequestBody CardSettlementRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
