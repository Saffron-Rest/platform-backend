package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.DeliveryPlatform;
import com.saffron.cashflow.domain.ManualDeliveryIncome;
import com.saffron.cashflow.domain.TaggedEntityType;
import com.saffron.cashflow.dto.ManualDeliveryIncomeRequest;
import com.saffron.cashflow.repository.ManualDeliveryIncomeRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import org.springframework.context.annotation.Lazy;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.ManualDeliverySettlement;
import com.saffron.cashflow.util.TreasurySettings;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ManualDeliveryService {

    private final ManualDeliveryIncomeRepository repository;
    private final SystemSettingRepository settingRepository;
    private final AuditService auditService;
    private final TagService tagService;
    private final CommentService commentService;

    public ManualDeliveryService(
            ManualDeliveryIncomeRepository repository,
            SystemSettingRepository settingRepository,
            AuditService auditService,
            @Lazy TagService tagService,
            @Lazy CommentService commentService) {
        this.repository = repository;
        this.settingRepository = settingRepository;
        this.auditService = auditService;
        this.tagService = tagService;
        this.commentService = commentService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String fromParam, String toParam, List<String> tagIds) {
        AuthHelper.requireOperations();
        LocalDate from = LocalDate.parse(fromParam);
        LocalDate to = LocalDate.parse(toParam);
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must be on or before 'to'");
        }
        TreasurySettings settings = loadSettings();
        List<ManualDeliveryIncome> rows = repository
                .findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to);
        if (tagIds != null && !tagIds.isEmpty()) {
            Set<String> allowed = new HashSet<>(
                    tagService.entityIdsTaggedWithAll(TaggedEntityType.MANUAL_DELIVERY, tagIds));
            rows = rows.stream().filter(r -> allowed.contains(r.getId())).toList();
        }
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(ManualDeliveryIncome::getId).toList();
        Map<String, List<Map<String, Object>>> tagsByRow = tagService.tagsForBulk(
                TaggedEntityType.MANUAL_DELIVERY, ids);
        Map<String, Long> commentsByRow = commentService.countByEntities(
                TaggedEntityType.MANUAL_DELIVERY, ids);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ManualDeliveryIncome r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(toMap(r, settings));
            m.put("tags", tagsByRow.getOrDefault(r.getId(), List.of()));
            m.put("commentCount", commentsByRow.getOrDefault(r.getId(), 0L));
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForDate(String dateParam) {
        LocalDate date = LocalDate.parse(dateParam);
        return list(date.toString(), date.toString(), null);
    }

    @Transactional
    public Map<String, Object> create(ManualDeliveryIncomeRequest req) {
        AuthHelper.requireOperations();
        ManualDeliveryIncome row = new ManualDeliveryIncome();
        applyRequest(row, req);
        row.setCreatedBy(AuthHelper.currentUser().id());
        row = repository.save(row);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "ManualDeliveryIncome", row.getId(),
                Map.of(), toMap(row, loadSettings()), null);
        return toMap(row, loadSettings());
    }

    @Transactional
    public Map<String, Object> update(String id, ManualDeliveryIncomeRequest req) {
        AuthHelper.requireOperations();
        ManualDeliveryIncome row = load(id);
        Map<String, Object> before = toMap(row, loadSettings());
        applyRequest(row, req);
        row = repository.save(row);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "ManualDeliveryIncome", id,
                before, toMap(row, loadSettings()), null);
        return toMap(row, loadSettings());
    }

    @Transactional
    public void delete(String id) {
        AuthHelper.requireOperations();
        ManualDeliveryIncome row = load(id);
        Map<String, Object> before = toMap(row, loadSettings());
        tagService.clearForEntity(TaggedEntityType.MANUAL_DELIVERY, id);
        repository.delete(row);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "ManualDeliveryIncome", id,
                before, Map.of(), null);
    }

    @Transactional(readOnly = true)
    public BigDecimal totalGrossBetween(LocalDate from, LocalDate to) {
        return repository.findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to).stream()
                .map(ManualDeliveryIncome::getGrossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public BigDecimal totalCardCreditBetween(LocalDate from, LocalDate to, TreasurySettings settings) {
        BigDecimal total = BigDecimal.ZERO;
        for (ManualDeliveryIncome row :
                repository.findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to)) {
            total = total.add(ManualDeliverySettlement.settledToCard(row, settings));
        }
        return total;
    }

    @Transactional(readOnly = true)
    public BigDecimal totalCardCreditForDate(LocalDate date, TreasurySettings settings) {
        return totalCardCreditBetween(date, date, settings);
    }

    @Transactional(readOnly = true)
    public Map<String, Double> platformGrossBetween(LocalDate from, LocalDate to) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (DeliveryPlatform p : DeliveryPlatform.values()) {
            out.put(p.name(), 0.0);
        }
        for (ManualDeliveryIncome row :
                repository.findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to)) {
            String key = row.getPlatform().name();
            out.merge(key, EntryCalculator.toDouble(row.getGrossAmount()), Double::sum);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ManualDeliveryIncome> findBetween(LocalDate from, LocalDate to) {
        return repository.findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to);
    }

    private ManualDeliveryIncome load(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Delivery income not found"));
    }

    private void applyRequest(ManualDeliveryIncome row, ManualDeliveryIncomeRequest req) {
        if (req.getGrossAmount() == null || req.getGrossAmount().signum() <= 0) {
            throw new BadRequestException("Gross amount must be greater than zero");
        }
        row.setEffectiveDate(LocalDate.parse(req.getEffectiveDate()));
        try {
            row.setPlatform(DeliveryPlatform.parse(req.getPlatform()));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid platform: " + req.getPlatform());
        }
        row.setGrossAmount(req.getGrossAmount());
        row.setSettledToCard(req.getSettledToCard());
        row.setNotes(req.getNotes());
    }

    private TreasurySettings loadSettings() {
        return settingRepository.findById(TreasurySettings.SETTINGS_KEY)
                .map(s -> TreasurySettings.fromMap(s.getValue()))
                .orElse(new TreasurySettings());
    }

    public static Map<String, Object> toMap(ManualDeliveryIncome row, TreasurySettings settings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("effectiveDate", row.getEffectiveDate().toString());
        m.put("platform", row.getPlatform().name());
        m.put("platformLabel", platformLabel(row.getPlatform()));
        m.put("grossAmount", EntryCalculator.toDouble(row.getGrossAmount()));
        BigDecimal settled = ManualDeliverySettlement.settledToCard(row, settings);
        m.put("settledToCard", EntryCalculator.toDouble(settled));
        m.put("settledOverridden", row.getSettledToCard() != null);
        if (row.getNotes() != null && !row.getNotes().isBlank()) {
            m.put("notes", row.getNotes());
        }
        m.put("createdAt", row.getCreatedAt().toString());
        return m;
    }

    private static String platformLabel(DeliveryPlatform p) {
        return switch (p) {
            case WOLT -> "Wolt";
            case BOLT -> "Bolt";
            case UBER_EATS -> "Uber Eats";
            case GLOVO -> "Glovo";
            case OTHER -> "Other";
        };
    }
}
