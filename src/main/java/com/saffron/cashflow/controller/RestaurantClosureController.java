package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.RestaurantClosureService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin calendar of restaurant closure days. The list is consulted by
 * the shift-create flow to bypass the "previous shift must be submitted"
 * gate on legitimately-closed days.
 */
@RestController
@RequestMapping("/api/restaurant-closures")
public class RestaurantClosureController {

    private final RestaurantClosureService service;

    public RestaurantClosureController(RestaurantClosureService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody ClosureRequest req) {
        return service.create(req.date(), req.reason());
    }

    @PutMapping("/{date}")
    public Map<String, Object> update(
            @PathVariable String date,
            @RequestBody ClosureRequest req) {
        return service.update(date, req.reason());
    }

    @DeleteMapping("/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String date) {
        service.delete(date);
    }

    public record ClosureRequest(String date, String reason) {}
}
