package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.SavedView;
import com.saffron.cashflow.repository.SavedViewRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * Saved view manager — per-user pinned filter snapshots scoped to a page.
 *
 * Filter payloads are opaque strings (UI-defined JSON shapes). We never
 * parse them server-side, so adding new filterable fields on a page is a
 * frontend-only change.
 */
@Service
public class SavedViewService {

    private static final int MAX_NAME_LEN = 80;
    private static final int MAX_FILTERS_LEN = 4000;
    private static final int MAX_VIEWS_PER_PAGE = 24;

    private final SavedViewRepository repository;
    private final AuditService auditService;

    public SavedViewService(SavedViewRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String page) {
        String userId = AuthHelper.currentUser().id();
        return repository.findByUserIdAndPageOrderByNameAsc(userId, normalizePage(page)).stream()
                .map(SavedViewService::toMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(String page, String name, String filtersJson, boolean asDefault) {
        String userId = AuthHelper.currentUser().id();
        String cleanPage = normalizePage(page);
        String cleanName = normalizeName(name);
        String cleanFilters = normalizeFilters(filtersJson);

        if (repository.findByUserIdAndPageOrderByNameAsc(userId, cleanPage).size() >= MAX_VIEWS_PER_PAGE) {
            throw new BadRequestException("Maximum " + MAX_VIEWS_PER_PAGE + " saved views per page");
        }
        repository.findByUserIdAndPageAndName(userId, cleanPage, cleanName).ifPresent(existing -> {
            throw new BadRequestException("A view named \"" + cleanName + "\" already exists on this page");
        });

        SavedView v = new SavedView();
        v.setUserId(userId);
        v.setPage(cleanPage);
        v.setName(cleanName);
        v.setFiltersJson(cleanFilters);
        v.setDefault(asDefault);
        v = repository.save(v);
        if (asDefault) repository.clearOtherDefaults(userId, cleanPage, v.getId());

        auditService.logChange(userId, com.saffron.cashflow.domain.AuditAction.CREATE,
                "SavedView", v.getId(), Map.of(),
                Map.of("page", cleanPage, "name", cleanName), null);
        return toMap(v);
    }

    @Transactional
    public Map<String, Object> update(String id, String name, String filtersJson, Boolean asDefault) {
        SavedView v = loadOwned(id);
        Map<String, Object> before = toMap(v);
        if (name != null) v.setName(normalizeName(name));
        if (filtersJson != null) v.setFiltersJson(normalizeFilters(filtersJson));
        if (asDefault != null) {
            v.setDefault(asDefault);
        }
        v = repository.save(v);
        if (Boolean.TRUE.equals(asDefault)) {
            repository.clearOtherDefaults(v.getUserId(), v.getPage(), v.getId());
        }
        auditService.logChange(AuthHelper.currentUser().id(),
                com.saffron.cashflow.domain.AuditAction.UPDATE,
                "SavedView", v.getId(), before, toMap(v), null);
        return toMap(v);
    }

    @Transactional
    public void delete(String id) {
        SavedView v = loadOwned(id);
        repository.delete(v);
        auditService.logChange(AuthHelper.currentUser().id(),
                com.saffron.cashflow.domain.AuditAction.DELETE,
                "SavedView", id, toMap(v), Map.of(), null);
    }

    // ---------- helpers ----------

    /** Loads a view by id and asserts the current caller owns it. Views
     *  are strictly private — no cross-user reads or edits. */
    private SavedView loadOwned(String id) {
        SavedView v = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Saved view not found"));
        if (!v.getUserId().equals(AuthHelper.currentUser().id())) {
            throw new NotFoundException("Saved view not found");
        }
        return v;
    }

    private static String normalizePage(String page) {
        if (page == null || page.isBlank()) throw new BadRequestException("page is required");
        // Slug shape — alphanumeric + dot + dash + underscore. Keeps URLs
        // / analytics safe.
        if (!page.matches("[a-zA-Z0-9._-]+")) throw new BadRequestException("Invalid page id");
        return page;
    }

    private static String normalizeName(String name) {
        if (name == null) throw new BadRequestException("name is required");
        String s = name.strip();
        if (s.isEmpty()) throw new BadRequestException("name is required");
        if (s.length() > MAX_NAME_LEN) {
            throw new BadRequestException("Name too long (max " + MAX_NAME_LEN + " chars)");
        }
        return s;
    }

    private static String normalizeFilters(String filtersJson) {
        if (filtersJson == null) throw new BadRequestException("filters required");
        String s = filtersJson.strip();
        if (s.isEmpty()) throw new BadRequestException("filters required");
        if (s.length() > MAX_FILTERS_LEN) {
            throw new BadRequestException("Filter payload too large");
        }
        // Light sanity check — must look like a JSON object/array. We
        // don't fully validate because the payload is opaque to us.
        if (!(s.startsWith("{") && s.endsWith("}")) && !(s.startsWith("[") && s.endsWith("]"))) {
            throw new BadRequestException("filters must be a JSON object or array");
        }
        return s;
    }

    private static Map<String, Object> toMap(SavedView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("page", v.getPage());
        m.put("name", v.getName());
        m.put("filters", v.getFiltersJson());
        m.put("isDefault", v.isDefault());
        m.put("createdAt", v.getCreatedAt().toString());
        m.put("updatedAt", v.getUpdatedAt().toString());
        return m;
    }
}
