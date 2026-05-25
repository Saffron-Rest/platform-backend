package com.saffron.cashflow.controller;

import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.integration.dotykacka.DotykackaSyncService;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.service.PosIntegrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pos/integrations")
public class PosIntegrationController {

    private final PosIntegrationService service;
    private final DotykackaSyncService dotykackaSync;
    private final PosSaleRepository saleRepository;

    public PosIntegrationController(
            PosIntegrationService service,
            DotykackaSyncService dotykackaSync,
            PosSaleRepository saleRepository) {
        this.service = service;
        this.dotykackaSync = dotykackaSync;
        this.saleRepository = saleRepository;
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

    /**
     * Quick health summary for the admin UI — counts how many sales we
     * received recently and lists the last few payloads. Lets admins
     * confirm "yes, Dotypos is pushing" without grepping the logs.
     */
    @GetMapping("/{id}/activity")
    public Map<String, Object> activity(@PathVariable String id) {
        AuthHelper.requireOperations();
        Instant now = Instant.now();
        Instant lastHour = now.minus(1, ChronoUnit.HOURS);
        Instant last24h = now.minus(24, ChronoUnit.HOURS);
        long total = saleRepository.countByIntegrationId(id);
        long inLastHour = saleRepository.countByIntegrationIdAndReceivedAtAfter(id, lastHour);
        long inLast24h = saleRepository.countByIntegrationIdAndReceivedAtAfter(id, last24h);
        List<PosSale> recent = saleRepository.findTop5ByIntegrationIdOrderByReceivedAtDesc(id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalSales", total);
        out.put("lastHour", inLastHour);
        out.put("last24h", inLast24h);
        out.put("recent", recent.stream().map(s -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("externalId", s.getExternalId());
            r.put("itemName", s.getItemName());
            r.put("sku", s.getSku());
            r.put("quantity", s.getQuantity());
            r.put("unitPrice", s.getUnitPrice());
            r.put("matched", s.getMenuItemId() != null);
            r.put("occurredAt", s.getOccurredAt() != null ? s.getOccurredAt().toString() : null);
            r.put("receivedAt", s.getReceivedAt() != null ? s.getReceivedAt().toString() : null);
            return r;
        }).toList());
        return out;
    }

    public record IntegrationRequest(String name, String vendor) {}
    public record DotykackaConfigRequest(
            String cloudId,
            String clientId,
            String clientSecret,
            String refreshToken) {}
    public record RegisterWebhookRequest(String baseUrl) {}
}
