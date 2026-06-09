package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.BankDeposit;
import com.saffron.cashflow.domain.BankDepositLink;
import com.saffron.cashflow.domain.CardSettlement;
import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.ExpenseItem;
import com.saffron.cashflow.domain.ManualDeliveryIncome;
import com.saffron.cashflow.domain.OwnerExpense;
import com.saffron.cashflow.domain.OwnerExpenseReimbursement;
import com.saffron.cashflow.domain.PaymentSource;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.SalaryPayment;
import com.saffron.cashflow.domain.SupplierInvoice;
import com.saffron.cashflow.domain.SupplierInvoicePayment;
import com.saffron.cashflow.domain.SystemSetting;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.RecordSalaryPaymentRequest;
import com.saffron.cashflow.dto.TreasurySettingsRequest;
import com.saffron.cashflow.dto.UpdateSalaryPaymentRequest;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.OwnerExpenseReimbursementRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.SupplierInvoicePaymentRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.ManualDeliverySettlement;
import com.saffron.cashflow.util.PlatformSettlement;
import com.saffron.cashflow.util.SalaryPaymentPeriod;
import com.saffron.cashflow.util.TreasuryRowKinds;
import com.saffron.cashflow.util.TreasurySettings;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.hibernate.Hibernate;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TreasuryService {

    private final SystemSettingRepository settingRepository;
    private final DailyEntryRepository entryRepository;
    private final SalaryPaymentRepository salaryPaymentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ManualDeliveryService manualDeliveryService;
    private final ExpenseService expenseService;
    private final CardSettlementService cardSettlementService;
    private final BankDepositService bankDepositService;
    private final TagService tagService;
    private final CommentService commentService;
    // Owner-paid expense reimbursements: when the restaurant pays back
    // an owner from the till (CASH) or from a card account (CARD),
    // that's a real cash-out movement and belongs in this ledger. Bank
    // transfers / cheques / "other" are bookkeeping-only here because
    // the till and card accounts didn't move.
    private final OwnerExpenseReimbursementRepository ownerReimbursementRepository;
    private final SupplierInvoicePaymentRepository supplierInvoicePaymentRepository;

    public TreasuryService(
            SystemSettingRepository settingRepository,
            DailyEntryRepository entryRepository,
            SalaryPaymentRepository salaryPaymentRepository,
            UserRepository userRepository,
            AuditService auditService,
            ManualDeliveryService manualDeliveryService,
            ExpenseService expenseService,
            CardSettlementService cardSettlementService,
            BankDepositService bankDepositService,
            @Lazy TagService tagService,
            @Lazy CommentService commentService,
            OwnerExpenseReimbursementRepository ownerReimbursementRepository,
            SupplierInvoicePaymentRepository supplierInvoicePaymentRepository) {
        this.settingRepository = settingRepository;
        this.entryRepository = entryRepository;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.manualDeliveryService = manualDeliveryService;
        this.expenseService = expenseService;
        this.cardSettlementService = cardSettlementService;
        this.bankDepositService = bankDepositService;
        this.tagService = tagService;
        this.commentService = commentService;
        this.ownerReimbursementRepository = ownerReimbursementRepository;
        this.supplierInvoicePaymentRepository = supplierInvoicePaymentRepository;
    }

    /** Default settlement % — any logged-in user (for shift report form). */
    @Transactional(readOnly = true)
    public Map<String, Object> settlementDefaults() {
        AuthHelper.currentUser();
        TreasurySettings settings = loadSettings();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cardSalesSettlementRate", settings.getCardSalesSettlementRate().doubleValue());
        Map<String, Double> rates = new LinkedHashMap<>();
        settings.getPlatformSettlementRates().forEach((k, v) -> rates.put(k, v.doubleValue()));
        result.put("platformSettlementRates", rates);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        AuthHelper.requireOperations();
        TreasurySettings settings = loadSettings();
        LocalDate from = LocalDate.of(2000, 1, 1);
        LocalDate to = LocalDate.now().plusYears(1);
        List<DailyEntry> entries = entryRepository.findLockedBetweenWithExpenses(from, to, EntryStatus.LOCKED);

        // Raw movements from locked shift reports (positive = inflow into treasury)
        BigDecimal cashFromShiftReports = BigDecimal.ZERO;
        BigDecimal cardFromShiftReports = BigDecimal.ZERO;
        for (DailyEntry e : entries) {
            cashFromShiftReports = cashFromShiftReports.add(cashNetFromEntry(e));
            cardFromShiftReports = cardFromShiftReports.add(EntryCalculator.cardNetForTreasury(e, settings));
        }

        // Manual finance-page entries (standalone expenses + manual delivery income)
        BigDecimal cardFromManualDelivery =
                manualDeliveryService.totalCardCreditBetween(from, to, settings);
        BigDecimal standaloneCashExpenses =
                expenseService.sumStandaloneBetween(from, to, PaymentSource.CASH);
        BigDecimal standaloneCardExpenses =
                expenseService.sumStandaloneBetween(from, to, PaymentSource.CARD);
        // Manual card-settlement reconciliations — kind-aware (delivery contributes full
        // settled amount; non-pending kinds contribute only the variance).
        BigDecimal cardFromManualSettlement =
                cardSettlementService.totalBalanceContributionBetween(from, to);
        // Bank-deposit reconciliations — same kind-aware rule. Delivery shares are added
        // in full (since the base excludes pending delivery); card-sales shares contribute
        // only the variance against their snapshot.
        BigDecimal cardFromBankDeposits =
                bankDepositService.totalBalanceContributionBetween(from, to);

        // Net contributions (what's actually added to the balance from each source)
        BigDecimal cashFromEntries = cashFromShiftReports.subtract(standaloneCashExpenses);
        BigDecimal cardFromEntries = cardFromShiftReports
                .add(cardFromManualDelivery)
                .add(cardFromManualSettlement)
                .add(cardFromBankDeposits)
                .subtract(standaloneCardExpenses);

        // Cash on hand = the most recent locked actual cash count (real drawer).
        // Falls back to initial cash balance if no locked report exists yet.
        Optional<DailyEntry> latestCount = findLatestLockedCount();
        LocalDate cutoff = latestCount.map(DailyEntry::getDate).orElse(null);
        Instant cutoffStamp = latestCount.map(DailyEntry::getSubmittedAt).orElse(null);

        // Anything paid from the drawer AFTER the latest physical count must be
        // subtracted from the displayed "cash on hand" — neither standalone cash
        // expenses (Finance page) nor cash salary payouts are baked into the count.
        // Same-day events are disambiguated by comparing the record's createdAt
        // timestamp to the count's submittedAt — a salary recorded AFTER the
        // count was locked has not yet reduced the drawer, so it counts as
        // post-count even when the calendar dates are equal. This fixes the
        // common "I paid salary today and the balance didn't move" issue.
        BigDecimal standaloneCashExpensesPostCount = BigDecimal.ZERO;
        for (ExpenseItem ex : expenseService.findStandaloneBetween(from, to)) {
            if (ex.getPaymentSource() != PaymentSource.CASH) continue;
            BigDecimal amt = ex.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            if (isAfterCutoff(ex.getEffectiveDate(), ex.getCreatedAt(), cutoff, cutoffStamp)) {
                standaloneCashExpensesPostCount = standaloneCashExpensesPostCount.add(amt);
            }
        }

        // Split salary payouts: any payout dated after the latest physical cash count
        // hasn't been reflected in the drawer yet and must be subtracted from the
        // displayed "cash on hand". Card-side always subtracts all payouts.
        // Payments flagged `excludeFromTreasury` are tracked separately so the
        // treasury card / ledger does NOT move when they are recorded.
        BigDecimal salaryCashOut = BigDecimal.ZERO;
        BigDecimal salaryCashOutPostCount = BigDecimal.ZERO;
        BigDecimal salaryCardOut = BigDecimal.ZERO;
        BigDecimal salaryCashExcluded = BigDecimal.ZERO;
        BigDecimal salaryCardExcluded = BigDecimal.ZERO;
        for (SalaryPayment p : salaryPaymentRepository.findAllByOrderByPaidDateDescCreatedAtDesc()) {
            if (p.isExcludeFromTreasury()) {
                if (p.getPaymentSource() == PaymentSource.CASH) {
                    salaryCashExcluded = salaryCashExcluded.add(p.getAmount());
                } else {
                    salaryCardExcluded = salaryCardExcluded.add(p.getAmount());
                }
                continue;
            }
            if (p.getPaymentSource() == PaymentSource.CASH) {
                salaryCashOut = salaryCashOut.add(p.getAmount());
                if (isAfterCutoff(p.getPaidDate(), p.getCreatedAt(), cutoff, cutoffStamp)) {
                    salaryCashOutPostCount = salaryCashOutPostCount.add(p.getAmount());
                }
            } else {
                salaryCardOut = salaryCardOut.add(p.getAmount());
            }
        }

        // Owner-expense reimbursements: the restaurant pays the owner back from
        // cash or card. Bank/cheque/other are bookkeeping-only and don't move
        // the till or card account, so methodToLedgerSource returns null for them.
        BigDecimal ownerCashOut = BigDecimal.ZERO;
        BigDecimal ownerCashOutPostCount = BigDecimal.ZERO;
        BigDecimal ownerCardOut = BigDecimal.ZERO;
        for (OwnerExpenseReimbursement r : ownerReimbursementRepository.findByPaidDateBetween(from, to)) {
            if (r.getAmount() == null || r.getAmount().signum() <= 0) continue;
            PaymentSource src = methodToLedgerSource(r.getMethod());
            if (src == null) continue;
            if (src == PaymentSource.CASH) {
                ownerCashOut = ownerCashOut.add(r.getAmount());
                if (isAfterCutoff(r.getPaidDate(), r.getCreatedAt(), cutoff, cutoffStamp)) {
                    ownerCashOutPostCount = ownerCashOutPostCount.add(r.getAmount());
                }
            } else {
                ownerCardOut = ownerCardOut.add(r.getAmount());
            }
        }

        // Supplier invoice payments (payables): CASH reduces the drawer,
        // CARD and BANK_TRANSFER reduce the card/bank balance.
        BigDecimal payableCashOut = BigDecimal.ZERO;
        BigDecimal payableCashOutPostCount = BigDecimal.ZERO;
        BigDecimal payableCardOut = BigDecimal.ZERO;
        for (SupplierInvoicePayment p : supplierInvoicePaymentRepository.findByPaymentDateBetween(from, to)) {
            if (p.getAmount() == null || p.getAmount().signum() <= 0) continue;
            PaymentSource src = methodToLedgerSource(p.getMethod());
            if (src == null) continue;
            if (src == PaymentSource.CASH) {
                payableCashOut = payableCashOut.add(p.getAmount());
                if (isAfterCutoff(p.getPaymentDate(), p.getCreatedAt(), cutoff, cutoffStamp)) {
                    payableCashOutPostCount = payableCashOutPostCount.add(p.getAmount());
                }
            } else {
                payableCardOut = payableCardOut.add(p.getAmount());
            }
        }

        BigDecimal cashRaw = latestCount
                .map(DailyEntry::getActualCashCounted)
                .orElse(settings.getInitialCashBalance());
        // Drawer after non-salary post-count outflows — the baseline before
        // we additionally subtract salaries (the UI's "include salary" toggle
        // flips between this baseline and the fully-adjusted balance).
        BigDecimal cashBalanceBeforeSalary = cashRaw
                .subtract(standaloneCashExpensesPostCount)
                .subtract(ownerCashOutPostCount)
                .subtract(payableCashOutPostCount);
        BigDecimal cashBalance = cashBalanceBeforeSalary.subtract(salaryCashOutPostCount);
        // Card balance stays cumulative (no physical count).
        BigDecimal cardBalanceBeforeSalary = settings.getInitialCardBalance().add(cardFromEntries);
        BigDecimal cardBalance = cardBalanceBeforeSalary
                .subtract(salaryCardOut)
                .subtract(ownerCardOut)
                .subtract(payableCardOut);
        // Cumulative cash balance kept for reference / cross-checks.
        BigDecimal cashComputedBalance = settings.getInitialCashBalance()
                .add(cashFromEntries)
                .subtract(salaryCashOut)
                .subtract(ownerCashOut)
                .subtract(payableCashOut);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", settings.toApiMap());
        result.put("cashBalance", toDouble(cashBalance));
        result.put("cardBalance", toDouble(cardBalance));
        // Pre-salary variants so the UI can toggle salary inclusion without re-fetching.
        result.put("cashBalanceBeforeSalary", toDouble(cashBalanceBeforeSalary));
        result.put("cardBalanceBeforeSalary", toDouble(cardBalanceBeforeSalary));
        // Raw drawer count (pre any post-count adjustments) for transparency.
        result.put("cashRawCount", toDouble(cashRaw));
        // Latest-count context for the cash card
        result.put("cashSource", latestCount.isPresent() ? "LATEST_COUNT" : "INITIAL");
        latestCount.ifPresent(e -> {
            result.put("cashLatestCountDate", e.getDate().toString());
            if (e.getCashier() != null) {
                result.put("cashLatestCountCashierName", e.getCashier().getName());
            }
            if (e.getSubmittedAt() != null) {
                result.put("cashLatestCountSubmittedAt", e.getSubmittedAt().toString());
            }
        });
        // Cumulative (computed) cash balance for transparency / audit
        result.put("cashComputedBalance", toDouble(cashComputedBalance));
        // Net values (what UI consumed previously — keep for backward compat)
        result.put("cashFromEntries", toDouble(cashFromEntries));
        result.put("cardFromEntries", toDouble(cardFromEntries));
        // Raw breakdown so the UI can display each component and the math adds up
        result.put("cashFromShiftReports", toDouble(cashFromShiftReports));
        result.put("cardFromShiftReports", toDouble(cardFromShiftReports));
        result.put("cardFromManualDelivery", toDouble(cardFromManualDelivery));
        result.put("cardFromManualSettlement", toDouble(cardFromManualSettlement));
        result.put("cardFromBankDeposits", toDouble(cardFromBankDeposits));
        result.put("standaloneCashExpenses", toDouble(standaloneCashExpenses));
        result.put("standaloneCashExpensesPostCount", toDouble(standaloneCashExpensesPostCount));
        result.put("standaloneCardExpenses", toDouble(standaloneCardExpenses));
        result.put("salaryPaidFromCash", toDouble(salaryCashOut));
        result.put("salaryPaidFromCashPostCount", toDouble(salaryCashOutPostCount));
        result.put("salaryPaidFromCard", toDouble(salaryCardOut));
        // Salary marked as excluded from treasury — tracked for transparency but
        // not subtracted from the displayed balances above.
        result.put("salaryPaidFromCashExcluded", toDouble(salaryCashExcluded));
        result.put("salaryPaidFromCardExcluded", toDouble(salaryCardExcluded));
        result.put("ownerReimbursementCashOut", toDouble(ownerCashOut));
        result.put("ownerReimbursementCashOutPostCount", toDouble(ownerCashOutPostCount));
        result.put("ownerReimbursementCardOut", toDouble(ownerCardOut));
        result.put("currency", "PLN");
        return result;
    }

    /** Chronological ledger of cash or card movements over the given window.
     *  Each row is a signed contribution to the running balance of that source.
     *  Includes an opening balance computed from everything before {@code from}. */
    @Transactional(readOnly = true)
    public Map<String, Object> ledger(String sourceParam, String fromParam, String toParam) {
        AuthHelper.requireOperations();
        PaymentSource source = parseSource(sourceParam);
        LocalDate from = LocalDate.parse(fromParam);
        LocalDate to = LocalDate.parse(toParam);
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must be on or before 'to'");
        }
        TreasurySettings settings = loadSettings();

        BigDecimal initial = source == PaymentSource.CASH
                ? settings.getInitialCashBalance()
                : settings.getInitialCardBalance();

        // Sum movements strictly before `from` to get the opening balance for the window
        BigDecimal opening = initial.add(sumMovementsBefore(source, from, settings));

        List<Map<String, Object>> rows = collectMovements(source, from, to, settings);
        // Oldest first for running balance walk, then we return that order; UI can reverse.
        rows.sort((a, b) -> {
            String da = (String) a.get("date");
            String db = (String) b.get("date");
            int byDate = da.compareTo(db);
            if (byDate != 0) return byDate;
            // Tiebreak: inflows before outflows (so end-of-day balance reflects net activity)
            double sa = signedAmount(a);
            double sb = signedAmount(b);
            return Double.compare(sb, sa);
        });

        BigDecimal running = opening;
        for (Map<String, Object> r : rows) {
            // Pending rows are visible but don't move the balance until reconciled.
            if (!Boolean.TRUE.equals(r.get("pending"))) {
                running = running.add(BigDecimal.valueOf(signedAmount(r)));
            }
            r.put("runningBalance", round2(running).doubleValue());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source.name());
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("openingBalance", round2(opening).doubleValue());
        result.put("closingBalance", round2(running).doubleValue());
        result.put("currency", "PLN");
        result.put("rows", rows);
        return result;
    }

    private PaymentSource parseSource(String s) {
        if (s == null) throw new BadRequestException("source is required (CASH or CARD)");
        try {
            return PaymentSource.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("source must be CASH or CARD");
        }
    }

    /**
     * Map a supplier-/owner-payment method onto the till/card ledger.
     *
     * <p>{@code BANK_TRANSFER} is treated as a card/bank-account movement —
     * the money leaves the restaurant's bank account, which is the same
     * ledger as card payments. {@code CHEQUE} and {@code OTHER} remain
     * bookkeeping-only and do not move any balance.</p>
     */
    private static PaymentSource methodToLedgerSource(SupplierInvoicePayment.PaymentMethod method) {
        if (method == null) return null;
        return switch (method) {
            case CASH -> PaymentSource.CASH;
            case CARD, BANK_TRANSFER -> PaymentSource.CARD;
            case CHEQUE, OTHER -> null;
        };
    }

    private BigDecimal sumMovementsBefore(PaymentSource source, LocalDate from, TreasurySettings settings) {
        if (from.isEqual(LocalDate.MIN)) return BigDecimal.ZERO;
        LocalDate earliest = LocalDate.of(2000, 1, 1);
        LocalDate end = from.minusDays(1);
        if (end.isBefore(earliest)) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : collectMovements(source, earliest, end, settings)) {
            if (Boolean.TRUE.equals(r.get("pending"))) continue;
            total = total.add(BigDecimal.valueOf(signedAmount(r)));
        }
        return total;
    }

    private List<Map<String, Object>> collectMovements(
            PaymentSource source, LocalDate from, LocalDate to, TreasurySettings settings) {
        List<Map<String, Object>> rows = new ArrayList<>();

        // Card-side: pre-load settlements so we can apply linked overrides as rows are built
        Map<String, CardSettlement> linkedSettlements = new HashMap<>();
        List<CardSettlement> standaloneSettlements = new ArrayList<>();
        Map<String, DepositLinkRef> depositLinks = new HashMap<>();
        if (source == PaymentSource.CARD) {
            for (CardSettlement s : cardSettlementService.findBetween(from, to)) {
                if (s.getLinkedKind() != null && s.getLinkedRefId() != null) {
                    linkedSettlements.put(linkKey(s.getLinkedKind(), s.getLinkedRefId()), s);
                } else {
                    standaloneSettlements.add(s);
                }
            }
            for (BankDeposit d : bankDepositService.findIntersecting(from, to)) {
                for (BankDepositLink l : d.getLinks()) {
                    depositLinks.put(
                            linkKey(l.getLinkedKind(), l.getLinkedRefId()),
                            new DepositLinkRef(d, l));
                }
            }
        }

        // 1) Locked shift reports — split into separate income / expense / transfer rows
        List<DailyEntry> entries = entryRepository.findLockedBetweenWithExpenses(from, to, EntryStatus.LOCKED);
        for (DailyEntry e : entries) {
            addShiftRows(rows, e, source, settings);
        }

        // 2) Manual delivery income — only affects the CARD ledger
        if (source == PaymentSource.CARD) {
            for (ManualDeliveryIncome d : manualDeliveryService.findBetween(from, to)) {
                BigDecimal credit = ManualDeliverySettlement.settledToCard(d, settings);
                if (credit.signum() == 0) continue;
                Map<String, Object> row = baseRow(
                        d.getEffectiveDate().toString(),
                        "MANUAL_DELIVERY",
                        "INCOME",
                        "Delivery income · " + d.getPlatform().name(),
                        credit,
                        "+",
                        "/finance",
                        "Open in Finance",
                        d.getId());
                if (d.getNotes() != null && !d.getNotes().isBlank()) {
                    row.put("notes", d.getNotes());
                }
                rows.add(row);
            }

            // 2b) Standalone manual card settlements (no linked row) — surfaced as their own
            //     ledger entries. Linked settlements are applied as inline overrides further below.
            for (CardSettlement s : standaloneSettlements) {
                BigDecimal delta = s.delta();
                if (delta.signum() == 0) continue;
                String sign = delta.signum() < 0 ? "-" : "+";
                String label = s.getGrossAmount().signum() > 0
                        ? "Card settlement · sold "
                                + round2(s.getGrossAmount()).toPlainString() + " → got "
                                + round2(s.getSettledAmount()).toPlainString()
                        : "Card settlement · " + round2(s.getSettledAmount()).toPlainString() + " credited";
                Map<String, Object> row = baseRow(
                        s.getEffectiveDate().toString(),
                        "CARD_SETTLEMENT",
                        "INCOME",
                        label,
                        delta.abs(),
                        sign,
                        "/treasury/history?source=card",
                        "Open settlement",
                        s.getId());
                row.put("grossAmount", round2(s.getGrossAmount()).doubleValue());
                row.put("settledAmount", round2(s.getSettledAmount()).doubleValue());
                if (s.getNotes() != null && !s.getNotes().isBlank()) {
                    row.put("notes", s.getNotes());
                }
                rows.add(row);
            }
        }

        // 3) Standalone (post-close) expenses paid from this source
        for (ExpenseItem ex : expenseService.findStandaloneBetween(from, to)) {
            if (ex.getPaymentSource() != source) continue;
            BigDecimal amt = ex.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            String desc = ex.getDescription();
            String cat = ex.getCategory() != null ? ex.getCategory().name() : "EXPENSE";
            String label = (desc != null && !desc.isBlank() ? desc : cat);
            Map<String, Object> row = baseRow(
                    ex.getEffectiveDate().toString(),
                    "STANDALONE_EXPENSE",
                    "STANDALONE_EXPENSE",
                    label,
                    amt,
                    "-",
                    "/finance",
                    "Open in Finance",
                    ex.getId());
            row.put("expenseCategory", cat);
            rows.add(row);
        }

        // 4) Salary payouts from this source — excluded payments are bookkeeping
        //    only and intentionally do NOT show up as treasury movements.
        for (SalaryPayment p : salaryPaymentRepository.findByPaidDateBetween(from, to)) {
            if (p.getPaymentSource() != source) continue;
            if (p.isExcludeFromTreasury()) continue;
            BigDecimal amt = p.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            String name = userRepository.findById(p.getUserId()).map(User::getName).orElse("Employee");
            Map<String, Object> row = baseRow(
                    p.getPaidDate().toString(),
                    "SALARY_PAYOUT",
                    "SALARY",
                    "Salary · " + name,
                    amt,
                    "-",
                    "/admin/payouts",
                    "Open payouts",
                    p.getId());
            if (p.getNotes() != null && !p.getNotes().isBlank()) {
                row.put("notes", p.getNotes());
            }
            rows.add(row);
        }

        // 5) Owner-expense reimbursements: cash going *out* of the till
        //    (or card account) when the restaurant pays the owner back
        //    for an out-of-pocket expense. Other methods (bank
        //    transfer, cheque, "other") are real money movements but
        //    they happen at the bank, not in the till — they aren't
        //    surfaced on this CASH/CARD ledger.
        for (OwnerExpenseReimbursement r : ownerReimbursementRepository.findByPaidDateBetween(from, to)) {
            PaymentSource ledgerSource = methodToLedgerSource(r.getMethod());
            if (ledgerSource == null || ledgerSource != source) continue;
            BigDecimal amt = r.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            OwnerExpense exp = r.getOwnerExpense();
            String ownerName = exp == null
                    ? "Owner"
                    : userRepository.findById(exp.getOwnerUserId())
                            .map(User::getName)
                            .orElse("Owner");
            String label = "Owner reimbursement · " + ownerName;
            if (exp != null && exp.getDescription() != null && !exp.getDescription().isBlank()) {
                label += " (" + exp.getDescription() + ")";
            }
            String refId = exp != null ? exp.getId() : r.getId();
            Map<String, Object> row = baseRow(
                    r.getPaidDate().toString(),
                    "OWNER_REIMBURSEMENT",
                    "STANDALONE_EXPENSE",
                    label,
                    amt,
                    "-",
                    refId != null
                            ? "/admin/owner-expenses?focus=" + refId
                            : "/admin/owner-expenses",
                    "Open owner expense",
                    refId);
            if (r.getReference() != null && !r.getReference().isBlank()) {
                row.put("notes",
                        (r.getNotes() != null && !r.getNotes().isBlank()
                                ? r.getNotes() + " · "
                                : "")
                                + "Ref " + r.getReference());
            } else if (r.getNotes() != null && !r.getNotes().isBlank()) {
                row.put("notes", r.getNotes());
            }
            rows.add(row);
        }

        // 6) Supplier invoice payments (payables): CASH from the drawer,
        //    CARD/BANK_TRANSFER from the bank account.
        for (SupplierInvoicePayment p : supplierInvoicePaymentRepository.findByPaymentDateBetween(from, to)) {
            PaymentSource ledgerSource = methodToLedgerSource(p.getMethod());
            if (ledgerSource == null || ledgerSource != source) continue;
            BigDecimal amt = p.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            SupplierInvoice inv = p.getInvoice();
            String supplierName = (inv != null && inv.getSupplier() != null)
                    ? inv.getSupplier().getName() : "Supplier";
            String label = "Payable · " + supplierName;
            if (inv != null && inv.getInvoiceNumber() != null && !inv.getInvoiceNumber().isBlank()) {
                label += " (" + inv.getInvoiceNumber() + ")";
            }
            Map<String, Object> row = baseRow(
                    p.getPaymentDate().toString(),
                    "PAYABLE_PAYMENT",
                    "STANDALONE_EXPENSE",
                    label,
                    amt,
                    "-",
                    "/reports?tab=payables",
                    "Open payables",
                    p.getId());
            String notesParts = "";
            if (p.getReference() != null && !p.getReference().isBlank()) {
                notesParts = "Ref " + p.getReference();
            }
            if (p.getNotes() != null && !p.getNotes().isBlank()) {
                notesParts = notesParts.isBlank() ? p.getNotes() : notesParts + " · " + p.getNotes();
            }
            if (!notesParts.isBlank()) row.put("notes", notesParts);
            rows.add(row);
        }

        // Apply linked card-settlement overrides to existing card rows (in place).
        // Each override replaces the row's amount with the actual bank-credited value,
        // and attaches the original snapshot + delta as metadata.
        if (source == PaymentSource.CARD && !linkedSettlements.isEmpty()) {
            for (Map<String, Object> row : rows) {
                applyLinkedSettlementOverride(row, linkedSettlements);
            }
        }

        // Apply bank-deposit overrides. Each linked row's amount is replaced with its
        // pro-rata share of the deposit's totalSettled, and gets deposit-summary metadata.
        if (source == PaymentSource.CARD && !depositLinks.isEmpty()) {
            for (Map<String, Object> row : rows) {
                applyBankDepositOverride(row, depositLinks);
            }
        }

        // Mark unreconciled rows of pending kinds (e.g. delivery) as "pending bank
        // settlement". These rows are visible in the ledger but don't move the running
        // balance until they're reconciled via a BankDeposit or CardSettlement.
        if (source == PaymentSource.CARD) {
            for (Map<String, Object> row : rows) {
                Object kind = row.get("kind");
                if (kind == null) continue;
                if (!TreasuryRowKinds.isPending(kind.toString())) continue;
                if (Boolean.TRUE.equals(row.get("settledOverride"))) continue;
                if (row.get("bankDepositId") != null) continue;
                row.put("pending", true);
            }
        }

        return rows;
    }

    /** Helper holding a deposit + one of its links for fast lookup by (kind, refId). */
    private record DepositLinkRef(BankDeposit deposit, BankDepositLink link) {}

    private static String linkKey(String kind, String refId) {
        return kind + "::" + refId;
    }

    private static void applyBankDepositOverride(
            Map<String, Object> row, Map<String, DepositLinkRef> linked) {
        Object kindObj = row.get("kind");
        Object refIdObj = row.get("refId");
        if (kindObj == null || refIdObj == null) return;
        if ("CARD_SETTLEMENT".equals(kindObj)) return;
        DepositLinkRef ref = linked.get(linkKey(kindObj.toString(), refIdObj.toString()));
        if (ref == null) return;

        // Don't double-override if a per-row CardSettlement already applied (shouldn't
        // happen due to mutex on create, but guard anyway).
        if (Boolean.TRUE.equals(row.get("settledOverride"))) return;

        Object originalAmountObj = row.get("amount");
        if (!(originalAmountObj instanceof Number)) return;
        double originalAmount = ((Number) originalAmountObj).doubleValue();
        BigDecimal share = ref.deposit().shareFor(ref.link());

        row.put("amount", share.doubleValue());
        row.put("bankDepositId", ref.deposit().getId());
        row.put("bankDepositDate", ref.deposit().getBankDate().toString());
        row.put("bankDepositSettled",
                ref.deposit().getTotalSettled().setScale(2, RoundingMode.HALF_UP).doubleValue());
        row.put("bankDepositGross", ref.deposit().totalGross().doubleValue());
        row.put("bankDepositVariance", ref.deposit().variance().doubleValue());
        row.put("bankDepositLinkCount", ref.deposit().getLinks().size());
        row.put("originalAmount", originalAmount);
        if (ref.deposit().getNotes() != null && !ref.deposit().getNotes().isBlank()) {
            row.put("bankDepositNotes", ref.deposit().getNotes());
        }
    }

    private static void applyLinkedSettlementOverride(
            Map<String, Object> row, Map<String, CardSettlement> linked) {
        Object kindObj = row.get("kind");
        Object refIdObj = row.get("refId");
        if (kindObj == null || refIdObj == null) return;
        // Don't allow CARD_SETTLEMENT rows to be re-overridden.
        if ("CARD_SETTLEMENT".equals(kindObj)) return;
        CardSettlement s = linked.get(linkKey(kindObj.toString(), refIdObj.toString()));
        if (s == null) return;

        Object originalAmountObj = row.get("amount");
        if (!(originalAmountObj instanceof Number)) return;
        double originalAmount = ((Number) originalAmountObj).doubleValue();
        double settledValue = s.getSettledAmount().setScale(2, RoundingMode.HALF_UP).doubleValue();

        row.put("amount", settledValue);
        row.put("settledOverride", true);
        row.put("originalAmount", originalAmount);
        row.put("settlementId", s.getId());
        if (s.getGrossAmount() != null && s.getGrossAmount().signum() > 0) {
            row.put("settledGross",
                    s.getGrossAmount().setScale(2, RoundingMode.HALF_UP).doubleValue());
        }
        if (s.getNotes() != null && !s.getNotes().isBlank()) {
            row.put("settledNotes", s.getNotes());
        }
    }

    /** Split a locked shift report into per-line treasury movements for the given source. */
    private void addShiftRows(
            List<Map<String, Object>> rows, DailyEntry e, PaymentSource source, TreasurySettings settings) {
        String date = e.getDate().toString();
        String entryRef = "/entry/" + e.getId();
        String cashierSuffix = e.getCashier() != null ? " · " + e.getCashier().getName() : "";

        if (source == PaymentSource.CASH) {
            addIfPositive(rows, date, "SHIFT_CASH_SALES", "INCOME",
                    "Cash sales" + cashierSuffix, e.getCashSales(), "+", entryRef, e.getId());
            addIfPositive(rows, date, "SHIFT_CASH_REFUND", "INCOME",
                    "Cash refunds" + cashierSuffix, e.getCashRefunds(), "-", entryRef, e.getId());
            addIfPositive(rows, date, "SHIFT_BANK_DEPOSIT", "TRANSFER",
                    "Bank deposit (cash → bank)" + cashierSuffix, e.getBankDeposit(), "-", entryRef, e.getId());
            addIfPositive(rows, date, "SHIFT_CASH_WITHDRAWAL", "TRANSFER",
                    "Cash withdrawal" + cashierSuffix, e.getCashWithdrawal(), "-", entryRef, e.getId());
            addIfPositive(rows, date, "SHIFT_OWNER_WITHDRAWAL", "TRANSFER",
                    "Owner withdrawal" + cashierSuffix, e.getOwnerWithdrawal(), "-", entryRef, e.getId());
            addShiftExpenseRows(rows, e, PaymentSource.CASH, entryRef, cashierSuffix);
        } else {
            BigDecimal cardSales = e.getCardSales();
            BigDecimal settledRate = settings.getCardSalesSettlementRate();
            BigDecimal settledCardSales = cardSales.multiply(settledRate)
                    .setScale(2, RoundingMode.HALF_UP);
            addIfPositive(rows, date, "SHIFT_CARD_SALES_SETTLED", "INCOME",
                    "Card sales settled" + cashierSuffix, settledCardSales, "+", entryRef, e.getId());
            addIfPositive(rows, date, "SHIFT_CARD_REFUND", "INCOME",
                    "Card refunds" + cashierSuffix, e.getCardRefunds(), "-", entryRef, e.getId());
            addIfPositive(rows, date, "SHIFT_PLATFORM_REFUND", "INCOME",
                    "Platform refunds" + cashierSuffix, e.getPlatformRefunds(), "-", entryRef, e.getId());
            addDeliveryRow(rows, e, "wolt", "Wolt", e.getWoltSales(), settings, date, entryRef, cashierSuffix);
            addDeliveryRow(rows, e, "bolt", "Bolt", e.getBoltSales(), settings, date, entryRef, cashierSuffix);
            addDeliveryRow(rows, e, "uberEats", "Uber Eats", e.getUberEatsSales(), settings, date, entryRef, cashierSuffix);
            addDeliveryRow(rows, e, "glovo", "Glovo", e.getGlovoSales(), settings, date, entryRef, cashierSuffix);
            addDeliveryRow(rows, e, "other", "Other delivery", e.getOtherPlatformSales(), settings, date, entryRef, cashierSuffix);
            addIfPositive(rows, date, "SHIFT_BANK_DEPOSIT", "TRANSFER",
                    "Bank deposit (cash → bank)" + cashierSuffix, e.getBankDeposit(), "+", entryRef, e.getId());
            addShiftExpenseRows(rows, e, PaymentSource.CARD, entryRef, cashierSuffix);
        }
    }

    private void addShiftExpenseRows(
            List<Map<String, Object>> rows, DailyEntry e, PaymentSource source,
            String entryRef, String cashierSuffix) {
        if (e.getExpenseItems() == null) return;
        for (ExpenseItem item : e.getExpenseItems()) {
            if (item.getPaymentSource() != source) continue;
            BigDecimal amt = item.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            String desc = item.getDescription();
            String cat = item.getCategory() != null ? item.getCategory().name() : "EXPENSE";
            String label = "Shift expense · " + (desc != null && !desc.isBlank() ? desc : cat) + cashierSuffix;
            Map<String, Object> row = baseRow(
                    e.getDate().toString(),
                    "SHIFT_EXPENSE",
                    "SHIFT_EXPENSE",
                    label,
                    amt,
                    "-",
                    entryRef,
                    "Open shift report",
                    item.getId());
            row.put("expenseCategory", cat);
            rows.add(row);
        }
    }

    private void addDeliveryRow(
            List<Map<String, Object>> rows, DailyEntry e, String platformKey, String platformLabel,
            BigDecimal sales, TreasurySettings settings, String date, String entryRef, String cashierSuffix) {
        if (sales == null || sales.signum() == 0) return;
        BigDecimal settled = PlatformSettlement.settledToCard(e, platformKey, sales, settings);
        if (settled.signum() == 0) return;
        Map<String, Object> row = baseRow(
                date,
                "SHIFT_DELIVERY_SETTLED",
                "INCOME",
                "Delivery · " + platformLabel + " settled" + cashierSuffix,
                settled,
                "+",
                entryRef,
                "Open shift report",
                e.getId());
        row.put("platform", platformKey);
        rows.add(row);
    }

    private static void addIfPositive(
            List<Map<String, Object>> rows, String date, String kind, String category,
            String label, BigDecimal amount, String sign, String refRoute, String refId) {
        if (amount == null || amount.signum() <= 0) return;
        rows.add(baseRow(date, kind, category, label, amount, sign, refRoute, "Open shift report", refId));
    }

    private static Map<String, Object> baseRow(
            String date, String kind, String category, String label, BigDecimal amount,
            String sign, String refRoute, String refLabel, String refId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("kind", kind);
        row.put("category", category);
        row.put("label", label);
        row.put("amount", round2(amount).doubleValue());
        row.put("sign", sign);
        if (refRoute != null) row.put("refRoute", refRoute);
        if (refLabel != null) row.put("refLabel", refLabel);
        if (refId != null) row.put("refId", refId);
        return row;
    }

    private static double signedAmount(Map<String, Object> row) {
        Object amt = row.get("amount");
        if (!(amt instanceof Number n)) return 0.0;
        return "-".equals(row.get("sign")) ? -n.doubleValue() : n.doubleValue();
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns true when a record dated {@code recordDate} (created at
     * {@code recordCreatedAt}) happened strictly AFTER the latest cash count
     * (dated {@code cutoffDate}, locked at {@code cutoffStamp}).
     *
     * <p>Same-day events are disambiguated by comparing the timestamps so a
     * salary recorded after the count was locked correctly counts as post-count
     * even though the calendar date is the same.
     */
    private static boolean isAfterCutoff(
            LocalDate recordDate, Instant recordCreatedAt,
            LocalDate cutoffDate, Instant cutoffStamp) {
        if (cutoffDate == null) return true;
        if (recordDate == null) return false;
        if (recordDate.isAfter(cutoffDate)) return true;
        if (recordDate.isBefore(cutoffDate)) return false;
        // Same calendar date — fall back to timestamps when both are present;
        // otherwise treat as post-count (the most likely real-world workflow:
        // drawer is counted at shift close, then payroll is processed).
        if (recordCreatedAt == null || cutoffStamp == null) return true;
        return recordCreatedAt.isAfter(cutoffStamp);
    }

    /** Latest locked report (any cashier) with an actual cash count > 0. */
    private Optional<DailyEntry> findLatestLockedCount() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Optional<LocalDate> day = entryRepository.findLatestRestaurantCloseDateBefore(tomorrow, EntryStatus.LOCKED);
        if (day.isEmpty()) return Optional.empty();
        List<DailyEntry> rows = entryRepository.findLatestRestaurantCloseOnDate(
                day.get(), EntryStatus.LOCKED, PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Transactional
    public Map<String, Object> updateSettings(TreasurySettingsRequest req) {
        AuthHelper.requireAdminOr(Permission.TREASURY_MANAGE);
        validateRates(req.cardSalesSettlementRate(), req.platformSettlementRates());

        TreasurySettings settings = new TreasurySettings();
        settings.setInitialCashBalance(req.initialCashBalance());
        settings.setInitialCardBalance(req.initialCardBalance());
        settings.setCardSalesSettlementRate(req.cardSalesSettlementRate());
        settings.setPlatformSettlementRates(normalizePlatformRates(req.platformSettlementRates()));

        SystemSetting row = settingRepository.findById(TreasurySettings.SETTINGS_KEY).orElse(new SystemSetting());
        row.setKey(TreasurySettings.SETTINGS_KEY);
        row.setValue(settings.toApiMap());
        settingRepository.save(row);

        auditService.log(AuthHelper.currentUser().id(), AuditAction.UPDATE, "TreasurySettings",
                TreasurySettings.SETTINGS_KEY, settings.toApiMap(), "Treasury settings updated");

        return overview();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSalaryPayments(
            String fromParam, String toParam, String userId, String source, String matchPeriod) {
        AuthHelper.requireAdminOr(Permission.SALARIES_VIEW, Permission.SALARIES_MANAGE);
        List<SalaryPayment> payments;
        if (fromParam != null && toParam != null) {
            LocalDate from = LocalDate.parse(fromParam);
            LocalDate to = LocalDate.parse(toParam);
            if ("payroll".equalsIgnoreCase(matchPeriod)) {
                payments = salaryPaymentRepository.findApplicableToPayrollPeriod(from, to);
            } else {
                payments = salaryPaymentRepository.findByPaidDateBetween(from, to);
            }
        } else {
            payments = salaryPaymentRepository.findAllByOrderByPaidDateDescCreatedAtDesc();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SalaryPayment p : payments) {
            if (userId != null && !userId.isBlank() && !userId.equals(p.getUserId())) {
                continue;
            }
            if (source != null && !source.isBlank()) {
                PaymentSource wanted = PaymentSource.valueOf(source.toUpperCase());
                if (p.getPaymentSource() != wanted) {
                    continue;
                }
            }
            rows.add(paymentToMap(p));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> recordSalaryPayment(RecordSalaryPaymentRequest req) {
        AuthHelper.requireAdminOr(Permission.SALARIES_MANAGE);
        User employee = userRepository.findById(req.userId())
                .orElseThrow(() -> new NotFoundException("Employee not found"));

        boolean excluded = Boolean.TRUE.equals(req.excludeFromTreasury());
        if (!excluded) {
            Map<String, Object> balances = overview();
            double available = req.source() == PaymentSource.CASH
                    ? (Double) balances.get("cashBalance")
                    : (Double) balances.get("cardBalance");
            if (req.amount().doubleValue() > available + 0.005) {
                throw new BadRequestException(
                        "Insufficient " + req.source().name().toLowerCase() + " balance (available "
                                + roundMoney(available) + " PLN)");
            }
        }

        SalaryPayment payment = new SalaryPayment();
        payment.setUserId(employee.getId());
        payment.setAmount(req.amount().setScale(2, RoundingMode.HALF_UP));
        payment.setPaidDate(req.paidDate());
        payment.setPaymentSource(req.source());
        payment.setPeriodFrom(req.periodFrom());
        payment.setPeriodTo(req.periodTo());
        payment.setNotes(req.notes());
        payment.setExcludeFromTreasury(excluded);
        payment.setCreatedBy(AuthHelper.currentUser().id());
        payment = salaryPaymentRepository.save(payment);

        auditService.log(AuthHelper.currentUser().id(), AuditAction.CREATE, "SalaryPayment", payment.getId(),
                paymentToMap(payment),
                "Salary paid from " + req.source().name() + (excluded ? " (excluded from treasury)" : ""));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", paymentToMap(payment));
        result.put("treasury", overview());
        return result;
    }

    @Transactional
    public Map<String, Object> updateSalaryPayment(String id, UpdateSalaryPaymentRequest req) {
        AuthHelper.requireAdminOr(Permission.SALARIES_MANAGE);
        SalaryPayment payment = salaryPaymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Salary payment not found"));
        Map<String, Object> before = paymentToMap(payment);

        PaymentSource targetSource = req.source() != null ? req.source() : payment.getPaymentSource();
        BigDecimal targetAmount = req.amount() != null
                ? req.amount().setScale(2, RoundingMode.HALF_UP)
                : payment.getAmount();
        boolean targetExcluded = req.excludeFromTreasury() != null
                ? req.excludeFromTreasury()
                : payment.isExcludeFromTreasury();

        // Re-validate against treasury only when the target version still affects
        // the balance — excluded payments skip the cash/card availability check.
        if (!targetExcluded) {
            Map<String, Object> balances = overview();
            double available = targetSource == PaymentSource.CASH
                    ? (Double) balances.get("cashBalance")
                    : (Double) balances.get("cardBalance");
            // Restore this payment's current contribution to the available balance,
            // since (when not excluded) it was already subtracted in `overview()`.
            if (!payment.isExcludeFromTreasury() && payment.getPaymentSource() == targetSource) {
                available += payment.getAmount().doubleValue();
            }
            if (targetAmount.doubleValue() > available + 0.005) {
                throw new BadRequestException(
                        "Insufficient " + targetSource.name().toLowerCase() + " balance (available "
                                + roundMoney(available) + " PLN)");
            }
        }

        if (req.amount() != null) payment.setAmount(targetAmount);
        if (req.paidDate() != null) payment.setPaidDate(req.paidDate());
        if (req.source() != null) payment.setPaymentSource(req.source());
        if (req.excludeFromTreasury() != null) payment.setExcludeFromTreasury(req.excludeFromTreasury());
        if (Boolean.TRUE.equals(req.clearPeriod())) {
            payment.setPeriodFrom(null);
            payment.setPeriodTo(null);
        } else {
            if (req.periodFrom() != null) payment.setPeriodFrom(req.periodFrom());
            if (req.periodTo() != null) payment.setPeriodTo(req.periodTo());
        }
        if (Boolean.TRUE.equals(req.clearNotes())) {
            payment.setNotes(null);
        } else if (req.notes() != null) {
            payment.setNotes(req.notes());
        }
        salaryPaymentRepository.save(payment);

        Map<String, Object> after = paymentToMap(payment);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "SalaryPayment",
                payment.getId(), before, after, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", after);
        result.put("treasury", overview());
        return result;
    }

    @Transactional
    public Map<String, Object> deleteSalaryPayment(String id) {
        AuthHelper.requireAdminOr(Permission.SALARIES_MANAGE);
        SalaryPayment payment = salaryPaymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Salary payment not found"));
        Map<String, Object> before = paymentToMap(payment);
        tagService.clearForEntity(com.saffron.cashflow.domain.TaggedEntityType.SALARY_PAYMENT, id);
        salaryPaymentRepository.delete(payment);
        auditService.log(AuthHelper.currentUser().id(), AuditAction.DELETE, "SalaryPayment", id,
                before, "Salary payment removed");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("treasury", overview());
        return result;
    }

    TreasurySettings loadSettings() {
        return settingRepository.findById(TreasurySettings.SETTINGS_KEY)
                .map(s -> TreasurySettings.fromMap(s.getValue()))
                .orElse(new TreasurySettings());
    }

    /** Net cash movement from a locked shift (drawer, not opening float). */
    private static BigDecimal cashNetFromEntry(DailyEntry e) {
        BigDecimal in = e.getCashSales();
        BigDecimal out = e.getCashRefunds()
                .add(e.getBankDeposit())
                .add(e.getCashWithdrawal())
                .add(e.getOwnerWithdrawal());
        if (hasExpenseItems(e)) {
            out = out.add(EntryCalculator.sumExpenseItems(e.getExpenseItems(), PaymentSource.CASH));
        } else {
            out = out.add(EntryCalculator.legacyExpenseFields(e));
        }
        return in.subtract(out);
    }

    private static boolean hasExpenseItems(DailyEntry e) {
        return Hibernate.isInitialized(e.getExpenseItems())
                && e.getExpenseItems() != null
                && !e.getExpenseItems().isEmpty();
    }

    private Map<String, Object> paymentToMap(SalaryPayment p) {
        String name = userRepository.findById(p.getUserId()).map(User::getName).orElse("Unknown");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("userId", p.getUserId());
        m.put("employeeName", name);
        m.put("amount", toDouble(p.getAmount()));
        m.put("paidDate", p.getPaidDate().toString());
        m.put("source", p.getPaymentSource().name());
        if (p.getPeriodFrom() != null) m.put("periodFrom", p.getPeriodFrom().toString());
        if (p.getPeriodTo() != null) m.put("periodTo", p.getPeriodTo().toString());
        if (p.getNotes() != null && !p.getNotes().isBlank()) m.put("notes", p.getNotes());
        m.put("excludeFromTreasury", p.isExcludeFromTreasury());
        m.put("createdAt", p.getCreatedAt().toString());
        m.put("tags", tagService.tagsFor(
                com.saffron.cashflow.domain.TaggedEntityType.SALARY_PAYMENT, p.getId()));
        m.put("commentCount", commentService.countByEntities(
                com.saffron.cashflow.domain.TaggedEntityType.SALARY_PAYMENT,
                List.of(p.getId())).getOrDefault(p.getId(), 0L));
        return m;
    }

    private static void validateRates(BigDecimal cardRate, Map<String, BigDecimal> platformRates) {
        if (cardRate.compareTo(BigDecimal.ZERO) < 0 || cardRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BadRequestException("Card sales settlement rate must be between 0 and 1");
        }
        for (var e : normalizePlatformRates(platformRates).entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) < 0 || e.getValue().compareTo(BigDecimal.ONE) > 0) {
                throw new BadRequestException("Platform rate for " + e.getKey() + " must be between 0 and 1");
            }
        }
    }

    private static Map<String, BigDecimal> normalizePlatformRates(Map<String, BigDecimal> input) {
        Map<String, BigDecimal> base = TreasurySettings.defaultPlatformRates();
        if (input != null) {
            input.forEach((k, v) -> {
                if (v != null) base.put(k, v);
            });
        }
        return base;
    }

    private static double toDouble(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String roundMoney(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
