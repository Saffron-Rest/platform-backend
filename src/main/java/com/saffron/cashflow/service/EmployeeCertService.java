package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.EmployeeCert;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.EmployeeCertRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CRUD + read views for {@link EmployeeCert}.
 *
 * <p>Admins land here to add a cert, set an expiry, and walk away — the
 * expiry-reminder job pings them at 30/14/1 days. The list view colour-codes
 * urgency so a manager can see "what expires this month" at a glance.</p>
 */
@Service
public class EmployeeCertService {

    /** Suggested cert type values shown as a datalist on the form. */
    public static final List<String> SUGGESTED_TYPES = List.of(
            "Książeczka sanepidowska",
            "BHP",
            "Alcohol licence",
            "First aid",
            "Food handler",
            "Fire safety",
            "HACCP training"
    );

    private final EmployeeCertRepository repository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public EmployeeCertService(
            EmployeeCertRepository repository,
            UserRepository userRepository,
            AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String userId) {
        AuthHelper.requireOperations();
        List<EmployeeCert> rows = repository.findOrdered(userId);
        Map<String, String> userNames = userRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, User::getName, (a, b) -> a));
        return rows.stream().map(c -> toMap(c, userNames)).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requireOperations();
        EmployeeCert c = require(id);
        return toMap(c, userNames(List.of(c)));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        EmployeeCert c = new EmployeeCert();
        applyMutable(c, body);
        if (c.getUserId() == null || c.getUserId().isBlank()) {
            throw new BadRequestException("userId is required");
        }
        if (c.getType() == null || c.getType().isBlank()) {
            throw new BadRequestException("Type is required");
        }
        userRepository.findById(c.getUserId())
                .orElseThrow(() -> new BadRequestException("User not found"));
        c = repository.save(c);
        auditService.logChange(user.id(), AuditAction.CREATE, "EmployeeCert", c.getId(),
                null, snapshot(c), null);
        return toMap(c, userNames(List.of(c)));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        EmployeeCert c = require(id);
        Map<String, Object> before = snapshot(c);
        applyMutable(c, body);
        c = repository.save(c);
        auditService.logChange(user.id(), AuditAction.UPDATE, "EmployeeCert", c.getId(),
                before, snapshot(c), null);
        return toMap(c, userNames(List.of(c)));
    }

    @Transactional
    public void delete(String id) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        EmployeeCert c = require(id);
        Map<String, Object> before = snapshot(c);
        repository.delete(c);
        auditService.logChange(user.id(), AuditAction.DELETE, "EmployeeCert", id,
                before, null, null);
    }

    // ------------------------------------------------------------------------

    private EmployeeCert require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Certificate not found"));
    }

    private void applyMutable(EmployeeCert c, Map<String, Object> body) {
        if (body.containsKey("userId")) c.setUserId(asString(body.get("userId")));
        if (body.containsKey("type")) c.setType(asString(body.get("type")));
        if (body.containsKey("number")) c.setNumber(asString(body.get("number")));
        if (body.containsKey("issuer")) c.setIssuer(asString(body.get("issuer")));
        if (body.containsKey("issuedOn")) {
            String s = asString(body.get("issuedOn"));
            c.setIssuedOn(s == null ? null : LocalDate.parse(s));
        }
        if (body.containsKey("expiresOn")) {
            String s = asString(body.get("expiresOn"));
            LocalDate prev = c.getExpiresOn();
            LocalDate next = s == null ? null : LocalDate.parse(s);
            c.setExpiresOn(next);
            // Reset the warning watermark when the expiry moves forward so
            // the new 30-day window fires correctly.
            if (!Objects.equals(prev, next)) {
                c.setLastWarningAt(null);
            }
        }
        if (body.containsKey("notes")) c.setNotes(asString(body.get("notes")));
        if (body.containsKey("filePath")) c.setFilePath(asString(body.get("filePath")));
    }

    private Map<String, String> userNames(List<EmployeeCert> certs) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (EmployeeCert c : certs) if (c.getUserId() != null) ids.add(c.getUserId());
        Map<String, String> out = new java.util.HashMap<>();
        for (User u : userRepository.findAllById(ids)) out.put(u.getId(), u.getName());
        return out;
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, Object> toMap(EmployeeCert c, Map<String, String> userNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("userId", c.getUserId());
        m.put("userName", userNames.get(c.getUserId()));
        m.put("type", c.getType());
        m.put("number", c.getNumber());
        m.put("issuer", c.getIssuer());
        m.put("issuedOn", c.getIssuedOn() != null ? c.getIssuedOn().toString() : null);
        m.put("expiresOn", c.getExpiresOn() != null ? c.getExpiresOn().toString() : null);
        m.put("notes", c.getNotes());
        m.put("filePath", c.getFilePath());
        m.put("status", urgency(c.getExpiresOn()));
        m.put("daysUntilExpiry", c.getExpiresOn() == null
                ? null
                : ChronoUnit.DAYS.between(LocalDate.now(), c.getExpiresOn()));
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    /** Compute the urgency badge to show in the UI. */
    public static String urgency(LocalDate expiresOn) {
        if (expiresOn == null) return "NO_EXPIRY";
        long days = ChronoUnit.DAYS.between(LocalDate.now(), expiresOn);
        if (days < 0) return "EXPIRED";
        if (days <= 7) return "URGENT";   // ≤ 1 week
        if (days <= 30) return "SOON";    // ≤ 1 month
        return "OK";
    }

    private static Map<String, Object> snapshot(EmployeeCert c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", c.getUserId());
        m.put("type", c.getType());
        m.put("number", c.getNumber());
        m.put("issuer", c.getIssuer());
        m.put("issuedOn", c.getIssuedOn() != null ? c.getIssuedOn().toString() : null);
        m.put("expiresOn", c.getExpiresOn() != null ? c.getExpiresOn().toString() : null);
        m.put("notes", c.getNotes());
        return m;
    }
}
