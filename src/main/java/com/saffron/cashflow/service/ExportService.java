package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.ExpenseItemRepository;
import com.saffron.cashflow.repository.ManualDeliveryIncomeRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.ManualDeliverySettlement;
import com.saffron.cashflow.util.TreasurySettings;
import com.saffron.cashflow.web.BadRequestException;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exports for the main list pages.
 *
 * Two output paths:
 *  - CSV / XLSX: flat tabular dumps (good for spreadsheet pivots).
 *  - PDF: structured management reports with brand header, KPI summary,
 *    grouped sections, subtotals and grand totals, page-number footer.
 *    The point is to read like an actual statement, not a CSV pasted into
 *    a frame.
 */
@Service
public class ExportService {

    // Brand palette pulled from the frontend tokens.
    private static final Color BRAND_INK = new Color(0x1D, 0x1B, 0x16);
    private static final Color BRAND_SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color BRAND_CREAM = new Color(0xFA, 0xF4, 0xE8);
    private static final Color ZEBRA = new Color(0xF8, 0xF7, 0xF3);
    private static final Color MUTED = new Color(0x5C, 0x55, 0x4A);
    private static final Color GRID_LINE = new Color(0xE2, 0xDD, 0xD2);

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final DailyEntryRepository entryRepository;
    private final ExpenseItemRepository expenseRepository;
    private final SalaryPaymentRepository salaryPaymentRepository;
    private final ManualDeliveryIncomeRepository manualDeliveryRepository;
    private final UserRepository userRepository;
    private final SystemSettingRepository settingRepository;

    public ExportService(
            DailyEntryRepository entryRepository,
            ExpenseItemRepository expenseRepository,
            SalaryPaymentRepository salaryPaymentRepository,
            ManualDeliveryIncomeRepository manualDeliveryRepository,
            UserRepository userRepository,
            SystemSettingRepository settingRepository) {
        this.entryRepository = entryRepository;
        this.expenseRepository = expenseRepository;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.manualDeliveryRepository = manualDeliveryRepository;
        this.userRepository = userRepository;
        this.settingRepository = settingRepository;
    }

    public enum Format {
        CSV, XLSX, PDF;
        public static Format parse(String s) {
            if (s == null || s.isBlank()) return CSV;
            return switch (s.trim().toLowerCase()) {
                case "csv" -> CSV;
                case "xlsx", "excel" -> XLSX;
                case "pdf" -> PDF;
                default -> throw new BadRequestException("Unsupported format: " + s);
            };
        }
    }

    public record ExportFilters(
            String type,
            Format format,
            LocalDate from,
            LocalDate to,
            String cashierId,
            String paymentSource,
            String platform) {}

    public record ExportResult(String filename, byte[] body, String contentType) {}

    @Transactional(readOnly = true)
    public ExportResult render(ExportFilters f) {
        AuthHelper.requireOperations();
        if (f.type() == null) throw new BadRequestException("type is required");
        return switch (f.type()) {
            case "expenses" -> renderExpenses(f);
            case "entries", "reports" -> renderEntries(f);
            case "payouts" -> renderPayouts(f);
            case "deliveries", "delivery" -> renderDeliveries(f);
            default -> throw new BadRequestException("Unsupported export type: " + f.type());
        };
    }

    // ========================================================================
    // EXPENSES
    // ========================================================================

    private ExportResult renderExpenses(ExportFilters f) {
        LocalDate from = f.from() != null ? f.from() : LocalDate.now().withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        List<ExpenseItem> rows = expenseRepository.findByEffectiveDateBetweenWithInvoices(from, to);
        if (f.paymentSource() != null && !f.paymentSource().isBlank()) {
            PaymentSource ps = PaymentSource.valueOf(f.paymentSource().toUpperCase());
            rows = rows.stream().filter(r -> r.getPaymentSource() == ps).toList();
        }
        Format format = f.format() == null ? Format.CSV : f.format();
        if (format == Format.PDF) {
            return buildExpensesPdf(rows, from, to);
        }
        // Flat CSV/XLSX
        List<String> headers = List.of(
                "Date", "Category", "Description", "Amount (PLN)", "Source",
                "Standalone", "Shift report", "Invoice count");
        List<Function<ExpenseItem, Object>> cols = List.of(
                ExpenseItem::getEffectiveDate,
                e -> e.getCategory() == null ? "" : categoryLabel(e.getCategory()),
                e -> nullSafe(e.getDescription()),
                ExpenseItem::getAmount,
                e -> e.getPaymentSource() == null ? "" : sourceLabel(e.getPaymentSource()),
                e -> e.getEntry() == null ? "Yes" : "No",
                e -> e.getEntry() != null ? e.getEntry().getDate() : "",
                e -> e.getInvoices() == null ? 0 : e.getInvoices().size());
        return flat("expenses", from, to, format, headers, rows, cols);
    }

