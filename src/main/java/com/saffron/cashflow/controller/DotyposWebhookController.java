package com.saffron.cashflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.domain.PosIntegration;
import com.saffron.cashflow.integration.dotykacka.DotykackaSyncService;
import com.saffron.cashflow.service.PosIntegrationService;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public POS push endpoint.
 *
 * Authenticates by a per-integration token embedded in the URL
 * ({@code ?token=…}). This works for any POS that posts webhooks but cannot
 * sign requests (e.g. Dotypos / Dotykačka, whose webhooks deliver an
 * {@code ORDERBEAN} JSON array). The token is the same
 * {@code webhookSecret} we generate when an integration is created, so
 * rotating it invalidates the URL in one step.
 *
 * Two URL paths point at the same handler:
 *   <ul>
 *     <li>{@code /api/pos/push/{id}} — preferred, vendor-agnostic.</li>
 *     <li>{@code /api/pos/dotypos-webhook/{id}} — original path, kept for
 *         compatibility with anything already registered.</li>
 *   </ul>
 */
@RestController
@RequestMapping({"/api/pos/push", "/api/pos/dotypos-webhook"})
public class DotyposWebhookController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PosIntegrationService integrationService;
    private final DotykackaSyncService syncService;

    public DotyposWebhookController(
            PosIntegrationService integrationService,
            DotykackaSyncService syncService) {
        this.integrationService = integrationService;
        this.syncService = syncService;
    }

    @PostMapping("/{id}")
    public Map<String, Object> receive(
            @PathVariable String id,
            @RequestParam("token") String token,
            @RequestBody(required = false) String body) {
        PosIntegration integration = integrationService.authorizeWebhookToken(id, token);
        JsonNode payload;
        try {
            payload = body == null || body.isBlank()
                    ? MAPPER.createArrayNode()
                    : MAPPER.readTree(body);
        } catch (Exception e) {
            throw new BadRequestException("Invalid JSON payload");
        }
        return syncService.ingestWebhook(integration, payload);
    }

    /**
     * Ping handler — returns OK so admins can sanity-check the URL from a
     * browser, and so the Dotypos "GET ping" webhook mode also succeeds. Does
     * not ingest any data. The token check still runs so an unauthenticated
     * GET returns 401, distinct from "endpoint missing" (which would be a 404
     * before security even sees the request).
     */
    @GetMapping("/{id}")
    public Map<String, Object> ping(
            @PathVariable String id,
            @RequestParam("token") String token) {
        PosIntegration integration = integrationService.authorizeWebhookToken(id, token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("integration", integration.getName());
        out.put("hint", "Endpoint is live. POST your webhook payload here.");
        return out;
    }
}
