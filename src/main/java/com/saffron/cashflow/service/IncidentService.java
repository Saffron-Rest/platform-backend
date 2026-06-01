package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.Incident;
import com.saffron.cashflow.domain.IncidentSeverity;
import com.saffron.cashflow.domain.IncidentStatus;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.IncidentRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operational incident log.
 *
 * <p>Operations roles (admin + manager) can create, edit, assign, and
 * resolve. Photo attachments go through {@link FileStorageService}'s
 * generic {@code storeUnderPrefix("incident")} helper.</p>
 *
 * <p>When an incident is assigned to someone, a notification is created
 * for that user via {@link NotificationService} so they see it in their
 * inbox immediately.</p>
 */
@Service
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public IncidentService(
            IncidentRepository incidentRepository,
            UserRepository userRepository,
            AuditService auditService,
            NotificationService notificationService) {
        this.incidentRepository = incidentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AuthHelper.requireOperations();
        List<Incident> rows = incidentRepository.findAllOrdered();
        Map<String, String> userNames = resolveUserNames(rows);
        return rows.stream().map(i -> toMap(i, userNames)).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requireOperations();
        Incident i = require(id);
        Map<String, String> names = resolveUserNames(List.of(i));
        return toMap(i, names);
    }

    /** Counts grouped by status for the dashboard tile. */
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        AuthHelper.requireOperations();
        Map<String, Long> m = new LinkedHashMap<>();
        for (IncidentStatus s : IncidentStatus.values()) {
            m.put(s.name(), incidentRepository.countByStatus(s));
        }
        return m;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        Incident i = new Incident();
        i.setReportedById(user.id());
        applyMutable(i, body);
        if (i.getTitle() == null || i.getTitle().isBlank()) {
            throw new BadRequestException("Title is required");
        }
        if (i.getOccurredOn() == null) i.setOccurredOn(LocalDate.now());
        i = incidentRepository.save(i);
        // Notify the assignee if one was set at creation time.
        if (i.getAssigneeId() != null && !i.getAssigneeId().equals(user.id())) {
            notifyAssignment(i, user.id());
        }
        auditService.logChange(user.id(), AuditAction.CREATE, "Incident", i.getId(),
                null, snapshot(i), null);
        Map<String, String> names = resolveUserNames(List.of(i));
        return toMap(i, names);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        Incident i = require(id);
        Map<String, Object> before = snapshot(i);
        String previousAssignee = i.getAssigneeId();
        applyMutable(i, body);
        i = incidentRepository.save(i);

        if (i.getAssigneeId() != null
                && !i.getAssigneeId().equals(previousAssignee)
                && !i.getAssigneeId().equals(user.id())) {
            notifyAssignment(i, user.id());
        }
        auditService.logChange(user.id(), AuditAction.UPDATE, "Incident", i.getId(),
                before, snapshot(i), null);
        Map<String, String> names = resolveUserNames(List.of(i));
        return toMap(i, names);
    }

    @Transactional
    public Map<String, Object> resolve(String id, Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        Incident i = require(id);
        Map<String, Object> before = snapshot(i);
        String notes = asString(body.get("resolutionNotes"));
        boolean dismiss = Boolean.TRUE.equals(body.get("dismiss"));
        i.setStatus(dismiss ? IncidentStatus.DISMISSED : IncidentStatus.RESOLVED);
        i.setResolutionNotes(notes);
        i.setResolvedAt(Instant.now());
        i.setResolvedById(user.id());
        i = incidentRepository.save(i);
        auditService.logChange(user.id(), AuditAction.UPDATE, "Incident", i.getId(),
                before, snapshot(i), Map.of("action", dismiss ? "dismissed" : "resolved"));
        Map<String, String> names = resolveUserNames(List.of(i));
        return toMap(i, names);
    }

    /**
     * Reopen a previously resolved/dismissed incident — admin can do this
     * if a fix didn't stick.
     */
    @Transactional
    public Map<String, Object> reopen(String id) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        Incident i = require(id);
        Map<String, Object> before = snapshot(i);
        i.setStatus(IncidentStatus.OPEN);
        i.setResolvedAt(null);
        i.setResolvedById(null);
        i.setResolutionNotes(null);
        i = incidentRepository.save(i);
        auditService.logChange(user.id(), AuditAction.UPDATE, "Incident", i.getId(),
                before, snapshot(i), Map.of("action", "reopened"));
        Map<String, String> names = resolveUserNames(List.of(i));
        return toMap(i, names);
    }

    @Transactional
    public void delete(String id) {
        AuthHelper.requireAdminOr(Permission.INCIDENTS_RESOLVE);
        AuthUser user = AuthHelper.currentUser();
        Incident i = require(id);
        Map<String, Object> before = snapshot(i);
        incidentRepository.delete(i);
        auditService.logChange(user.id(), AuditAction.DELETE, "Incident", id,
                before, null, null);
    }

    // ------------------------------------------------------------------------

    private Incident require(String id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Incident not found"));
    }

    private void notifyAssignment(Incident i, String actorId) {
        notificationService.create(
                i.getAssigneeId(),
                "incident_assigned",
                "Assigned: " + i.getTitle(),
                i.getDescription() != null && !i.getDescription().isBlank()
                        ? i.getDescription().substring(0, Math.min(160, i.getDescription().length()))
                        : null,
                "/admin/incidents",
                "Incident",
                i.getId(),
                actorId);
    }

    private void applyMutable(Incident i, Map<String, Object> body) {
        if (body.containsKey("title")) i.setTitle(asString(body.get("title")));
        if (body.containsKey("category")) i.setCategory(asString(body.get("category")));
        if (body.containsKey("occurredOn")) {
            String s = asString(body.get("occurredOn"));
            i.setOccurredOn(s == null ? null : LocalDate.parse(s));
        }
        if (body.containsKey("severity")) {
            try { i.setSeverity(IncidentSeverity.valueOf(asString(body.get("severity")).toUpperCase())); }
            catch (Exception ex) { throw new BadRequestException("Invalid severity"); }
        }
        if (body.containsKey("status")) {
            try { i.setStatus(IncidentStatus.valueOf(asString(body.get("status")).toUpperCase())); }
            catch (Exception ex) { throw new BadRequestException("Invalid status"); }
        }
        if (body.containsKey("description")) i.setDescription(asString(body.get("description")));
        if (body.containsKey("estimatedCost")) {
            Object v = body.get("estimatedCost");
            if (v == null || (v instanceof String s && s.isBlank())) i.setEstimatedCost(null);
            else if (v instanceof Number n) i.setEstimatedCost(new BigDecimal(n.toString()));
            else try { i.setEstimatedCost(new BigDecimal(v.toString())); }
                 catch (NumberFormatException ex) { throw new BadRequestException("Invalid amount"); }
        }
        if (body.containsKey("photoPath")) i.setPhotoPath(asString(body.get("photoPath")));
        if (body.containsKey("assigneeId")) {
            String a = asString(body.get("assigneeId"));
            i.setAssigneeId(a);
        }
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Map<String, String> resolveUserNames(List<Incident> incidents) {
        Set<String> ids = new HashSet<>();
        for (Incident i : incidents) {
            if (i.getReportedById() != null) ids.add(i.getReportedById());
            if (i.getAssigneeId() != null) ids.add(i.getAssigneeId());
            if (i.getResolvedById() != null) ids.add(i.getResolvedById());
        }
        Map<String, String> out = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) out.put(u.getId(), u.getName());
        return out;
    }

    private static Map<String, Object> toMap(Incident i, Map<String, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("title", i.getTitle());
        m.put("category", i.getCategory());
        m.put("occurredOn", i.getOccurredOn() != null ? i.getOccurredOn().toString() : null);
        m.put("severity", i.getSeverity() != null ? i.getSeverity().name() : null);
        m.put("status", i.getStatus() != null ? i.getStatus().name() : null);
        m.put("description", i.getDescription());
        m.put("estimatedCost", i.getEstimatedCost());
        m.put("photoPath", i.getPhotoPath());
        m.put("reportedById", i.getReportedById());
        m.put("reportedByName", names.get(i.getReportedById()));
        m.put("assigneeId", i.getAssigneeId());
        m.put("assigneeName", names.get(i.getAssigneeId()));
        m.put("resolvedById", i.getResolvedById());
        m.put("resolvedByName", names.get(i.getResolvedById()));
        m.put("resolutionNotes", i.getResolutionNotes());
        m.put("resolvedAt", i.getResolvedAt());
        m.put("createdAt", i.getCreatedAt());
        m.put("updatedAt", i.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> snapshot(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", i.getTitle());
        m.put("category", i.getCategory());
        m.put("occurredOn", i.getOccurredOn() != null ? i.getOccurredOn().toString() : null);
        m.put("severity", i.getSeverity() != null ? i.getSeverity().name() : null);
        m.put("status", i.getStatus() != null ? i.getStatus().name() : null);
        m.put("description", i.getDescription());
        m.put("estimatedCost", i.getEstimatedCost());
        m.put("assigneeId", i.getAssigneeId());
        m.put("resolutionNotes", i.getResolutionNotes());
        return m;
    }
}
