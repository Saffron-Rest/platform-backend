package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.DeleteEntryRequest;
import com.saffron.cashflow.dto.EntryRequest;
import com.saffron.cashflow.service.EntryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/entries")
public class EntryController {

    private final EntryService entryService;

    public EntryController(EntryService entryService) {
        this.entryService = entryService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cashierId,
            @RequestParam(required = false) String status) {
        return entryService.list(from, to, cashierId, status);
    }

    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today(
            @RequestParam(required = false) String cashierId,
            @RequestParam(required = false) String date) {
        Map<String, Object> entry = entryService.getToday(cashierId, date);
        if (entry == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(entry);
    }

    @GetMapping("/suggested-opening")
    public Map<String, Object> suggestedOpening(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String cashierId) {
        return entryService.getSuggestedOpening(date, cashierId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return entryService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody EntryRequest request) {
        return entryService.create(request);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody EntryRequest request) {
        return entryService.update(id, request);
    }

    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable String id) {
        return entryService.submit(id);
    }

    @PostMapping("/{id}/unlock")
    public Map<String, Object> unlock(@PathVariable String id) {
        return entryService.unlock(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, @Valid @RequestBody DeleteEntryRequest body) {
        entryService.delete(id, body.reason());
        return Map.of("ok", true);
    }
}
