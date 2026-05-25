package com.saffron.cashflow.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
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

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * PDF renderer for the Analytics page (period summary + per-shift detail).
 *
 * Layout matches the rest of the export system (Finance, Shift reports,
 * Payouts) so a restaurant owner sees the same brand band, KPI strip,
 * grouped sections, and totals across every report.
 *
 * The public surface is intentionally narrow: a single {@code build(...)}
 * entry point that takes the period summary the API already produces.
 */
public final class PdfReportBuilder {

    // Brand palette pulled from the frontend tokens — matches ExportService.
    private static final Color BRAND_INK = new Color(0x1D, 0x1B, 0x16);
    private static final Color BRAND_SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color BRAND_CREAM = new Color(0xFA, 0xF4, 0xE8);
    private static final Color ZEBRA = new Color(0xF8, 0xF7, 0xF3);
    private static final Color MUTED = new Color(0x5C, 0x55, 0x4A);
    private static final Color GRID_LINE = new Color(0xE2, 0xDD, 0xD2);
    private static final Color POSITIVE = new Color(0x16, 0x78, 0x48);
    private static final Color NEGATIVE = new Color(0xB9, 0x32, 0x32);

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private PdfReportBuilder() {}

    public static byte[] build(
            String period,
            LocalDate from,
            LocalDate to,
            Map<String, Object> summary,
            List<Map<String, Object>> rows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            boolean singleShift = rows.size() == 1;
            String title = singleShift ? "Shift report" : "Analytics report";
            String subtitle = singleShift
                    ? "Full breakdown of a single shift — sales, expenses and cash reconciliation."
                    : describePeriod(period);

            Totals totals = Totals.from(summary, rows);

            Document doc = new Document(PageSize.A4, 36, 36, 60, 48);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new FooterEvent(title));
            doc.open();

            renderCover(doc, title, from, to, totals, subtitle);
            renderPeriodTotals(doc, totals);

            if (!rows.isEmpty()) {
                renderByCashier(doc, rows);
                renderExpenseBreakdown(doc, rows);
                renderPayoutsSummary(doc, rows);
                renderNotes(doc, rows);
            } else {
                Paragraph empty = new Paragraph("No entries in this period.",
                        font(FontFactory.HELVETICA, 10, MUTED));
                empty.setSpacingBefore(20f);
                doc.add(empty);
            }

