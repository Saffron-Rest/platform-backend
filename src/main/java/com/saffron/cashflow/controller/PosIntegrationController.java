package com.saffron.cashflow.controller;

import com.saffron.cashflow.integration.dotykacka.DotykackaSyncService;
import com.saffron.cashflow.service.PosIntegrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pos/integrations")
public class PosIntegrationController {

    private final PosIntegrationService service;
    private final DotykackaSyncService dotykackaSync;

    public PosIntegrationController(PosIntegrationService service, DotykackaSyncService dotykackaSync) {
        this.service = service;
        this.dotykackaSync = dotykackaSync;
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

    // ---------- Dotykačka ----------

    @PutMapping("/{id}/dotykacka")
    public Map<String, Object> configureDotykacka(@PathVariable String id, @RequestBody DotykackaConfigRequest req) {
        return service.updateDotykackaConfig(
                id, req.cloudId(), req.clientId(), req.clientSecret(), req.refreshToken());
    }

    @PostMapping("/{id}/dotykacka/sync")
    public Map<String, Object> syncDotykacka(@PathVariable String id) {
        return dotykackaSync.syncOne(id);
    }

    @PostMapping("/{id}/dotykacka/webhook/register")
    public Map<String, Object> registerWebhook(
            @PathVariable String id,
            @RequestBody(required = false) RegisterWebhookRequest req) {
        return service.registerDotyposWebhook(id, req == null ? null : req.baseUrl());
    }

    @DeleteMapping("/{id}/dotykacka/webhook")
    public Map<String, Object> unregisterWebhook(@PathVariable String id) {
        return service.unregisterDotyposWebhook(id);
    }

    public record IntegrationRequest(String name, String vendor) {}
    public record DotykackaConfigRequest(
            String cloudId,
            String clientId,
            String clientSecret,
            String refreshToken) {}
    public record RegisterWebhookRequest(String baseUrl) {}
}
