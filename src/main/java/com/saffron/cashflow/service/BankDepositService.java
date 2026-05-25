package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.BankDeposit;
import com.saffron.cashflow.domain.BankDepositLink;
import com.saffron.cashflow.dto.BankDepositRequest;
import com.saffron.cashflow.repository.BankDepositLinkRepository;
import com.saffron.cashflow.repository.BankDepositRepository;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bank deposit reconciliations. Each {@link BankDeposit} bundles one or more card-side
 * source rows (shift card sales, delivery settlements, manual delivery income) under a
 * single bank credit so the variance between sold and credited is captured once.
 */
@Service
public class BankDepositService {

    private final BankDepositRepository depositRepository;
    private final BankDepositLinkRepository linkRepository;
    private final CardSettlementRepository cardSettlementRepository;
    private final AuditService auditService;
    private final TagService tagService;
    private final CommentService commentService;

    public BankDepositService(
            BankDepositRepository depositRepository,
            BankDepositLinkRepository linkRepository,
            CardSettlementRepository cardSettlementRepository,
            AuditService auditService,
            @org.springframework.context.annotation.Lazy TagService tagService,
            @org.springframework.context.annotation.Lazy CommentService commentService) {
        this.depositRepository = depositRepository;
        this.linkRepository = linkRepository;
        this.cardSettlementRepository = cardSettlementRepository;
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
        // Show deposits whose bankDate OR any linkedDate falls in window so a deposit
        // booked next month doesn't suddenly hide today's linked rows.
        Set<String> seen = new HashSet<>();
        List<BankDeposit> rows = new ArrayList<>();
        for (BankDeposit d : depositRepository.findByBankDateBetween(from, to)) {
            if (seen.add(d.getId())) rows.add(d);
        }
        for (BankDeposit d : depositRepository.findByLinkedDateBetween(from, to)) {
            if (seen.add(d.getId())) rows.add(d);
        }
        // Bulk-fetch tags + comment counts once per page so each row doesn't
        // make its own query.
        List<String> ids = rows.stream().map(BankDeposit::getId).toList();
        Map<String, List<Map<String, Object>>> tagsByDeposit =
                tagService.tagsForBulk(com.saffron.cashflow.domain.TaggedEntityType.BANK_DEPOSIT, ids);
        Map<String, Long> commentsByDeposit =
                commentService.countByEntities(com.saffron.cashflow.domain.TaggedEntityType.BANK_DEPOSIT, ids);

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (BankDeposit d : rows) {
            Map<String, Object> m = new LinkedHashMap<>(toMap(d));
            m.put("tags", tagsByDeposit.getOrDefault(d.getId(), List.of()));
            m.put("commentCount", commentsByDeposit.getOrDefault(d.getId(), 0L));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(BankDepositRequest req) {
        AuthHelper.requireOperations();
        if (req.getLinks() == null || req.getLinks().isEmpty()) {
            throw new BadRequestException("At least one row must be selected");
        }

        // Mutex: no link can already be claimed by another deposit, and no CardSettlement
        // can exist for the same (kind, refId) — otherwise we'd double-override.
        Set<String> linkKeys = new HashSet<>();
        for (BankDepositRequest.LinkPayload l : req.getLinks()) {
            String key = l.getLinkedKind() + "::" + l.getLinkedRefId();
            if (!linkKeys.add(key)) {
                throw new BadRequestException("Duplicate row in selection: " + key);
            }
            // Manually-added rows (Finance > Add Delivery, standalone settlements) are
            // already settled at entry time and can't be batched into a bank deposit.
            if (TreasuryRowKinds.isAlreadySettled(l.getLinkedKind())) {
                throw new BadRequestException(
                        "This row was entered manually with its actual settled amount — "
                                + "no further reconciliation needed.");
            }
            linkRepository.findByLinkedKindAndLinkedRefId(l.getLinkedKind(), l.getLinkedRefId())
                    .ifPresent(existing -> {
                        throw new BadRequestException(
                                "Row already settled by bank deposit on "
                                        + existing.getBankDeposit().getBankDate());
                    });
            cardSettlementRepository
                    .findByLinkedKindAndLinkedRefId(l.getLinkedKind(), l.getLinkedRefId())
                    .ifPresent(existing -> {
                        throw new BadRequestException(
                                "Row already has an inline 'Settle to actual' override; remove it first.");
                    });
        }

        BankDeposit deposit = new BankDeposit();
        deposit.setBankDate(LocalDate.parse(req.getBankDate()));
        deposit.setTotalSettled(req.getTotalSettled());
        deposit.setNotes(normalize(req.getNotes()));
        deposit.setCreatedBy(AuthHelper.currentUser().id());

        for (BankDepositRequest.LinkPayload l : req.getLinks()) {
            BankDepositLink link = new BankDepositLink();
            link.setLinkedKind(l.getLinkedKind());
            link.setLinkedRefId(l.getLinkedRefId());
            link.setLinkedDate(LocalDate.parse(l.getLinkedDate()));
            link.setGrossAmount(l.getGrossAmount() != null ? l.getGrossAmount() : BigDecimal.ZERO);
            deposit.addLink(link);
        }

        deposit = depositRepository.save(deposit);
        Map<String, Object> result = toMap(deposit);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE,
                "BankDeposit", deposit.getId(), Map.of(), result, null);
        return result;
    }

    @Transactional
    public void delete(String id) {
        AuthHelper.requireOperations();
        BankDeposit d = depositRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Bank deposit not found"));
        Map<String, Object> before = toMap(d);
        depositRepository.delete(d);
        tagService.clearForEntity(com.saffron.cashflow.domain.TaggedEntityType.BANK_DEPOSIT, id);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE,
                "BankDeposit", id, before, Map.of(), null);
    }

