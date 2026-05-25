package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.PosIntegrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pos/integrations")
public class PosIntegrationController {

    private final PosIntegrationService service;

    public PosIntegrationController(PosIntegrationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody IntegrationRequest req) {
        return service.create(req.name(), req.vendor());
    }

    @PostMapping("/{id}/rotate-secret")
    public Map<String, Object> rotateSecret(@PathVariable String id) {
        return service.rotateSecret(id);
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable String id) {
        return service.setActive(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable String id) {
        return service.setActive(id, false);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    public record IntegrationRequest(String name, String vendor) {}
}
