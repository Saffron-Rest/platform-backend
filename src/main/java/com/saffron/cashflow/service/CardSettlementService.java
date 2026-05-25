package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.CardSettlement;
import com.saffron.cashflow.dto.CardSettlementRequest;
import com.saffron.cashflow.repository.BankDepositLinkRepository;
import com.saffron.cashflow.repository.CardSettlementRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.TreasuryRowKinds;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual card-sales settlement reconciliations. Each entry records what was rung up on
 * card vs what the bank actually credited; the difference adjusts the card / bank treasury
 * balance after the fact.
 */
@Service
public class CardSettlementService {

    private final CardSettlementRepository repository;
    private final BankDepositLinkRepository bankDepositLinkRepository;
    private final AuditService auditService;
    private final TagService tagService;
    private final CommentService commentService;

    public CardSettlementService(
            CardSettlementRepository repository,
            BankDepositLinkRepository bankDepositLinkRepository,
            AuditService auditService,
            @org.springframework.context.annotation.Lazy TagService tagService,
            @org.springframework.context.annotation.Lazy CommentService commentService) {
        this.repository = repository;
        this.bankDepositLinkRepository = bankDepositLinkRepository;
        this.auditService = auditService;
        this.tagService = tagService;
        this.commentService = commentService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String fromParam, String toParam) {
        AuthHelper.requireOperations();
        LocalDate from = LocalDate.parse(fromParam);
        LocalDate to = LocalDate.parse(toParam);
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must be on or before 'to'");
        }
        List<CardSettlement> raw = repository
                .findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to);
        List<String> ids = raw.stream().map(CardSettlement::getId).toList();
        Map<String, List<Map<String, Object>>> tagsByRow =
                tagService.tagsForBulk(com.saffron.cashflow.domain.TaggedEntityType.CARD_SETTLEMENT, ids);
        Map<String, Long> commentsByRow =
                commentService.countByEntities(com.saffron.cashflow.domain.TaggedEntityType.CARD_SETTLEMENT, ids);
        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        for (CardSettlement s : raw) {
            Map<String, Object> m = new LinkedHashMap<>(toMap(s));
            m.put("tags", tagsByRow.getOrDefault(s.getId(), List.of()));
            m.put("commentCount", commentsByRow.getOrDefault(s.getId(), 0L));
            rows.add(m);
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> create(CardSettlementRequest req) {
        AuthHelper.requireOperations();
        String linkedKind = normalize(req.getLinkedKind());
        String linkedRefId = normalize(req.getLinkedRefId());

        // Upsert if a settlement already exists for the linked row
        if (linkedKind != null && linkedRefId != null) {
            // Manually-added rows (Finance > Add Delivery, standalone settlements) are
            // entered with the actual settled amount, so they don't need another
            // reconciliation pass.
            if (TreasuryRowKinds.isAlreadySettled(linkedKind)) {
                throw new BadRequestException(
                        "This row was entered manually with its actual settled amount — "
                                + "no further reconciliation needed.");
            }
            // Mutual exclusion: a row claimed by a bank deposit can't also have an inline override
            bankDepositLinkRepository
                    .findByLinkedKindAndLinkedRefId(linkedKind, linkedRefId)
                    .ifPresent(existing -> {
                        throw new BadRequestException(
                                "Row already settled by a bank deposit on "
                                        + existing.getBankDeposit().getBankDate()
                                        + "; remove that deposit first.");
                    });

            CardSettlement existing = repository
                    .findByLinkedKindAndLinkedRefId(linkedKind, linkedRefId)
                    .orElse(null);
            if (existing != null) {
                Map<String, Object> before = toMap(existing);
                applyRequest(existing, req);
                existing.setLinkedKind(linkedKind);
                existing.setLinkedRefId(linkedRefId);
                existing = repository.save(existing);
                auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE,
                        "CardSettlement", existing.getId(), before, toMap(existing), null);
                return toMap(existing);
            }
        }

        CardSettlement row = new CardSettlement();
        applyRequest(row, req);
        row.setLinkedKind(linkedKind);
        row.setLinkedRefId(linkedRefId);
        row.setCreatedBy(AuthHelper.currentUser().id());
        row = repository.save(row);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE,
                "CardSettlement", row.getId(), Map.of(), toMap(row), null);
        return toMap(row);
    }

    @Transactional
    public Map<String, Object> update(String id, CardSettlementRequest req) {
        AuthHelper.requireOperations();
        CardSettlement row = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Settlement not found"));
        Map<String, Object> before = toMap(row);
        applyRequest(row, req);
        row = repository.save(row);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE,
                "CardSettlement", id, before, toMap(row), null);
        return toMap(row);
    }

    @Transactional
    public void delete(String id) {
        AuthHelper.requireOperations();
        CardSettlement row = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Settlement not found"));
        Map<String, Object> before = toMap(row);
        repository.delete(row);
        tagService.clearForEntity(com.saffron.cashflow.domain.TaggedEntityType.CARD_SETTLEMENT, id);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE,
                "CardSettlement", id, before, Map.of(), null);
    }

    /** Net balance contribution from settlements over the window, kind-aware.
     *
     *  <p>For settlements linked to a "pending" kind (e.g. delivery), the contribution is
     *  the full {@code settledAmount} (the base balance carries no projected value for
     *  pending rows). For counted kinds, only the delta (settled − gross) applies because
     *  the gross is already in the base sum.
     */
    @Transactional(readOnly = true)
    public BigDecimal totalBalanceContributionBetween(LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        for (CardSettlement s :
                repository.findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to)) {
            if (TreasuryRowKinds.isPending(s.getLinkedKind())) {
                total = total.add(s.getSettledAmount());
            } else {
                total = total.add(s.delta());
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** Raw settlement rows for a window (used by treasury ledger to render rows). */
    @Transactional(readOnly = true)
    public List<CardSettlement> findBetween(LocalDate from, LocalDate to) {
        return repository.findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to);
    }

    private void applyRequest(CardSettlement row, CardSettlementRequest req) {
        if (req.getSettledAmount() == null) {
            throw new BadRequestException("Settled amount is required");
        }
        if (req.getSettledAmount().signum() < 0) {
            throw new BadRequestException("Settled amount must be zero or positive");
        }
        BigDecimal gross = req.getGrossAmount() != null ? req.getGrossAmount() : BigDecimal.ZERO;
        if (gross.signum() < 0) {
            throw new BadRequestException("Gross amount must be zero or positive");
        }
        row.setEffectiveDate(LocalDate.parse(req.getEffectiveDate()));
        row.setGrossAmount(gross);
        row.setSettledAmount(req.getSettledAmount());
        row.setNotes(req.getNotes() != null && !req.getNotes().isBlank() ? req.getNotes().trim() : null);
    }

    public static Map<String, Object> toMap(CardSettlement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("effectiveDate", s.getEffectiveDate().toString());
        m.put("grossAmount", s.getGrossAmount().setScale(2, RoundingMode.HALF_UP).doubleValue());
        m.put("settledAmount", s.getSettledAmount().setScale(2, RoundingMode.HALF_UP).doubleValue());
        m.put("delta", s.delta().doubleValue());
        if (s.getLinkedKind() != null) {
            m.put("linkedKind", s.getLinkedKind());
        }
        if (s.getLinkedRefId() != null) {
            m.put("linkedRefId", s.getLinkedRefId());
        }
        if (s.getNotes() != null && !s.getNotes().isBlank()) {
            m.put("notes", s.getNotes());
        }
        if (s.getCreatedAt() != null) {
            m.put("createdAt", s.getCreatedAt().toString());
        }
        return m;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
