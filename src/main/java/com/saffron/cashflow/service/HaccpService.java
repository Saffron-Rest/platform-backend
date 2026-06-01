package com.saffron.cashflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.HaccpKind;
import com.saffron.cashflow.domain.HaccpLog;
import com.saffron.cashflow.domain.HaccpStatus;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.HaccpLogRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HACCP log service.
 *
 * <p>Anyone with a session can create entries; admins can edit/delete. We
 * deliberately permit cashiers to write because the whole point of HACCP
 * is "the person at the fridge logs the temperature" — gatekeeping that
 * to admins defeats the purpose.</p>
 */
@Service
public class HaccpService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HaccpLogRepository repository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public HaccpService(
            HaccpLogRepository repository,
            UserRepository userRepository,
            AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(LocalDate from, LocalDate to, HaccpKind kind) {
        LocalDate fromDate = from != null ? from : LocalDate.now().minusDays(14);
        LocalDate toDate = to != null ? to : LocalDate.now();
        if (fromDate.isAfter(toDate)) {
            throw new BadRequestException("'from' must be on or before 'to'");
        }
        List<HaccpLog> rows = repository.findBetween(fromDate, toDate, kind);
        Map<String, String> userNames = userNames(rows);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (HaccpLog h : rows) out.add(toMap(h, userNames));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        HaccpLog h = require(id);
        return toMap(h, userNames(List.of(h)));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        AuthUser user = AuthHelper.currentUser();
        HaccpLog h = new HaccpLog();
        h.setRecordedById(user.id());
        applyMutable(h, body);
        if (h.getKind() == null) throw new BadRequestException("kind is required");
        h = repository.save(h);
        auditService.logChange(user.id(), AuditAction.CREATE, "HaccpLog", h.getId(),
                null, snapshot(h), null);
        return toMap(h, userNames(List.of(h)));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requireOperations();
        AuthUser user = AuthHelper.currentUser();
        HaccpLog h = require(id);
        Map<String, Object> before = snapshot(h);
        applyMutable(h, body);
        h = repository.save(h);
        auditService.logChange(user.id(), AuditAction.UPDATE, "HaccpLog", h.getId(),
                before, snapshot(h), null);
        return toMap(h, userNames(List.of(h)));
    }

    @Transactional
    public void delete(String id) {
        AuthHelper.requireAdminOr(Permission.HACCP_CONFIGURE);
        AuthUser user = AuthHelper.currentUser();
        HaccpLog h = require(id);
        Map<String, Object> before = snapshot(h);
        repository.delete(h);
        auditService.logChange(user.id(), AuditAction.DELETE, "HaccpLog", id,
                before, null, null);
    }

    // ------------------------------------------------------------------------

    private HaccpLog require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("HACCP entry not found"));
    }

    private void applyMutable(HaccpLog h, Map<String, Object> body) {
        if (body.containsKey("kind")) {
            try { h.setKind(HaccpKind.valueOf(body.get("kind").toString().toUpperCase())); }
            catch (Exception ex) { throw new BadRequestException("Invalid kind"); }
        }
        if (body.containsKey("recordedOn")) {
            String s = asString(body.get("recordedOn"));
            h.setRecordedOn(s == null ? null : LocalDate.parse(s));
        }
        if (body.containsKey("location")) h.setLocation(asString(body.get("location")));
        if (body.containsKey("temperatureC")) {
            Object v = body.get("temperatureC");
            if (v == null || (v instanceof String s && s.isBlank())) h.setTemperatureC(null);
            else if (v instanceof Number n) h.setTemperatureC(new BigDecimal(n.toString()));
            else try { h.setTemperatureC(new BigDecimal(v.toString())); }
                 catch (NumberFormatException ex) { throw new BadRequestException("Invalid temperature"); }
        }
        if (body.containsKey("status")) {
            try { h.setStatus(HaccpStatus.valueOf(body.get("status").toString().toUpperCase())); }
            catch (Exception ex) { throw new BadRequestException("Invalid status"); }
        }
        if (body.containsKey("notes")) h.setNotes(asString(body.get("notes")));
        if (body.containsKey("photoPath")) h.setPhotoPath(asString(body.get("photoPath")));
        if (body.containsKey("data")) {
            Object raw = body.get("data");
            if (raw == null) h.setData(null);
            else if (raw instanceof String s) h.setData(s);
            else {
                try { h.setData(MAPPER.writeValueAsString(raw)); }
                catch (Exception ex) { throw new BadRequestException("Invalid data payload"); }
            }
        }
    }

    private Map<String, String> userNames(List<HaccpLog> logs) {
        Set<String> ids = new HashSet<>();
        for (HaccpLog h : logs) if (h.getRecordedById() != null) ids.add(h.getRecordedById());
        Map<String, String> out = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) out.put(u.getId(), u.getName());
        return out;
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, Object> toMap(HaccpLog h, Map<String, String> userNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("kind", h.getKind() != null ? h.getKind().name() : null);
        m.put("recordedOn", h.getRecordedOn() != null ? h.getRecordedOn().toString() : null);
        m.put("recordedAt", h.getRecordedAt());
        m.put("recordedById", h.getRecordedById());
        m.put("recordedByName", userNames.get(h.getRecordedById()));
        m.put("location", h.getLocation());
        m.put("temperatureC", h.getTemperatureC());
        m.put("status", h.getStatus() != null ? h.getStatus().name() : null);
        m.put("notes", h.getNotes());
        m.put("photoPath", h.getPhotoPath());
        m.put("data", parseData(h.getData()));
        m.put("createdAt", h.getCreatedAt());
        return m;
    }

    private static Object parseData(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Object>() {});
        } catch (Exception ex) {
            return json; // Fall back to raw string so we don't lose data.
        }
    }

    private static Map<String, Object> snapshot(HaccpLog h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", h.getKind() != null ? h.getKind().name() : null);
        m.put("recordedOn", h.getRecordedOn() != null ? h.getRecordedOn().toString() : null);
        m.put("location", h.getLocation());
        m.put("temperatureC", h.getTemperatureC());
        m.put("status", h.getStatus() != null ? h.getStatus().name() : null);
        m.put("notes", h.getNotes());
        return m;
    }
}
