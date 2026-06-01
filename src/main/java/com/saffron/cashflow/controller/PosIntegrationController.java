package com.saffron.cashflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.domain.PosIntegration;
import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.integration.dotykacka.DotykackaSyncService;
import com.saffron.cashflow.repository.PosIntegrationRepository;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.service.PosIngestService;
import com.saffron.cashflow.service.PosIntegrationService;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pos/integrations")
public class PosIntegrationController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PosIntegrationService service;
    private final DotykackaSyncService dotykackaSync;
    private final PosSaleRepository saleRepository;
    private final PosIntegrationRepository integrationRepository;
    private final PosIngestService ingestService;

    public PosIntegrationController(
            PosIntegrationService service,
            DotykackaSyncService dotykackaSync,
            PosSaleRepository saleRepository,
            PosIntegrationRepository integrationRepository,
            PosIngestService ingestService) {
        this.service = service;
        this.dotykackaSync = dotykackaSync;
        this.saleRepository = saleRepository;
        this.integrationRepository = integrationRepository;
        this.ingestService = ingestService;
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
            @RequestBody(required = false) RegisterWebhookRequest req,
            jakarta.servlet.http.HttpServletRequest httpReq) {
        String override = req == null ? null : req.baseUrl();
        // Fall back to the request's own origin if the admin didn't pass an
        // override and the app.public-base-url config is empty. Honours the
        // X-Forwarded-* headers Kong sets, so we end up with the real public
        // origin (e.g. https://cash-flow.saffron.waw.pl) instead of the
        // internal :3001 the backend container listens on.
        if (override == null || override.isBlank()) {
            override = deriveOrigin(httpReq);
        }
        return service.registerDotyposWebhook(id, override);
    }

    private static String deriveOrigin(jakarta.servlet.http.HttpServletRequest req) {
        if (req == null) return null;
        String proto = req.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) proto = req.getScheme();
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = req.getHeader("Host");
        if (host == null || host.isBlank()) return null;
        // X-Forwarded-Host may include the port already; only append default
        // proxy port if neither host nor proto already encode it.
        return proto + "://" + host;
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

    /**
     * Synthesize a Dotypos-style ORDERBEAN receipt and run it through the
     * webhook ingest path. Lets admins verify the integration end-to-end
     * without having to ring up a real sale on the POS.
     */
    @PostMapping("/{id}/test-receipt")
    public Map<String, Object> testReceipt(@PathVariable String id) {
        AuthHelper.requireOperations();
        PosIntegration integration = integrationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Integration not found"));
        String externalId = "saffron-test-" + UUID.randomUUID();
        // Minimal payload — ingestOrder pulls items from the canonical array.
        String json = "[{"
                + "\"orderid\":\"" + externalId + "\","
                + "\"branchid\":0,"
                + "\"completed\":\"" + Instant.now().toString() + "\","
                + "\"versiondate\":" + Instant.now().toEpochMilli() + ","
                + "\"items\":[{"
                + "\"productid\":null,"
                + "\"name\":\"Saffron self-test\","
                + "\"quantity\":\"1\","
                + "\"pricewithvat\":\"1.00\""
                + "}]"
                + "}]";
        try {
            return dotykackaSync.ingestWebhook(integration, MAPPER.readTree(json));
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", e.getMessage());
            return err;
        }
    }

    /**
     * Admin-only POS sale simulator. Builds a fake receipt from the request
     * body and runs it through the same ingest path the live webhook uses,
     * including the stock-decrement post-handler. Set {@code dryRun} to true
     * to roll back every mutation just before returning — handy for verifying
     * that menu→stock mappings produce the expected on-hand delta without
     * actually moving inventory.
     *
     * <p>Returns the raw ingest summary (inserted / skipped / unmatched) plus
     * a {@code stockImpact} array showing before/after on-hand for every
     * stock item the simulated lines would have touched.</p>
     */
    @PostMapping("/{id}/simulate-sale")
    public Map<String, Object> simulateSale(
            @PathVariable String id,
            @RequestBody SimulateSaleRequest req) {
        List<PosIngestService.SimulationLine> lines = new ArrayList<>();
        if (req.items() != null) {
            for (SimulateSaleLine raw : req.items()) {
                lines.add(new PosIngestService.SimulationLine(
                        raw.menuItemId(),
                        raw.sku(),
                        raw.name(),
                        raw.quantity(),
                        raw.unitPrice()));
            }
        }
        Instant occurredAt = null;
        if (req.occurredAt() != null && !req.occurredAt().isBlank()) {
            try { occurredAt = Instant.parse(req.occurredAt()); }
            catch (Exception ignored) { /* keep null — service defaults to now */ }
        }
        PosIngestService.SimulationRequest simReq = new PosIngestService.SimulationRequest(
                lines,
                req.paymentMethod(),
                occurredAt,
                req.dryRun() != null && req.dryRun());
        return ingestService.simulate(id, simReq);
    }

    public record IntegrationRequest(String name, String vendor) {}
    public record DotykackaConfigRequest(
            String cloudId,
            String clientId,
            String clientSecret,
            String refreshToken) {}
    public record RegisterWebhookRequest(String baseUrl) {}

    public record SimulateSaleRequest(
            List<SimulateSaleLine> items,
            String paymentMethod,
            String occurredAt,
            Boolean dryRun) {}

    public record SimulateSaleLine(
            String menuItemId,
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice) {}
}
