package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.PosIntegration;
import com.saffron.cashflow.integration.dotykacka.DotykackaClient;
import com.saffron.cashflow.repository.PosIntegrationRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-facing CRUD over POS integrations + secret rotation.
 *
 * The webhook receiving path itself lives in {@link PosIngestService} —
 * separated so the receiving controller can stay tiny and signature
 * verification happens close to the request body.
 */
@Service
public class PosIntegrationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private final PosIntegrationRepository repository;
    private final AuditService auditService;
    private final DotykackaClient dotykackaClient;
    private final String publicBaseUrl;

    public PosIntegrationService(
            PosIntegrationRepository repository,
            AuditService auditService,
            DotykackaClient dotykackaClient,
            @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.repository = repository;
        this.auditService = auditService;
        this.dotykackaClient = dotykackaClient;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AuthHelper.requireOperations();
        return repository.findAllByOrderByNameAsc().stream().map(PosIntegrationService::toMap).toList();
    }

    @Transactional
    public Map<String, Object> create(String name, String vendor) {
        AuthHelper.requireAdmin();
        String clean = requireName(name);
        repository.findFirstByNameIgnoreCase(clean).ifPresent(existing -> {
            throw new BadRequestException("Integration \"" + existing.getName() + "\" already exists");
        });
        PosIntegration p = new PosIntegration();
        p.setName(clean);
        p.setVendor(trimToNull(vendor, 40));
        p.setWebhookSecret(generateSecret());
        p.setActive(true);
        p = repository.save(p);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "PosIntegration", p.getId(),
                Map.of(), Map.of("name", p.getName(), "vendor", String.valueOf(p.getVendor())), null);
        return toMapWithSecret(p);
    }

    /**
     * Save Dotykačka-specific credentials on an existing integration. We
     * never log the secret values, only the action.
     */
    @Transactional
    public Map<String, Object> updateDotykackaConfig(
            String id,
            String cloudId,
            String clientId,
            String clientSecret,
            String refreshToken) {
        AuthHelper.requireAdmin();
        PosIntegration p = repository.findById(id).orElseThrow(() -> new NotFoundException("Integration not found"));
        p.setVendor("dotykacka");
        if (cloudId != null) p.setDotykackaCloudId(trimToNull(cloudId, 64));
        if (clientId != null) p.setDotykackaClientId(trimToNull(clientId, 128));
        if (clientSecret != null && !clientSecret.isBlank()) {
            p.setDotykackaClientSecret(clientSecret.trim());
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            p.setDotykackaRefreshToken(refreshToken.trim());
            // Reset cursor so the next sync re-pulls from the default window.
            p.setDotykackaSyncCursor(null);
        }
        p = repository.save(p);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "PosIntegration", p.getId(),
                Map.of(), Map.of("dotykacka", "configured"), null);
        return toMap(p);
    }

    @Transactional
    public Map<String, Object> rotateSecret(String id) {
        AuthHelper.requireAdmin();
        PosIntegration p = repository.findById(id).orElseThrow(() -> new NotFoundException("Integration not found"));
        p.setWebhookSecret(generateSecret());
        p = repository.save(p);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "PosIntegration", p.getId(),
                Map.of(), Map.of("rotatedSecret", true), null);
        return toMapWithSecret(p);
    }

    @Transactional
    public Map<String, Object> setActive(String id, boolean active) {
        AuthHelper.requireAdmin();
        PosIntegration p = repository.findById(id).orElseThrow(() -> new NotFoundException("Integration not found"));
        p.setActive(active);
        p = repository.save(p);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "PosIntegration", p.getId(),
                Map.of(), Map.of("active", active), null);
        return toMap(p);
    }

    @Transactional
    public void delete(String id) {
        AuthHelper.requireAdmin();
        PosIntegration p = repository.findById(id).orElseThrow(() -> new NotFoundException("Integration not found"));
        repository.delete(p);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "PosIntegration", id,
                Map.of("name", p.getName()), Map.of(), null);
    }

    @Transactional
    public void markReceived(PosIntegration integration, String externalId) {
        integration.setLastSeenAt(Instant.now());
        integration.setLastExternalId(externalId);
        repository.save(integration);
    }

    // ---------- Dotypos webhook registration ----------

    /**
     * Register a webhook on the Dotypos side so receipts arrive in real time.
     * Returns the updated integration map.
     *
     * The URL we register includes the integration id + a per-integration
     * token (the same {@code webhookSecret} we generated on create), since
     * Dotypos doesn't sign payloads.
     */
    @Transactional
    public Map<String, Object> registerDotyposWebhook(String id, String overrideBaseUrl) {
        AuthHelper.requireAdmin();
        PosIntegration p = repository.findById(id).orElseThrow(() -> new NotFoundException("Integration not found"));
        if (!"dotykacka".equalsIgnoreCase(p.getVendor())) {
            throw new BadRequestException("Not a Dotykačka integration");
        }
        if (p.getDotykackaCloudId() == null || p.getDotykackaRefreshToken() == null) {
            throw new BadRequestException("Save Dotypos credentials first");
        }
        String base = (overrideBaseUrl != null && !overrideBaseUrl.isBlank())
                ? overrideBaseUrl.replaceAll("/+$", "")
                : publicBaseUrl;
        if (base.isEmpty()) {
            throw new BadRequestException(
                    "Public base URL is not configured. Set app.public-base-url or pass overrideBaseUrl.");
        }
        // Best-effort cleanup of a previously-registered webhook.
        if (p.getDotykackaWebhookId() != null) {
            try {
                String token = dotykackaClient.getAccessToken(p.getDotykackaRefreshToken(), p.getDotykackaCloudId());
                dotykackaClient.deleteWebhook(token, p.getDotykackaCloudId(), p.getDotykackaWebhookId());
            } catch (Exception ignored) {
                // Stale id — Dotypos may have already dropped it. Carry on.
            }
        }
        String webhookUrl = base + "/api/pos/push/" + p.getId()
                + "?token=" + p.getWebhookSecret();
        String accessToken = dotykackaClient.getAccessToken(p.getDotykackaRefreshToken(), p.getDotykackaCloudId());
        long webhookId = dotykackaClient.registerWebhook(
                accessToken, p.getDotykackaCloudId(), webhookUrl, "ORDERBEAN");
        p.setDotykackaWebhookId(webhookId);
        p = repository.save(p);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "PosIntegration", p.getId(),
                Map.of(), Map.of("dotyposWebhookId", webhookId), Map.of("url", webhookUrl));
        return toMap(p);
    }

    @Transactional
    public Map<String, Object> unregisterDotyposWebhook(String id) {
        AuthHelper.requireAdmin();
        PosIntegration p = repository.findById(id).orElseThrow(() -> new NotFoundException("Integration not found"));
        if (p.getDotykackaWebhookId() != null
                && p.getDotykackaRefreshToken() != null
                && p.getDotykackaCloudId() != null) {
            try {
                String token = dotykackaClient.getAccessToken(p.getDotykackaRefreshToken(), p.getDotykackaCloudId());
                dotykackaClient.deleteWebhook(token, p.getDotykackaCloudId(), p.getDotykackaWebhookId());
            } catch (Exception ignored) {
                // Treat as already-gone.
            }
        }
        p.setDotykackaWebhookId(null);
        p = repository.save(p);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "PosIntegration", p.getId(),
                Map.of(), Map.of("dotyposWebhookId", "removed"), null);
        return toMap(p);
    }

    /** Helper for the webhook controller: load + validate that the URL token
     *  matches before any ingest happens. */
    @Transactional(readOnly = true)
    public PosIntegration authorizeWebhookToken(String id, String token) {
        PosIntegration p = repository.findById(id)
                .orElseThrow(() -> new BadRequestException("Unknown integration"));
        if (!p.isActive()) throw new BadRequestException("Integration is inactive");
        if (token == null || !token.equals(p.getWebhookSecret())) {
            throw new BadRequestException("Bad token");
        }
        return p;
    }

    private static String generateSecret() {
        byte[] buf = new byte[SECRET_BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new BadRequestException("Name is required");
        String t = name.trim();
        if (t.length() > 80) throw new BadRequestException("Name too long (max 80)");
        return t;
    }

    private static String trimToNull(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Public response — includes a ready-to-paste push URL with the token
     *  already embedded, since admins need to copy it into Dotypos / any POS
     *  webhook config. The plain HMAC webhook URL is also included for
     *  advanced custom integrations. */
    private static Map<String, Object> toMap(PosIntegration p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("vendor", p.getVendor());
        m.put("active", p.isActive());
        m.put("lastSeenAt", p.getLastSeenAt() != null ? p.getLastSeenAt().toString() : null);
        m.put("lastExternalId", p.getLastExternalId());
        m.put("lastSyncedAt", p.getLastSyncedAt() != null ? p.getLastSyncedAt().toString() : null);
        m.put("webhookUrl", "/api/pos/webhook/" + p.getId());
        m.put("pushUrl", "/api/pos/push/" + p.getId() + "?token=" + p.getWebhookSecret());
        m.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        // Dotykačka — we expose presence flags only, never the values themselves.
        if ("dotykacka".equalsIgnoreCase(p.getVendor())) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cloudId", p.getDotykackaCloudId());
            d.put("hasClientId", p.getDotykackaClientId() != null);
            d.put("hasClientSecret", p.getDotykackaClientSecret() != null);
            d.put("hasRefreshToken", p.getDotykackaRefreshToken() != null);
            d.put("syncCursor", p.getDotykackaSyncCursor() != null
                    ? p.getDotykackaSyncCursor().toString() : null);
            d.put("webhookId", p.getDotykackaWebhookId());
            d.put("webhookRegistered", p.getDotykackaWebhookId() != null);
            m.put("dotykacka", d);
        }
        return m;
    }

    /** Returned only on create / rotate so the admin can copy the secret. */
    private static Map<String, Object> toMapWithSecret(PosIntegration p) {
        Map<String, Object> m = new LinkedHashMap<>(toMap(p));
        m.put("webhookSecret", p.getWebhookSecret());
        return m;
    }
}