    private ExportResult buildExpensesPdf(List<ExpenseItem> rows, LocalDate from, LocalDate to) {
        // KPI totals
        BigDecimal totalSpend = sum(rows, ExpenseItem::getAmount);
        BigDecimal cashSpend = sum(rows.stream()
                .filter(e -> e.getPaymentSource() == PaymentSource.CASH).toList(),
                ExpenseItem::getAmount);
        BigDecimal cardSpend = sum(rows.stream()
                .filter(e -> e.getPaymentSource() == PaymentSource.CARD).toList(),
                ExpenseItem::getAmount);

        List<Kpi> kpis = List.of(
                kpi("Total spend", money(totalSpend)),
                kpi("Cash", money(cashSpend)),
                kpi("Card", money(cardSpend)),
                kpi("Items", String.valueOf(rows.size())));

        // Group by category, ordered by section total desc
        Map<ExpenseCategory, List<ExpenseItem>> grouped = rows.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getCategory() == null ? ExpenseCategory.OTHER : e.getCategory(),
                        LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<ExpenseCategory, List<ExpenseItem>>> sections = grouped.entrySet().stream()
                .sorted((a, b) -> sum(b.getValue(), ExpenseItem::getAmount)
                        .compareTo(sum(a.getValue(), ExpenseItem::getAmount)))
                .toList();

        // Columns: Date | Description | Source | Standalone | Amount
        float[] widths = {2.2f, 5.5f, 1.6f, 1.6f, 2.1f};
        List<String> headers = List.of("Date", "Description", "Source", "Standalone", "Amount");
        int[] alignments = {
                Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT,
                Element.ALIGN_LEFT, Element.ALIGN_RIGHT
        };

        PdfReport report = new PdfReport("Expenses report", from, to, kpis,
                "Grouped by category, ordered by spend (highest first).");
        report.build(doc -> {
            for (var entry : sections) {
                ExpenseCategory cat = entry.getKey();
                List<ExpenseItem> items = entry.getValue().stream()
                        .sorted(Comparator.comparing(ExpenseItem::getEffectiveDate))
                        .toList();
                BigDecimal subtotal = sum(items, ExpenseItem::getAmount);
                report.sectionHeader(categoryLabel(cat), items.size() + " item" + (items.size() == 1 ? "" : "s"), money(subtotal));
                PdfPTable table = report.tableStart(widths, headers, alignments);
                int i = 0;
                for (ExpenseItem e : items) {
                    Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
                    report.bodyCell(table, dateShort(e.getEffectiveDate()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, descriptionFor(e), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, sourceLabel(e.getPaymentSource()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, e.getEntry() == null ? "Post-close" : "On report", Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, money(e.getAmount()), Element.ALIGN_RIGHT, bg);
                }
                report.subtotalRow(table, "Subtotal · " + categoryLabel(cat), money(subtotal), widths.length, widths.length - 1);
                doc.add(table);
                report.spacer(doc, 8f);
            }
            report.grandTotal(doc, "Grand total", money(totalSpend));
        });

        return report.finish("expenses", from, to);
    }

    // ========================================================================
    // SHIFT REPORTS
    // ========================================================================

    private ExportResult renderEntries(ExportFilters f) {
        LocalDate from = f.from() != null ? f.from() : LocalDate.now().withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        List<DailyEntry> rows = entryRepository.findAll().stream()
                .filter(e -> e.getDeletedAt() == null)
                .filter(e -> !e.getDate().isBefore(from) && !e.getDate().isAfter(to))
                .filter(e -> f.cashierId() == null || f.cashierId().isBlank()
                        || f.cashierId().equals(e.getCashierId()))
                .sorted(Comparator.comparing(DailyEntry::getDate).reversed())
                .toList();
        Format format = f.format() == null ? Format.CSV : f.format();
        if (format == Format.PDF) {
            return buildEntriesPdf(rows, from, to);
        }
        List<String> headers = List.of(
                "Date", "Cashier", "Status", "Cash sales", "Card sales",
                "Wolt", "Bolt", "Uber Eats", "Glovo", "Other platforms",
                "Opening balance", "Actual cash counted", "Difference",
                "Submitted at");
        List<Function<DailyEntry, Object>> cols = List.of(
                DailyEntry::getDate,
                e -> e.getCashier() != null ? e.getCashier().getName() : "",
                e -> e.getStatus() == null ? "" : e.getStatus().name(),
                DailyEntry::getCashSales,
                DailyEntry::getCardSales,
                DailyEntry::getWoltSales,
                DailyEntry::getBoltSales,
                DailyEntry::getUberEatsSales,
                DailyEntry::getGlovoSales,
                DailyEntry::getOtherPlatformSales,
                DailyEntry::getOpeningBalance,
                DailyEntry::getActualCashCounted,
                DailyEntry::getDifference,
                e -> e.getSubmittedAt() == null ? "" : e.getSubmittedAt().toString());
        return flat("shift-reports", from, to, format, headers, rows, cols);
    }

    private ExportResult buildEntriesPdf(List<DailyEntry> rows, LocalDate from, LocalDate to) {
        BigDecimal totalCash = sum(rows, DailyEntry::getCashSales);
        BigDecimal totalCard = sum(rows, DailyEntry::getCardSales);
        BigDecimal totalDelivery = rows.stream()
                .map(e -> nz(e.getWoltSales()).add(nz(e.getBoltSales()))
                        .add(nz(e.getUberEatsSales())).add(nz(e.getGlovoSales()))
                        .add(nz(e.getOtherPlatformSales())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netDifference = sum(rows, DailyEntry::getDifference);

        List<Kpi> kpis = List.of(
                kpi("Cash sales", money(totalCash)),
                kpi("Card sales", money(totalCard)),
                kpi("Delivery", money(totalDelivery)),
                kpi("Net cash diff.", money(netDifference)));

        // Group by cashier — gives one section per person.
        Map<String, List<DailyEntry>> byCashier = rows.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getCashier() != null ? e.getCashier().getName() : "Unknown",
                        TreeMap::new, Collectors.toList()));

        float[] widths = {2.3f, 1.4f, 1.8f, 1.8f, 1.8f, 1.8f};
        List<String> headers = List.of("Date", "Status", "Cash", "Card", "Delivery", "Cash diff.");
        int[] alignments = {
                Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_RIGHT,
                Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT
        };

        PdfReport report = new PdfReport("Shift reports", from, to, kpis,
                "Grouped by cashier. Delivery column is the sum of all platforms (Wolt + Bolt + Uber + Glovo + other).");
        report.build(doc -> {
            for (var entry : byCashier.entrySet()) {
                List<DailyEntry> items = entry.getValue().stream()
                        .sorted(Comparator.comparing(DailyEntry::getDate))
                        .toList();
                BigDecimal cashierCash = sum(items, DailyEntry::getCashSales);
                BigDecimal cashierCard = sum(items, DailyEntry::getCardSales);
                BigDecimal cashierDeliv = items.stream()
                        .map(e -> nz(e.getWoltSales()).add(nz(e.getBoltSales()))
                                .add(nz(e.getUberEatsSales())).add(nz(e.getGlovoSales()))
                                .add(nz(e.getOtherPlatformSales())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal cashierDiff = sum(items, DailyEntry::getDifference);
                BigDecimal cashierTotal = cashierCash.add(cashierCard).add(cashierDeliv);

                report.sectionHeader(entry.getKey(),
                        items.size() + " shift" + (items.size() == 1 ? "" : "s"),
                        money(cashierTotal));
                PdfPTable table = report.tableStart(widths, headers, alignments);
                int i = 0;
                for (DailyEntry e : items) {
                    Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
                    BigDecimal deliv = nz(e.getWoltSales()).add(nz(e.getBoltSales()))
                            .add(nz(e.getUberEatsSales())).add(nz(e.getGlovoSales()))
                            .add(nz(e.getOtherPlatformSales()));
                    report.bodyCell(table, dateShort(e.getDate()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, statusLabel(e.getStatus()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, money(e.getCashSales()), Element.ALIGN_RIGHT, bg);
                    report.bodyCell(table, money(e.getCardSales()), Element.ALIGN_RIGHT, bg);
                    report.bodyCell(table, money(deliv), Element.ALIGN_RIGHT, bg);
                    report.bodyCell(table, signedMoney(e.getDifference()), Element.ALIGN_RIGHT, bg);
                }
                // Subtotal row with all numeric columns filled in
                report.subtotalRowMulti(table,
                        "Subtotal · " + entry.getKey(),
                        widths.length,
                        new int[]{2, 3, 4, 5},
                        new String[]{money(cashierCash), money(cashierCard), money(cashierDeliv), signedMoney(cashierDiff)});
                doc.add(table);
                report.spacer(doc, 8f);
            }
            // Grand total row stacked
            report.grandTotalMulti(doc, "Grand total",
                    new String[]{"Cash " + money(totalCash), "Card " + money(totalCard),
                            "Delivery " + money(totalDelivery), "Net diff " + signedMoney(netDifference)});
        });
        return report.finish("shift-reports", from, to);
    }

    // ========================================================================
    // PAYOUTS
    // ========================================================================

    private ExportResult renderPayouts(ExportFilters f) {
        LocalDate from = f.from() != null ? f.from() : LocalDate.now().withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        Map<String, String> names = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, u -> u.getName() == null ? "" : u.getName()));
        List<SalaryPayment> rows = salaryPaymentRepository.findAll().stream()
                .filter(p -> !p.getPaidDate().isBefore(from) && !p.getPaidDate().isAfter(to))
                .filter(p -> f.cashierId() == null || f.cashierId().isBlank()
                        || f.cashierId().equals(p.getUserId()))
                .filter(p -> f.paymentSource() == null || f.paymentSource().isBlank()
                        || p.getPaymentSource() == PaymentSource.valueOf(f.paymentSource().toUpperCase()))
                .sorted(Comparator.comparing(SalaryPayment::getPaidDate).reversed())
                .toList();
        Format format = f.format() == null ? Format.CSV : f.format();
        if (format == Format.PDF) {
            return buildPayoutsPdf(rows, names, from, to);
        }
        List<String> headers = List.of(
                "Paid date", "Employee", "Amount (PLN)", "Source",
                "Period from", "Period to", "Excluded from treasury", "Notes");
        List<Function<SalaryPayment, Object>> cols = List.of(
                SalaryPayment::getPaidDate,
                p -> names.getOrDefault(p.getUserId(), ""),
                SalaryPayment::getAmount,
                p -> p.getPaymentSource() == null ? "" : sourceLabel(p.getPaymentSource()),
                p -> p.getPeriodFrom() == null ? "" : p.getPeriodFrom(),
                p -> p.getPeriodTo() == null ? "" : p.getPeriodTo(),
                p -> p.isExcludeFromTreasury() ? "Yes" : "No",
                p -> nullSafe(p.getNotes()));
        return flat("payouts", from, to, format, headers, rows, cols);
    }

    private ExportResult buildPayoutsPdf(List<SalaryPayment> rows, Map<String, String> names,
                                          LocalDate from, LocalDate to) {
        BigDecimal totalPaid = sum(rows, SalaryPayment::getAmount);
        BigDecimal cashPaid = sum(rows.stream()
                .filter(p -> p.getPaymentSource() == PaymentSource.CASH).toList(),
                SalaryPayment::getAmount);
        BigDecimal cardPaid = sum(rows.stream()
                .filter(p -> p.getPaymentSource() == PaymentSource.CARD).toList(),
                SalaryPayment::getAmount);
        BigDecimal excludedAmount = sum(rows.stream()
                .filter(SalaryPayment::isExcludeFromTreasury).toList(),
                SalaryPayment::getAmount);
        long distinctEmployees = rows.stream().map(SalaryPayment::getUserId).distinct().count();

        List<Kpi> kpis = List.of(
                kpi("Total paid", money(totalPaid)),
                kpi("Cash", money(cashPaid)),
                kpi("Card", money(cardPaid)),
                kpi("Employees", String.valueOf(distinctEmployees)));

        Map<String, List<SalaryPayment>> byUser = rows.stream()
                .collect(Collectors.groupingBy(SalaryPayment::getUserId,
                        LinkedHashMap::new, Collectors.toList()));

        List<Map.Entry<String, List<SalaryPayment>>> sections = byUser.entrySet().stream()
                .sorted((a, b) -> sum(b.getValue(), SalaryPayment::getAmount)
                        .compareTo(sum(a.getValue(), SalaryPayment::getAmount)))
                .toList();

        float[] widths = {2.0f, 1.4f, 2.6f, 1.8f, 1.6f, 1.8f};
        List<String> headers = List.of("Paid", "Source", "Period", "Excluded?", "Notes", "Amount");
        int[] alignments = {
                Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT,
                Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_RIGHT
        };

        String subtitle = excludedAmount.signum() > 0
                ? "Grouped by employee. Items marked Excluded? = Yes don't move the treasury balance."
                : "Grouped by employee, ordered by total paid (highest first).";
        PdfReport report = new PdfReport("Payouts statement", from, to, kpis, subtitle);
        report.build(doc -> {
            for (var entry : sections) {
                List<SalaryPayment> items = entry.getValue().stream()
                        .sorted(Comparator.comparing(SalaryPayment::getPaidDate).reversed())
                        .toList();
                BigDecimal subtotal = sum(items, SalaryPayment::getAmount);
                String employee = names.getOrDefault(entry.getKey(), "Unknown");
                report.sectionHeader(employee,
                        items.size() + " payment" + (items.size() == 1 ? "" : "s"),
                        money(subtotal));
                PdfPTable table = report.tableStart(widths, headers, alignments);
                int i = 0;
                for (SalaryPayment p : items) {
                    Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
                    report.bodyCell(table, dateShort(p.getPaidDate()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, sourceLabel(p.getPaymentSource()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, periodLabel(p.getPeriodFrom(), p.getPeriodTo()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, p.isExcludeFromTreasury() ? "Yes" : "No", Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, truncate(p.getNotes(), 60), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, money(p.getAmount()), Element.ALIGN_RIGHT, bg);
                }
                report.subtotalRow(table, "Subtotal · " + employee, money(subtotal), widths.length, widths.length - 1);
                doc.add(table);
                report.spacer(doc, 8f);
            }
            report.grandTotal(doc, "Total paid out", money(totalPaid));
        });
        return report.finish("payouts", from, to);
    }

    // ========================================================================
    // DELIVERIES
    // ========================================================================

    private ExportResult renderDeliveries(ExportFilters f) {
        LocalDate from = f.from() != null ? f.from() : LocalDate.now().withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        TreasurySettings settings = loadSettings();
        List<ManualDeliveryIncome> rows = manualDeliveryRepository
                .findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to).stream()
                .filter(r -> f.platform() == null || f.platform().isBlank()
                        || f.platform().equalsIgnoreCase(r.getPlatform().name()))
                .toList();
        Format format = f.format() == null ? Format.CSV : f.format();
        if (format == Format.PDF) {
            return buildDeliveriesPdf(rows, settings, from, to);
        }
        List<String> headers = List.of(
                "Date", "Platform", "Gross (PLN)", "Settled to card (PLN)",
                "Settlement overridden", "Notes");
        List<Function<ManualDeliveryIncome, Object>> cols = List.of(
                ManualDeliveryIncome::getEffectiveDate,
                r -> r.getPlatform().name(),
                ManualDeliveryIncome::getGrossAmount,
                r -> EntryCalculator.toDouble(ManualDeliverySettlement.settledToCard(r, settings)),
                r -> r.getSettledToCard() != null ? "Yes" : "No",
                r -> nullSafe(r.getNotes()));
        return flat("delivery-income", from, to, format, headers, rows, cols);
    }

    private ExportResult buildDeliveriesPdf(List<ManualDeliveryIncome> rows,
                                             TreasurySettings settings,
                                             LocalDate from, LocalDate to) {
        BigDecimal totalGross = sum(rows, ManualDeliveryIncome::getGrossAmount);
        BigDecimal totalSettled = rows.stream()
                .map(r -> ManualDeliverySettlement.settledToCard(r, settings))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = totalGross.subtract(totalSettled);

        List<Kpi> kpis = List.of(
                kpi("Total gross", money(totalGross)),
                kpi("Settled to card", money(totalSettled)),
                kpi("Commission", money(commission)),
                kpi("Items", String.valueOf(rows.size())));

        Map<String, List<ManualDeliveryIncome>> byPlatform = rows.stream()
                .collect(Collectors.groupingBy(r -> r.getPlatform().name(),
                        LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<String, List<ManualDeliveryIncome>>> sections = byPlatform.entrySet().stream()
                .sorted((a, b) -> sum(b.getValue(), ManualDeliveryIncome::getGrossAmount)
                        .compareTo(sum(a.getValue(), ManualDeliveryIncome::getGrossAmount)))
                .toList();

        float[] widths = {2.2f, 1.4f, 2.0f, 2.0f, 2.4f};
        List<String> headers = List.of("Date", "Override?", "Gross", "Settled", "Notes");
        int[] alignments = {
                Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_RIGHT,
                Element.ALIGN_RIGHT, Element.ALIGN_LEFT
        };

        PdfReport report = new PdfReport("Delivery income", from, to, kpis,
                "Grouped by platform, ordered by gross income. Settled = what hits the bank after platform commission.");
        report.build(doc -> {
            for (var entry : sections) {
                List<ManualDeliveryIncome> items = entry.getValue().stream()
                        .sorted(Comparator.comparing(ManualDeliveryIncome::getEffectiveDate))
                        .toList();
                BigDecimal gross = sum(items, ManualDeliveryIncome::getGrossAmount);
                BigDecimal settled = items.stream()
                        .map(r -> ManualDeliverySettlement.settledToCard(r, settings))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                report.sectionHeader(entry.getKey(),
                        items.size() + " item" + (items.size() == 1 ? "" : "s"),
                        money(gross));
                PdfPTable table = report.tableStart(widths, headers, alignments);
                int i = 0;
                for (ManualDeliveryIncome r : items) {
                    Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
                    BigDecimal rowSettled = ManualDeliverySettlement.settledToCard(r, settings);
                    report.bodyCell(table, dateShort(r.getEffectiveDate()), Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, r.getSettledToCard() != null ? "Yes" : "No", Element.ALIGN_LEFT, bg);
                    report.bodyCell(table, money(r.getGrossAmount()), Element.ALIGN_RIGHT, bg);
                    report.bodyCell(table, money(rowSettled), Element.ALIGN_RIGHT, bg);
                    report.bodyCell(table, truncate(r.getNotes(), 60), Element.ALIGN_LEFT, bg);
                }
                // Subtotal across gross + settled columns
                report.subtotalRowMulti(table, "Subtotal · " + entry.getKey(), widths.length,
                        new int[]{2, 3}, new String[]{money(gross), money(settled)});
                doc.add(table);
                report.spacer(doc, 8f);
            }
            report.grandTotalMulti(doc, "Grand total",
                    new String[]{"Gross " + money(totalGross), "Settled " + money(totalSettled),
                            "Commission " + money(commission)});
        });
        return report.finish("delivery-income", from, to);
    }

    // ========================================================================
    // FLAT CSV / XLSX (unchanged behavior)
    // ========================================================================

    private <T> ExportResult flat(String slug, LocalDate from, LocalDate to,
                                   Format format, List<String> headers, List<T> rows,
                                   List<Function<T, Object>> cols) {
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (T row : rows) {
            List<Object> values = new ArrayList<>(cols.size());
            for (Function<T, Object> col : cols) values.add(col.apply(row));
            data.add(values);
        }
        return switch (format == null ? Format.CSV : format) {
            case CSV -> csv(slug, from, to, headers, data);
            case XLSX -> xlsx(slug, from, to, headers, data);
            case PDF -> throw new IllegalStateException("PDF should be built via per-type renderer");
        };
    }

    private ExportResult csv(String slug, LocalDate from, LocalDate to,
                              List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(joinCells(headers.stream().map(Object.class::cast).toList())).append("\r\n");
        for (List<Object> row : rows) sb.append(joinCells(row)).append("\r\n");
        return new ExportResult(slug + "_" + from + "_" + to + ".csv",
                sb.toString().getBytes(StandardCharsets.UTF_8),
                "text/csv; charset=utf-8");
    }

    private ExportResult xlsx(String slug, LocalDate from, LocalDate to,
                               List<String> headers, List<List<Object>> rows) {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet(slug);
            org.apache.poi.ss.usermodel.CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                org.apache.poi.ss.usermodel.Cell c = headerRow.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(headerStyle);
            }
            for (int r = 0; r < rows.size(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) writeXlsxCell(row.createCell(c), values.get(c));
            }
            for (int i = 0; i < headers.size(); i++) sheet.autoSizeColumn(i);
            sheet.createFreezePane(0, 1);
            wb.write(bos);
            return new ExportResult(slug + "_" + from + "_" + to + ".xlsx", bos.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (Exception e) {
            throw new RuntimeException("Excel export failed", e);
        }
    }

    private static void writeXlsxCell(org.apache.poi.ss.usermodel.Cell cell, Object value) {
        if (value == null) { cell.setBlank(); return; }
        if (value instanceof Number n) { cell.setCellValue(n.doubleValue()); return; }
        if (value instanceof Boolean b) { cell.setCellValue(b); return; }
        if (value instanceof BigDecimal bd) { cell.setCellValue(bd.doubleValue()); return; }
        cell.setCellValue(value.toString());
    }

    private static String joinCells(List<Object> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeCell(cells.get(i)));
        }
        return sb.toString();
    }

    private static String escapeCell(Object value) {
        if (value == null) return "";
        String s = value instanceof BigDecimal bd
                ? bd.stripTrailingZeros().toPlainString()
                : value.toString();
        boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return needsQuoting ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ========================================================================
    // Shared helpers
    // ========================================================================

    private TreasurySettings loadSettings() {
        return settingRepository.findById(TreasurySettings.SETTINGS_KEY)
                .map(s -> TreasurySettings.fromMap(s.getValue()))
                .orElse(new TreasurySettings());
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> getter) {
        BigDecimal total = BigDecimal.ZERO;
        for (T r : rows) total = total.add(nz(getter.apply(r)));
        return total;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String money(BigDecimal v) {
        BigDecimal x = nz(v).setScale(2, RoundingMode.HALF_UP);
        // 1,234.56 PLN — neutral grouping with English-style separators, easy
        // to scan internationally without locale surprises.
        String plain = x.toPlainString();
        boolean negative = plain.startsWith("-");
        if (negative) plain = plain.substring(1);
        int dot = plain.indexOf('.');
        String intPart = dot < 0 ? plain : plain.substring(0, dot);
        String fracPart = dot < 0 ? "00" : plain.substring(dot + 1);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) grouped.append(',');
            grouped.append(intPart.charAt(i));
        }
        String out = grouped + "." + fracPart + " PLN";
        return negative ? "-" + out : out;
    }

    /** Money with explicit sign for differences (+ or -). */
    private static String signedMoney(BigDecimal v) {
        BigDecimal x = nz(v);
        if (x.signum() > 0) return "+" + money(x);
        return money(x);
    }

    private static String dateShort(LocalDate d) {
        return d == null ? "" : d.format(SHORT_DATE);
    }

    private static String categoryLabel(ExpenseCategory c) {
        if (c == null) return "Other";
        // Convert SCREAMING_SNAKE to Title Case for readability.
        String[] parts = c.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            String p = parts[i];
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String sourceLabel(PaymentSource s) {
        if (s == null) return "—";
        return switch (s) {
            case CASH -> "Cash";
            case CARD -> "Card";
        };
    }

    private static String statusLabel(EntryStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case DRAFT -> "Draft";
            case LOCKED -> "Submitted";
        };
    }

    private static String descriptionFor(ExpenseItem e) {
        String desc = e.getDescription();
        if (desc == null || desc.isBlank()) return categoryLabel(e.getCategory());
        return truncate(desc, 80);
    }

    private static String periodLabel(LocalDate from, LocalDate to) {
        if (from == null && to == null) return "—";
        if (from == null) return "→ " + to.format(MONTH_LABEL);
        if (to == null) return from.format(MONTH_LABEL) + " →";
        if (from.equals(to)) return from.format(MONTH_LABEL);
        return from.format(MONTH_LABEL) + " → " + to.format(MONTH_LABEL);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    private static Kpi kpi(String label, String value) {
        return new Kpi(label, value);
    }

    private record Kpi(String label, String value) {}

    // ========================================================================
    // PdfReport — branded report builder
    // ========================================================================

    /**
     * Compact PDF builder that owns the layout (brand header, KPI strip,
     * sections, footer). Per-type methods use sectionHeader / tableStart /
     * bodyCell / subtotalRow / grandTotal to fill in content.
     */
    private static final class PdfReport {

        private final Document doc;
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        private final String title;
        private final LocalDate from;
        private final LocalDate to;
        private final List<Kpi> kpis;
        private final String subtitle;

        PdfReport(String title, LocalDate from, LocalDate to, List<Kpi> kpis, String subtitle) {
            // A4 portrait, comfortable margins — most reports fit nicely.
            this.doc = new Document(PageSize.A4, 36, 36, 60, 48);
            this.title = title;
            this.from = from;
            this.to = to;
            this.kpis = kpis;
            this.subtitle = subtitle;
        }

        void build(java.util.function.Consumer<Document> body) {
            try {
                PdfWriter writer = PdfWriter.getInstance(doc, bos);
                writer.setPageEvent(new FooterEvent(title));
                doc.open();
                renderCover();
                body.accept(doc);
                doc.close();
            } catch (Exception e) {
                throw new RuntimeException("PDF render failed: " + e.getMessage(), e);
            }
        }

        ExportResult finish(String slug, LocalDate from, LocalDate to) {
            return new ExportResult(slug + "_" + from + "_" + to + ".pdf",
                    bos.toByteArray(), "application/pdf");
        }

        private void renderCover() throws com.lowagie.text.DocumentException {
            Font brandFont = FontFactory.getFont(FontFactory.HELVETICA, 9, BRAND_SAFFRON);
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, BRAND_INK);
            Font subFont = FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED);

            Paragraph brand = new Paragraph("SAFFRON · CASH FLOW", brandFont);
            brand.setSpacingAfter(2f);
            doc.add(brand);

            Paragraph t = new Paragraph(title, titleFont);
            t.setSpacingAfter(2f);
            doc.add(t);

            String range = from.format(MONTH_LABEL) + " → " + to.format(MONTH_LABEL);
            Paragraph rangeP = new Paragraph(range + "   ·   generated " + LocalDate.now().format(MONTH_LABEL), subFont);
            rangeP.setSpacingAfter(10f);
            doc.add(rangeP);

            if (subtitle != null && !subtitle.isBlank()) {
                Paragraph sub = new Paragraph(subtitle, subFont);
                sub.setSpacingAfter(14f);
                doc.add(sub);
            }

            // KPI strip — equal-width boxes.
            if (kpis != null && !kpis.isEmpty()) {
                PdfPTable kt = new PdfPTable(kpis.size());
                kt.setWidthPercentage(100);
                kt.setSpacingAfter(18f);
                for (Kpi k : kpis) {
                    PdfPCell cell = new PdfPCell();
                    cell.setBorder(Rectangle.BOX);
                    cell.setBorderColor(GRID_LINE);
                    cell.setBackgroundColor(BRAND_CREAM);
                    cell.setPadding(10f);
                    Paragraph label = new Paragraph(k.label().toUpperCase(),
                            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, MUTED));
                    label.setSpacingAfter(4f);
                    cell.addElement(label);
                    Paragraph value = new Paragraph(k.value(),
                            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, BRAND_INK));
                    cell.addElement(value);
                    kt.addCell(cell);
                }
                doc.add(kt);
            }
        }

        void sectionHeader(String title, String meta, String trailingAmount) throws com.lowagie.text.DocumentException {
            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            try {
                header.setWidths(new float[]{6f, 3f});
            } catch (com.lowagie.text.DocumentException ignored) {}
            header.setSpacingBefore(4f);
            header.setSpacingAfter(0f);

            PdfPCell left = new PdfPCell();
            left.setBorder(Rectangle.NO_BORDER);
            left.setPaddingBottom(6f);
            left.setPaddingTop(8f);
            Paragraph titleP = new Paragraph(title,
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BRAND_INK));
            left.addElement(titleP);
            if (meta != null && !meta.isBlank()) {
                Paragraph metaP = new Paragraph(meta,
                        FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED));
                left.addElement(metaP);
            }
            header.addCell(left);

            PdfPCell right = new PdfPCell();
            right.setBorder(Rectangle.NO_BORDER);
            right.setPaddingTop(10f);
            right.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph amount = new Paragraph(trailingAmount,
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BRAND_SAFFRON));
            amount.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(amount);
            header.addCell(right);

            doc.add(header);
        }

        PdfPTable tableStart(float[] widths, List<String> headers, int[] alignments) {
            PdfPTable table = new PdfPTable(widths.length);
            table.setWidthPercentage(100);
            try {
                table.setWidths(widths);
            } catch (com.lowagie.text.DocumentException ignored) {}
            table.setSpacingBefore(2f);
            table.setHeaderRows(1);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
            for (int i = 0; i < headers.size(); i++) {
                PdfPCell cell = new PdfPCell(new Phrase(headers.get(i), headerFont));
                cell.setBackgroundColor(BRAND_INK);
                cell.setHorizontalAlignment(alignments[i]);
                cell.setBorderColor(BRAND_INK);
                cell.setPadding(6f);
                table.addCell(cell);
            }
            return table;
        }

        void bodyCell(PdfPTable table, String text, int alignment, Color bg) {
            PdfPCell cell = new PdfPCell(new Phrase(text,
                    FontFactory.getFont(FontFactory.HELVETICA, 9, BRAND_INK)));
            cell.setBackgroundColor(bg);
            cell.setHorizontalAlignment(alignment);
            cell.setBorderColor(GRID_LINE);
            cell.setBorderWidth(0.5f);
            cell.setPaddingTop(5f);
            cell.setPaddingBottom(5f);
            cell.setPaddingLeft(6f);
            cell.setPaddingRight(6f);
            table.addCell(cell);
        }

        /** Single-amount subtotal — label spans all columns up to the amount,
         *  amount sits in the last column. */
        void subtotalRow(PdfPTable table, String label, String amount, int totalCols, int amountCol) {
            Font sumFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, BRAND_INK);
            PdfPCell labelCell = new PdfPCell(new Phrase(label, sumFont));
            labelCell.setColspan(amountCol);
            labelCell.setBackgroundColor(BRAND_CREAM);
            labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            labelCell.setBorderColor(GRID_LINE);
            labelCell.setPadding(6f);
            table.addCell(labelCell);
            for (int i = amountCol; i < totalCols; i++) {
                if (i == amountCol) {
                    PdfPCell amountCell = new PdfPCell(new Phrase(amount, sumFont));
                    amountCell.setBackgroundColor(BRAND_CREAM);
                    amountCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    amountCell.setBorderColor(GRID_LINE);
                    amountCell.setPadding(6f);
                    table.addCell(amountCell);
                } else {
                    PdfPCell pad = new PdfPCell(new Phrase("", sumFont));
                    pad.setBackgroundColor(BRAND_CREAM);
                    pad.setBorderColor(GRID_LINE);
                    table.addCell(pad);
                }
            }
        }

