package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.PosIntegration;
import com.saffron.cashflow.repository.PosIntegrationRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
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

    public PosIntegrationService(PosIntegrationRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
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

    /** Public response — does NOT include the secret. */
    private static Map<String, Object> toMap(PosIntegration p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("vendor", p.getVendor());
        m.put("active", p.isActive());
        m.put("lastSeenAt", p.getLastSeenAt() != null ? p.getLastSeenAt().toString() : null);
        m.put("lastExternalId", p.getLastExternalId());
        m.put("webhookUrl", "/api/pos/webhook/" + p.getId());
        m.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        return m;
    }

    /** Returned only on create / rotate so the admin can copy the secret. */
    private static Map<String, Object> toMapWithSecret(PosIntegration p) {
        Map<String, Object> m = new LinkedHashMap<>(toMap(p));
        m.put("webhookSecret", p.getWebhookSecret());
        return m;
    }
}