            doc.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException("PDF generation failed", ex);
        }
    }

    // ========================================================================
    // Cover + KPI strip
    // ========================================================================

    private static void renderCover(Document doc, String title, LocalDate from, LocalDate to,
                                     Totals totals, String subtitle) throws DocumentException {
        Paragraph brand = new Paragraph("SAFFRON · CASH FLOW",
                font(FontFactory.HELVETICA, 9, BRAND_SAFFRON));
        brand.setSpacingAfter(2f);
        doc.add(brand);

        Paragraph t = new Paragraph(title, font(FontFactory.HELVETICA_BOLD, 18, BRAND_INK));
        t.setSpacingAfter(2f);
        doc.add(t);

        String range = from.format(MONTH_LABEL) + " → " + to.format(MONTH_LABEL);
        Paragraph rangeP = new Paragraph(
                range + "   ·   generated " + LocalDate.now().format(MONTH_LABEL),
                font(FontFactory.HELVETICA, 10, MUTED));
        rangeP.setSpacingAfter(10f);
        doc.add(rangeP);

        if (subtitle != null && !subtitle.isBlank()) {
            Paragraph sub = new Paragraph(subtitle, font(FontFactory.HELVETICA, 10, MUTED));
            sub.setSpacingAfter(14f);
            doc.add(sub);
        }

        PdfPTable kpi = new PdfPTable(4);
        kpi.setWidthPercentage(100);
        kpi.setSpacingAfter(18f);
        addKpi(kpi, "Net revenue", money(totals.netSales), BRAND_INK);
        addKpi(kpi, "Cash sales", money(totals.cashSales), BRAND_INK);
        addKpi(kpi, "Card sales", money(totals.cardSales), BRAND_INK);
        addKpi(kpi, "Cash variance", signedMoney(totals.difference), varianceColor(totals.difference));
        doc.add(kpi);
    }

    private static void addKpi(PdfPTable t, String label, String value, Color valueColor) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(GRID_LINE);
        cell.setBackgroundColor(BRAND_CREAM);
        cell.setPadding(10f);
        Paragraph l = new Paragraph(label.toUpperCase(Locale.ENGLISH),
                font(FontFactory.HELVETICA_BOLD, 7, MUTED));
        l.setSpacingAfter(4f);
        cell.addElement(l);
        cell.addElement(new Paragraph(value, font(FontFactory.HELVETICA_BOLD, 13, valueColor)));
        t.addCell(cell);
    }

    // ========================================================================
    // Period totals statement
    // ========================================================================

    private static void renderPeriodTotals(Document doc, Totals t) throws DocumentException {
        sectionHeader(doc, "Period totals",
                t.lockedCount + " submitted" + (t.draftCount > 0 ? " · " + t.draftCount + " draft" : ""),
                money(t.netSales));

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[]{4.5f, 2f});
        } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);

        statementRow(table, "Cash sales", money(t.cashSales), false);
        statementRow(table, "Card sales", money(t.cardSales), false);
        statementRow(table, "Platform sales (Wolt, Bolt, Uber, Glovo, other)", money(t.platformSales), false);
        statementRow(table, "Gross revenue", money(t.grossSales), true);
        statementRow(table, "Returns / refunds", "-" + money(t.returns), false);
        statementRow(table, "Net revenue", money(t.netSales), true);
        statementRow(table, "Payouts (bank, cash, owner)", "-" + money(t.payouts), false);
        statementRow(table, "Operating expenses", "-" + money(t.expenseLines), false);
        statementRow(table, "Total outflows", "-" + money(t.payouts + t.expenseLines), true);
        statementRow(table, "Expected cash (sum of shifts)", money(t.expectedCash), false);
        statementRow(table, "Actual cash counted", money(t.actualCash), false);
        statementColoredRow(table, "Cash variance (over / short)",
                signedMoney(t.difference), varianceColor(t.difference));
        statementRow(table, "Card balance (net position)", money(t.cardBalance), false);

        doc.add(table);
    }

    private static void statementRow(PdfPTable table, String label, String value, boolean strong) {
        Font labelFont = strong
                ? font(FontFactory.HELVETICA_BOLD, 10, BRAND_INK)
                : font(FontFactory.HELVETICA, 9, BRAND_INK);
        Font valueFont = strong
                ? font(FontFactory.HELVETICA_BOLD, 10, BRAND_INK)
                : font(FontFactory.HELVETICA, 9, BRAND_INK);
        Color bg = strong ? BRAND_CREAM : Color.WHITE;
        table.addCell(statementCell(label, labelFont, Element.ALIGN_LEFT, bg));
        table.addCell(statementCell(value, valueFont, Element.ALIGN_RIGHT, bg));
    }

    private static void statementColoredRow(PdfPTable table, String label, String value, Color amountColor) {
        Font labelFont = font(FontFactory.HELVETICA_BOLD, 10, BRAND_INK);
        Font valueFont = font(FontFactory.HELVETICA_BOLD, 10, amountColor);
        table.addCell(statementCell(label, labelFont, Element.ALIGN_LEFT, BRAND_CREAM));
        table.addCell(statementCell(value, valueFont, Element.ALIGN_RIGHT, BRAND_CREAM));
    }

    private static PdfPCell statementCell(String text, Font font, int align, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(align);
        cell.setBorderColor(GRID_LINE);
        cell.setBorderWidth(0.5f);
        cell.setPadding(6f);
        return cell;
    }

    // ========================================================================
    // By-cashier section
    // ========================================================================

    private static void renderByCashier(Document doc, List<Map<String, Object>> rows) throws DocumentException {
        // Aggregate per cashier
        Map<String, CashierAgg> agg = new TreeMap<>();
        for (Map<String, Object> e : rows) {
            String name = cashierName(e);
            CashierAgg c = agg.computeIfAbsent(name, k -> new CashierAgg());
            c.shifts++;
            c.cash += num(e.get("cashSales"));
            c.card += num(e.get("cardSales"));
            c.delivery += num(e.get("woltSales")) + num(e.get("boltSales"))
                    + num(e.get("uberEatsSales")) + num(e.get("glovoSales"))
                    + num(e.get("otherPlatformSales"));
            c.difference += num(e.get("difference"));
        }

        List<Map.Entry<String, CashierAgg>> sorted = new java.util.ArrayList<>(agg.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue().total(), a.getValue().total()));

        double grandCash = 0, grandCard = 0, grandDeliv = 0, grandDiff = 0;
        for (var e : sorted) {
            grandCash += e.getValue().cash;
            grandCard += e.getValue().card;
            grandDeliv += e.getValue().delivery;
            grandDiff += e.getValue().difference;
        }

        sectionHeader(doc, "By cashier",
                sorted.size() + " cashier" + (sorted.size() == 1 ? "" : "s"),
                money(grandCash + grandCard + grandDeliv));

        float[] widths = {2.4f, 1.0f, 1.6f, 1.6f, 1.6f, 1.6f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try {
            table.setWidths(widths);
        } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);

        addTableHeader(table, List.of("Cashier", "Shifts", "Cash", "Card", "Delivery", "Cash variance"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_CENTER, Element.ALIGN_RIGHT,
                        Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT});

        int i = 0;
        for (var entry : sorted) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            CashierAgg c = entry.getValue();
            addBodyCell(table, entry.getKey(), Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, String.valueOf(c.shifts), Element.ALIGN_CENTER, bg, BRAND_INK);
            addBodyCell(table, money(c.cash), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(c.card), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(c.delivery), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, signedMoney(c.difference), Element.ALIGN_RIGHT, bg, varianceColor(c.difference));
        }

        // Total row
        Font totalFont = font(FontFactory.HELVETICA_BOLD, 9, BRAND_INK);
        addCell(table, "Total", totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, String.valueOf(rows.size()), totalFont, Element.ALIGN_CENTER, BRAND_CREAM);
        addCell(table, money(grandCash), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandCard), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandDeliv), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, signedMoney(grandDiff),
                font(FontFactory.HELVETICA_BOLD, 9, varianceColor(grandDiff)),
                Element.ALIGN_RIGHT, BRAND_CREAM);

        doc.add(table);
    }

    // ========================================================================
    // Expense breakdown by category
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void renderExpenseBreakdown(Document doc, List<Map<String, Object>> rows) throws DocumentException {
        // Aggregate by category, split cash vs card
        Map<String, double[]> byCategory = new TreeMap<>();
        for (Map<String, Object> e : rows) {
            List<Map<String, Object>> expenses = (List<Map<String, Object>>) e.get("expenses");
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
        if (byCategory.isEmpty()) return;

        double grandTotal = 0, grandCash = 0, grandCard = 0;
        for (double[] v : byCategory.values()) {
            grandTotal += v[0];
            grandCard += v[1];
            grandCash += v[2];
        }

        sectionHeader(doc, "Expenses by category",
                byCategory.size() + " categor" + (byCategory.size() == 1 ? "y" : "ies"),
                money(grandTotal));

        float[] widths = {3.5f, 1.6f, 1.6f, 1.8f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try {
            table.setWidths(widths);
        } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);

        addTableHeader(table, List.of("Category", "Cash", "Card", "Total"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT});

        // Sort by total spend desc
        List<Map.Entry<String, double[]>> entries = new java.util.ArrayList<>(byCategory.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        int i = 0;
        for (var entry : entries) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            double[] s = entry.getValue();
            addBodyCell(table, entry.getKey(), Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, money(s[2]), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(s[1]), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(s[0]), Element.ALIGN_RIGHT, bg, BRAND_INK);
        }
        Font totalFont = font(FontFactory.HELVETICA_BOLD, 9, BRAND_INK);
        addCell(table, "Total", totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandCash), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandCard), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandTotal), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        doc.add(table);
    }

    // ========================================================================
    // Payouts summary
    // ========================================================================

    private static void renderPayoutsSummary(Document doc, List<Map<String, Object>> rows) throws DocumentException {
        double bank = 0, cash = 0, owner = 0;
        for (Map<String, Object> e : rows) {
            bank += num(e.get("bankDeposit"));
            cash += num(e.get("cashWithdrawal"));
            owner += num(e.get("ownerWithdrawal"));
        }
        double total = bank + cash + owner;
        if (total == 0) return;

        sectionHeader(doc, "Payouts & transfers", "From shift reports", money(total));

        float[] widths = {3.5f, 2f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try {
            table.setWidths(widths);
        } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);

        addTableHeader(table, List.of("Type", "Amount"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_RIGHT});

        int i = 0;
        if (bank != 0) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            addBodyCell(table, "Bank deposit", Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, money(bank), Element.ALIGN_RIGHT, bg, BRAND_INK);
        }
        if (cash != 0) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            addBodyCell(table, "Cash withdrawal", Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, money(cash), Element.ALIGN_RIGHT, bg, BRAND_INK);
        }
        if (owner != 0) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            addBodyCell(table, "Owner withdrawal", Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, money(owner), Element.ALIGN_RIGHT, bg, BRAND_INK);
        }
        Font totalFont = font(FontFactory.HELVETICA_BOLD, 9, BRAND_INK);
        addCell(table, "Total", totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(total), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        doc.add(table);
    }

    // ========================================================================
    // Notes
    // ========================================================================

    private static void renderNotes(Document doc, List<Map<String, Object>> rows) throws DocumentException {
        java.util.List<String> notes = new java.util.ArrayList<>();
        for (Map<String, Object> e : rows) {
            Object raw = e.get("notes");
            if (raw == null) continue;
            String text = String.valueOf(raw);
            if (text.isBlank()) continue;
            notes.add(dateShort(e.get("date")) + " · " + cashierName(e) + ": " + text);
        }
        if (notes.isEmpty()) return;

        sectionHeader(doc, "Notes", notes.size() + " note" + (notes.size() == 1 ? "" : "s"), "");
        for (String n : notes) {
            Paragraph p = new Paragraph("• " + n, font(FontFactory.HELVETICA, 9, BRAND_INK));
            p.setSpacingAfter(4f);
            p.setIndentationLeft(8f);
            doc.add(p);
        }
    }

    // ========================================================================
    // Layout helpers
    // ========================================================================

    private static void sectionHeader(Document doc, String title, String meta, String trailingAmount)
            throws DocumentException {
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        try {
            header.setWidths(new float[]{6f, 3f});
        } catch (DocumentException ignored) {}
        header.setSpacingBefore(4f);
        header.setSpacingAfter(0f);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.setPaddingBottom(6f);
        left.setPaddingTop(8f);
        left.addElement(new Paragraph(title, font(FontFactory.HELVETICA_BOLD, 12, BRAND_INK)));
        if (meta != null && !meta.isBlank()) {
            left.addElement(new Paragraph(meta, font(FontFactory.HELVETICA, 9, MUTED)));
        }
        header.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setPaddingTop(10f);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (trailingAmount != null && !trailingAmount.isBlank()) {
            Paragraph amount = new Paragraph(trailingAmount,
                    font(FontFactory.HELVETICA_BOLD, 12, BRAND_SAFFRON));
            amount.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(amount);
        }
        header.addCell(right);

        doc.add(header);
    }

    private static void addTableHeader(PdfPTable table, List<String> headers, int[] alignments) {
        Font hf = font(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        for (int i = 0; i < headers.size(); i++) {
            PdfPCell cell = new PdfPCell(new Phrase(headers.get(i), hf));
            cell.setBackgroundColor(BRAND_INK);
            cell.setBorderColor(BRAND_INK);
            cell.setHorizontalAlignment(alignments[i]);
            cell.setPadding(6f);
            table.addCell(cell);
        }
    }

    private static void addBodyCell(PdfPTable table, String text, int alignment, Color bg, Color textColor) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(FontFactory.HELVETICA, 9, textColor)));
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

    private static void addCell(PdfPTable table, String text, Font font, int alignment, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(alignment);
        cell.setBorderColor(GRID_LINE);
        cell.setBorderWidth(0.5f);
        cell.setPadding(6f);
        table.addCell(cell);
    }

    // ========================================================================
    // Formatting helpers
    // ========================================================================

    private static Font font(String family, float size, Color color) {
        return FontFactory.getFont(family, size, color);
    }

    /** Money: `1,234.56 PLN` — same format as the rest of the export system. */
    private static String money(double v) {
        BigDecimal x = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
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

    /** Money with explicit sign for variances. */
    private static String signedMoney(double v) {
        return v > 0 ? "+" + money(v) : money(v);
    }

    private static Color varianceColor(double v) {
        if (v < -0.01) return NEGATIVE;
        if (v > 0.01) return POSITIVE;
        return BRAND_INK;
    }

    private static String dateShort(Object raw) {
        if (raw == null) return "";
        try {
            return LocalDate.parse(String.valueOf(raw)).format(SHORT_DATE);
        } catch (Exception ignored) {
            return String.valueOf(raw);
        }
    }

    private static String describePeriod(String period) {
        if (period == null) return "";
        return switch (period.toLowerCase(Locale.ENGLISH)) {
            case "daily" -> "Daily summary";
            case "weekly" -> "Weekly summary";
            case "monthly" -> "Monthly summary";
            default -> "";
        };
    }

    private static double num(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static String cashierName(Map<String, Object> e) {
        Object c = e.get("cashier");
        if (c instanceof Map<?, ?> m) {
            Object n = ((Map<String, Object>) m).get("name");
            return n != null ? String.valueOf(n) : "Unknown";
        }
        return "Unknown";
    }

    private static String formatCategory(Object cat) {
        if (cat == null) return "Other";
        String s = String.valueOf(cat).toLowerCase(Locale.ENGLISH).replace('_', ' ');
        // Title-case the words for readability.
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char ch : s.toCharArray()) {
            sb.append(upper ? Character.toUpperCase(ch) : ch);
            upper = ch == ' ';
        }
        return sb.toString();
    }

    private static String formatSource(Object src) {
        if (src == null) return "Cash";
        return "CARD".equals(String.valueOf(src)) ? "Card" : "Cash";
    }

    // ========================================================================
    // Aggregates
    // ========================================================================

    private static final class CashierAgg {
        int shifts;
        double cash, card, delivery, difference;

        double total() {
            return cash + card + delivery;
        }
    }

    private static final class Totals {
        double grossSales, netSales, returns;
        double cashSales, cardSales, platformSales;
        double payouts, expenseLines;
        double expectedCash, actualCash, difference, cardBalance;
        int draftCount, lockedCount;

        static Totals from(Map<String, Object> summary, List<Map<String, Object>> rows) {
            Totals t = new Totals();
            @SuppressWarnings("unchecked")
            Map<String, Object> totals = summary == null
                    ? null
                    : (Map<String, Object>) summary.get("totals");
            if (totals != null) {
                t.cashSales = num(totals.get("cashSales"));
                t.cardSales = num(totals.get("cardSales"));
                t.returns = num(totals.get("returns"));
                t.payouts = num(totals.get("payouts"));
                double rawExpenses = num(totals.get("expenses"));
                t.expectedCash = num(totals.get("expectedCash"));
                t.actualCash = num(totals.get("actualCash"));
                t.difference = num(totals.get("difference"));
                t.cardBalance = num(totals.get("cardBalance"));
                t.draftCount = totals.get("draftCount") instanceof Number n ? n.intValue() : 0;
                t.lockedCount = totals.get("lockedCount") instanceof Number n ? n.intValue() : 0;
                // expenses field from summary already excludes payouts in some flows
                // and includes them in others — be defensive.
                t.expenseLines = Math.max(0, rawExpenses - t.payouts);
            }
            for (Map<String, Object> e : rows) {
                t.platformSales += num(e.get("woltSales")) + num(e.get("boltSales"))
                        + num(e.get("uberEatsSales")) + num(e.get("glovoSales"))
                        + num(e.get("otherPlatformSales"));
            }
            t.grossSales = t.cashSales + t.cardSales + t.platformSales;
            t.netSales = t.grossSales - t.returns;
            return t;
        }
    }

    // ========================================================================
    // Footer
    // ========================================================================

    private static final class FooterEvent extends PdfPageEventHelper {
        private final String reportTitle;

        FooterEvent(String reportTitle) {
            this.reportTitle = reportTitle;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle pageSize = document.getPageSize();
            Font f = font(FontFactory.HELVETICA, 8, MUTED);
            float y = pageSize.getBottom() + 20;
            ColumnText.showTextAligned(writer.getDirectContent(),
                    Element.ALIGN_LEFT, new Phrase("Saffron · " + reportTitle, f),
                    pageSize.getLeft() + document.leftMargin(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(),
                    Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), f),
                    pageSize.getRight() - document.rightMargin(), y, 0);
        }
    }

}
