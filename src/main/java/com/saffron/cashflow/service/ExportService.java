package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.ExpenseItemRepository;
import com.saffron.cashflow.repository.ManualDeliveryIncomeRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.TreasurySettings;
import com.saffron.cashflow.util.ManualDeliverySettlement;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates CSV exports for the main list pages. Centralised here so every
 * page gets the same column conventions (ISO dates, decimal points, UTF-8
 * BOM so Excel opens the file with the right encoding).
 *
 * Each export type lives in a switch in {@link #render} so adding a new
 * one means defining a column set + row mapper and nothing else. Filters
 * live in a {@link ExportFilters} record.
 */
@Service
public class ExportService {

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

    public record ExportFilters(
            String type,
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

    // ---------- per-type ----------

    private ExportResult renderExpenses(ExportFilters f) {
        LocalDate from = f.from() != null ? f.from() : LocalDate.now().withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        List<ExpenseItem> rows = expenseRepository.findByEffectiveDateBetweenWithInvoices(from, to);
        if (f.paymentSource() != null && !f.paymentSource().isBlank()) {
            PaymentSource ps = PaymentSource.valueOf(f.paymentSource().toUpperCase());
            rows = rows.stream().filter(r -> r.getPaymentSource() == ps).toList();
        }
        List<String> headers = List.of(
                "Date", "Category", "Description", "Amount (PLN)", "Source",
                "Standalone", "Shift report", "Invoice count");
        List<Function<ExpenseItem, Object>> cols = List.of(
                e -> e.getEffectiveDate(),
                e -> e.getCategory() == null ? "" : e.getCategory().name(),
                e -> nullSafe(e.getDescription()),
                ExpenseItem::getAmount,
                e -> e.getPaymentSource() == null ? "" : e.getPaymentSource().name(),
                e -> e.getEntry() == null,
                e -> e.getEntry() != null ? e.getEntry().getDate() : "",
                e -> e.getInvoices() == null ? 0 : e.getInvoices().size());
        return csv("expenses", from, to, headers, rows, cols);
    }

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
        return csv("shift-reports", from, to, headers, rows, cols);
    }

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
        List<String> headers = List.of(
                "Paid date", "Employee", "Amount (PLN)", "Source",
                "Period from", "Period to", "Excluded from treasury", "Notes");
        List<Function<SalaryPayment, Object>> cols = List.of(
                SalaryPayment::getPaidDate,
                p -> names.getOrDefault(p.getUserId(), ""),
                SalaryPayment::getAmount,
                p -> p.getPaymentSource() == null ? "" : p.getPaymentSource().name(),
                p -> p.getPeriodFrom() == null ? "" : p.getPeriodFrom(),
                p -> p.getPeriodTo() == null ? "" : p.getPeriodTo(),
                SalaryPayment::isExcludeFromTreasury,
                p -> nullSafe(p.getNotes()));
        return csv("payouts", from, to, headers, rows, cols);
    }

    private ExportResult renderDeliveries(ExportFilters f) {
        LocalDate from = f.from() != null ? f.from() : LocalDate.now().withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : LocalDate.now();
        TreasurySettings settings = loadSettings();
        List<ManualDeliveryIncome> rows = manualDeliveryRepository
                .findByEffectiveDateBetweenOrderByEffectiveDateDescCreatedAtDesc(from, to).stream()
                .filter(r -> f.platform() == null || f.platform().isBlank()
                        || f.platform().equalsIgnoreCase(r.getPlatform().name()))
                .toList();
        List<String> headers = List.of(
                "Date", "Platform", "Gross (PLN)", "Settled to card (PLN)",
                "Settlement overridden", "Notes");
        List<Function<ManualDeliveryIncome, Object>> cols = List.of(
                ManualDeliveryIncome::getEffectiveDate,
                r -> r.getPlatform().name(),
                ManualDeliveryIncome::getGrossAmount,
                r -> EntryCalculator.toDouble(ManualDeliverySettlement.settledToCard(r, settings)),
                r -> r.getSettledToCard() != null,
                r -> nullSafe(r.getNotes()));
        return csv("delivery-income", from, to, headers, rows, cols);
    }

    // ---------- CSV helper ----------

    private <T> ExportResult csv(
            String slug,
            LocalDate from,
            LocalDate to,
            List<String> headers,
            List<T> rows,
            List<Function<T, Object>> cols) {
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM so Excel opens the file in the correct encoding without
        // the "Get Data > From Text" dance.
        sb.append('\uFEFF');
        sb.append(joinCells(headers.stream().map(Object.class::cast).toList())).append("\r\n");
        for (T row : rows) {
            List<Object> values = new ArrayList<>(cols.size());
            for (Function<T, Object> col : cols) values.add(col.apply(row));
            sb.append(joinCells(values)).append("\r\n");
        }
        String filename = slug + "_" + from + "_" + to + ".csv";
        return new ExportResult(filename, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/csv; charset=utf-8");
    }

    private static String joinCells(List<Object> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeCell(cells.get(i)));
        }
        return sb.toString();
    }

    /** RFC4180-style escaping: quote the cell whenever it contains comma,
     *  quote, CR or LF; escape inner quotes by doubling them. */
    private static String escapeCell(Object value) {
        if (value == null) return "";
        String s;
        if (value instanceof BigDecimal bd) {
            s = bd.stripTrailingZeros().toPlainString();
        } else {
            s = value.toString();
        }
        boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuoting) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private TreasurySettings loadSettings() {
        return settingRepository.findById(TreasurySettings.SETTINGS_KEY)
                .map(s -> TreasurySettings.fromMap(s.getValue()))
                .orElse(new TreasurySettings());
    }
}
