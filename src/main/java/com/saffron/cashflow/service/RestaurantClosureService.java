package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.RestaurantClosure;
import com.saffron.cashflow.repository.RestaurantClosureRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.ConflictException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD over restaurant closure days. Reads are open to operations
 * roles (managers/admins both consult the calendar). Writes are
 * admin-only — closures change the gating rules for shift creation, so
 * they're treated as a settings-class change.
 */
@Service
public class RestaurantClosureService {

    private final RestaurantClosureRepository repository;
    private final AuditService auditService;

    public RestaurantClosureService(
            RestaurantClosureRepository repository,
            AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AuthHelper.requireOperations();
        return repository.findAllByOrderByDateAsc().stream().map(RestaurantClosureService::toMap).toList();
    }

    @Transactional
    public Map<String, Object> create(String dateStr, String reason) {
        AuthHelper.requireAdminOr(Permission.SETTINGS_MANAGE);
        LocalDate date = parseDate(dateStr);
        String cleanReason = requireReason(reason);
        if (repository.existsById(date)) {
            throw new ConflictException(Map.of(
                    "error", "A closure already exists for " + date,
                    "date", date.toString()));
        }
        RestaurantClosure c = new RestaurantClosure();
        c.setDate(date);
        c.setReason(cleanReason);
        c.setCreatedBy(AuthHelper.currentUser().id());
        c = repository.save(c);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE,
                "RestaurantClosure", date.toString(),
                Map.of(),
                Map.of("date", date.toString(), "reason", cleanReason),
                null);
        return toMap(c);
    }

    @Transactional
    public Map<String, Object> update(String dateStr, String reason) {
        AuthHelper.requireAdminOr(Permission.SETTINGS_MANAGE);
        LocalDate date = parseDate(dateStr);
        RestaurantClosure c = repository.findById(date)
                .orElseThrow(() -> new NotFoundException("Closure not found"));
        String cleanReason = requireReason(reason);
        Map<String, Object> before = Map.of("reason", c.getReason());
        c.setReason(cleanReason);
        c = repository.save(c);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE,
                "RestaurantClosure", date.toString(),
                before, Map.of("reason", cleanReason), null);
        return toMap(c);
    }

    @Transactional
    public void delete(String dateStr) {
        AuthHelper.requireAdminOr(Permission.SETTINGS_MANAGE);
        LocalDate date = parseDate(dateStr);
        RestaurantClosure c = repository.findById(date)
                .orElseThrow(() -> new NotFoundException("Closure not found"));
        Map<String, Object> before = Map.of(
                "date", c.getDate().toString(),
                "reason", c.getReason());
        repository.delete(c);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE,
                "RestaurantClosure", date.toString(), before, Map.of(), null);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) throw new BadRequestException("Date is required");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Date must be in YYYY-MM-DD format");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 200) {
            throw new BadRequestException("Reason too long (max 200 chars)");
        }
        return trimmed;
    }

    private static Map<String, Object> toMap(RestaurantClosure c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", c.getDate().toString());
        m.put("reason", c.getReason());
        m.put("createdBy", c.getCreatedBy());
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        return m;
    }
}
