package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.CardSettlement;
import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.ExpenseItem;
import com.saffron.cashflow.domain.ManualDeliveryIncome;
import com.saffron.cashflow.domain.PaymentSource;
import com.saffron.cashflow.domain.SalaryPayment;
import com.saffron.cashflow.domain.SystemSetting;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.RecordSalaryPaymentRequest;
import com.saffron.cashflow.dto.TreasurySettingsRequest;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.ManualDeliverySettlement;
import com.saffron.cashflow.util.PlatformSettlement;
import com.saffron.cashflow.util.SalaryPaymentPeriod;
import com.saffron.cashflow.util.TreasurySettings;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.hibernate.Hibernate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    public TreasuryService(
            SystemSettingRepository settingRepository,
            DailyEntryRepository entryRepository,
            SalaryPaymentRepository salaryPaymentRepository,
            UserRepository userRepository,
            AuditService auditService,
            ManualDeliveryService manualDeliveryService,
            ExpenseService expenseService,
            CardSettlementService cardSettlementService) {
        this.settingRepository = settingRepository;
        this.entryRepository = entryRepository;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.manualDeliveryService = manualDeliveryService;
        this.expenseService = expenseService;
        this.cardSettlementService = cardSettlementService;
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
        // Manual card-settlement reconciliations (sum of variances). Can be negative.
        BigDecimal cardFromManualSettlement =
                cardSettlementService.totalDeltaBetween(from, to);

        // Net contributions (what's actually added to the balance from each source)
        BigDecimal cashFromEntries = cashFromShiftReports.subtract(standaloneCashExpenses);
        BigDecimal cardFromEntries = cardFromShiftReports
                .add(cardFromManualDelivery)
                .add(cardFromManualSettlement)
                .subtract(standaloneCardExpenses);

        BigDecimal salaryCashOut = BigDecimal.ZERO;
        BigDecimal salaryCardOut = BigDecimal.ZERO;
        for (SalaryPayment p : salaryPaymentRepository.findAllByOrderByPaidDateDescCreatedAtDesc()) {
            if (p.getPaymentSource() == PaymentSource.CASH) {
                salaryCashOut = salaryCashOut.add(p.getAmount());
            } else {
                salaryCardOut = salaryCardOut.add(p.getAmount());
            }
        }

        // Cash on hand = the most recent locked actual cash count (real drawer).
        // Falls back to initial cash balance if no locked report exists yet.
        Optional<DailyEntry> latestCount = findLatestLockedCount();
        BigDecimal cashBalance = latestCount
                .map(DailyEntry::getActualCashCounted)
                .orElse(settings.getInitialCashBalance());
        // Card balance stays cumulative (no physical count).
        BigDecimal cardBalance = settings.getInitialCardBalance()
                .add(cardFromEntries)
                .subtract(salaryCardOut);
        // Cumulative cash balance kept for reference / cross-checks.
        BigDecimal cashComputedBalance = settings.getInitialCashBalance()
                .add(cashFromEntries)
                .subtract(salaryCashOut);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", settings.toApiMap());
        result.put("cashBalance", toDouble(cashBalance));
        result.put("cardBalance", toDouble(cardBalance));
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
        result.put("standaloneCashExpenses", toDouble(standaloneCashExpenses));
        result.put("standaloneCardExpenses", toDouble(standaloneCardExpenses));
        result.put("salaryPaidFromCash", toDouble(salaryCashOut));
        result.put("salaryPaidFromCard", toDouble(salaryCardOut));
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
            running = running.add(BigDecimal.valueOf(signedAmount(r)));
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

    private BigDecimal sumMovementsBefore(PaymentSource source, LocalDate from, TreasurySettings settings) {
        if (from.isEqual(LocalDate.MIN)) return BigDecimal.ZERO;
        LocalDate earliest = LocalDate.of(2000, 1, 1);
        LocalDate end = from.minusDays(1);
        if (end.isBefore(earliest)) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : collectMovements(source, earliest, end, settings)) {
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
        if (source == PaymentSource.CARD) {
            for (CardSettlement s : cardSettlementService.findBetween(from, to)) {
                if (s.getLinkedKind() != null && s.getLinkedRefId() != null) {
                    linkedSettlements.put(linkKey(s.getLinkedKind(), s.getLinkedRefId()), s);
                } else {
                    standaloneSettlements.add(s);
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

        // 4) Salary payouts from this source
        for (SalaryPayment p : salaryPaymentRepository.findByPaidDateBetween(from, to)) {
            if (p.getPaymentSource() != source) continue;
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

        // Apply linked card-settlement overrides to existing card rows (in place).
        // Each override replaces the row's amount with the actual bank-credited value,
        // and attaches the original snapshot + delta as metadata.
        if (source == PaymentSource.CARD && !linkedSettlements.isEmpty()) {
            for (Map<String, Object> row : rows) {
                applyLinkedSettlementOverride(row, linkedSettlements);
            }
        }

        return rows;
    }

    private static String linkKey(String kind, String refId) {
        return kind + "::" + refId;
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
        AuthHelper.requireAdmin();
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
        AuthHelper.requireAdmin();
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
        AuthHelper.requireAdmin();
        User employee = userRepository.findById(req.userId())
                .orElseThrow(() -> new NotFoundException("Employee not found"));

        Map<String, Object> balances = overview();
        double available = req.source() == PaymentSource.CASH
                ? (Double) balances.get("cashBalance")
                : (Double) balances.get("cardBalance");
        if (req.amount().doubleValue() > available + 0.005) {
            throw new BadRequestException(
                    "Insufficient " + req.source().name().toLowerCase() + " balance (available "
                            + roundMoney(available) + " PLN)");
        }

        SalaryPayment payment = new SalaryPayment();
        payment.setUserId(employee.getId());
        payment.setAmount(req.amount().setScale(2, RoundingMode.HALF_UP));
        payment.setPaidDate(req.paidDate());
        payment.setPaymentSource(req.source());
        payment.setPeriodFrom(req.periodFrom());
        payment.setPeriodTo(req.periodTo());
        payment.setNotes(req.notes());
        payment.setCreatedBy(AuthHelper.currentUser().id());
        payment = salaryPaymentRepository.save(payment);

        auditService.log(AuthHelper.currentUser().id(), AuditAction.CREATE, "SalaryPayment", payment.getId(),
                paymentToMap(payment), "Salary paid from " + req.source().name());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", paymentToMap(payment));
        result.put("treasury", overview());
        return result;
    }

    private TreasurySettings loadSettings() {
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
        m.put("createdAt", p.getCreatedAt().toString());
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
