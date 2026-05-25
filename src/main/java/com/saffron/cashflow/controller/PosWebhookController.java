package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.PosIngestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public webhook endpoint for POS vendors. The body is consumed as a raw
 * string so we can verify the HMAC against the exact bytes the vendor signed.
 *
 * Authentication is via the {@code X-Pos-Signature} header (HMAC-SHA256 of the
 * raw body using the integration's shared secret) — handled inside
 * {@link PosIngestService#ingest}.
 */
@RestController
@RequestMapping("/api/pos/webhook")
public class PosWebhookController {

    private final PosIngestService ingestService;

    public PosWebhookController(PosIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/{integrationId}")
    public Map<String, Object> receive(
            @PathVariable String integrationId,
            @RequestHeader(value = "X-Pos-Signature", required = false) String signature,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        // If the caller sent the body as a parsed JSON, Spring still provides
        // the raw text via @RequestBody String. Fall back to empty so the
        // signature check fails loudly instead of silently treating empty as
        // valid.
        return ingestService.ingest(integrationId, signature, body == null ? "" : body);
    }
}
