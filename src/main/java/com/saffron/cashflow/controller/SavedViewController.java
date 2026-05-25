package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.SavedViewService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/saved-views")
public class SavedViewController {

    private final SavedViewService service;

    public SavedViewController(SavedViewService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam("page") String page) {
        return service.list(page);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateSavedViewRequest req) {
        return service.create(req.page(), req.name(), req.filters(), Boolean.TRUE.equals(req.isDefault()));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateSavedViewRequest req) {
        return service.update(id, req.name(), req.filters(), req.isDefault());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    public record CreateSavedViewRequest(
            @NotBlank String page,
            @NotBlank String name,
            @NotBlank String filters,
            Boolean isDefault) {}

    public record UpdateSavedViewRequest(String name, String filters, Boolean isDefault) {}
}
