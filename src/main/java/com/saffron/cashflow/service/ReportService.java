package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.report.AnalyticsReportContext;
import com.saffron.cashflow.report.PdfReportBuilder;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.SystemSetting;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.EntryMapper;
import com.saffron.cashflow.util.TreasurySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReportService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportService.class);

    private final DailyEntryRepository entryRepository;
    private final AuditService auditService;
    private final ManualDeliveryService manualDeliveryService;
    private final ExpenseService expenseService;
    private final SystemSettingRepository settingRepository;
    // The analytics PDF aggregates data from many other services. We inject
    // them lazily because some of them ({@link ProfitLossService},
    // {@link TreasuryService}) also depend on {@code ReportService} transitively
    // through shared utilities; lazy injection avoids the circular-dependency
    // pitfall while still letting us call them at export time.
    private final ProfitLossService profitLossService;
    private final TreasuryService treasuryService;
    private final SalaryService salaryService;
    private final MenuAnalyticsService menuAnalyticsService;
    private final MenuEngineService menuEngineService;
    private final AnalyticsService analyticsService;

    public ReportService(
            DailyEntryRepository entryRepository,
            AuditService auditService,
            ManualDeliveryService manualDeliveryService,
            ExpenseService expenseService,
            SystemSettingRepository settingRepository,
            @Lazy ProfitLossService profitLossService,
            @Lazy TreasuryService treasuryService,
            @Lazy SalaryService salaryService,
            @Lazy MenuAnalyticsService menuAnalyticsService,
            @Lazy MenuEngineService menuEngineService,
            @Lazy AnalyticsService analyticsService) {
        this.entryRepository = entryRepository;
        this.auditService = auditService;
        this.manualDeliveryService = manualDeliveryService;
        this.expenseService = expenseService;
        this.settingRepository = settingRepository;
        this.profitLossService = profitLossService;
        this.treasuryService = treasuryService;
        this.salaryService = salaryService;
        this.menuAnalyticsService = menuAnalyticsService;
        this.menuEngineService = menuEngineService;
        this.analyticsService = analyticsService;
    }

    public Map<String, Object> summary(
            String period, String dateStr, String cashierId, String from, String to, String status) {
        AuthHelper.requireOperations();
        DateRange range = resolveRange(period, dateStr, from, to);
        EntryStatus statusFilter = resolveStatusFilter(from, to, status);
        List<DailyEntry> entries = fetchEntries(range, cashierId, statusFilter);
        return buildSummary(period, range, entries);
    }

    public byte[] export(
            String format,
            String period,
            String dateStr,
            String cashierId,
            String from,
            String to,
            String status) {
        AuthHelper.requireOperations();
        DateRange range = resolveRange(period, dateStr, from, to);
        EntryStatus statusFilter = resolveStatusFilter(from, to, status);
        List<DailyEntry> entries = fetchEntries(range, cashierId, statusFilter);
        Map<String, Object> summary = buildSummary(period, range, entries);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("rows");

        byte[] data = switch (format.toLowerCase()) {
            case "csv" -> exportCsv(rows, summary);
            case "excel" -> exportExcel(rows, summary);
            case "pdf" -> exportPdf(entries, range, period, summary);
            default -> throw new IllegalArgumentException("Format must be csv, excel, or pdf");
        };
        Map<String, Object> exportMeta = new LinkedHashMap<>();
        exportMeta.put("period", period);
        exportMeta.put("from", range.from().toString());
        exportMeta.put("to", range.to().toString());
        exportMeta.put("rowCount", rows.size());
        if (cashierId != null) exportMeta.put("cashierId", cashierId);
        if (status != null) exportMeta.put("status", status);
        auditService.logExport(AuthHelper.currentUser().id(), format, exportMeta);
        return data;
    }

    @Transactional(readOnly = true)
    public byte[] exportEntryPdf(String entryId) {
        AuthHelper.requireOperations();
        DailyEntry entry = entryRepository.findActiveByIdWithExpenses(entryId)
                .orElseThrow(() -> new com.saffron.cashflow.web.NotFoundException("Not found"));
        DateRange range = new DateRange(entry.getDate(), entry.getDate());
        List<DailyEntry> entries = List.of(entry);
        Map<String, Object> summary = buildSummary("daily", range, entries);
        byte[] data = exportPdf(entries, range, "daily", summary);
        auditService.logExport(AuthHelper.currentUser().id(), "pdf",
                Map.of("entryId", entryId, "date", entry.getDate().toString()));
        return data;
    }

    private List<DailyEntry> fetchEntries(DateRange range, String cashierId, EntryStatus status) {
        String filterCashier = cashierId != null && !cashierId.isBlank() ? cashierId : null;
        Specification<DailyEntry> spec = EntrySpecification.filter(filterCashier, range.from(), range.to(), status);
        return entryRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "date").and(Sort.by("id")));
    }

    private static EntryStatus resolveStatusFilter(String from, String to, String status) {
        if (status != null && !status.isBlank()) {
            return EntryStatus.valueOf(status);
        }
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            return null;
        }
        return EntryStatus.LOCKED;
    }

    private static DateRange resolveRange(String period, String dateStr, String from, String to) {
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            LocalDate f = LocalDate.parse(from);
            LocalDate t = LocalDate.parse(to);
            if (f.isAfter(t)) {
                throw new com.saffron.cashflow.web.BadRequestException("'from' must be on or before 'to'");
            }
            return new DateRange(f, t);
        }
        return parseRange(period, dateStr);
    }

    private Map<String, Object> buildSummary(String period, DateRange range, List<DailyEntry> entries) {
        List<DailyEntry> loaded = new ArrayList<>();
        for (DailyEntry e : entries) {
            loaded.add(entryRepository.findActiveByIdWithExpenses(e.getId()).orElse(e));
        }
        TreasurySettings treasury = settingRepository.findById(TreasurySettings.SETTINGS_KEY)
                .map(SystemSetting::getValue)
                .map(TreasurySettings::fromMap)
                .orElseGet(TreasurySettings::new);
        List<Map<String, Object>> rows = loaded.stream().map(e -> EntryMapper.toMap(e, treasury)).toList();

        double sales = 0, cashSales = 0, cardSales = 0, returns = 0, expenses = 0, payouts = 0;
        double expectedCash = 0, actualCash = 0, difference = 0, cardBalance = 0;
        int draftCount = 0, lockedCount = 0;
        for (DailyEntry e : loaded) {
            sales += EntryCalculator.toDouble(EntryCalculator.totalSales(e));
            cashSales += EntryCalculator.toDouble(e.getCashSales());
            cardSales += EntryCalculator.toDouble(e.getCardSales());
            returns += EntryCalculator.toDouble(EntryCalculator.totalReturns(e));
            expenses += EntryCalculator.toDouble(EntryCalculator.totalExpenses(e));
            payouts += EntryCalculator.toDouble(EntryCalculator.totalPayouts(e));
            expectedCash += EntryCalculator.toDouble(e.getClosingBalance());
            actualCash += EntryCalculator.toDouble(e.getActualCashCounted());
            difference += EntryCalculator.toDouble(e.getDifference());
            cardBalance += EntryCalculator.toDouble(EntryCalculator.cardNetForTreasury(e, treasury));
            if (e.getStatus() == EntryStatus.DRAFT) {
                draftCount++;
            } else if (e.getStatus() == EntryStatus.LOCKED) {
                lockedCount++;
            }
        }

        BigDecimal manualDelivery = manualDeliveryService.totalGrossBetween(range.from(), range.to());
        double manualDeliveryD = EntryCalculator.toDouble(manualDelivery);
        double manualDeliveryToCard = EntryCalculator.toDouble(
                manualDeliveryService.totalCardCreditBetween(range.from(), range.to(), treasury));
        sales += manualDeliveryD;
        cardBalance += manualDeliveryToCard;

        BigDecimal standaloneExpenses = expenseService.sumStandaloneBetween(range.from(), range.to());
        double standaloneExpensesD = EntryCalculator.toDouble(standaloneExpenses);
        expenses += standaloneExpensesD;

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("sales", sales);
        totals.put("manualDeliverySales", manualDeliveryD);
        totals.put("manualDeliveryToCard", manualDeliveryToCard);
        totals.put("standaloneExpenses", standaloneExpensesD);
        totals.put("cashSales", cashSales);
        totals.put("cardSales", cardSales);
        totals.put("returns", returns);
        totals.put("expenses", expenses);
        totals.put("payouts", payouts);
        totals.put("expectedCash", expectedCash);
        totals.put("actualCash", actualCash);
        totals.put("difference", difference);
        totals.put("cardBalance", cardBalance);
        totals.put("draftCount", draftCount);
        totals.put("lockedCount", lockedCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("from", range.from().toString());
        result.put("to", range.to().toString());
        result.put("rows", rows);
        result.put("totals", totals);
        result.put("count", rows.size());
        return result;
    }

    private byte[] exportCsv(List<Map<String, Object>> rows, Map<String, Object> summary) {
        StringBuilder sb = new StringBuilder("Date,Cashier,Opening,Cash Sales,Card Sales,Total Sales,Returns,Expenses,Closing,Actual,Difference\n");
        for (Map<String, Object> e : rows) {
            sb.append(e.get("date")).append(",");
            sb.append(cashierName(e)).append(",");
            sb.append(e.get("openingBalance")).append(",");
            sb.append(e.get("cashSales")).append(",");
            sb.append(e.get("cardSales")).append(",");
            sb.append(totalSalesFromMap(e)).append(",");
            sb.append(totalReturnsFromMap(e)).append(",");
            sb.append(totalExpensesFromMap(e)).append(",");
            sb.append(e.get("closingBalance")).append(",");
            sb.append(e.get("actualCashCounted")).append(",");
            sb.append(e.get("difference")).append("\n");
        }
        return sb.toString().getBytes();
    }

    private byte[] exportExcel(List<Map<String, Object>> rows, Map<String, Object> summary) throws RuntimeException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Report");
            Row header = sheet.createRow(0);
            String[] cols = {"Date", "Cashier", "Opening", "Cash", "Card", "Wolt", "Bolt", "Uber", "Glovo", "Other", "Returns", "Expenses", "Closing", "Actual", "Diff"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            int r = 1;
            for (Map<String, Object> e : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(String.valueOf(e.get("date")));
                row.createCell(1).setCellValue(cashierName(e));
                row.createCell(2).setCellValue(((Number) e.get("openingBalance")).doubleValue());
                row.createCell(3).setCellValue(((Number) e.get("cashSales")).doubleValue());
                row.createCell(4).setCellValue(((Number) e.get("cardSales")).doubleValue());
                row.createCell(5).setCellValue(((Number) e.get("woltSales")).doubleValue());
                row.createCell(6).setCellValue(((Number) e.get("boltSales")).doubleValue());
                row.createCell(7).setCellValue(((Number) e.get("uberEatsSales")).doubleValue());
                row.createCell(8).setCellValue(((Number) e.get("glovoSales")).doubleValue());
                row.createCell(9).setCellValue(((Number) e.get("otherPlatformSales")).doubleValue());
                row.createCell(10).setCellValue(totalReturnsFromMap(e));
                row.createCell(11).setCellValue(totalExpensesFromMap(e));
                row.createCell(12).setCellValue(((Number) e.get("closingBalance")).doubleValue());
                row.createCell(13).setCellValue(((Number) e.get("actualCashCounted")).doubleValue());
                row.createCell(14).setCellValue(((Number) e.get("difference")).doubleValue());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] exportPdf(List<DailyEntry> entries, DateRange range, String period, Map<String, Object> summary) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("rows");

        // Build a prior-period summary of equal length so the cover KPIs can
        // show vs-prior deltas. Single-shift exports don't get this — there's
        // no meaningful "prior" for a one-day window of one cashier.
        Map<String, Object> priorSummary = null;
        LocalDate priorFrom = null, priorTo = null;
        boolean singleShift = entries.size() == 1 && range.from().equals(range.to());
        if (!singleShift) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
            priorTo = range.from().minusDays(1);
            priorFrom = priorTo.minusDays(days - 1);
            try {
                List<DailyEntry> priorEntries = fetchEntries(
                        new DateRange(priorFrom, priorTo), null, EntryStatus.LOCKED);
                priorSummary = buildSummary(period, new DateRange(priorFrom, priorTo), priorEntries);
            } catch (Exception ex) {
                LOG.warn("Prior-period summary skipped: {}", ex.getMessage());
            }
        }

        // Each of the optional sections may legitimately fail (insufficient
        // permissions, no menu data, no POS integration). We swallow per-
        // section failures so a single missing piece doesn't blow up the
        // whole export.
        Map<String, Object> pnl = safeCall("profit & loss", () ->
                profitLossService.profitAndLoss(
                        range.from().toString(), range.to().toString(),
                        "PL", "LOCKED", true));
        Map<String, Object> treasury = safeCall("treasury overview", treasuryService::overview);
        // Payroll requires admin; managers can't see it. Skip silently in
        // that case so the rest of the PDF still renders.
        Map<String, Object> payroll = !singleShift ? safeCall("payroll", () ->
                salaryService.calculate(range.from().toString(), range.to().toString())) : null;
        Map<String, Object> menu = !singleShift ? safeCall("menu analytics", () ->
                menuAnalyticsService.compute(range.from(), range.to())) : null;
        Map<String, Object> menuEng = !singleShift ? safeCall("menu engineering", () ->
                menuEngineService.compute(range.from(), range.to())) : null;

        // Every standalone expense in the period (i.e. expenses not tied to
        // a shift report). These get rendered as a "Detailed expense ledger"
        // section so the owner can see every line item — what the previous
        // PDF was missing.
        List<Map<String, Object>> standalone = safeCall("standalone expenses", () -> {
            List<com.saffron.cashflow.domain.ExpenseItem> items =
                    expenseService.findStandaloneBetween(range.from(), range.to());
            List<Map<String, Object>> out = new ArrayList<>(items.size());
            for (var ex : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                LocalDate d = ex.getEffectiveDate();
                m.put("date", d != null ? d.toString() : null);
                m.put("category", ex.getCategory() != null ? ex.getCategory().name() : null);
                m.put("description", ex.getDescription());
                m.put("amount", EntryCalculator.toDouble(ex.getAmount()));
                m.put("paymentSource", ex.getPaymentSource() != null ? ex.getPaymentSource().name() : null);
                m.put("standalone", true);
                out.add(m);
            }
            return out;
        });

        AnalyticsReportContext ctx = new AnalyticsReportContext(
                period, range.from(), range.to(),
                summary, rows,
                priorSummary, priorFrom, priorTo,
                pnl, treasury, payroll, menu, menuEng,
                standalone);
        return PdfReportBuilder.build(ctx);
    }

    /**
     * Run an optional aggregator and log-and-swallow any error so a missing
     * section never blocks the rest of the export. Returns {@code null} on
     * failure — {@link PdfReportBuilder} treats null sections as "skip".
     */
    private static <T> T safeCall(String label, java.util.function.Supplier<T> fn) {
        try {
            return fn.get();
        } catch (Exception ex) {
            LOG.info("PDF export: skipping {} section ({})", label, ex.getMessage());
            return null;
        }
    }

    private static String cashierName(Map<String, Object> e) {
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) e.get("cashier");
        return c != null ? String.valueOf(c.get("name")) : "";
    }

    private static double totalSalesFromMap(Map<String, Object> e) {
        return num(e, "cashSales") + num(e, "cardSales") + num(e, "woltSales") + num(e, "boltSales")
                + num(e, "uberEatsSales") + num(e, "glovoSales") + num(e, "otherPlatformSales");
    }

    private static double totalReturnsFromMap(Map<String, Object> e) {
        return num(e, "cashRefunds") + num(e, "cardRefunds") + num(e, "platformRefunds");
    }

    private static double totalExpensesFromMap(Map<String, Object> e) {
        return num(e, "bankDeposit") + num(e, "cashWithdrawal") + num(e, "ownerWithdrawal")
                + num(e, "supplierPayments") + num(e, "pettyCash") + num(e, "supplies")
                + num(e, "staffMeals") + num(e, "deliveryCosts") + num(e, "otherExpenses");
    }

    private static double num(Map<String, Object> e, String key) {
        return ((Number) e.getOrDefault(key, 0)).doubleValue();
    }

    private static DateRange parseRange(String period, String dateStr) {
        LocalDate base = dateStr != null && !dateStr.isBlank() ? LocalDate.parse(dateStr) : LocalDate.now();
        LocalDate from = base;
        LocalDate to = base;
        if ("weekly".equals(period)) {
            DayOfWeek dow = from.getDayOfWeek();
            int diff = dow == DayOfWeek.SUNDAY ? 6 : dow.getValue() - 1;
            from = from.minusDays(diff);
            to = from.plusDays(6);
        } else if ("monthly".equals(period)) {
            from = base.withDayOfMonth(1);
            to = base.withDayOfMonth(base.lengthOfMonth());
        }
        return new DateRange(from, to);
    }

    private record DateRange(LocalDate from, LocalDate to) {}
}