        /** Multi-column subtotal — fills several numeric columns at once. */
        void subtotalRowMulti(PdfPTable table, String label, int totalCols,
                              int[] amountCols, String[] amounts) {
            Font sumFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, BRAND_INK);
            Set<Integer> amountSet = new HashSet<>();
            for (int c : amountCols) amountSet.add(c);
            int firstAmount = amountCols[0];
            // Label spans columns 0..firstAmount-1
            PdfPCell labelCell = new PdfPCell(new Phrase(label, sumFont));
            labelCell.setColspan(firstAmount);
            labelCell.setBackgroundColor(BRAND_CREAM);
            labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            labelCell.setBorderColor(GRID_LINE);
            labelCell.setPadding(6f);
            table.addCell(labelCell);
            int amountIndex = 0;
            for (int i = firstAmount; i < totalCols; i++) {
                String txt = "";
                if (amountSet.contains(i) && amountIndex < amounts.length) {
                    txt = amounts[amountIndex++];
                }
                PdfPCell cell = new PdfPCell(new Phrase(txt, sumFont));
                cell.setBackgroundColor(BRAND_CREAM);
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                cell.setBorderColor(GRID_LINE);
                cell.setPadding(6f);
                table.addCell(cell);
            }
        }

        void grandTotal(Document doc, String label, String amount) throws com.lowagie.text.DocumentException {
            PdfPTable tbl = new PdfPTable(2);
            tbl.setWidthPercentage(100);
            try {
                tbl.setWidths(new float[]{6f, 3f});
            } catch (com.lowagie.text.DocumentException ignored) {}
            tbl.setSpacingBefore(12f);
            Font gFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.WHITE);
            PdfPCell lc = new PdfPCell(new Phrase(label, gFont));
            lc.setBackgroundColor(BRAND_INK);
            lc.setBorder(Rectangle.NO_BORDER);
            lc.setPadding(10f);
            tbl.addCell(lc);
            PdfPCell ac = new PdfPCell(new Phrase(amount, gFont));
            ac.setBackgroundColor(BRAND_INK);
            ac.setBorder(Rectangle.NO_BORDER);
            ac.setHorizontalAlignment(Element.ALIGN_RIGHT);
            ac.setPadding(10f);
            tbl.addCell(ac);
            doc.add(tbl);
        }

        /** Grand-total band that lists several totals side by side. */
        void grandTotalMulti(Document doc, String label, String[] amounts) throws com.lowagie.text.DocumentException {
            PdfPTable tbl = new PdfPTable(amounts.length + 1);
            tbl.setWidthPercentage(100);
            tbl.setSpacingBefore(12f);
            Font gFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE);
            Font subFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.WHITE);
            PdfPCell lc = new PdfPCell(new Phrase(label, gFont));
            lc.setBackgroundColor(BRAND_INK);
            lc.setBorder(Rectangle.NO_BORDER);
            lc.setPadding(10f);
            tbl.addCell(lc);
            for (String a : amounts) {
                PdfPCell ac = new PdfPCell(new Phrase(a, subFont));
                ac.setBackgroundColor(BRAND_INK);
                ac.setBorder(Rectangle.NO_BORDER);
                ac.setHorizontalAlignment(Element.ALIGN_RIGHT);
                ac.setPadding(10f);
                tbl.addCell(ac);
            }
            doc.add(tbl);
        }

        void spacer(Document doc, float height) {
            Paragraph p = new Paragraph(new Chunk(" "));
            p.setLeading(height);
            try {
                doc.add(p);
            } catch (com.lowagie.text.DocumentException ignored) {}
        }
    }

    /** Page-number + brand footer drawn on every page. */
    private static final class FooterEvent extends PdfPageEventHelper {
        private final String reportTitle;

        FooterEvent(String reportTitle) {
            this.reportTitle = reportTitle;
        }

        @Override
        public void onEndPage(com.lowagie.text.pdf.PdfWriter writer, Document document) {
            Rectangle pageSize = document.getPageSize();
            Font font = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
            String left = "Saffron · " + reportTitle;
            String right = "Page " + writer.getPageNumber();
            float marginLeft = document.leftMargin();
            float marginRight = document.rightMargin();
            float y = pageSize.getBottom() + 20;
            ColumnText.showTextAligned(writer.getDirectContent(),
                    Element.ALIGN_LEFT, new Phrase(left, font),
                    pageSize.getLeft() + marginLeft, y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(),
                    Element.ALIGN_RIGHT, new Phrase(right, font),
                    pageSize.getRight() - marginRight, y, 0);
        }
    }
}
