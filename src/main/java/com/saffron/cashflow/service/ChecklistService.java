package com.saffron.cashflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.ChecklistRun;
import com.saffron.cashflow.domain.ChecklistTemplate;
import com.saffron.cashflow.domain.ChecklistType;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.ChecklistRunRepository;
import com.saffron.cashflow.repository.ChecklistTemplateRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service backing the opening / closing / periodic checklist feature.
 *
 * <p>Templates are created by admins; runs are completed by anyone with
 * an authenticated session (cashier, manager, admin). The service is
 * deliberately permissive on the run side because the daily UX needs to
 * survive a tired closer at 23:00 — we error only on really broken input
 * (unknown template, malformed item id).</p>
 *
 * <p>Items live as JSON inside the template row, see
 * {@link ChecklistTemplate} for the rationale.</p>
 */
@Service
public class ChecklistService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChecklistTemplateRepository templateRepository;
    private final ChecklistRunRepository runRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public ChecklistService(
            ChecklistTemplateRepository templateRepository,
            ChecklistRunRepository runRepository,
            UserRepository userRepository,
            AuditService auditService) {
        this.templateRepository = templateRepository;
        this.runRepository = runRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    // ============== TEMPLATES =================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates(boolean includeArchived) {
        List<ChecklistTemplate> rows = includeArchived
                ? templateRepository.findAllByOrderByActiveDescTypeAscNameAsc()
                : templateRepository.findByActiveTrueOrderByTypeAscNameAsc();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ChecklistTemplate t : rows) out.add(templateToMap(t));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTemplate(String id) {
        return templateToMap(requireTemplate(id));
    }

    @Transactional
    public Map<String, Object> createTemplate(Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        ChecklistTemplate t = new ChecklistTemplate();
        applyTemplate(t, body);
        if (t.getName() == null || t.getName().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        t = templateRepository.save(t);
        auditService.logChange(user.id(), AuditAction.CREATE, "ChecklistTemplate", t.getId(),
                null, snapshot(t), null);
        return templateToMap(t);
    }

    @Transactional
    public Map<String, Object> updateTemplate(String id, Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        ChecklistTemplate t = requireTemplate(id);
        Map<String, Object> before = snapshot(t);
        applyTemplate(t, body);
        t = templateRepository.save(t);
        auditService.logChange(user.id(), AuditAction.UPDATE, "ChecklistTemplate", t.getId(),
                before, snapshot(t), null);
        return templateToMap(t);
    }

    @Transactional
    public void deleteTemplate(String id) {
        AuthHelper.requireAdmin();
        AuthUser user = AuthHelper.currentUser();
        ChecklistTemplate t = requireTemplate(id);
        // Soft-delete by archiving — runs reference this row so a hard
        // delete cascades and loses history.
        t.setActive(false);
        templateRepository.save(t);
        auditService.logChange(user.id(), AuditAction.DELETE, "ChecklistTemplate", id,
                snapshot(t), null, Map.of("action", "archived"));
    }

    // ============== RUNS ======================================================

    /**
     * Today's run view used by the daily-run page: for each active
     * template, returns the template plus either the existing run for
     * today or a blank starter.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> todayRuns(LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        List<ChecklistTemplate> templates = templateRepository.findByActiveTrueOrderByTypeAscNameAsc();
        Map<String, String> userNames = userNames(runRepository.findForDate(target));
        List<Map<String, Object>> out = new ArrayList<>(templates.size());
        for (ChecklistTemplate t : templates) {
            ChecklistRun r = runRepository.findFirstByTemplateIdAndRunDate(t.getId(), target)
                    .orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template", templateToMap(t));
            m.put("run", r == null ? null : runToMap(r, userNames));
            out.add(m);
        }
        return out;
    }

    /**
     * History list — last N days of runs across all templates with the
     * progress so an admin can see "did the closer actually finish?".
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(int days) {
        int span = Math.min(Math.max(days, 1), 90);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(span - 1L);
        List<ChecklistRun> runs = runRepository.findBetween(from, to);
        Map<String, String> userNames = userNames(runs);
        Map<String, String> templateNames = new HashMap<>();
        for (ChecklistTemplate t : templateRepository.findAll()) templateNames.put(t.getId(), t.getName());
        List<Map<String, Object>> out = new ArrayList<>(runs.size());
        for (ChecklistRun r : runs) {
            Map<String, Object> m = runToMap(r, userNames);
            m.put("templateName", templateNames.get(r.getTemplateId()));
            out.add(m);
        }
        return out;
    }

    /**
     * Upsert today's run for a template. The caller sends the full
     * response map — we recompute completion counts and persist.
     */
    @Transactional
    public Map<String, Object> upsertRun(String templateId, Map<String, Object> body) {
        AuthUser user = AuthHelper.currentUser();
        ChecklistTemplate template = requireTemplate(templateId);
        LocalDate runDate = parseDate(body.get("runDate"), LocalDate.now());

        ChecklistRun run = runRepository.findFirstByTemplateIdAndRunDate(templateId, runDate)
                .orElseGet(() -> {
                    ChecklistRun fresh = new ChecklistRun();
                    fresh.setTemplateId(templateId);
                    fresh.setRunDate(runDate);
                    return fresh;
                });

        // Read the items list off the template so we can validate keys and
        // compute totals — defends against the UI sending stale ids after
        // an admin renamed the items.
        List<Map<String, Object>> items = parseList(template.getItems());
        Set<String> validIds = new HashSet<>();
        for (Map<String, Object> it : items) {
            Object id = it.get("id");
            if (id != null) validIds.add(id.toString());
        }

        Map<String, Object> responses = parseObject(
                body.containsKey("responses") ? body.get("responses") : "{}");
        Map<String, Object> sanitised = new LinkedHashMap<>();
        int completed = 0;
        for (Map.Entry<String, Object> e : responses.entrySet()) {
            if (!validIds.contains(e.getKey())) continue;
            if (!(e.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> resp = new LinkedHashMap<>();
            boolean checked = Boolean.TRUE.equals(raw.get("checked"));
            resp.put("checked", checked);
            Object notes = raw.get("notes");
            if (notes != null && !notes.toString().isBlank()) resp.put("notes", notes.toString());
            Object photo = raw.get("photoPath");
            if (photo != null && !photo.toString().isBlank()) resp.put("photoPath", photo.toString());
            resp.put("checkedAt", checked ? Instant.now().toString() : null);
            sanitised.put(e.getKey(), resp);
            if (checked) completed++;
        }

        run.setResponses(writeJson(sanitised));
        run.setTotalItems(items.size());
        run.setCompletedItems(completed);
        if (body.containsKey("notes")) {
            Object n = body.get("notes");
            run.setNotes(n == null ? null : n.toString());
        }
        run.setCompletedById(user.id());
        run = runRepository.save(run);

        auditService.logChange(user.id(), AuditAction.UPDATE, "ChecklistRun", run.getId(),
                null, Map.of("templateId", templateId, "completed", completed, "total", items.size()), null);

        Map<String, String> names = userNames(List.of(run));
        return runToMap(run, names);
    }

    // ============== INTERNAL ==================================================

    private ChecklistTemplate requireTemplate(String id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Checklist template not found"));
    }

    private void applyTemplate(ChecklistTemplate t, Map<String, Object> body) {
        if (body.containsKey("name")) {
            Object n = body.get("name");
            t.setName(n == null ? null : n.toString().trim());
        }
        if (body.containsKey("type")) {
            try { t.setType(ChecklistType.valueOf(body.get("type").toString().toUpperCase())); }
            catch (Exception ex) { throw new BadRequestException("Invalid type"); }
        }
        if (body.containsKey("role")) {
            Object r = body.get("role");
            t.setRole(r == null || r.toString().isBlank() ? null : r.toString());
        }
        if (body.containsKey("description")) {
            Object d = body.get("description");
            t.setDescription(d == null || d.toString().isBlank() ? null : d.toString());
        }
        if (body.containsKey("active")) t.setActive(Boolean.TRUE.equals(body.get("active")));
        if (body.containsKey("items")) {
            List<Map<String, Object>> items = parseList(body.get("items"));
            // Normalise — ensure every item has an id + non-empty label.
            for (Map<String, Object> it : items) {
                if (it.get("id") == null || it.get("id").toString().isBlank()) {
                    it.put("id", UUID.randomUUID().toString());
                }
                if (it.get("label") == null || it.get("label").toString().isBlank()) {
                    throw new BadRequestException("Every checklist item needs a label");
                }
                // Defaults so the FE doesn't have to send them every time.
                if (!it.containsKey("requiresPhoto")) it.put("requiresPhoto", false);
                if (!it.containsKey("requiresTemperature")) it.put("requiresTemperature", false);
            }
            t.setItems(writeJson(items));
        }
    }

    private Map<String, String> userNames(List<ChecklistRun> runs) {
        Set<String> ids = new HashSet<>();
        for (ChecklistRun r : runs) if (r.getCompletedById() != null) ids.add(r.getCompletedById());
        Map<String, String> out = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) out.put(u.getId(), u.getName());
        return out;
    }

    private static LocalDate parseDate(Object v, LocalDate fallback) {
        if (v == null) return fallback;
        String s = v.toString().trim();
        if (s.isEmpty()) return fallback;
        try { return LocalDate.parse(s); }
        catch (Exception ex) { throw new BadRequestException("Invalid date"); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseList(Object raw) {
        if (raw == null) return new ArrayList<>();
        try {
            if (raw instanceof String s) {
                if (s.isBlank()) return new ArrayList<>();
                return MAPPER.readValue(s, new TypeReference<List<Map<String, Object>>>() {});
            }
            if (raw instanceof List<?> l) {
                List<Map<String, Object>> out = new ArrayList<>(l.size());
                for (Object it : l) {
                    if (it instanceof Map<?, ?> m) out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
                return out;
            }
        } catch (Exception ex) {
            throw new BadRequestException("Could not parse items: " + ex.getMessage());
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(Object raw) {
        if (raw == null) return Collections.emptyMap();
        try {
            if (raw instanceof String s) {
                if (s.isBlank()) return Collections.emptyMap();
                return MAPPER.readValue(s, new TypeReference<Map<String, Object>>() {});
            }
            if (raw instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        } catch (Exception ex) {
            throw new BadRequestException("Could not parse responses: " + ex.getMessage());
        }
        return Collections.emptyMap();
    }

    private static String writeJson(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Serialise failure", ex); }
    }

    private static Map<String, Object> templateToMap(ChecklistTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("type", t.getType() != null ? t.getType().name() : null);
        m.put("role", t.getRole());
        m.put("description", t.getDescription());
        m.put("items", parseList(t.getItems()));
        m.put("active", t.isActive());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> runToMap(ChecklistRun r, Map<String, String> userNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("templateId", r.getTemplateId());
        m.put("runDate", r.getRunDate() != null ? r.getRunDate().toString() : null);
        m.put("completedById", r.getCompletedById());
        m.put("completedByName", userNames.get(r.getCompletedById()));
        m.put("responses", parseObject(r.getResponses()));
        m.put("totalItems", r.getTotalItems());
        m.put("completedItems", r.getCompletedItems());
        m.put("notes", r.getNotes());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> snapshot(ChecklistTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.getName());
        m.put("type", t.getType() != null ? t.getType().name() : null);
        m.put("itemCount", parseList(t.getItems()).size());
        m.put("active", t.isActive());
        return m;
    }
}
