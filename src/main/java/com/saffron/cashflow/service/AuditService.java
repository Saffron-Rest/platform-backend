package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.AuditLog;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.AuditLogRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.AuditContext;
import com.saffron.cashflow.util.AuditDiff;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String userId, AuditAction action, String entityType, String entityId, Map<String, Object> details) {
        log(userId, action, entityType, entityId, details, AuditDiff.summarize(action, entityType, List.of()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(
            String userId,
            AuditAction action,
            String entityType,
            String entityId,
            Map<String, Object> details,
            String summary) {
        persist(userId, action, entityType, entityId, details, summary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logChange(
            String userId,
            AuditAction action,
            String entityType,
            String entityId,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> extra) {
        List<Map<String, Object>> changes = AuditDiff.diff(before, after);
        Map<String, Object> details = new LinkedHashMap<>();
        if (extra != null) details.putAll(extra);
        if (!changes.isEmpty()) details.put("changes", changes);
        if (before != null && !before.isEmpty()) details.put("before", before);
        if (after != null && !after.isEmpty()) details.put("after", after);
        String summary = AuditDiff.summarize(action, entityType, changes);
        persist(userId, action, entityType, entityId, details.isEmpty() ? null : details, summary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailedLogin(String email, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", email);
        details.put("reason", reason);
        persist(null, AuditAction.LOGIN_FAILED, "Auth", null, details, "Failed sign-in attempt");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logExport(String userId, String format, Map<String, Object> params) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", format);
        if (params != null) details.putAll(params);
        persist(userId, AuditAction.EXPORT, "Report", null, details, "Exported " + format.toUpperCase() + " report");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> search(
            int limit,
            int offset,
            String action,
            String entityType,
            String userId,
            String entityId,
            String from,
            String to,
            String q) {
        AuthHelper.requireOperations();
        int pageSize = Math.min(Math.max(limit, 1), 200);
        int page = offset / pageSize;

        AuditAction actionFilter = parseAction(action);
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);

        Specification<AuditLog> spec = AuditSpecification.filter(
                actionFilter, entityType, userId, entityId, fromDate, toDate, q);

        Page<AuditLog> pageResult = auditLogRepository.findAll(
                spec,
                PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> items = pageResult.getContent().stream().map(this::toMap).toList();
        return Map.of(
                "items", items,
                "total", pageResult.getTotalElements(),
                "limit", pageSize,
                "offset", offset);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getById(String id) {
        AuthHelper.requireOperations();
        AuditLog log = auditLogRepository.findByIdWithUser(id)
                .orElseThrow(() -> new NotFoundException("Audit entry not found"));
        return toMap(log);
    }

    /** @deprecated use {@link #search(int, int, String, String, String, String, String, String, String)} */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRecent(int limit) {
        Map<String, Object> result = search(limit, 0, null, null, null, null, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        return items;
    }

    private void persist(
            String userId,
            AuditAction action,
            String entityType,
            String entityId,
            Map<String, Object> details,
            String summary) {
        AuditLog log = new AuditLog();
        if (userId != null && !userId.isBlank()) {
            log.setUser(userRepository.getReferenceById(userId));
        }
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDetails(details);
        log.setSummary(truncate(summary, 500));
        log.setIpAddress(AuditContext.clientIp());
        log.setUserAgent(AuditContext.userAgent());
        auditLogRepository.save(log);
    }

    private Map<String, Object> toMap(AuditLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("userId", log.getUserId());
        m.put("action", log.getAction().name());
        m.put("entityType", log.getEntityType());
        m.put("entityId", log.getEntityId());
        m.put("summary", log.getSummary());
        m.put("details", log.getDetails());
        m.put("ipAddress", log.getIpAddress());
        m.put("userAgent", log.getUserAgent());
        m.put("createdAt", log.getCreatedAt().toString());
        if (log.getUser() != null) {
            User u = log.getUser();
            m.put("user", Map.of(
                    "id", u.getId(),
                    "name", u.getName(),
                    "email", u.getEmail(),
                    "role", u.getRole().name()));
        }
        return m;
    }

    private static AuditAction parseAction(String action) {
        if (action == null || action.isBlank()) return null;
        return AuditAction.valueOf(action);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDate.parse(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
