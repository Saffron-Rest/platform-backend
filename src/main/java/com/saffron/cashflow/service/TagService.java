package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.Tag;
import com.saffron.cashflow.domain.TagAssignment;
import com.saffron.cashflow.domain.TaggedEntityType;
import com.saffron.cashflow.repository.BankDepositRepository;
import com.saffron.cashflow.repository.CardSettlementRepository;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.ExpenseItemRepository;
import com.saffron.cashflow.repository.ManualDeliveryIncomeRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.TagAssignmentRepository;
import com.saffron.cashflow.repository.TagRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tag CRUD + (un)assignment for any taggable record type.
 * All mutations are restricted to operations roles (admin/manager) so
 * cashiers can't pollute the shared tag library.
 */
@Service
public class TagService {

    /** 7-char hex (#RGB or #RRGGBB) — keeps the colour palette safe. */
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$");
    private static final int MAX_NAME_LEN = 64;

    private final TagRepository tagRepository;
    private final TagAssignmentRepository assignmentRepository;
    private final DailyEntryRepository entryRepository;
    private final ExpenseItemRepository expenseRepository;
    private final SalaryPaymentRepository salaryPaymentRepository;
    private final ManualDeliveryIncomeRepository manualDeliveryRepository;
    private final BankDepositRepository bankDepositRepository;
    private final CardSettlementRepository cardSettlementRepository;
    private final AuditService auditService;

    public TagService(
            TagRepository tagRepository,
            TagAssignmentRepository assignmentRepository,
            DailyEntryRepository entryRepository,
            ExpenseItemRepository expenseRepository,
            SalaryPaymentRepository salaryPaymentRepository,
            ManualDeliveryIncomeRepository manualDeliveryRepository,
            BankDepositRepository bankDepositRepository,
            CardSettlementRepository cardSettlementRepository,
            AuditService auditService) {
        this.tagRepository = tagRepository;
        this.assignmentRepository = assignmentRepository;
        this.entryRepository = entryRepository;
        this.expenseRepository = expenseRepository;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.manualDeliveryRepository = manualDeliveryRepository;
        this.bankDepositRepository = bankDepositRepository;
        this.cardSettlementRepository = cardSettlementRepository;
        this.auditService = auditService;
    }

