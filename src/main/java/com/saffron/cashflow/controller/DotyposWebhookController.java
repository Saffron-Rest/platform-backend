package com.saffron.cashflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.domain.PosIntegration;
import com.saffron.cashflow.integration.dotykacka.DotykackaSyncService;
import com.saffron.cashflow.service.PosIntegrationService;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives webhooks pushed by Dotypos (payloadEntity = "ORDERBEAN").
 *
 * Dotypos doesn't sign their webhook payloads — instead we embed a
 * per-integration token in the registered URL and check it on every call.
 * That token is the same {@code webhookSecret} we generate when an
 * integration is created, so rotating it via the admin UI invalidates the
 * Dotypos webhook in one step.
 */
@RestController
@RequestMapping("/api/pos/dotypos-webhook")
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
}
