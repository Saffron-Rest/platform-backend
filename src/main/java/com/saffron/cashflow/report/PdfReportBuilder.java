package com.saffron.cashflow.report;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accountant-oriented cash flow PDF: reconciliation statements, revenue breakdown,
 * shift register, expense analysis, and per-shift detail.
 */
public final class PdfReportBuilder {

    private static final String BUSINESS_NAME = "Saffron Restaurant";
    private static final String REPORT_SUBTITLE = "Daily Cash & Card Reconciliation";

    private static final Color BRAND_DARK = new Color(92, 58, 24);
    private static final Color INK = new Color(26, 26, 26);
    private static final Color MUTED = new Color(95, 95, 95);
    private static final Color ROW_ALT = new Color(248, 246, 242);
    private static final Color BORDER = new Color(210, 205, 198);
    private static final Color SECTION_BG = new Color(252, 249, 245);
    private static final Color SUCCESS = new Color(22, 120, 72);
    private static final Color DANGER = new Color(185, 50, 50);
    private static final Color TOTAL_ROW = new Color(235, 228, 218);

    private static final NumberFormat PLN = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL"));
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("pl-PL"));
    private static final DateTimeFormatter GEN_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"));
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private PdfReportBuilder() {}

    public static byte[] build(
            String period,
            LocalDate from,
            LocalDate to,
            Map<String, Object> summary,
            java.util.List<Map<String, Object>> rows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            boolean singleShift = rows.size() == 1;
            String reportTitle = singleShift
                    ? "Shift Reconciliation Report"
                    : "Consolidated Cash Flow Report";

            Document doc = new Document(PageSize.A4, 48, 48, 56, 52);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new ReportFooter(reportTitle, from, to));
            doc.open();

            Fonts f = Fonts.create();
            PeriodTotals totals = PeriodTotals.from(summary, rows);

            doc.add(buildCover(reportTitle, period, from, to, summary, totals, f));
            doc.add(sectionHeading("1. Executive summary", f));
            doc.add(buildExecutiveSummary(totals, f));
            doc.add(spacer(10));

            doc.add(sectionHeading("2. Cash drawer reconciliation (period)", f));
            doc.add(buildCashReconciliationStatement(totals, f, false));
            doc.add(spacer(8));

            doc.add(sectionHeading("3. Card reconciliation (period)", f));
            doc.add(buildCardReconciliationStatement(totals, f));
            doc.add(spacer(8));

            doc.add(sectionHeading("4. Revenue analysis", f));
            doc.add(buildRevenueTable(totals, f));
            doc.add(spacer(10));

            if (!rows.isEmpty()) {
                doc.setPageSize(PageSize.A4.rotate());
                doc.newPage();
                doc.add(sectionHeading("5. Shift register", f));
                doc.add(periodNote(from, to, rows.size(), f));
                doc.add(buildShiftRegister(rows, totals, f));
                doc.add(spacer(12));

                doc.setPageSize(PageSize.A4);
                doc.newPage();
                doc.add(sectionHeading("6. Expense analysis", f));
                doc.add(buildExpenseCategorySummary(rows, f));
                doc.add(spacer(10));
                doc.add(buildPayoutsSummary(rows, f));

                doc.add(sectionHeading("7. Shift detail", f));
                for (int i = 0; i < rows.size(); i++) {
                    if (i > 0) doc.add(spacer(6));
                    doc.add(buildShiftDetail(rows.get(i), f));
                }

                doc.add(sectionHeading("8. Expense register (itemized)", f));
                doc.add(buildExpenseRegister(rows, f));
            } else {
                doc.add(new Paragraph("No entries in this period.", f.body));
            }

            addNotesSection(doc, rows, f);

            doc.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException("PDF generation failed", ex);
        }
    }

    // ——— Cover & metadata ———

    private static PdfPTable buildCover(
            String reportTitle,
            String period,
            LocalDate from,
            LocalDate to,
            Map<String, Object> summary,
            PeriodTotals totals,
            Fonts f) throws DocumentException {
        PdfPTable wrap = new PdfPTable(1);
        wrap.setWidthPercentage(100);

        PdfPCell banner = new PdfPCell();
        banner.setBorder(Rectangle.NO_BORDER);
        banner.setBackgroundColor(BRAND_DARK);
        banner.setPadding(20);
        banner.addElement(new Paragraph(BUSINESS_NAME, f.coverBrand));
        banner.addElement(new Paragraph(reportTitle, f.coverTitle));
        banner.addElement(new Paragraph(REPORT_SUBTITLE, f.coverSubtitle));
        wrap.addCell(banner);

        PdfPCell meta = new PdfPCell();
        meta.setBorder(Rectangle.NO_BORDER);
        meta.setPaddingTop(16);
        meta.setPaddingBottom(8);
        meta.addElement(metaTable(from, to, period, summary, totals, f));
        wrap.addCell(meta);

        PdfPCell disclaimer = new PdfPCell();
        disclaimer.setBorder(Rectangle.NO_BORDER);
        disclaimer.setPaddingTop(12);
        Paragraph p = new Paragraph(
                "This document is prepared for accounting and management review. "
                        + "Amounts are in Polish złoty (PLN). Platform delivery sales are recorded separately "
                        + "and are not included in the physical cash drawer calculation unless paid in cash.",
                f.small);
        p.setLeading(12);
        disclaimer.addElement(p);
        wrap.addCell(disclaimer);

        return wrap;
    }

    private static PdfPTable metaTable(
            LocalDate from,
            LocalDate to,
            String period,
            Map<String, Object> summary,
            PeriodTotals totals,
            Fonts f) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{1.1f, 2f});

        int count = summary.get("count") instanceof Number n ? n.intValue() : 0;
        String statusNote = totals.draftCount > 0
                ? totals.lockedCount + " submitted, " + totals.draftCount + " draft"
                : count + " submitted";

        addMetaRow(t, "Reporting period", formatRange(from, to), f);
        addMetaRow(t, "Period type", capitalize(period), f);
        addMetaRow(t, "Entries included", statusNote, f);
        addMetaRow(t, "Report generated", GEN_FMT.format(LocalDateTime.now()), f);
        addMetaRow(t, "Currency", "PLN (zł)", f);
        addMetaRow(t, "Net revenue (period)", money(totals.netSales), f);
        addMetaRow(t, "Cash over / short (period)", money(totals.difference), f, varianceColor(totals.difference));

        return t;
    }

    private static void addMetaRow(PdfPTable t, String label, String value, Fonts f) {
        addMetaRow(t, label, value, f, INK);
    }

    private static void addMetaRow(PdfPTable t, String label, String value, Fonts f, Color valueColor) {
        PdfPCell l = new PdfPCell(new Phrase(label, f.metaLabel));
        l.setBorder(Rectangle.NO_BORDER);
        l.setPadding(5);
        l.setBackgroundColor(SECTION_BG);
        PdfPCell v = new PdfPCell(new Phrase(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, valueColor)));
        v.setBorder(Rectangle.NO_BORDER);
        v.setPadding(5);
        v.setBackgroundColor(SECTION_BG);
        t.addCell(l);
        t.addCell(v);
    }

    // ——— Summary sections ———

    private static PdfPTable buildExecutiveSummary(PeriodTotals t, Fonts f) throws DocumentException {
        PdfPTable grid = new PdfPTable(3);
        grid.setWidthPercentage(100);
        grid.setWidths(new float[]{1, 1, 1});
        grid.setSpacingBefore(4);

        kpi(grid, "Gross revenue", t.grossSales, f);
        kpi(grid, "Returns", t.returns, f);
        kpi(grid, "Net revenue", t.netSales, f);
        kpi(grid, "Cash sales", t.cashSales, f);
        kpi(grid, "Card sales", t.cardSales, f);
        kpi(grid, "Platform sales", t.platformSales, f);
        kpi(grid, "Payouts & transfers", t.payouts, f);
        kpi(grid, "Operating expenses", t.expenseLines, f);
        kpi(grid, "Total outflows", t.payouts + t.expenseLines, f);
        kpi(grid, "Expected cash (Σ shifts)", t.expectedCash, f);
        kpi(grid, "Actual counted (Σ shifts)", t.actualCash, f);
        kpi(grid, "Cash variance", t.difference, f, varianceColor(t.difference));
        return grid;
    }

    private static void kpi(PdfPTable grid, String label, double value, Fonts f) {
        kpi(grid, label, value, f, INK);
    }

    private static void kpi(PdfPTable grid, String label, double value, Fonts f, Color color) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BORDER);
        cell.setPadding(10);
        cell.setBackgroundColor(Color.WHITE);
        cell.addElement(new Paragraph(label.toUpperCase(Locale.ROOT), f.kpiLabel));
        cell.addElement(new Paragraph(money(value), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, color)));
        grid.addCell(cell);
    }

    private static PdfPTable buildCashReconciliationStatement(PeriodTotals t, Fonts f, boolean shiftLevel)
            throws DocumentException {
        PdfPTable table = statementTable(f);
        addStatementLine(table, "Expected cash in drawer (sum of shifts)", t.expectedCash, f, true);
        addStatementLine(table, "Actual cash counted (sum of shifts)", t.actualCash, f, true);
        addStatementLine(table, "Variance — over (+) / short (−)", t.difference, f, true, varianceColor(t.difference));
        if (!shiftLevel) {
            addStatementLine(table, "  (aggregated across all shifts in period)", null, f, false);
        }
        return table;
    }

    private static PdfPTable buildCardReconciliationStatement(PeriodTotals t, Fonts f) throws DocumentException {
        PdfPTable table = statementTable(f);
        addStatementLine(table, "Card sales", t.cardSales, f, false);
        addStatementLine(table, "Less: card refunds", -t.cardReturns, f, false);
        addStatementLine(table, "Less: card expenses", -t.cardExpenses, f, false);
        addStatementLine(table, "Card balance (net position)", t.cardBalance, f, true);
        return table;
    }

    private static PdfPTable statementTable(Fonts f) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2.2f, 1f});
        table.setSpacingBefore(4);
        return table;
    }

    private static void addStatementLine(PdfPTable table, String label, Double amount, Fonts f, boolean bold) {
        addStatementLine(table, label, amount, f, bold, INK);
    }

    private static void addStatementLine(
            PdfPTable table, String label, Double amount, Fonts f, boolean bold, Color amountColor) {
        Font labelFont = bold ? f.statementBold : f.body;
        table.addCell(statementCell(label, labelFont, Element.ALIGN_LEFT, bold ? SECTION_BG : Color.WHITE));
        String value = amount != null ? money(amount) : "";
        Font valueFont = FontFactory.getFont(
                FontFactory.HELVETICA, bold ? 10 : 9, bold ? Font.BOLD : Font.NORMAL, amountColor);
        table.addCell(statementCell(value, valueFont, Element.ALIGN_RIGHT, bold ? SECTION_BG : Color.WHITE));
    }

    private static PdfPCell statementCell(String text, Font font, int align, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorderColor(BORDER);
        cell.setPadding(7);
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private static PdfPTable buildRevenueTable(PeriodTotals t, Fonts f) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(72);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setWidths(new float[]{2f, 1f});

        table.addCell(headerCell("Revenue channel", f.tableHeader));
        table.addCell(headerCell("Amount (PLN)", f.tableHeader));

        addAmountRow(table, "Cash sales", t.cashSales, f, false);
        addAmountRow(table, "Card sales", t.cardSales, f, false);
        addAmountRow(table, "Wolt", t.wolt, f, false);
        addAmountRow(table, "Bolt Food", t.bolt, f, false);
        addAmountRow(table, "Uber Eats", t.uber, f, false);
        addAmountRow(table, "Glovo", t.glovo, f, false);
        addAmountRow(table, "Other platforms", t.otherPlatform, f, false);
        addAmountRow(table, "Gross revenue", t.grossSales, f, true);
        addAmountRow(table, "Cash refunds", -t.cashReturns, f, false);
        addAmountRow(table, "Card refunds", -t.cardReturns, f, false);
        addAmountRow(table, "Platform refunds", -t.platformReturns, f, false);
        addAmountRow(table, "Net revenue", t.netSales, f, true);
        return table;
    }

    private static void addAmountRow(PdfPTable table, String label, double amount, Fonts f, boolean total) {
        Color bg = total ? TOTAL_ROW : Color.WHITE;
        Font lf = total ? f.statementBold : f.body;
        table.addCell(bodyCell(label, lf, bg, Element.ALIGN_LEFT));
        table.addCell(bodyCell(money(amount), total ? f.statementBold : f.body, bg, Element.ALIGN_RIGHT));
    }

    // ——— Shift register (landscape) ———

    private static PdfPTable buildShiftRegister(java.util.List<Map<String, Object>> rows, PeriodTotals totals, Fonts f)
            throws DocumentException {
        float[] widths = {
                0.9f, 1.2f, 0.65f, 0.85f, 0.85f, 0.85f, 0.7f, 0.7f, 0.7f, 0.7f, 0.65f,
                0.8f, 0.75f, 0.75f, 0.85f, 0.85f, 0.85f, 0.8f
        };
        PdfPTable table = new PdfPTable(18);
        table.setWidthPercentage(100);
        table.setWidths(widths);
        table.setHeaderRows(1);
        table.setSpacingBefore(6);

        String[] headers = {
                "Date", "Cashier", "Status", "Opening", "Cash", "Card", "Wolt", "Bolt", "Uber", "Glovo", "Other",
                "Platforms", "Returns", "Payouts", "Expenses", "Expected", "Counted", "Variance"
        };
        for (String h : headers) {
            table.addCell(headerCell(h, f.tableHeaderSmall));
        }

        RegisterSums sums = new RegisterSums();
        boolean alt = false;
        for (Map<String, Object> e : rows) {
            ShiftTotals s = ShiftTotals.from(e);
            sums.add(e, s);
            Color bg = alt ? ROW_ALT : Color.WHITE;
            alt = !alt;
            table.addCell(bodyCell(fmtDate(str(e.get("date"))), f.tableSmall, bg, Element.ALIGN_LEFT));
            table.addCell(bodyCell(cashierName(e), f.tableSmall, bg, Element.ALIGN_LEFT));
            table.addCell(bodyCell(formatStatus(e.get("status")), f.tableSmall, bg, Element.ALIGN_CENTER));
            table.addCell(bodyCell(money(e.get("openingBalance")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("cashSales")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("cardSales")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("woltSales")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("boltSales")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("uberEatsSales")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("glovoSales")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("otherPlatformSales")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(s.platformSales), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(s.returns), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("payoutsTotal")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(s.totalExpenses), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("closingBalance")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("actualCashCounted")), f.tableSmall, bg, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(e.get("difference")), f.tableSmall, bg, Element.ALIGN_RIGHT, varianceColor(s.difference)));
        }

        table.addCell(totalCell("TOTAL", f, 3, Element.ALIGN_LEFT));
        table.addCell(totalCell(money(sums.opening), f, 1));
        table.addCell(totalCell(money(sums.cash), f, 1));
        table.addCell(totalCell(money(sums.card), f, 1));
        table.addCell(totalCell(money(sums.wolt), f, 1));
        table.addCell(totalCell(money(sums.bolt), f, 1));
        table.addCell(totalCell(money(sums.uber), f, 1));
        table.addCell(totalCell(money(sums.glovo), f, 1));
        table.addCell(totalCell(money(sums.other), f, 1));
        table.addCell(totalCell(money(sums.platforms), f, 1));
        table.addCell(totalCell(money(sums.returns), f, 1));
        table.addCell(totalCell(money(sums.payouts), f, 1));
        table.addCell(totalCell(money(sums.expenses), f, 1));
        table.addCell(totalCell(money(totals.expectedCash), f, 1));
        table.addCell(totalCell(money(totals.actualCash), f, 1));
        table.addCell(totalCell(money(totals.difference), f, 1, varianceColor(totals.difference)));

        return table;
    }

    private static PdfPCell totalCell(String text, Fonts f, int colspan) {
        return totalCell(text, f, colspan, Element.ALIGN_RIGHT, INK);
    }

    private static PdfPCell totalCell(String text, Fonts f, int colspan, int align) {
        return totalCell(text, f, colspan, align, INK);
    }

    private static PdfPCell totalCell(String text, Fonts f, int colspan, Color textColor) {
        return totalCell(text, f, colspan, Element.ALIGN_RIGHT, textColor);
    }

    private static PdfPCell totalCell(String text, Fonts f, int colspan, int align, Color textColor) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, textColor)));
        cell.setColspan(colspan);
        cell.setBackgroundColor(TOTAL_ROW);
        cell.setBorderColor(BORDER);
        cell.setPadding(5);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static final class RegisterSums {
        double opening, cash, card, wolt, bolt, uber, glovo, other, platforms, returns, payouts, expenses;

        void add(Map<String, Object> e, ShiftTotals s) {
            opening += num(e.get("openingBalance"));
            cash += num(e.get("cashSales"));
            card += num(e.get("cardSales"));
            wolt += num(e.get("woltSales"));
            bolt += num(e.get("boltSales"));
            uber += num(e.get("uberEatsSales"));
            glovo += num(e.get("glovoSales"));
            other += num(e.get("otherPlatformSales"));
            platforms += s.platformSales;
            returns += s.returns;
            payouts += num(e.get("payoutsTotal"));
            expenses += s.totalExpenses;
        }
    }

    // ——— Per-shift detail ———

    private static PdfPTable buildShiftDetail(Map<String, Object> e, Fonts f) throws DocumentException {
        ShiftTotals s = ShiftTotals.from(e);
        PdfPTable wrap = new PdfPTable(1);
        wrap.setWidthPercentage(100);
        wrap.setSpacingBefore(4);

        PdfPCell head = new PdfPCell();
        head.setBorderColor(BRAND_DARK);
        head.setBorderWidth(1);
        head.setBackgroundColor(SECTION_BG);
        head.setPadding(10);

        String submitted = e.get("submittedAt") != null ? " · Submitted " + str(e.get("submittedAt")) : "";
        head.addElement(new Paragraph(
                fmtDate(str(e.get("date"))) + "  ·  " + cashierName(e) + "  ·  " + formatStatus(e.get("status")) + submitted,
                f.shiftHead));
        head.addElement(new Paragraph("Entry ID: " + str(e.get("id")), f.small));
        wrap.addCell(head);

        PdfPCell body = new PdfPCell();
        body.setBorder(Rectangle.NO_BORDER);
        body.setPaddingTop(8);

        PdfPTable cols = new PdfPTable(2);
        cols.setWidthPercentage(100);
        cols.setWidths(new float[]{1, 1});

        cols.addCell(detailColumn("Revenue", buildShiftRevenueLines(s, f), f));
        cols.addCell(detailColumn("Outflows & reconciliation", buildShiftOutflowLines(e, s, f), f));
        body.addElement(cols);

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> expenses = (java.util.List<Map<String, Object>>) e.get("expenses");
        if (expenses != null && !expenses.isEmpty()) {
            body.addElement(spacer(6));
            body.addElement(new Paragraph("Expenses this shift", f.section));
            PdfPTable ex = new PdfPTable(4);
            ex.setWidthPercentage(100);
            ex.setWidths(new float[]{0.7f, 2.2f, 1.1f, 0.8f});
            ex.setSpacingBefore(4);
            ex.addCell(headerCell("Source", f.tableHeaderSmall));
            ex.addCell(headerCell("Description", f.tableHeaderSmall));
            ex.addCell(headerCell("Category", f.tableHeaderSmall));
            ex.addCell(headerCell("Amount", f.tableHeaderSmall));
            for (Map<String, Object> item : expenses) {
                ex.addCell(bodyCell(formatSource(item.get("paymentSource")), f.tableSmall, Color.WHITE, Element.ALIGN_LEFT));
                ex.addCell(bodyCell(str(item.get("description")), f.tableSmall, Color.WHITE, Element.ALIGN_LEFT));
                ex.addCell(bodyCell(formatCategory(item.get("category")), f.tableSmall, Color.WHITE, Element.ALIGN_LEFT));
                ex.addCell(bodyCell(money(item.get("amount")), f.tableSmall, Color.WHITE, Element.ALIGN_RIGHT));
            }
            body.addElement(ex);
        }

        if (e.get("notes") != null && !str(e.get("notes")).isBlank()) {
            body.addElement(spacer(4));
            body.addElement(new Paragraph("Notes: " + str(e.get("notes")), f.body));
        }

        wrap.addCell(body);
        return wrap;
    }

    private static PdfPCell detailColumn(String title, PdfPTable lines, Fonts f) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(4);
        cell.addElement(new Paragraph(title, f.section));
        cell.addElement(lines);
        return cell;
    }

    private static PdfPTable buildShiftRevenueLines(ShiftTotals s, Fonts f) throws DocumentException {
        PdfPTable t = statementTable(f);
        addStatementLine(t, "Cash sales", s.cashSales, f, false);
        addStatementLine(t, "Card sales", s.cardSales, f, false);
        addStatementLine(t, "Wolt / Bolt / Uber / Glovo / Other", s.platformSales, f, false);
        addStatementLine(t, "Gross sales", s.grossSales, f, false);
        addStatementLine(t, "Returns", -s.returns, f, false);
        addStatementLine(t, "Net sales", s.netSales, f, true);
        return t;
    }

    private static PdfPTable buildShiftOutflowLines(Map<String, Object> e, ShiftTotals s, Fonts f)
            throws DocumentException {
        PdfPTable t = statementTable(f);
        addStatementLine(t, "Opening balance", num(e.get("openingBalance")), f, false);
        addStatementLine(t, "+ Cash sales", s.cashSales, f, false);
        addStatementLine(t, "− Cash refunds", -num(e.get("cashRefunds")), f, false);
        addStatementLine(t, "− Payouts (bank / cash / owner)", -num(e.get("payoutsTotal")), f, false);
        addStatementLine(t, "− Cash expenses", -s.cashExpenses, f, false);
        addStatementLine(t, "= Expected in drawer", num(e.get("closingBalance")), f, true);
        addStatementLine(t, "Actual counted", num(e.get("actualCashCounted")), f, false);
        addStatementLine(t, "Variance", s.difference, f, true, varianceColor(s.difference));
        addStatementLine(t, "Card balance", num(e.get("cardBalance")), f, false);
        return t;
    }

    // ——— Expense analysis ———

    private static PdfPTable buildExpenseCategorySummary(java.util.List<Map<String, Object>> rows, Fonts f)
            throws DocumentException {
        Map<String, double[]> byCategory = new TreeMap<>();
        for (Map<String, Object> e : rows) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> expenses = (java.util.List<Map<String, Object>>) e.get("expenses");
            if (expenses == null) continue;
            for (Map<String, Object> ex : expenses) {
                String cat = formatCategory(ex.get("category"));
                double[] sums = byCategory.computeIfAbsent(cat, k -> new double[3]);
                double amt = num(ex.get("amount"));
                sums[0] += amt;
                if ("Card".equals(formatSource(ex.get("paymentSource")))) {
                    sums[1] += amt;
                } else {
                    sums[2] += amt;
                }
            }
        }

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(85);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setWidths(new float[]{1.5f, 1f, 1f, 1f});
        table.addCell(headerCell("Category", f.tableHeader));
        table.addCell(headerCell("Cash", f.tableHeader));
        table.addCell(headerCell("Card", f.tableHeader));
        table.addCell(headerCell("Total", f.tableHeader));

        double cashT = 0, cardT = 0, grand = 0;
        for (Map.Entry<String, double[]> en : byCategory.entrySet()) {
            double[] s = en.getValue();
            table.addCell(bodyCell(en.getKey(), f.body, Color.WHITE, Element.ALIGN_LEFT));
            table.addCell(bodyCell(money(s[2]), f.body, Color.WHITE, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(s[1]), f.body, Color.WHITE, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(s[0]), f.body, Color.WHITE, Element.ALIGN_RIGHT));
            cashT += s[2];
            cardT += s[1];
            grand += s[0];
        }
        if (byCategory.isEmpty()) {
            table.addCell(bodyCell("No itemized expenses in period", f.body, Color.WHITE, Element.ALIGN_LEFT, 4));
        } else {
            table.addCell(bodyCell("Total", f.statementBold, TOTAL_ROW, Element.ALIGN_LEFT));
            table.addCell(bodyCell(money(cashT), f.statementBold, TOTAL_ROW, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(cardT), f.statementBold, TOTAL_ROW, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(grand), f.statementBold, TOTAL_ROW, Element.ALIGN_RIGHT));
        }
        return table;
    }

    private static PdfPTable buildPayoutsSummary(java.util.List<Map<String, Object>> rows, Fonts f) throws DocumentException {
        double bank = 0, cash = 0, owner = 0;
        for (Map<String, Object> e : rows) {
            bank += num(e.get("bankDeposit"));
            cash += num(e.get("cashWithdrawal"));
            owner += num(e.get("ownerWithdrawal"));
        }
        if (bank == 0 && cash == 0 && owner == 0) {
            return new PdfPTable(1);
        }

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setSpacingBefore(6);
        table.setWidths(new float[]{2f, 1f});
        table.addCell(headerCell("Payout / transfer type", f.tableHeader));
        table.addCell(headerCell("Amount", f.tableHeader));
        addAmountRow(table, "Bank deposit", bank, f, false);
        addAmountRow(table, "Cash withdrawal", cash, f, false);
        addAmountRow(table, "Owner withdrawal", owner, f, false);
        addAmountRow(table, "Total payouts", bank + cash + owner, f, true);
        return table;
    }

    @SuppressWarnings("unchecked")
    private static PdfPTable buildExpenseRegister(java.util.List<Map<String, Object>> rows, Fonts f) throws DocumentException {
        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{0.9f, 1.1f, 0.6f, 1.4f, 1f, 0.8f});
        table.setHeaderRows(1);
        table.setSpacingBefore(6);

        String[] headers = {"Date", "Cashier", "Source", "Description", "Category", "Amount"};
        for (String h : headers) {
            table.addCell(headerCell(h, f.tableHeaderSmall));
        }

        boolean any = false;
        for (Map<String, Object> e : rows) {
            java.util.List<Map<String, Object>> expenses = (java.util.List<Map<String, Object>>) e.get("expenses");
            if (expenses == null || expenses.isEmpty()) continue;
            any = true;
            for (Map<String, Object> ex : expenses) {
                table.addCell(bodyCell(fmtDate(str(e.get("date"))), f.tableSmall, Color.WHITE, Element.ALIGN_LEFT));
                table.addCell(bodyCell(cashierName(e), f.tableSmall, Color.WHITE, Element.ALIGN_LEFT));
                table.addCell(bodyCell(formatSource(ex.get("paymentSource")), f.tableSmall, Color.WHITE, Element.ALIGN_CENTER));
                table.addCell(bodyCell(str(ex.get("description")), f.tableSmall, Color.WHITE, Element.ALIGN_LEFT));
                table.addCell(bodyCell(formatCategory(ex.get("category")), f.tableSmall, Color.WHITE, Element.ALIGN_LEFT));
                table.addCell(bodyCell(money(ex.get("amount")), f.tableSmall, Color.WHITE, Element.ALIGN_RIGHT));
            }
        }
        if (!any) {
            PdfPCell empty = new PdfPCell(new Phrase("No itemized expenses recorded.", f.body));
            empty.setColspan(6);
            empty.setBorder(Rectangle.NO_BORDER);
            empty.setPadding(8);
            table.addCell(empty);
        }
        return table;
    }

    private static void addNotesSection(Document doc, java.util.List<Map<String, Object>> rows, Fonts f) throws DocumentException {
        java.util.List<String> notes = new ArrayList<>();
        for (Map<String, Object> e : rows) {
            if (e.get("notes") != null && !str(e.get("notes")).isBlank()) {
                notes.add(fmtDate(str(e.get("date"))) + " (" + cashierName(e) + "): " + str(e.get("notes")));
            }
        }
        if (notes.isEmpty()) return;
        doc.add(spacer(8));
        doc.add(sectionHeading("9. Notes & comments", f));
        for (String note : notes) {
            Paragraph p = new Paragraph("• " + note, f.body);
            p.setSpacingAfter(4);
            doc.add(p);
        }
    }

    // ——— Helpers ———

    private static Paragraph sectionHeading(String text, Fonts f) {
        Paragraph p = new Paragraph(text, f.section);
        p.setSpacingBefore(6);
        p.setSpacingAfter(4);
        return p;
    }

    private static Paragraph periodNote(LocalDate from, LocalDate to, int count, Fonts f) {
        return new Paragraph(
                formatRange(from, to) + " · " + count + " shift " + (count == 1 ? "report" : "reports"),
                f.small);
    }

    private static Paragraph spacer(float pt) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingBefore(pt);
        return p;
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(BRAND_DARK);
        cell.setPadding(5);
        cell.setBorderColor(BRAND_DARK);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell bodyCell(String text, Font font, Color bg, int align) {
        return bodyCell(text, font, bg, align, INK);
    }

    private static PdfPCell bodyCell(String text, Font font, Color bg, int align, Color textColor) {
        Font f = FontFactory.getFont(font.getFamilyname(), font.getSize(), textColor);
        PdfPCell cell = new PdfPCell(new Phrase(text, f));
        cell.setBackgroundColor(bg);
        cell.setPadding(4);
        cell.setBorderColor(BORDER);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell bodyCell(String text, Font font, Color bg, int align, int colspan) {
        PdfPCell cell = bodyCell(text, font, bg, align);
        cell.setColspan(colspan);
        return cell;
    }

    private static String cashierName(Map<String, Object> e) {
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) e.get("cashier");
        return c != null ? String.valueOf(c.get("name")) : "—";
    }

    private static String formatStatus(Object status) {
        if (status == null) return "—";
        String s = String.valueOf(status);
        return "LOCKED".equals(s) ? "Submitted" : "DRAFT".equals(s) ? "Draft" : s;
    }

    private static String formatSource(Object src) {
        if (src == null) return "Cash";
        return "CARD".equals(String.valueOf(src)) ? "Card" : "Cash";
    }

    private static String formatCategory(Object cat) {
        if (cat == null) return "Other";
        return String.valueOf(cat).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String formatRange(LocalDate from, LocalDate to) {
        if (from.equals(to)) return DATE_FMT.format(from);
        return DATE_FMT.format(from) + " – " + DATE_FMT.format(to);
    }

    private static String fmtDate(String iso) {
        try {
            return DATE_FMT.format(LocalDate.parse(iso, ISO_DATE));
        } catch (Exception e) {
            return iso;
        }
    }

    private static String money(Object value) {
        return PLN.format(num(value));
    }

    private static double num(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return 0;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static Color varianceColor(double diff) {
        if (diff < -0.01) return DANGER;
        if (diff > 0.01) return SUCCESS;
        return INK;
    }

    // ——— Aggregates ———

    private static final class PeriodTotals {
        double grossSales, netSales, returns, cashSales, cardSales, platformSales;
        double cashReturns, cardReturns, platformReturns;
        double wolt, bolt, uber, glovo, otherPlatform;
        double payouts, expenseLines, expenses, expectedCash, actualCash, difference, cardBalance;
        double cardExpenses;
        int draftCount, lockedCount;

        static PeriodTotals from(Map<String, Object> summary, java.util.List<Map<String, Object>> rows) {
            PeriodTotals t = new PeriodTotals();
            @SuppressWarnings("unchecked")
            Map<String, Object> totals = (Map<String, Object>) summary.get("totals");
            if (totals != null) {
                t.cashSales = num(totals.get("cashSales"));
                t.cardSales = num(totals.get("cardSales"));
                t.returns = num(totals.get("returns"));
                t.payouts = num(totals.get("payouts"));
                t.expenses = num(totals.get("expenses"));
                t.expectedCash = num(totals.get("expectedCash"));
                t.actualCash = num(totals.get("actualCash"));
                t.difference = num(totals.get("difference"));
                t.cardBalance = num(totals.get("cardBalance"));
                t.draftCount = totals.get("draftCount") instanceof Number n ? n.intValue() : 0;
                t.lockedCount = totals.get("lockedCount") instanceof Number n ? n.intValue() : 0;
            }
            for (Map<String, Object> e : rows) {
                ShiftTotals s = ShiftTotals.from(e);
                t.wolt += num(e.get("woltSales"));
                t.bolt += num(e.get("boltSales"));
                t.uber += num(e.get("uberEatsSales"));
                t.glovo += num(e.get("glovoSales"));
                t.otherPlatform += num(e.get("otherPlatformSales"));
                t.cashReturns += num(e.get("cashRefunds"));
                t.cardReturns += num(e.get("cardRefunds"));
                t.platformReturns += num(e.get("platformRefunds"));
                t.expenseLines += num(e.get("expenseLinesTotal"));
                t.cardExpenses += num(e.get("expenseCardTotal"));
            }
            t.platformSales = t.wolt + t.bolt + t.uber + t.glovo + t.otherPlatform;
            t.grossSales = t.cashSales + t.cardSales + t.platformSales;
            t.netSales = t.grossSales - t.returns;
            if (t.expenseLines == 0 && t.expenses > t.payouts) {
                t.expenseLines = t.expenses - t.payouts;
            }
            return t;
        }
    }

    private static final class ShiftTotals {
        double cashSales, cardSales, platformSales, grossSales, returns, netSales;
        double totalExpenses, cashExpenses, difference;

        static ShiftTotals from(Map<String, Object> e) {
            ShiftTotals s = new ShiftTotals();
            s.cashSales = num(e.get("cashSales"));
            s.cardSales = num(e.get("cardSales"));
            s.platformSales = num(e.get("woltSales")) + num(e.get("boltSales")) + num(e.get("uberEatsSales"))
                    + num(e.get("glovoSales")) + num(e.get("otherPlatformSales"));
            s.grossSales = s.cashSales + s.cardSales + s.platformSales;
            s.returns = num(e.get("cashRefunds")) + num(e.get("cardRefunds")) + num(e.get("platformRefunds"));
            s.netSales = s.grossSales - s.returns;
            s.cashExpenses = num(e.get("expenseCashTotal"));
            s.totalExpenses = totalExpenses(e);
            s.difference = num(e.get("difference"));
            return s;
        }
    }

    private static double totalExpenses(Map<String, Object> e) {
        if (e.containsKey("payoutsTotal") || e.containsKey("expenseLinesTotal")) {
            return num(e.get("payoutsTotal")) + num(e.get("expenseLinesTotal"));
        }
        return num(e.get("bankDeposit")) + num(e.get("cashWithdrawal")) + num(e.get("ownerWithdrawal"))
                + num(e.get("supplierPayments")) + num(e.get("pettyCash")) + num(e.get("supplies"))
                + num(e.get("staffMeals")) + num(e.get("deliveryCosts")) + num(e.get("otherExpenses"));
    }

    private static final class Fonts {
        final Font coverBrand, coverTitle, coverSubtitle, section, body, small, metaLabel;
        final Font kpiLabel, statementBold, tableHeader, tableHeaderSmall, tableSmall, shiftHead;

        private Fonts(
                Font coverBrand,
                Font coverTitle,
                Font coverSubtitle,
                Font section,
                Font body,
                Font small,
                Font metaLabel,
                Font kpiLabel,
                Font statementBold,
                Font tableHeader,
                Font tableHeaderSmall,
                Font tableSmall,
                Font shiftHead) {
            this.coverBrand = coverBrand;
            this.coverTitle = coverTitle;
            this.coverSubtitle = coverSubtitle;
            this.section = section;
            this.body = body;
            this.small = small;
            this.metaLabel = metaLabel;
            this.kpiLabel = kpiLabel;
            this.statementBold = statementBold;
            this.tableHeader = tableHeader;
            this.tableHeaderSmall = tableHeaderSmall;
            this.tableSmall = tableSmall;
            this.shiftHead = shiftHead;
        }

        static Fonts create() {
            return new Fonts(
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(255, 220, 180)),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.WHITE),
                    FontFactory.getFont(FontFactory.HELVETICA, 11, new Color(255, 248, 240)),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, BRAND_DARK),
                    FontFactory.getFont(FontFactory.HELVETICA, 9, INK),
                    FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED),
                    FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED),
                    FontFactory.getFont(FontFactory.HELVETICA, 7, MUTED),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, Color.WHITE),
                    FontFactory.getFont(FontFactory.HELVETICA, 7, INK),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK));
        }
    }

    private static final class ReportFooter extends PdfPageEventHelper {
        private final String reportTitle;
        private final LocalDate from;
        private final LocalDate to;

        ReportFooter(String reportTitle, LocalDate from, LocalDate to) {
            this.reportTitle = reportTitle;
            this.from = from;
            this.to = to;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Font footerFont = FontFactory.getFont(FontFactory.HELVETICA, 7, MUTED);
            String left = BUSINESS_NAME + " · " + reportTitle + " · Confidential";
            String right = formatRange(from, to)
                    + " · Generated " + GEN_FMT.format(LocalDateTime.now())
                    + " · Page " + writer.getPageNumber();

            PdfPTable footer = new PdfPTable(2);
            footer.setTotalWidth(document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin());
            try {
                footer.setWidths(new float[]{1.2f, 1f});
            } catch (DocumentException ignored) {}

            PdfPCell c1 = new PdfPCell(new Phrase(left, footerFont));
            c1.setBorder(Rectangle.TOP);
            c1.setBorderColor(BORDER);
            c1.setPaddingTop(6);
            c1.setHorizontalAlignment(Element.ALIGN_LEFT);
            PdfPCell c2 = new PdfPCell(new Phrase(right, footerFont));
            c2.setBorder(Rectangle.TOP);
            c2.setBorderColor(BORDER);
            c2.setPaddingTop(6);
            c2.setHorizontalAlignment(Element.ALIGN_RIGHT);
            footer.addCell(c1);
            footer.addCell(c2);
            footer.writeSelectedRows(0, -1, document.leftMargin(), document.bottomMargin() - 4, writer.getDirectContent());
        }

        private String formatRange(LocalDate from, LocalDate to) {
            if (from.equals(to)) return DATE_FMT.format(from);
            return DATE_FMT.format(from) + " – " + DATE_FMT.format(to);
        }
    }
}
