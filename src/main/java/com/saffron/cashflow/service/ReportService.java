package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.report.PdfReportBuilder;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.EntryMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReportService {

    private final DailyEntryRepository entryRepository;
    private final AuditService auditService;

    public ReportService(DailyEntryRepository entryRepository, AuditService auditService) {
        this.entryRepository = entryRepository;
        this.auditService = auditService;
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
        List<Map<String, Object>> rows = loaded.stream().map(EntryMapper::toMap).toList();

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
            cardBalance += EntryCalculator.toDouble(EntryCalculator.cardBalance(e));
            if (e.getStatus() == EntryStatus.DRAFT) {
                draftCount++;
            } else if (e.getStatus() == EntryStatus.LOCKED) {
                lockedCount++;
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("sales", sales);
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

    private byte[] exportPdf(List<DailyEntry> entries, DateRange range, String period, Map<String, Object> summary) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("rows");
        return PdfReportBuilder.build(period, range.from(), range.to(), summary, rows);
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