    /** Total balance contribution of deposits in the window — kind-aware per link.
     *
     *  <p>For links of "pending" kinds (delivery), the contribution is the full pro-rata
     *  share (since the underlying delivery row is not in the base card balance until
     *  reconciled). For counted kinds (card sales settled, refunds, etc.), only the
     *  variance (settled share − gross snapshot) is added because the base already
     *  includes the gross amount.
     */
    @Transactional(readOnly = true)
    public BigDecimal totalBalanceContributionBetween(LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        Set<String> seen = new HashSet<>();
        for (BankDeposit d : depositRepository.findByBankDateBetween(from, to)) {
            if (seen.add(d.getId())) total = total.add(contributionOf(d));
        }
        for (BankDeposit d : depositRepository.findByLinkedDateBetween(from, to)) {
            if (seen.add(d.getId())) total = total.add(contributionOf(d));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal contributionOf(BankDeposit d) {
        BigDecimal contribution = BigDecimal.ZERO;
        for (BankDepositLink l : d.getLinks()) {
            BigDecimal share = d.shareFor(l);
            if (TreasuryRowKinds.isPending(l.getLinkedKind())) {
                contribution = contribution.add(share);
            } else {
                contribution = contribution.add(share.subtract(l.getGrossAmount()));
            }
        }
        return contribution;
    }

    /** All deposits intersecting the window (by bankDate OR any linkedDate). */
    @Transactional(readOnly = true)
    public List<BankDeposit> findIntersecting(LocalDate from, LocalDate to) {
        Set<String> seen = new HashSet<>();
        List<BankDeposit> out = new ArrayList<>();
        for (BankDeposit d : depositRepository.findByBankDateBetween(from, to)) {
            if (seen.add(d.getId())) out.add(d);
        }
        for (BankDeposit d : depositRepository.findByLinkedDateBetween(from, to)) {
            if (seen.add(d.getId())) out.add(d);
        }
        return out;
    }

    public static Map<String, Object> toMap(BankDeposit d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("bankDate", d.getBankDate().toString());
        m.put("totalSettled", d.getTotalSettled().setScale(2, RoundingMode.HALF_UP).doubleValue());
        m.put("totalGross", d.totalGross().doubleValue());
        m.put("variance", d.variance().doubleValue());
        m.put("linkCount", d.getLinks().size());
        if (d.getNotes() != null) m.put("notes", d.getNotes());
        if (d.getCreatedAt() != null) m.put("createdAt", d.getCreatedAt().toString());
        List<Map<String, Object>> links = new ArrayList<>();
        for (BankDepositLink l : d.getLinks()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.getId());
            lm.put("linkedKind", l.getLinkedKind());
            lm.put("linkedRefId", l.getLinkedRefId());
            lm.put("linkedDate", l.getLinkedDate().toString());
            lm.put("grossAmount", l.getGrossAmount().setScale(2, RoundingMode.HALF_UP).doubleValue());
            lm.put("share", d.shareFor(l).doubleValue());
            links.add(lm);
        }
        m.put("links", links);
        return m;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
