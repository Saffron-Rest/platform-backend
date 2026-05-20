package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
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

    public TreasuryService(
            SystemSettingRepository settingRepository,
            DailyEntryRepository entryRepository,
            SalaryPaymentRepository salaryPaymentRepository,
            UserRepository userRepository,
            AuditService auditService,
            ManualDeliveryService manualDeliveryService,
            ExpenseService expenseService) {
        this.settingRepository = settingRepository;
        this.entryRepository = entryRepository;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.manualDeliveryService = manualDeliveryService;
        this.expenseService = expenseService;
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

        // Net contributions (what's actually added to the balance from each source)
        BigDecimal cashFromEntries = cashFromShiftReports.subtract(standaloneCashExpenses);
        BigDecimal cardFromEntries = cardFromShiftReports
                .add(cardFromManualDelivery)
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
        result.put("standaloneCashExpenses", toDouble(standaloneCashExpenses));
        result.put("standaloneCardExpenses", toDouble(standaloneCardExpenses));
        result.put("salaryPaidFromCash", toDouble(salaryCashOut));
        result.put("salaryPaidFromCard", toDouble(salaryCardOut));
        result.put("currency", "PLN");
        return result;
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
