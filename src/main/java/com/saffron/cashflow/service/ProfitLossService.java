package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.ManualDeliveryIncome;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.ExpenseCategory;
import com.saffron.cashflow.domain.ExpenseItem;
import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.domain.WorkShift;
import com.saffron.cashflow.report.ProfitLossTemplate;
import com.saffron.cashflow.domain.SalaryPayment;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.repository.WorkShiftRepository;
import com.saffron.cashflow.util.SalaryPaymentPeriod;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.SalaryCalculator;
import com.saffron.cashflow.util.WeeklyOperatingHours;
import com.saffron.cashflow.web.BadRequestException;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProfitLossService {

    private static final EnumSet<ExpenseCategory> COGS = EnumSet.of(
            ExpenseCategory.SUPPLIER, ExpenseCategory.SUPPLIES);

    private final DailyEntryRepository entryRepository;
    private final UserRepository userRepository;
    private final WorkShiftRepository workShiftRepository;
    private final SettingsService settingsService;
    private final SalaryPaymentRepository salaryPaymentRepository;
    private final ManualDeliveryService manualDeliveryService;
    private final ExpenseService expenseService;
    // Resolves the historical pay rate for a given (user, date) so the
    // accrued-labour total matches the Salaries panel after mid-period
    // pay changes.
    private final PayRateService payRateService;

    public ProfitLossService(
            DailyEntryRepository entryRepository,
            UserRepository userRepository,
            WorkShiftRepository workShiftRepository,
            SettingsService settingsService,
            SalaryPaymentRepository salaryPaymentRepository,
            ManualDeliveryService manualDeliveryService,
            ExpenseService expenseService,
            PayRateService payRateService) {
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
        this.workShiftRepository = workShiftRepository;
        this.settingsService = settingsService;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.manualDeliveryService = manualDeliveryService;
        this.expenseService = expenseService;
        this.payRateService = payRateService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profitAndLoss(
            String fromParam,
            String toParam,
            String templateParam,
            String statusParam,
            boolean includeLabor) {
        AuthHelper.requireOperations();
        LocalDate to = parseDate(toParam, LocalDate.now());
        LocalDate from = parseDate(fromParam, to.withDayOfMonth(1));
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must be on or before 'to'");
        }

        ProfitLossTemplate template = ProfitLossTemplate.parse(templateParam);
        EntryStatus status;
        if (statusParam == null || statusParam.isBlank()) {
            status = EntryStatus.LOCKED;
        } else if ("ALL".equalsIgnoreCase(statusParam)) {
            status = null;
        } else {
            status = EntryStatus.valueOf(statusParam);
        }

        Specification<DailyEntry> spec = EntrySpecification.filter(null, from, to, status);
        List<DailyEntry> entries = entryRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "date"));

        List<DailyEntry> loaded = new ArrayList<>();
        for (DailyEntry e : entries) {
            loaded.add(entryRepository.findActiveByIdWithExpenses(e.getId()).orElse(e));
        }

        RevenueTotals revenue = new RevenueTotals();
        EnumMap<ExpenseCategory, BigDecimal> byCategory = new EnumMap<>(ExpenseCategory.class);
        for (ExpenseCategory c : ExpenseCategory.values()) {
            byCategory.put(c, BigDecimal.ZERO);
        }
        PayoutTotals payouts = new PayoutTotals();

        for (DailyEntry e : loaded) {
            revenue.add(e);
            accumulateExpenses(e, byCategory);
            payouts.add(e);
        }
        for (ManualDeliveryIncome manual : manualDeliveryService.findBetween(from, to)) {
            revenue.addManual(manual);
        }
        for (ExpenseItem standalone : expenseService.findStandaloneBetween(from, to)) {
            ExpenseCategory cat = standalone.getCategory() != null ? standalone.getCategory() : ExpenseCategory.OTHER;
            byCategory.merge(cat, standalone.getAmount(), BigDecimal::add);
        }

        BigDecimal cogs = sumCategories(byCategory, COGS);
        BigDecimal operatingEx = sumCategories(byCategory, allExcept(COGS));
        BigDecimal laborAccrued = includeLabor ? computeLaborAccrued(from, to) : BigDecimal.ZERO;
        BigDecimal laborPaid = includeLabor ? computeLaborPaid(from, to) : BigDecimal.ZERO;
        BigDecimal labor = laborPaid.compareTo(BigDecimal.ZERO) > 0 ? laborPaid : laborAccrued;

        double grossRevenue = EntryCalculator.toDouble(revenue.gross);
        double returns = EntryCalculator.toDouble(revenue.returns);
        double netRevenue = round(grossRevenue - returns);
        double cogsD = EntryCalculator.toDouble(cogs);
        double grossProfit = round(netRevenue - cogsD);
        double opExD = EntryCalculator.toDouble(operatingEx);
        double laborD = EntryCalculator.toDouble(labor);
        double operatingProfit = round(grossProfit - opExD - laborD);
        double distributions = EntryCalculator.toDouble(payouts.ownerWithdrawal);
        double netProfit = round(operatingProfit - distributions);

        Map<String, Object> margins = marginBlock(netRevenue, grossProfit, operatingProfit, netProfit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("template", template.name());
        result.put("templateLabel", templateLabel(template));
        result.put("status", status != null ? status.name() : "ALL");
        result.put("reportCount", loaded.size());
        result.put("generatedAt", Instant.now().toString());
        result.put("currency", "PLN");
        result.put("includeLabor", includeLabor);
        result.put("laborUsesPaidAmounts", includeLabor && laborPaid.compareTo(BigDecimal.ZERO) > 0);
        result.put("laborAccrued", EntryCalculator.toDouble(laborAccrued));
        result.put("laborPaid", EntryCalculator.toDouble(laborPaid));
        result.put("footerNote", footerNote(template));
        result.put("margins", margins);
        result.put("totals", totalsMap(
                grossRevenue, returns, netRevenue, cogsD, grossProfit, opExD, laborD, operatingProfit, distributions, netProfit));
        result.put("expensesByCategory", categoryBreakdown(byCategory));
        result.put("lines", buildLines(
                template, revenue, returns, netRevenue, byCategory, cogsD, grossProfit, laborD, opExD, operatingProfit, payouts, distributions, netProfit));
        return result;
    }

    private List<Map<String, Object>> buildLines(
            ProfitLossTemplate template,
            RevenueTotals revenue,
            double returns,
            double netRevenue,
            EnumMap<ExpenseCategory, BigDecimal> byCategory,
            double cogsD,
            double grossProfit,
            double laborD,
            double opExD,
            double operatingProfit,
            PayoutTotals payouts,
            double distributions,
            double netProfit) {
        List<Map<String, Object>> lines = new ArrayList<>();
        Labels L = Labels.forTemplate(template);

        lines.add(section(L.revenueSection));
        lines.add(line("cash_sales", L.cashSales, EntryCalculator.toDouble(revenue.cash), 1, false));
        lines.add(line("card_sales", L.cardSales, EntryCalculator.toDouble(revenue.card), 1, false));
        if (revenue.platform.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(line("platform_sales", L.platformSales, EntryCalculator.toDouble(revenue.platform), 1, false));
            addPlatformDetail(lines, revenue);
        }
        lines.add(subtotal("gross_revenue", L.grossRevenue, EntryCalculator.toDouble(revenue.gross)));
        if (returns > 0) {
            lines.add(line("returns", L.returns, -returns, 1, false));
        }
        lines.add(subtotal("net_revenue", L.netRevenue, netRevenue));

        lines.add(section(L.cogsSection));
        for (ExpenseCategory c : COGS) {
            double amt = EntryCalculator.toDouble(byCategory.get(c));
            if (amt > 0) {
                lines.add(line("cogs_" + c.name(), categoryLabel(L, c), amt, 1, false));
            }
        }
        lines.add(subtotal("cogs_total", L.cogsTotal, cogsD));
        lines.add(subtotal("gross_profit", L.grossProfit, grossProfit));

        lines.add(section(L.opexSection));
        for (ExpenseCategory c : ExpenseCategory.values()) {
            if (COGS.contains(c)) {
                continue;
            }
            double amt = EntryCalculator.toDouble(byCategory.get(c));
            if (amt > 0) {
                lines.add(line("opex_" + c.name(), categoryLabel(L, c), amt, 1, false));
            }
        }
        if (laborD > 0) {
            lines.add(line("labor", L.labor, laborD, 1, false));
        }
        lines.add(subtotal("operating_expenses", L.opexTotal, opExD + laborD));
        lines.add(subtotal("operating_profit", L.operatingProfit, operatingProfit));

        if (distributions > 0) {
            lines.add(section(L.distributionsSection));
            lines.add(line("owner_withdrawal", L.ownerWithdrawal, distributions, 1, false));
        }

        lines.add(subtotal("net_profit", L.netProfit, netProfit));
        return lines;
    }

    private void addPlatformDetail(List<Map<String, Object>> lines, RevenueTotals revenue) {
        addIfPositive(lines, "wolt", "Wolt", revenue.wolt);
        addIfPositive(lines, "bolt", "Bolt", revenue.bolt);
        addIfPositive(lines, "uber", "Uber Eats", revenue.uber);
        addIfPositive(lines, "glovo", "Glovo", revenue.glovo);
        addIfPositive(lines, "other_platform", "Other platforms", revenue.otherPlatform);
    }

    private static void addIfPositive(List<Map<String, Object>> lines, String key, String label, BigDecimal v) {
        double d = EntryCalculator.toDouble(v);
        if (d > 0) {
            lines.add(line(key, label, d, 2, false));
        }
    }

    private static Map<String, Object> line(String key, String label, double amount, int indent, boolean bold) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("amount", amount);
        m.put("indent", indent);
        m.put("bold", bold);
        m.put("subtotal", false);
        m.put("section", false);
        return m;
    }

    private static Map<String, Object> subtotal(String key, String label, double amount) {
        Map<String, Object> m = line(key, label, amount, 0, true);
        m.put("subtotal", true);
        return m;
    }

    private static Map<String, Object> section(String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "section");
        m.put("label", label);
        m.put("section", true);
        m.put("indent", 0);
        return m;
    }

    private static Map<String, Object> marginBlock(
            double netRevenue, double grossProfit, double operatingProfit, double netProfit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grossProfit", grossProfit);
        m.put("operatingProfit", operatingProfit);
        m.put("netProfit", netProfit);
        m.put("grossMarginPct", pct(grossProfit, netRevenue));
        m.put("operatingMarginPct", pct(operatingProfit, netRevenue));
        m.put("netMarginPct", pct(netProfit, netRevenue));
        return m;
    }

    private static double pct(double profit, double revenue) {
        if (revenue <= 0) {
            return 0.0;
        }
        return round(profit / revenue * 100.0);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Map<String, Object> totalsMap(
            double grossRevenue,
            double returns,
            double netRevenue,
            double cogs,
            double grossProfit,
            double operatingExpenses,
            double labor,
            double operatingProfit,
            double distributions,
            double netProfit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grossRevenue", grossRevenue);
        m.put("returns", returns);
        m.put("netRevenue", netRevenue);
        m.put("cogs", cogs);
        m.put("grossProfit", grossProfit);
        m.put("operatingExpenses", operatingExpenses);
        m.put("labor", labor);
        m.put("operatingProfit", operatingProfit);
        m.put("distributions", distributions);
        m.put("netProfit", netProfit);
        return m;
    }

    private static List<Map<String, Object>> categoryBreakdown(EnumMap<ExpenseCategory, BigDecimal> byCategory) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExpenseCategory c : ExpenseCategory.values()) {
            double amt = EntryCalculator.toDouble(byCategory.get(c));
            if (amt <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c.name());
            row.put("amount", amt);
            row.put("cogs", COGS.contains(c));
            rows.add(row);
        }
        return rows;
    }

    private void accumulateExpenses(DailyEntry e, EnumMap<ExpenseCategory, BigDecimal> byCategory) {
        if (Hibernate.isInitialized(e.getExpenseItems())
                && e.getExpenseItems() != null
                && !e.getExpenseItems().isEmpty()) {
            for (ExpenseItem item : e.getExpenseItems()) {
                ExpenseCategory cat = item.getCategory() != null ? item.getCategory() : ExpenseCategory.OTHER;
                byCategory.merge(cat, item.getAmount(), BigDecimal::add);
            }
            return;
        }
        merge(byCategory, ExpenseCategory.SUPPLIER, e.getSupplierPayments());
        merge(byCategory, ExpenseCategory.PETTY_CASH, e.getPettyCash());
        merge(byCategory, ExpenseCategory.SUPPLIES, e.getSupplies());
        merge(byCategory, ExpenseCategory.STAFF_MEALS, e.getStaffMeals());
        merge(byCategory, ExpenseCategory.DELIVERY, e.getDeliveryCosts());
        merge(byCategory, ExpenseCategory.OTHER, e.getOtherExpenses());
    }

    private static void merge(EnumMap<ExpenseCategory, BigDecimal> map, ExpenseCategory cat, BigDecimal amount) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            map.merge(cat, amount, BigDecimal::add);
        }
    }

    private BigDecimal computeLaborPaid(LocalDate from, LocalDate to) {
        List<SalaryPayment> paid = salaryPaymentRepository.findByPaidDateBetween(from, to);
        return SalaryPaymentPeriod.totalPaidInRange(paid, from, to).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Accrued labour for the period.
     *
     * IMPORTANT: this method MUST agree shift-for-shift with
     * {@link SalaryService#calculate(String, String)} or the P&L "Labor
     * accrued" line will silently disagree with the Salaries panel for
     * the same period — a bug we used to ship every time a manager
     * changed a cashier's rate mid-month.
     *
     * The previous implementation read {@code cashier.getPayType()} /
     * {@code getPayAmount()} directly, which is the CURRENT rate (not
     * the rate that was in effect on each historical shift date). It
     * also bucketed all MONTHLY shifts under one rate, even if the rate
     * changed during the period.
     *
     * We now resolve the per-shift rate through {@link PayRateService}
     * exactly like SalaryService does, and we aggregate MONTHLY days by
     * resolved-rate bucket so the period-prorating maths match.
     */
    private BigDecimal computeLaborAccrued(LocalDate from, LocalDate to) {
        WeeklyOperatingHours hours = settingsService.loadWeeklyHours();
        List<WorkShift> shifts = workShiftRepository.findWorkingBetween(from, to);
        Map<String, List<WorkShift>> byUser = shifts.stream()
                .collect(java.util.stream.Collectors.groupingBy(WorkShift::getUserId));

        BigDecimal grandTotal = BigDecimal.ZERO;
        List<User> cashiers = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.CASHIER)
                .toList();

        for (User cashier : cashiers) {
            List<WorkShift> userShifts = byUser.getOrDefault(cashier.getId(), List.of());
            if (userShifts.isEmpty()) {
                continue;
            }

            // Bucket MONTHLY shifts by the rate that was actually in effect on
            // each date — same approach as SalaryService.java:127-132.
            Map<String, Integer> monthlyDaysByRate = new java.util.LinkedHashMap<>();
            Map<String, BigDecimal> monthlyAmountByRate = new java.util.HashMap<>();
            BigDecimal shiftPaySum = BigDecimal.ZERO;

            for (WorkShift shift : userShifts) {
                PayRateService.ResolvedPay rate =
                        payRateService.resolve(cashier.getId(), shift.getDate(), cashier);
                PayType payType = rate.payType() != null ? rate.payType() : PayType.HOURLY;
                BigDecimal payAmount = rate.payAmount() != null ? rate.payAmount() : BigDecimal.ZERO;

                if (payType == PayType.MONTHLY) {
                    String key = payType.name() + "|" + payAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
                    monthlyDaysByRate.merge(key, 1, Integer::sum);
                    monthlyAmountByRate.putIfAbsent(key, payAmount);
                } else {
                    shiftPaySum = shiftPaySum.add(
                            SalaryCalculator.payForShift(shift, payType, payAmount, hours));
                }
            }

            BigDecimal cashierTotal = shiftPaySum;
            for (Map.Entry<String, Integer> band : monthlyDaysByRate.entrySet()) {
                BigDecimal amount = monthlyAmountByRate.get(band.getKey());
                cashierTotal = cashierTotal.add(
                        SalaryCalculator.monthlyPayForPeriod(band.getValue(), from, to, amount));
            }
            grandTotal = grandTotal.add(cashierTotal);
        }
        return grandTotal.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumCategories(EnumMap<ExpenseCategory, BigDecimal> map, java.util.Set<ExpenseCategory> cats) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ExpenseCategory c : cats) {
            sum = sum.add(map.getOrDefault(c, BigDecimal.ZERO));
        }
        return sum;
    }

    private static java.util.Set<ExpenseCategory> allExcept(java.util.Set<ExpenseCategory> exclude) {
        java.util.Set<ExpenseCategory> set = java.util.EnumSet.allOf(ExpenseCategory.class);
        set.removeAll(exclude);
        return set;
    }

    private static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(s);
    }

    private static String templateLabel(ProfitLossTemplate t) {
        return switch (t) {
            case US -> "US (P&L statement)";
            case EU -> "EU / IFRS-style";
            case PL -> "Poland (RZiS simplified)";
            default -> "Standard";
        };
    }

    private static String footerNote(ProfitLossTemplate t) {
        return switch (t) {
            case US ->
                    "Prepared from daily shift reports. Amounts are accrual-style from cashier entries, not tax filings.";
            case EU ->
                    "Turnover and operating costs from shift data. VAT is not shown separately — consult your accountant for statutory filings.";
            case PL ->
                    "Uproszczony rachunek zysków i strat na podstawie raportów zmianowych. Do JPK i VAT skonsultuj się z księgowym.";
            default -> "Generated automatically from submitted shift reports as transactions are recorded.";
        };
    }

    private static String categoryLabel(Labels L, ExpenseCategory c) {
        return switch (c) {
            case SUPPLIER -> L.supplier;
            case SUPPLIES -> L.supplies;
            case STAFF_MEALS -> L.staffMeals;
            case DELIVERY -> L.delivery;
            case PETTY_CASH -> L.pettyCash;
            case UTILITIES -> L.utilities;
            case CLEANING -> L.cleaning;
            case MAINTENANCE -> L.maintenance;
            case RENT -> L.rent;
            case MARKETING -> L.marketing;
            default -> L.other;
        };
    }

    private static final class RevenueTotals {
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal card = BigDecimal.ZERO;
        BigDecimal wolt = BigDecimal.ZERO;
        BigDecimal bolt = BigDecimal.ZERO;
        BigDecimal uber = BigDecimal.ZERO;
        BigDecimal glovo = BigDecimal.ZERO;
        BigDecimal otherPlatform = BigDecimal.ZERO;
        BigDecimal platform = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal returns = BigDecimal.ZERO;

        void add(DailyEntry e) {
            cash = cash.add(e.getCashSales());
            card = card.add(e.getCardSales());
            wolt = wolt.add(e.getWoltSales());
            bolt = bolt.add(e.getBoltSales());
            uber = uber.add(e.getUberEatsSales());
            glovo = glovo.add(e.getGlovoSales());
            otherPlatform = otherPlatform.add(e.getOtherPlatformSales());
            platform = platform.add(e.getWoltSales()).add(e.getBoltSales()).add(e.getUberEatsSales())
                    .add(e.getGlovoSales()).add(e.getOtherPlatformSales());
            gross = gross.add(EntryCalculator.totalSales(e));
            returns = returns.add(EntryCalculator.totalReturns(e));
        }

        void addManual(ManualDeliveryIncome m) {
            BigDecimal g = m.getGrossAmount();
            gross = gross.add(g);
            platform = platform.add(g);
            switch (m.getPlatform()) {
                case WOLT -> wolt = wolt.add(g);
                case BOLT -> bolt = bolt.add(g);
                case UBER_EATS -> uber = uber.add(g);
                case GLOVO -> glovo = glovo.add(g);
                case OTHER -> otherPlatform = otherPlatform.add(g);
            }
        }
    }

    private static final class PayoutTotals {
        BigDecimal ownerWithdrawal = BigDecimal.ZERO;

        void add(DailyEntry e) {
            ownerWithdrawal = ownerWithdrawal.add(e.getOwnerWithdrawal());
        }
    }

    private static final class Labels {
        final String revenueSection;
        final String cashSales;
        final String cardSales;
        final String platformSales;
        final String grossRevenue;
        final String returns;
        final String netRevenue;
        final String cogsSection;
        final String supplier;
        final String supplies;
        final String cogsTotal;
        final String grossProfit;
        final String opexSection;
        final String labor;
        final String opexTotal;
        final String operatingProfit;
        final String distributionsSection;
        final String ownerWithdrawal;
        final String netProfit;
        final String staffMeals;
        final String delivery;
        final String pettyCash;
        final String utilities;
        final String cleaning;
        final String maintenance;
        final String rent;
        final String marketing;
        final String other;

        Labels(
                String revenueSection,
                String cashSales,
                String cardSales,
                String platformSales,
                String grossRevenue,
                String returns,
                String netRevenue,
                String cogsSection,
                String supplier,
                String supplies,
                String cogsTotal,
                String grossProfit,
                String opexSection,
                String labor,
                String opexTotal,
                String operatingProfit,
                String distributionsSection,
                String ownerWithdrawal,
                String netProfit,
                String staffMeals,
                String delivery,
                String pettyCash,
                String utilities,
                String cleaning,
                String maintenance,
                String rent,
                String marketing,
                String other) {
            this.revenueSection = revenueSection;
            this.cashSales = cashSales;
            this.cardSales = cardSales;
            this.platformSales = platformSales;
            this.grossRevenue = grossRevenue;
            this.returns = returns;
            this.netRevenue = netRevenue;
            this.cogsSection = cogsSection;
            this.supplier = supplier;
            this.supplies = supplies;
            this.cogsTotal = cogsTotal;
            this.grossProfit = grossProfit;
            this.opexSection = opexSection;
            this.labor = labor;
            this.opexTotal = opexTotal;
            this.operatingProfit = operatingProfit;
            this.distributionsSection = distributionsSection;
            this.ownerWithdrawal = ownerWithdrawal;
            this.netProfit = netProfit;
            this.staffMeals = staffMeals;
            this.delivery = delivery;
            this.pettyCash = pettyCash;
            this.utilities = utilities;
            this.cleaning = cleaning;
            this.maintenance = maintenance;
            this.rent = rent;
            this.marketing = marketing;
            this.other = other;
        }

        static Labels forTemplate(ProfitLossTemplate t) {
            return switch (t) {
                case US -> new Labels(
                        "Revenue",
                        "Cash sales",
                        "Card sales",
                        "Delivery platform revenue",
                        "Total revenue",
                        "Returns & allowances",
                        "Net revenue",
                        "Cost of goods sold",
                        "Food & beverage purchases",
                        "Kitchen supplies",
                        "Total COGS",
                        "Gross profit",
                        "Operating expenses",
                        "Payroll & labor",
                        "Total operating expenses",
                        "Operating income",
                        "Owner distributions",
                        "Owner draw",
                        "Net income",
                        "Staff meals",
                        "Delivery fees",
                        "Petty cash",
                        "Utilities",
                        "Cleaning",
                        "Maintenance",
                        "Rent",
                        "Marketing",
                        "Other operating");
                case EU -> new Labels(
                        "Turnover",
                        "Cash turnover",
                        "Card turnover",
                        "Platform turnover",
                        "Gross turnover",
                        "Sales returns",
                        "Net turnover",
                        "Cost of sales",
                        "Suppliers",
                        "Consumables",
                        "Total cost of sales",
                        "Gross margin",
                        "Operating costs",
                        "Staff costs (payroll)",
                        "Total operating costs",
                        "EBIT (operating result)",
                        "Distributions",
                        "Owner withdrawals",
                        "Profit for the period",
                        "Staff meals",
                        "Delivery",
                        "Petty cash",
                        "Utilities",
                        "Cleaning",
                        "Maintenance",
                        "Rent",
                        "Marketing",
                        "Other");
                case PL -> new Labels(
                        "Przychody ze sprzedaży",
                        "Sprzedaż gotówkowa",
                        "Sprzedaż kartą",
                        "Sprzedaż platformy",
                        "Przychody brutto",
                        "Zwroty i korekty",
                        "Przychody netto",
                        "Koszty sprzedanych produktów",
                        "Dostawcy",
                        "Materiały i zaopatrzenie",
                        "Razem KUP",
                        "Zysk brutto ze sprzedaży",
                        "Koszty operacyjne",
                        "Wynagrodzenia",
                        "Razem koszty operacyjne",
                        "Zysk operacyjny",
                        "Wypłaty dla właściciela",
                        "Wypłata właściciela",
                        "Zysk netto",
                        "Posiłki pracownicze",
                        "Dostawy",
                        "Drobne wydatki",
                        "Media",
                        "Sprzątanie",
                        "Konserwacja",
                        "Czynsz",
                        "Marketing",
                        "Inne");
                default -> new Labels(
                        "Revenue",
                        "Cash sales",
                        "Card sales",
                        "Platform sales",
                        "Gross revenue",
                        "Returns & refunds",
                        "Net revenue",
                        "Cost of goods sold",
                        "Supplier payments",
                        "Supplies",
                        "Total COGS",
                        "Gross profit",
                        "Operating expenses",
                        "Labor (payroll)",
                        "Total operating expenses",
                        "Operating profit",
                        "Distributions",
                        "Owner withdrawal",
                        "Net profit",
                        "Staff meals",
                        "Delivery costs",
                        "Petty cash",
                        "Utilities",
                        "Cleaning",
                        "Maintenance",
                        "Rent",
                        "Marketing",
                        "Other");
            };
        }
    }
}