    // ---------- CRUD ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTags() {
        List<Tag> tags = tagRepository.findAllByOrderByNameAsc();
        if (tags.isEmpty()) return List.of();
        Map<String, Long> counts = new HashMap<>();
        for (Tag t : tags) counts.put(t.getId(), assignmentRepository.countByTagId(t.getId()));
        return tags.stream().map(t -> toMap(t, counts.getOrDefault(t.getId(), 0L))).toList();
    }

    @Transactional
    public Map<String, Object> createTag(String name, String color, String description) {
        AuthHelper.requireOperations();
        String cleanName = requireName(name);
        tagRepository.findFirstByNameIgnoreCase(cleanName).ifPresent(existing -> {
            throw new BadRequestException("A tag named \"" + existing.getName() + "\" already exists");
        });
        Tag tag = new Tag();
        tag.setName(cleanName);
        tag.setColor(normalizeColor(color));
        tag.setDescription(trimToNull(description, 200));
        tag.setCreatedBy(AuthHelper.currentUser().id());
        tag = tagRepository.save(tag);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "Tag", tag.getId(),
                Map.of(), Map.of("name", tag.getName(), "color", tag.getColor()), null);
        return toMap(tag, 0L);
    }

    @Transactional
    public Map<String, Object> updateTag(String id, String name, String color, String description) {
        AuthHelper.requireOperations();
        Tag tag = tagRepository.findById(id).orElseThrow(() -> new NotFoundException("Tag not found"));
        Map<String, Object> before = Map.of(
                "name", tag.getName(),
                "color", String.valueOf(tag.getColor()),
                "description", String.valueOf(tag.getDescription()));
        if (name != null) {
            String cleanName = requireName(name);
            tagRepository.findFirstByNameIgnoreCase(cleanName).ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    throw new BadRequestException("A tag named \"" + existing.getName() + "\" already exists");
                }
            });
            tag.setName(cleanName);
        }
        if (color != null) tag.setColor(normalizeColor(color));
        if (description != null) tag.setDescription(trimToNull(description, 200));
        tag = tagRepository.save(tag);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "Tag", tag.getId(),
                before, Map.of("name", tag.getName(), "color", String.valueOf(tag.getColor())), null);
        long usage = assignmentRepository.countByTagId(tag.getId());
        return toMap(tag, usage);
    }

    @Transactional
    public void deleteTag(String id) {
        AuthHelper.requireOperations();
        Tag tag = tagRepository.findById(id).orElseThrow(() -> new NotFoundException("Tag not found"));
        long usage = assignmentRepository.countByTagId(id);
        assignmentRepository.deleteByTagId(id);
        tagRepository.delete(tag);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "Tag", id,
                Map.of("name", tag.getName()), Map.of(),
                Map.of("removedAssignments", usage));
    }

    // ---------- Assignment ----------

    /** Idempotent — assigning the same tag twice is a no-op. */
    @Transactional
    public void assign(String tagId, TaggedEntityType entityType, String entityId) {
        AuthHelper.requireOperations();
        Tag tag = tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("Tag not found"));
        assertTargetExists(entityType, entityId);
        assignmentRepository.findByTagIdAndEntityTypeAndEntityId(tagId, entityType, entityId)
                .orElseGet(() -> {
                    TagAssignment a = new TagAssignment();
                    a.setTagId(tagId);
                    a.setEntityType(entityType);
                    a.setEntityId(entityId);
                    a.setAssignedBy(AuthHelper.currentUser().id());
                    return assignmentRepository.save(a);
                });
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, entityType.name(), entityId,
                Map.of(), Map.of("addedTag", tag.getName()), Map.of("tagId", tagId));
    }

    @Transactional
    public void unassign(String tagId, TaggedEntityType entityType, String entityId) {
        AuthHelper.requireOperations();
        Tag tag = tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("Tag not found"));
        assignmentRepository.findByTagIdAndEntityTypeAndEntityId(tagId, entityType, entityId)
                .ifPresent(assignmentRepository::delete);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, entityType.name(), entityId,
                Map.of("tag", tag.getName()), Map.of(), Map.of("tagId", tagId));
    }

    /** Replace the full tag set of a record in one call. */
    @Transactional
    public void replace(TaggedEntityType entityType, String entityId, Set<String> tagIds) {
        AuthHelper.requireOperations();
        assertTargetExists(entityType, entityId);
        Set<String> desired = tagIds == null ? Set.of() : new HashSet<>(tagIds);
        // Verify each tag id resolves so we don't leave dangling rows.
        for (String tagId : desired) {
            tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("Tag not found: " + tagId));
        }
        List<TagAssignment> current = assignmentRepository.findByEntityTypeAndEntityId(entityType, entityId);
        Set<String> currentIds = current.stream().map(TagAssignment::getTagId).collect(Collectors.toSet());
        for (TagAssignment a : current) {
            if (!desired.contains(a.getTagId())) assignmentRepository.delete(a);
        }
        for (String tagId : desired) {
            if (currentIds.contains(tagId)) continue;
            TagAssignment a = new TagAssignment();
            a.setTagId(tagId);
            a.setEntityType(entityType);
            a.setEntityId(entityId);
            a.setAssignedBy(AuthHelper.currentUser().id());
            assignmentRepository.save(a);
        }
    }

    /** Apply the same tag to many records in a single transaction. */
    @Transactional
    public void bulkAssign(String tagId, TaggedEntityType entityType, List<String> entityIds) {
        AuthHelper.requireOperations();
        if (entityIds == null || entityIds.isEmpty()) return;
        Tag tag = tagRepository.findById(tagId).orElseThrow(() -> new NotFoundException("Tag not found"));
        for (String entityId : entityIds) {
            assertTargetExists(entityType, entityId);
            assignmentRepository.findByTagIdAndEntityTypeAndEntityId(tagId, entityType, entityId)
                    .orElseGet(() -> {
                        TagAssignment a = new TagAssignment();
                        a.setTagId(tagId);
                        a.setEntityType(entityType);
                        a.setEntityId(entityId);
                        a.setAssignedBy(AuthHelper.currentUser().id());
                        return assignmentRepository.save(a);
                    });
        }
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "Tag", tagId,
                Map.of(), Map.of("addedToCount", entityIds.size()),
                Map.of("entityType", entityType.name()));
        // Keep `tag` referenced so static analysis doesn't strip the lookup.
        Objects.requireNonNull(tag);
    }

    /** When a target record is deleted, drop its assignments to avoid orphans. */
    @Transactional
    public void clearForEntity(TaggedEntityType entityType, String entityId) {
        assignmentRepository.deleteByEntityTypeAndEntityId(entityType, entityId);
    }

    // ---------- Read helpers used by mappers ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tagsFor(TaggedEntityType entityType, String entityId) {
        List<TagAssignment> rows = assignmentRepository.findByEntityTypeAndEntityId(entityType, entityId);
        if (rows.isEmpty()) return List.of();
        Set<String> ids = rows.stream().map(TagAssignment::getTagId).collect(Collectors.toSet());
        Map<String, Tag> byId = tagRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Tag::getId, t -> t));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                .map(t -> shortTagMap(t))
                .toList();
    }

    /** Bulk variant — single query for a whole page of records. */
    @Transactional(readOnly = true)
    public Map<String, List<Map<String, Object>>> tagsForBulk(TaggedEntityType entityType, List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return Map.of();
        List<TagAssignment> rows = assignmentRepository.findByEntityTypeAndEntityIdIn(entityType, entityIds);
        if (rows.isEmpty()) return Map.of();
        Set<String> tagIds = rows.stream().map(TagAssignment::getTagId).collect(Collectors.toSet());
        Map<String, Tag> tagsById = tagRepository.findAllById(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, t -> t));
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (TagAssignment a : rows) {
            Tag t = tagsById.get(a.getTagId());
            if (t == null) continue;
            result.computeIfAbsent(a.getEntityId(), k -> new ArrayList<>()).add(shortTagMap(t));
        }
        for (List<Map<String, Object>> list : result.values()) {
            list.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
        }
        return result;
    }

    /** Returns the (entityType, entityId) pairs that have ALL the given tags
     *  — used by list filters that want to narrow by tag. Empty input returns
     *  empty list (interpreted by callers as "no filter"). */
    @Transactional(readOnly = true)
    public List<String> entityIdsTaggedWithAll(TaggedEntityType entityType, List<String> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return List.of();
        Map<String, Integer> hits = new HashMap<>();
        for (String tagId : tagIds) {
            List<String> ids = assignmentRepository.findEntityIdsByTagIdsAndEntityType(
                    List.of(tagId), entityType);
            for (String id : ids) hits.merge(id, 1, Integer::sum);
        }
        int need = tagIds.size();
        return hits.entrySet().stream()
                .filter(e -> e.getValue() >= need)
                .map(Map.Entry::getKey)
                .toList();
    }

    // ---------- Helpers ----------

    private void assertTargetExists(TaggedEntityType entityType, String entityId) {
        boolean exists = switch (entityType) {
            case ENTRY -> entryRepository.findById(entityId).isPresent();
            case EXPENSE -> expenseRepository.findById(entityId).isPresent();
            case SALARY_PAYMENT -> salaryPaymentRepository.findById(entityId).isPresent();
            case MANUAL_DELIVERY -> manualDeliveryRepository.findById(entityId).isPresent();
            case BANK_DEPOSIT -> bankDepositRepository.findById(entityId).isPresent();
            case CARD_SETTLEMENT -> cardSettlementRepository.findById(entityId).isPresent();
        };
        if (!exists) {
            throw new NotFoundException(entityType.name() + " not found: " + entityId);
        }
    }

    private static String requireName(String name) {
        if (name == null) throw new BadRequestException("Tag name is required");
        String trimmed = name.trim();
        if (trimmed.isEmpty()) throw new BadRequestException("Tag name is required");
        if (trimmed.length() > MAX_NAME_LEN) {
            throw new BadRequestException("Tag name too long (max " + MAX_NAME_LEN + " chars)");
        }
        return trimmed;
    }

    private static String normalizeColor(String color) {
        if (color == null || color.isBlank()) return null;
        String c = color.trim();
        if (!HEX_COLOR.matcher(c).matches()) {
            throw new BadRequestException("Color must be a hex value like #2F80ED");
        }
        return c.toLowerCase();
    }

    private static String trimToNull(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Map<String, Object> toMap(Tag t, long usageCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("color", t.getColor());
        m.put("description", t.getDescription());
        m.put("usageCount", usageCount);
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        return m;
    }

    /** Compact form used inside owning-record responses. */
    private static Map<String, Object> shortTagMap(Tag t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("color", t.getColor());
        return m;
    }
}
