package com.saffron.cashflow.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Multi-section analytics PDF renderer.
 *
 * <p>Produces a management-report-style document covering the full
 * picture a restaurant owner needs each period — sales mix, P&amp;L,
 * treasury position, payroll exposure, per-cashier scorecard, menu
 * engineering, and notes. Every section is gated on data presence so
 * the same builder handles both the rich monthly export and the
 * stripped-down single-shift export.</p>
 *
 * <p>Designed to be called from {@link com.saffron.cashflow.service.ReportService}
 * via a single {@link #build(AnalyticsReportContext)} entry point. All
 * data is supplied through {@link AnalyticsReportContext}; this class
 * never reaches into the database.</p>
 *
 * <p>Uses the bundled Noto Serif / Noto Sans fonts so Azerbaijani and
 * Polish diacritics render correctly — same approach as
 * {@code MenuPrintService}.</p>
 */
public final class PdfReportBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(PdfReportBuilder.class);

    // ========================================================================
    // Brand palette (matches MenuPrintService + ExportService)
    // ========================================================================
    private static final Color BRAND_INK = new Color(0x1D, 0x1B, 0x16);
    private static final Color BRAND_SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color BRAND_SAFFRON_DEEP = new Color(0xA4, 0x52, 0x12);
    private static final Color BRAND_CREAM = new Color(0xFA, 0xF4, 0xE8);
    private static final Color BRAND_CREAM_DEEP = new Color(0xF3, 0xE9, 0xD1);
    private static final Color ZEBRA = new Color(0xF8, 0xF7, 0xF3);
    private static final Color MUTED = new Color(0x5C, 0x55, 0x4A);
    private static final Color GRID_LINE = new Color(0xE2, 0xDD, 0xD2);
    private static final Color POSITIVE = new Color(0x16, 0x78, 0x48);
    private static final Color NEGATIVE = new Color(0xB9, 0x32, 0x32);

    // Per-platform color palette for the revenue chart.
    private static final Color PLATFORM_CASH = new Color(0x2E, 0x7D, 0x32);
    private static final Color PLATFORM_CARD = new Color(0x1A, 0x4B, 0x8A);
    private static final Color PLATFORM_WOLT = new Color(0x00, 0xB1, 0xC1);
    private static final Color PLATFORM_BOLT = new Color(0x34, 0xD1, 0x86);
    private static final Color PLATFORM_UBER = new Color(0x16, 0x16, 0x16);
    private static final Color PLATFORM_GLOVO = new Color(0xFF, 0xA5, 0x00);
    private static final Color PLATFORM_OTHER = new Color(0x96, 0x6F, 0x33);

    // ========================================================================
    // Bundled Unicode fonts. Built-in PDF fonts (WinAnsi) silently drop
    // Azerbaijani schwa, Ş/ş, ə and a few other characters that show up in
    // cashier names and notes. Bundling Noto + embedding subsets fixes it.
    // ========================================================================
    private static final BaseFont SANS_REG = loadFont("NotoSans-Regular.ttf", BaseFont.HELVETICA);
    private static final BaseFont SANS_BOLD = loadFont("NotoSans-Bold.ttf", BaseFont.HELVETICA_BOLD);
    private static final BaseFont SANS_ITALIC = loadFont("NotoSans-Italic.ttf", BaseFont.HELVETICA_OBLIQUE);
    private static final BaseFont SERIF_BOLD = loadFont("NotoSerif-Bold.ttf", BaseFont.HELVETICA_BOLD);

    private static BaseFont loadFont(String name, String fallback) {
        try (InputStream in = PdfReportBuilder.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) throw new IllegalStateException("font not on classpath: " + name);
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    BaseFont.CACHED, bytes, null);
        } catch (Exception e) {
            LOG.warn("PdfReportBuilder: failed to load {} ({}). "
                    + "Falling back to {}. Non-Latin characters may not render.",
                    name, e.getMessage(), fallback);
            try {
                return BaseFont.createFont(fallback, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot load any PDF font", ex);
            }
        }
    }

    private static Font font(BaseFont bf, float size, Color color) {
        Font f = new Font(bf, size);
        f.setColor(color);
        return f;
    }

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_OF_WEEK = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_NUM = DateTimeFormatter.ofPattern("d", Locale.ENGLISH);

    private PdfReportBuilder() {}

    // ========================================================================
    // Public entry point
    // ========================================================================

    /**
     * Render the analytics PDF.
     *
     * @param ctx fully-populated bundle of aggregates; any optional field
     *            may be {@code null} and its section will simply be skipped.
     * @return PDF bytes — never null.
     */
    public static byte[] build(AnalyticsReportContext ctx) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            boolean singleShift = ctx.isSingleShift();
            String title = singleShift ? "Shift report" : "Analytics report";

            Totals totals = Totals.from(ctx.summary(), ctx.rows());
            Totals priorTotals = ctx.priorSummary() != null
                    ? Totals.from(ctx.priorSummary(), null)
                    : null;

            Document doc = new Document(PageSize.A4, 36, 36, 64, 52);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new FooterEvent(title));
            doc.open();

            renderCover(doc, title, ctx, totals, priorTotals);
            renderHeadlineKpis(doc, totals, priorTotals);

            if (ctx.rows() == null || ctx.rows().isEmpty()) {
                Paragraph empty = new Paragraph(
                        "No shift reports recorded for this period.",
                        font(SANS_ITALIC, 10, MUTED));
                empty.setSpacingBefore(24f);
                doc.add(empty);
            } else {
                renderRevenueMix(doc, totals);
                renderDailyChart(doc, ctx.rows(), ctx.from(), ctx.to());

                if (ctx.profitLoss() != null) {
                    renderProfitLoss(doc, ctx.profitLoss());
                }
                if (ctx.treasury() != null) {
                    renderTreasurySnapshot(doc, ctx.treasury());
                }
                if (ctx.payroll() != null) {
                    renderPayrollExposure(doc, ctx.payroll(), totals.netSales);
                }
                renderByCashier(doc, ctx.rows());
                renderExpenseBreakdown(doc, ctx.rows(), ctx.profitLoss());
                renderPayoutsSummary(doc, ctx.rows());
                if (ctx.menuAnalytics() != null) {
                    renderTopMenuItems(doc, ctx.menuAnalytics());
                }
                if (ctx.menuEngineering() != null) {
                    renderMenuActions(doc, ctx.menuEngineering());
                }
                renderNotes(doc, ctx.rows());
            }

            doc.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException("PDF generation failed", ex);
        }
    }

    // ========================================================================
    // Cover — eyebrow, title, range, narrative paragraph
    // ========================================================================

    private static void renderCover(Document doc, String title, AnalyticsReportContext ctx,
                                     Totals totals, Totals prior) throws DocumentException {
        Paragraph brand = new Paragraph("SAFFRON · CASH FLOW",
                font(SANS_BOLD, 9, BRAND_SAFFRON));
        brand.setSpacingAfter(2f);
        doc.add(brand);

        Paragraph t = new Paragraph(title, font(SERIF_BOLD, 22, BRAND_INK));
        t.setSpacingAfter(2f);
        doc.add(t);

        String range = ctx.from().format(LONG_DATE) + " → " + ctx.to().format(LONG_DATE);
        long days = Period.between(ctx.from(), ctx.to()).getDays() + 1;
        String rangeLine = range + "   ·   " + days + (days == 1 ? " day" : " days")
                + "   ·   generated " + LocalDate.now().format(LONG_DATE);
        Paragraph rangeP = new Paragraph(rangeLine, font(SANS_REG, 9, MUTED));
        rangeP.setSpacingAfter(12f);
        doc.add(rangeP);

        // Narrative paragraph — describes what happened in plain language
        // so a busy owner can read it like a memo before diving into the
        // numbers below.
        String narrative = buildNarrative(ctx, totals, prior);
        Paragraph sub = new Paragraph(narrative, font(SANS_REG, 10.5f, BRAND_INK));
        sub.setLeading(15f);
        sub.setSpacingAfter(16f);
        doc.add(sub);
    }

    /** Compose a human-readable summary of the period. */
    private static String buildNarrative(AnalyticsReportContext ctx, Totals t, Totals prior) {
        StringBuilder sb = new StringBuilder();
        if (ctx.isSingleShift()) {
            sb.append("Full breakdown of one shift — opening cash, sales by channel, "
                    + "expenses, payouts, and the closing reconciliation.");
            return sb.toString();
        }
        String periodLabel = describePeriod(ctx.period());
        if (periodLabel != null && !periodLabel.isBlank()) {
            sb.append(periodLabel).append(". ");
        } else {
            sb.append("Operations review for the selected period. ");
        }

        sb.append("Net revenue ").append(money(t.netSales));
        if (prior != null && prior.netSales > 0) {
            double delta = t.netSales - prior.netSales;
            double pct = (delta / prior.netSales) * 100.0;
            String dir = delta >= 0 ? "up" : "down";
            sb.append(" — ").append(dir).append(' ')
                    .append(String.format(Locale.ENGLISH, "%.1f%%", Math.abs(pct)))
                    .append(" vs the prior ").append(daysBetween(ctx.priorFrom(), ctx.priorTo()))
                    .append(" days (").append(money(prior.netSales)).append(")");
        }
        sb.append(". Cash sales ").append(money(t.cashSales))
                .append(", card sales ").append(money(t.cardSales));
        if (t.platformSales > 0.5) {
            sb.append(", platform sales ").append(money(t.platformSales));
        }
        sb.append('.');

        // Variance flag
        if (Math.abs(t.difference) > 0.5) {
            String flavor = t.difference < 0 ? "short" : "over";
            sb.append(" Cash drawer ").append(flavor).append(' ')
                    .append(money(Math.abs(t.difference)))
                    .append(" across ").append(t.lockedCount + t.draftCount).append(" shift")
                    .append((t.lockedCount + t.draftCount) == 1 ? "" : "s").append('.');
        }
        if (t.draftCount > 0) {
            sb.append(' ').append(t.draftCount).append(" report")
                    .append(t.draftCount == 1 ? "" : "s")
                    .append(" still in draft.");
        }
        return sb.toString();
    }

    // ========================================================================
    // Headline KPIs with vs-prior delta
    // ========================================================================

    private static void renderHeadlineKpis(Document doc, Totals t, Totals prior) throws DocumentException {
        PdfPTable kpi = new PdfPTable(4);
        kpi.setWidthPercentage(100);
        kpi.setSpacingAfter(16f);
        addKpi(kpi, "Net revenue", money(t.netSales), BRAND_INK,
                deltaLabel(t.netSales, prior == null ? null : prior.netSales));
        addKpi(kpi, "Cash sales", money(t.cashSales), BRAND_INK,
                deltaLabel(t.cashSales, prior == null ? null : prior.cashSales));
        addKpi(kpi, "Card sales", money(t.cardSales), BRAND_INK,
                deltaLabel(t.cardSales, prior == null ? null : prior.cardSales));
        addKpi(kpi, "Cash variance", signedMoney(t.difference), varianceColor(t.difference),
                deltaLabel(t.difference, prior == null ? null : prior.difference));
        doc.add(kpi);
    }

    private static void addKpi(PdfPTable t, String label, String value, Color valueColor, String delta) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(GRID_LINE);
        cell.setBackgroundColor(BRAND_CREAM);
        cell.setPadding(10f);
        Paragraph l = new Paragraph(label.toUpperCase(Locale.ENGLISH),
                font(SANS_BOLD, 7, MUTED));
        l.setSpacingAfter(4f);
        cell.addElement(l);
        cell.addElement(new Paragraph(value, font(SERIF_BOLD, 13, valueColor)));
        if (delta != null) {
            Paragraph d = new Paragraph(delta,
                    font(SANS_REG, 7.5f, delta.startsWith("+") ? POSITIVE
                            : delta.startsWith("-") ? NEGATIVE : MUTED));
            d.setSpacingBefore(2f);
            cell.addElement(d);
        }
        t.addCell(cell);
    }

    private static String deltaLabel(double current, Double prior) {
        if (prior == null || Math.abs(prior) < 0.005) return null;
        double delta = current - prior;
        double pct = (delta / prior) * 100.0;
        String sign = delta >= 0 ? "+" : "-";
        return sign + String.format(Locale.ENGLISH, "%.1f%% vs prior", Math.abs(pct));
    }

    // ========================================================================
    // Revenue mix — per-platform breakdown with share bars
    // ========================================================================

    private static void renderRevenueMix(Document doc, Totals t) throws DocumentException {
        double total = t.cashSales + t.cardSales + t.woltSales + t.boltSales
                + t.uberSales + t.glovoSales + t.otherPlatform;
        if (total <= 0.005) return;

        sectionHeader(doc, "Revenue mix", "Where the sales came from", money(total));

        float[] widths = {2.2f, 1.4f, 4f, 1.2f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);

        addTableHeader(table, List.of("Channel", "Amount", "Share", "%"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_RIGHT, Element.ALIGN_LEFT, Element.ALIGN_RIGHT});

        addMixRow(table, "Cash sales",        t.cashSales,    total, PLATFORM_CASH, 0);
        addMixRow(table, "Card sales",        t.cardSales,    total, PLATFORM_CARD, 1);
        if (t.woltSales > 0.005) addMixRow(table, "Wolt", t.woltSales, total, PLATFORM_WOLT, 2);
        if (t.boltSales > 0.005) addMixRow(table, "Bolt", t.boltSales, total, PLATFORM_BOLT, 3);
        if (t.uberSales > 0.005) addMixRow(table, "Uber Eats", t.uberSales, total, PLATFORM_UBER, 4);
        if (t.glovoSales > 0.005) addMixRow(table, "Glovo", t.glovoSales, total, PLATFORM_GLOVO, 5);
        if (t.otherPlatform > 0.005) addMixRow(table, "Other platforms", t.otherPlatform, total, PLATFORM_OTHER, 6);

        doc.add(table);
    }

    private static void addMixRow(PdfPTable table, String label, double amount, double total,
                                   Color barColor, int zebraIndex) {
        Color bg = (zebraIndex & 1) == 1 ? ZEBRA : Color.WHITE;
        double share = total > 0 ? amount / total : 0;
        addBodyCell(table, label, Element.ALIGN_LEFT, bg, BRAND_INK);
        addBodyCell(table, money(amount), Element.ALIGN_RIGHT, bg, BRAND_INK);
        // Share bar cell drawn with a CellEvent so we get pixel-perfect rectangles
        PdfPCell barCell = new PdfPCell();
        barCell.setBackgroundColor(bg);
        barCell.setBorderColor(GRID_LINE);
        barCell.setBorderWidth(0.5f);
        barCell.setPaddingTop(7f);
        barCell.setPaddingBottom(7f);
        barCell.setPaddingLeft(6f);
        barCell.setPaddingRight(6f);
        barCell.setFixedHeight(20f);
        barCell.setCellEvent(new ShareBarCellEvent(share, barColor));
        table.addCell(barCell);
        addBodyCell(table, String.format(Locale.ENGLISH, "%.1f%%", share * 100), Element.ALIGN_RIGHT, bg, BRAND_INK);
    }

    /** Cell event that paints a horizontal share bar inside the cell area. */
    private static final class ShareBarCellEvent implements com.lowagie.text.pdf.PdfPCellEvent {
        private final double share;
        private final Color color;
        ShareBarCellEvent(double share, Color color) {
            this.share = Math.max(0, Math.min(1, share));
            this.color = color;
        }
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[com.lowagie.text.pdf.PdfPTable.BACKGROUNDCANVAS];
            float pad = 5f;
            float x = position.getLeft() + pad;
            float y = position.getBottom() + (position.getHeight() / 2f) - 3f;
            float w = position.getWidth() - (pad * 2);
            float h = 6f;
            cb.saveState();
            // Track
            cb.setColorFill(BRAND_CREAM_DEEP);
            cb.rectangle(x, y, w, h);
            cb.fill();
            // Bar
            cb.setColorFill(color);
            cb.rectangle(x, y, (float) (w * share), h);
            cb.fill();
            cb.restoreState();
        }
    }

    // ========================================================================
    // Daily revenue chart — stacked bar (cash, card, platforms) per day
    // ========================================================================

    private static void renderDailyChart(Document doc, List<Map<String, Object>> rows,
                                          LocalDate from, LocalDate to) throws DocumentException {
        // Aggregate per-day totals
        Map<LocalDate, double[]> byDay = new TreeMap<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to) && Period.between(from, cursor).getDays() < 92) {
            byDay.put(cursor, new double[3]); // cash / card / platforms
            cursor = cursor.plusDays(1);
        }
        for (Map<String, Object> e : rows) {
            LocalDate d;
            try { d = LocalDate.parse(String.valueOf(e.get("date"))); }
            catch (Exception ex) { continue; }
            double[] s = byDay.get(d);
            if (s == null) continue;
            s[0] += num(e.get("cashSales"));
            s[1] += num(e.get("cardSales"));
            s[2] += num(e.get("woltSales")) + num(e.get("boltSales"))
                    + num(e.get("uberEatsSales")) + num(e.get("glovoSales"))
                    + num(e.get("otherPlatformSales"));
        }
        if (byDay.isEmpty() || byDay.size() > 92) {
            // Skip when the range is empty or too wide to render meaningfully.
            return;
        }
        double max = 0;
        for (double[] s : byDay.values()) max = Math.max(max, s[0] + s[1] + s[2]);
        if (max <= 0.005) return;

        sectionHeader(doc, "Daily revenue", byDay.size() + " day" + (byDay.size() == 1 ? "" : "s"),
                "Cash · Card · Platforms");

        // Anchor a PdfPTable as a placeholder so the chart flows with the
        // document — actual rendering happens in a cell event so we get
        // exact coordinates.
        PdfPTable chartHost = new PdfPTable(1);
        chartHost.setWidthPercentage(100);
        chartHost.setSpacingAfter(14f);
        PdfPCell host = new PdfPCell();
        host.setBorder(Rectangle.NO_BORDER);
        host.setFixedHeight(180f);
        host.setCellEvent(new DailyBarChartCellEvent(byDay, max));
        chartHost.addCell(host);
        doc.add(chartHost);

        // Legend
        PdfPTable legend = new PdfPTable(3);
        legend.setWidthPercentage(60);
        legend.setHorizontalAlignment(Element.ALIGN_LEFT);
        legend.setSpacingAfter(14f);
        addLegendCell(legend, "Cash", PLATFORM_CASH);
        addLegendCell(legend, "Card", PLATFORM_CARD);
        addLegendCell(legend, "Platforms", PLATFORM_WOLT);
        doc.add(legend);
    }

    private static void addLegendCell(PdfPTable t, String label, Color color) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3f);
        cell.setCellEvent((c, pos, canvases) -> {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            float dx = pos.getLeft() + 4f;
            float dy = pos.getBottom() + (pos.getHeight() / 2f) - 3f;
            cb.saveState();
            cb.setColorFill(color);
            cb.rectangle(dx, dy, 8f, 6f);
            cb.fill();
            cb.restoreState();
        });
        cell.setPaddingLeft(18f);
        cell.addElement(new Paragraph(label, font(SANS_REG, 9, BRAND_INK)));
        t.addCell(cell);
    }

    /** Stacked bar chart for daily revenue. */
    private static final class DailyBarChartCellEvent implements com.lowagie.text.pdf.PdfPCellEvent {
        private final Map<LocalDate, double[]> data;
        private final double maxVal;
        DailyBarChartCellEvent(Map<LocalDate, double[]> data, double maxVal) {
            this.data = data;
            this.maxVal = maxVal;
        }
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            float padL = 36f, padR = 8f, padT = 8f, padB = 22f;
            float chartX = position.getLeft() + padL;
            float chartY = position.getBottom() + padB;
            float chartW = position.getWidth() - padL - padR;
            float chartH = position.getHeight() - padT - padB;
            int n = data.size();
            if (n <= 0 || chartH <= 0) return;

            cb.saveState();
            // Grid (4 dotted lines from 0 → max)
            cb.setColorStroke(GRID_LINE);
            cb.setLineWidth(0.4f);
            cb.setLineDash(new float[]{1.5f, 1.5f}, 0);
            for (int i = 0; i <= 4; i++) {
                float y = chartY + (chartH * i / 4f);
                cb.moveTo(chartX, y);
                cb.lineTo(chartX + chartW, y);
            }
            cb.stroke();
            cb.setLineDash(new float[]{}, 0);

            // Y-axis labels (0, 25%, 50%, 75%, 100% of max)
            Font axisFont = font(SANS_REG, 7, MUTED);
            for (int i = 0; i <= 4; i++) {
                double v = maxVal * i / 4.0;
                float y = chartY + (chartH * i / 4f) - 2f;
                ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                        new Phrase(shortMoney(v), axisFont), chartX - 4f, y, 0);
            }

            float barGap = 2f;
            float barW = Math.max(2f, (chartW - barGap * (n - 1)) / n);
            int idx = 0;
            // X-axis tick labels (show every Nth so they don't overlap)
            int xStep = n <= 10 ? 1 : n <= 31 ? Math.max(1, n / 10) : 7;

            for (Map.Entry<LocalDate, double[]> entry : data.entrySet()) {
                double[] s = entry.getValue();
                double total = s[0] + s[1] + s[2];
                float x = chartX + idx * (barW + barGap);
                if (total > 0.005) {
                    float cashH = (float) ((s[0] / maxVal) * chartH);
                    float cardH = (float) ((s[1] / maxVal) * chartH);
                    float platH = (float) ((s[2] / maxVal) * chartH);
                    float y = chartY;
                    if (cashH > 0) {
                        cb.setColorFill(PLATFORM_CASH);
                        cb.rectangle(x, y, barW, cashH);
                        cb.fill();
                        y += cashH;
                    }
                    if (cardH > 0) {
                        cb.setColorFill(PLATFORM_CARD);
                        cb.rectangle(x, y, barW, cardH);
                        cb.fill();
                        y += cardH;
                    }
                    if (platH > 0) {
                        cb.setColorFill(PLATFORM_WOLT);
                        cb.rectangle(x, y, barW, platH);
                        cb.fill();
                    }
                }
                if (idx % xStep == 0 || idx == n - 1) {
                    String label = n <= 14
                            ? entry.getKey().format(DAY_OF_WEEK)
                            : entry.getKey().format(DAY_NUM);
                    ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                            new Phrase(label, axisFont),
                            x + barW / 2f, chartY - 10f, 0);
                }
                idx++;
            }
            // Axis line
            cb.setColorStroke(BRAND_INK);
            cb.setLineWidth(0.6f);
            cb.moveTo(chartX, chartY);
            cb.lineTo(chartX + chartW, chartY);
            cb.stroke();
            cb.restoreState();
        }
    }

    // ========================================================================
    // Profit & Loss
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void renderProfitLoss(Document doc, Map<String, Object> pnl) throws DocumentException {
        List<Map<String, Object>> lines = (List<Map<String, Object>>) pnl.get("lines");
        Map<String, Object> totals = (Map<String, Object>) pnl.get("totals");
        Map<String, Object> margins = (Map<String, Object>) pnl.get("margins");
        if (lines == null || totals == null) return;

        double netProfit = num(totals.get("netProfit"));
        sectionHeader(doc, "Profit & Loss",
                "Revenue → costs → profit (" + safeStr(pnl.get("templateLabel"), "P&L") + ")",
                signedMoney(netProfit));

        // Margin chips (Gross / Operating / Net)
        if (margins != null) {
            PdfPTable chips = new PdfPTable(3);
            chips.setWidthPercentage(100);
            chips.setSpacingBefore(2f);
            chips.setSpacingAfter(8f);
            addMarginChip(chips, "Gross margin", marginPct(margins.get("grossMarginPct")));
            addMarginChip(chips, "Operating margin", marginPct(margins.get("operatingMarginPct")));
            addMarginChip(chips, "Net margin", marginPct(margins.get("netMarginPct")));
            doc.add(chips);
        }

        // P&L line table
        float[] widths = {6f, 2f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
        table.setSpacingAfter(14f);

        for (Map<String, Object> raw : lines) {
            boolean section = Boolean.TRUE.equals(raw.get("section"));
            boolean subtotal = Boolean.TRUE.equals(raw.get("subtotal"));
            boolean bold = Boolean.TRUE.equals(raw.get("bold"));
            int indent = raw.get("indent") instanceof Number n ? n.intValue() : 0;
            String label = safeStr(raw.get("label"), "");
            Double amount = raw.get("amount") instanceof Number n ? n.doubleValue() : null;

            Font lblFont = section
                    ? font(SANS_BOLD, 10, BRAND_SAFFRON_DEEP)
                    : (subtotal || bold)
                        ? font(SANS_BOLD, 9.5f, BRAND_INK)
                        : font(SANS_REG, 9, BRAND_INK);
            Font amtFont = section
                    ? font(SANS_BOLD, 10, BRAND_SAFFRON_DEEP)
                    : (subtotal || bold)
                        ? font(SANS_BOLD, 9.5f, BRAND_INK)
                        : font(SANS_REG, 9, BRAND_INK);
            Color bg = section ? BRAND_CREAM
                    : subtotal ? new Color(0xFB, 0xF7, 0xEE)
                    : Color.WHITE;

            PdfPCell lblCell = new PdfPCell(new Phrase(label, lblFont));
            lblCell.setBackgroundColor(bg);
            lblCell.setBorderColor(GRID_LINE);
            lblCell.setBorderWidth(section ? 0f : 0.4f);
            lblCell.setPaddingTop(section ? 8f : 5f);
            lblCell.setPaddingBottom(section ? 6f : 5f);
            lblCell.setPaddingLeft(6f + (indent * 12f));
            lblCell.setPaddingRight(6f);
            table.addCell(lblCell);

            PdfPCell amtCell = new PdfPCell(new Phrase(
                    section || amount == null ? "" : money(amount),
                    amtFont));
            amtCell.setBackgroundColor(bg);
            amtCell.setBorderColor(GRID_LINE);
            amtCell.setBorderWidth(section ? 0f : 0.4f);
            amtCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            amtCell.setPaddingTop(section ? 8f : 5f);
            amtCell.setPaddingBottom(section ? 6f : 5f);
            amtCell.setPaddingLeft(6f);
            amtCell.setPaddingRight(6f);
            table.addCell(amtCell);
        }
        doc.add(table);
    }

    private static void addMarginChip(PdfPTable t, String label, String pct) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(GRID_LINE);
        cell.setBackgroundColor(Color.WHITE);
        cell.setPadding(10f);
        Paragraph l = new Paragraph(label.toUpperCase(Locale.ENGLISH),
                font(SANS_BOLD, 7, MUTED));
        l.setSpacingAfter(4f);
        cell.addElement(l);
        cell.addElement(new Paragraph(pct, font(SERIF_BOLD, 14, BRAND_INK)));
        t.addCell(cell);
    }

    private static String marginPct(Object v) {
        if (!(v instanceof Number n)) return "—";
        return String.format(Locale.ENGLISH, "%.1f%%", n.doubleValue());
    }

    // ========================================================================
    // Treasury snapshot
    // ========================================================================

    private static void renderTreasurySnapshot(Document doc, Map<String, Object> treasury) throws DocumentException {
        double cashOnHand = num(treasury.get("cashOnHand"));
        double cashPool = num(treasury.get("cashPool"));
        double cardPool = num(treasury.get("cardPool"));
        Object lastCount = treasury.get("latestCountDate");
        Object lastCountBy = treasury.get("latestCountCashier");

        sectionHeader(doc, "Treasury position", "Live balances — current physical cash & card pool",
                money(cashOnHand + cardPool));

        float[] widths = {3f, 2f, 4f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);
        addTableHeader(table, List.of("Pool", "Balance", "Notes"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_RIGHT, Element.ALIGN_LEFT});

        int i = 0;
        Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
        addBodyCell(table, "Cash on hand", Element.ALIGN_LEFT, bg, BRAND_INK);
        addBodyCell(table, money(cashOnHand), Element.ALIGN_RIGHT, bg, BRAND_INK);
        String cashNote = lastCount != null
                ? "Last counted " + safeStr(lastCount, "—")
                  + (lastCountBy != null ? " by " + lastCountBy : "")
                : "No physical count yet";
        addBodyCell(table, cashNote, Element.ALIGN_LEFT, bg, MUTED);

        bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
        addBodyCell(table, "Cash pool (excl. unsettled salary)", Element.ALIGN_LEFT, bg, BRAND_INK);
        addBodyCell(table, money(cashPool), Element.ALIGN_RIGHT, bg, BRAND_INK);
        addBodyCell(table, "Theoretical cash if every cash payout cleared", Element.ALIGN_LEFT, bg, MUTED);

        bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
        addBodyCell(table, "Card pool (held by processor)", Element.ALIGN_LEFT, bg, BRAND_INK);
        addBodyCell(table, money(cardPool), Element.ALIGN_RIGHT, bg, BRAND_INK);
        addBodyCell(table, "Net of deposits to bank and platform settlements", Element.ALIGN_LEFT, bg, MUTED);

        // Totals row
        Font tf = font(SANS_BOLD, 9, BRAND_INK);
        addCell(table, "Total liquid position", tf, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(cashOnHand + cardPool), tf, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, "", tf, Element.ALIGN_LEFT, BRAND_CREAM);

        doc.add(table);
    }

    // ========================================================================
    // Payroll exposure
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void renderPayrollExposure(Document doc, Map<String, Object> payroll, double netRevenue)
            throws DocumentException {
        List<Map<String, Object>> employees = (List<Map<String, Object>>) payroll.get("employees");
        if (employees == null || employees.isEmpty()) return;

        double totalPay = num(payroll.get("grandTotalPay"));
        double totalPaid = num(payroll.get("grandTotalPaid"));
        double totalRemaining = num(payroll.get("grandTotalRemaining"));
        double laborPct = netRevenue > 0.005 ? (totalPay / netRevenue) * 100.0 : 0;

        sectionHeader(doc, "Payroll exposure",
                String.format(Locale.ENGLISH, "Labour cost · %.1f%% of net revenue", laborPct),
                money(totalPay));

        // 3 quick stats
        PdfPTable chips = new PdfPTable(3);
        chips.setWidthPercentage(100);
        chips.setSpacingBefore(2f);
        chips.setSpacingAfter(10f);
        addMarginChip(chips, "Accrued", money(totalPay));
        addMarginChip(chips, "Paid so far", money(totalPaid));
        addMarginChip(chips, "Remaining", money(totalRemaining));
        doc.add(chips);

        // Top earners table — show top 8 by accrued pay
        List<Map<String, Object>> sorted = new ArrayList<>(employees);
        sorted.sort((a, b) -> Double.compare(num(b.get("totalPay")), num(a.get("totalPay"))));
        List<Map<String, Object>> top = sorted.subList(0, Math.min(8, sorted.size()));

        float[] widths = {3f, 1.3f, 1.6f, 1.6f, 1.6f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);
        addTableHeader(table, List.of("Employee", "Shifts", "Earned", "Paid", "Remaining"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_CENTER, Element.ALIGN_RIGHT,
                        Element.ALIGN_RIGHT, Element.ALIGN_RIGHT});

        int i = 0;
        for (Map<String, Object> emp : top) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            addBodyCell(table, safeStr(emp.get("name"), "—"), Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, String.valueOf(emp.getOrDefault("shiftCount", 0)),
                    Element.ALIGN_CENTER, bg, BRAND_INK);
            addBodyCell(table, money(num(emp.get("totalPay"))), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(num(emp.get("paidAmount"))), Element.ALIGN_RIGHT, bg, BRAND_INK);
            double rem = num(emp.get("remainingPay"));
            addBodyCell(table, money(rem), Element.ALIGN_RIGHT, bg,
                    rem > 0.005 ? NEGATIVE : BRAND_INK);
        }
        if (sorted.size() > top.size()) {
            PdfPCell more = new PdfPCell(new Phrase(
                    (sorted.size() - top.size()) + " more employees not shown",
                    font(SANS_ITALIC, 8, MUTED)));
            more.setColspan(widths.length);
            more.setBorder(Rectangle.NO_BORDER);
            more.setHorizontalAlignment(Element.ALIGN_CENTER);
            more.setPaddingTop(6f);
            more.setPaddingBottom(2f);
            table.addCell(more);
        }
        doc.add(table);
    }

    // ========================================================================
    // By-cashier scorecard
    // ========================================================================

    private static void renderByCashier(Document doc, List<Map<String, Object>> rows) throws DocumentException {
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
            c.expenses += sumExpenseLines(e);
            if ("DRAFT".equals(String.valueOf(e.get("status")))) c.drafts++;
        }

        List<Map.Entry<String, CashierAgg>> sorted = new ArrayList<>(agg.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue().total(), a.getValue().total()));

        double grandCash = 0, grandCard = 0, grandDeliv = 0, grandDiff = 0;
        int grandDrafts = 0;
        for (var e : sorted) {
            grandCash += e.getValue().cash;
            grandCard += e.getValue().card;
            grandDeliv += e.getValue().delivery;
            grandDiff += e.getValue().difference;
            grandDrafts += e.getValue().drafts;
        }

        sectionHeader(doc, "By cashier",
                sorted.size() + " cashier" + (sorted.size() == 1 ? "" : "s")
                        + (grandDrafts > 0 ? " · " + grandDrafts + " draft" : ""),
                money(grandCash + grandCard + grandDeliv));

        float[] widths = {2.4f, 0.9f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);

        addTableHeader(table,
                List.of("Cashier", "Shifts", "Cash", "Card", "Delivery", "Expenses", "Cash variance"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_CENTER, Element.ALIGN_RIGHT,
                        Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT});

        int i = 0;
        for (var entry : sorted) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            CashierAgg c = entry.getValue();
            String name = entry.getKey() + (c.drafts > 0 ? " · " + c.drafts + " draft" : "");
            addBodyCell(table, name, Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, String.valueOf(c.shifts), Element.ALIGN_CENTER, bg, BRAND_INK);
            addBodyCell(table, money(c.cash), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(c.card), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(c.delivery), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(c.expenses), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, signedMoney(c.difference), Element.ALIGN_RIGHT, bg, varianceColor(c.difference));
        }

        Font totalFont = font(SANS_BOLD, 9, BRAND_INK);
        addCell(table, "Total", totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, String.valueOf(rows.size()), totalFont, Element.ALIGN_CENTER, BRAND_CREAM);
        addCell(table, money(grandCash), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandCard), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandDeliv), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        double grandExp = sorted.stream().mapToDouble(e -> e.getValue().expenses).sum();
        addCell(table, money(grandExp), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, signedMoney(grandDiff),
                font(SANS_BOLD, 9, varianceColor(grandDiff)),
                Element.ALIGN_RIGHT, BRAND_CREAM);

        doc.add(table);
    }

    // ========================================================================
    // Expense breakdown — augmented with P&L per-category split when available
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void renderExpenseBreakdown(Document doc, List<Map<String, Object>> rows,
                                                Map<String, Object> pnl) throws DocumentException {
        // Prefer the P&L view if available (it merges standalone + shift-internal)
        Map<String, Double> byCategoryTotal = new TreeMap<>();
        if (pnl != null) {
            List<Map<String, Object>> bc = (List<Map<String, Object>>) pnl.get("expensesByCategory");
            if (bc != null) {
                for (Map<String, Object> r : bc) {
                    double amt = num(r.get("amount"));
                    if (amt > 0.005) {
                        byCategoryTotal.merge(safeStr(r.get("label"), "Other"), amt, Double::sum);
                    }
                }
            }
        }
        // Per-payment-source split has to come from per-shift rows
        Map<String, double[]> bySrc = new TreeMap<>();
        for (Map<String, Object> e : rows) {
            List<Map<String, Object>> expenses = (List<Map<String, Object>>) e.get("expenses");
            if (expenses == null) continue;
            for (Map<String, Object> ex : expenses) {
                String cat = formatCategory(ex.get("category"));
                double[] sums = bySrc.computeIfAbsent(cat, k -> new double[2]);
                double amt = num(ex.get("amount"));
                if ("CARD".equals(String.valueOf(ex.get("paymentSource")))) sums[0] += amt;
                else sums[1] += amt;
            }
        }
        if (byCategoryTotal.isEmpty() && bySrc.isEmpty()) return;

        // Merge: use byCategoryTotal as the authoritative total per category;
        // fall back to bySrc when P&L wasn't provided.
        if (byCategoryTotal.isEmpty()) {
            for (var e : bySrc.entrySet()) {
                byCategoryTotal.put(e.getKey(), e.getValue()[0] + e.getValue()[1]);
            }
        }

        double grandTotal = 0;
        for (double v : byCategoryTotal.values()) grandTotal += v;

        sectionHeader(doc, "Expenses by category",
                byCategoryTotal.size() + " categor" + (byCategoryTotal.size() == 1 ? "y" : "ies"),
                money(grandTotal));

        float[] widths = {3.5f, 1.5f, 1.5f, 1.7f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
        table.setSpacingBefore(2f);
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);

        addTableHeader(table, List.of("Category", "Cash", "Card", "Total"),
                new int[]{Element.ALIGN_LEFT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT});

        List<Map.Entry<String, Double>> entries = new ArrayList<>(byCategoryTotal.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        double grandCash = 0, grandCard = 0;
        int i = 0;
        for (var entry : entries) {
            Color bg = (i++ & 1) == 1 ? ZEBRA : Color.WHITE;
            double total = entry.getValue();
            double[] src = bySrc.get(entry.getKey());
            double card = src == null ? 0 : src[0];
            double cash = src == null ? Math.max(0, total - card) : src[1];
            // When P&L provided the total but we have no per-source split
            // we'll show cash = total, card = 0 as a heuristic.
            if (src == null) {
                cash = total;
                card = 0;
            }
            grandCash += cash;
            grandCard += card;
            addBodyCell(table, entry.getKey(), Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, money(cash), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(card), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, money(total), Element.ALIGN_RIGHT, bg, BRAND_INK);
        }
        Font totalFont = font(SANS_BOLD, 9, BRAND_INK);
        addCell(table, "Total", totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandCash), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandCard), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(grandTotal), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        doc.add(table);
    }

    // ========================================================================
    // Payouts & transfers (unchanged from before)
    // ========================================================================

    private static void renderPayoutsSummary(Document doc, List<Map<String, Object>> rows) throws DocumentException {
        double bank = 0, cash = 0, owner = 0;
        for (Map<String, Object> e : rows) {
            bank += num(e.get("bankDeposit"));
            cash += num(e.get("cashWithdrawal"));
            owner += num(e.get("ownerWithdrawal"));
        }
        double total = bank + cash + owner;
        if (total < 0.005) return;

        sectionHeader(doc, "Payouts & transfers", "Money moved out of the till during shifts", money(total));

        float[] widths = {3.5f, 2f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
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
        Font totalFont = font(SANS_BOLD, 9, BRAND_INK);
        addCell(table, "Total", totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        addCell(table, money(total), totalFont, Element.ALIGN_RIGHT, BRAND_CREAM);
        doc.add(table);
    }

    // ========================================================================
    // Top menu items — top 10 by revenue, with margin %
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void renderTopMenuItems(Document doc, Map<String, Object> menu) throws DocumentException {
        List<Map<String, Object>> items = (List<Map<String, Object>>) menu.get("items");
        if (items == null || items.isEmpty()) return;
        Map<String, Object> totals = (Map<String, Object>) menu.get("totals");

        List<Map<String, Object>> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble((Map<String, Object> m) -> num(m.get("revenue"))).reversed());
        List<Map<String, Object>> top = sorted.subList(0, Math.min(10, sorted.size()));

        double totalRevenue = totals != null ? num(totals.get("revenue")) : 0;
        sectionHeader(doc, "Top menu items",
                "Top " + top.size() + " by revenue · " + items.size() + " items tracked",
                money(totalRevenue));

        float[] widths = {0.5f, 3f, 1.5f, 1.2f, 1.2f, 1.3f};
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidthPercentage(100);
        try { table.setWidths(widths); } catch (DocumentException ignored) {}
        table.setSpacingAfter(14f);
        table.setHeaderRows(1);
        addTableHeader(table, List.of("#", "Item", "Revenue", "Qty", "Margin %", "Share"),
                new int[]{Element.ALIGN_CENTER, Element.ALIGN_LEFT, Element.ALIGN_RIGHT,
                        Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT});

        int rank = 1;
        for (Map<String, Object> item : top) {
            Color bg = (rank & 1) == 0 ? ZEBRA : Color.WHITE;
            addBodyCell(table, String.valueOf(rank), Element.ALIGN_CENTER, bg, MUTED);
            addBodyCell(table, safeStr(item.get("name"), "—"), Element.ALIGN_LEFT, bg, BRAND_INK);
            addBodyCell(table, money(num(item.get("revenue"))), Element.ALIGN_RIGHT, bg, BRAND_INK);
            addBodyCell(table, formatQty(num(item.get("quantity"))), Element.ALIGN_RIGHT, bg, BRAND_INK);
            double mpct = num(item.get("marginPct"));
            addBodyCell(table, String.format(Locale.ENGLISH, "%.1f%%", mpct),
                    Element.ALIGN_RIGHT, bg,
                    mpct >= 60 ? POSITIVE : mpct >= 40 ? BRAND_INK : NEGATIVE);
            addBodyCell(table, String.format(Locale.ENGLISH, "%.1f%%", num(item.get("share")) * 100),
                    Element.ALIGN_RIGHT, bg, BRAND_INK);
            rank++;
        }
        doc.add(table);
    }

    // ========================================================================
    // Menu engineering callouts — top suggestions
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void renderMenuActions(Document doc, Map<String, Object> engineering) throws DocumentException {
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) engineering.get("suggestions");
        if (suggestions == null || suggestions.isEmpty()) return;

        sectionHeader(doc, "What to do this period",
                "Menu engineering actions (top " + Math.min(suggestions.size(), 6) + ")", "");

        int shown = 0;
        for (Map<String, Object> s : suggestions) {
            if (shown >= 6) break;
            String severity = safeStr(s.get("severity"), "low").toLowerCase(Locale.ENGLISH);
            String title = safeStr(s.get("title"), "");
            String detail = safeStr(s.get("detail"), "");
            if (title.isBlank() && detail.isBlank()) continue;

            Color barColor = "high".equals(severity) ? NEGATIVE
                    : "medium".equals(severity) ? BRAND_SAFFRON
                    : POSITIVE;

            PdfPTable card = new PdfPTable(new float[]{0.18f, 6f});
            card.setWidthPercentage(100);
            card.setSpacingBefore(2f);
            card.setSpacingAfter(6f);

            // Left accent bar
            PdfPCell bar = new PdfPCell();
            bar.setBackgroundColor(barColor);
            bar.setBorder(Rectangle.NO_BORDER);
            bar.setFixedHeight(36f);
            card.addCell(bar);

            PdfPCell body = new PdfPCell();
            body.setBorder(Rectangle.BOX);
            body.setBorderColor(GRID_LINE);
            body.setBorderWidthLeft(0f);
            body.setBackgroundColor(Color.WHITE);
            body.setPaddingTop(6f);
            body.setPaddingBottom(6f);
            body.setPaddingLeft(10f);
            body.setPaddingRight(10f);
            Paragraph t = new Paragraph(title, font(SANS_BOLD, 10, BRAND_INK));
            t.setSpacingAfter(2f);
            body.addElement(t);
            if (!detail.isBlank()) {
                Paragraph d = new Paragraph(detail, font(SANS_REG, 9, MUTED));
                d.setLeading(12f);
                body.addElement(d);
            }
            card.addCell(body);
            doc.add(card);
            shown++;
        }
        // Trailing spacer
        Paragraph spacer = new Paragraph(" ", font(SANS_REG, 6, BRAND_INK));
        spacer.setSpacingAfter(8f);
        doc.add(spacer);
    }

    // ========================================================================
    // Notes (unchanged)
    // ========================================================================

    private static void renderNotes(Document doc, List<Map<String, Object>> rows) throws DocumentException {
        List<String> notes = new ArrayList<>();
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
            Paragraph p = new Paragraph("• " + n, font(SANS_REG, 9, BRAND_INK));
            p.setSpacingAfter(4f);
            p.setIndentationLeft(8f);
            p.setLeading(13f);
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
        try { header.setWidths(new float[]{6f, 3f}); } catch (DocumentException ignored) {}
        header.setSpacingBefore(8f);
        header.setSpacingAfter(0f);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.setBorderWidthBottom(1f);
        left.setBorderColorBottom(BRAND_SAFFRON);
        left.setPaddingBottom(6f);
        left.setPaddingTop(8f);
        left.addElement(new Paragraph(title, font(SERIF_BOLD, 13, BRAND_INK)));
        if (meta != null && !meta.isBlank()) {
            Paragraph m = new Paragraph(meta, font(SANS_REG, 9, MUTED));
            m.setSpacingBefore(1f);
            left.addElement(m);
        }
        header.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setBorderWidthBottom(1f);
        right.setBorderColorBottom(BRAND_SAFFRON);
        right.setPaddingTop(12f);
        right.setPaddingBottom(6f);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (trailingAmount != null && !trailingAmount.isBlank()) {
            Paragraph amount = new Paragraph(trailingAmount,
                    font(SERIF_BOLD, 13, BRAND_SAFFRON));
            amount.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(amount);
        }
        header.addCell(right);

        doc.add(header);
    }

    private static void addTableHeader(PdfPTable table, List<String> headers, int[] alignments) {
        Font hf = font(SANS_BOLD, 8, Color.WHITE);
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
        PdfPCell cell = new PdfPCell(new Phrase(text, font(SANS_REG, 9, textColor)));
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

    /** Short money for axis labels — `1.2k` / `850`. */
    private static String shortMoney(double v) {
        if (Math.abs(v) >= 1_000) {
            return String.format(Locale.ENGLISH, "%.1fk", v / 1_000.0).replace(".0k", "k");
        }
        return String.format(Locale.ENGLISH, "%.0f", v);
    }

    private static String signedMoney(double v) {
        return v > 0 ? "+" + money(v) : money(v);
    }

    private static Color varianceColor(double v) {
        if (v < -0.01) return NEGATIVE;
        if (v > 0.01) return POSITIVE;
        return BRAND_INK;
    }

    private static String formatQty(double q) {
        if (Math.abs(q - Math.round(q)) < 0.005) {
            return String.format(Locale.ENGLISH, "%d", Math.round(q));
        }
        return String.format(Locale.ENGLISH, "%.2f", q);
    }

    private static String dateShort(Object raw) {
        if (raw == null) return "";
        try { return LocalDate.parse(String.valueOf(raw)).format(SHORT_DATE); }
        catch (Exception ignored) { return String.valueOf(raw); }
    }

    private static String describePeriod(String period) {
        if (period == null) return null;
        return switch (period.toLowerCase(Locale.ENGLISH)) {
            case "daily" -> "Daily summary";
            case "weekly" -> "Weekly summary";
            case "monthly" -> "Monthly summary";
            default -> null;
        };
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null) return 0;
        return Period.between(from, to).getDays() + 1;
    }

    private static double num(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static String safeStr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
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

    @SuppressWarnings("unchecked")
    private static double sumExpenseLines(Map<String, Object> e) {
        List<Map<String, Object>> expenses = (List<Map<String, Object>>) e.get("expenses");
        if (expenses == null) return 0;
        double sum = 0;
        for (Map<String, Object> ex : expenses) sum += num(ex.get("amount"));
        return sum;
    }

    private static String formatCategory(Object cat) {
        if (cat == null) return "Other";
        String s = String.valueOf(cat).toLowerCase(Locale.ENGLISH).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char ch : s.toCharArray()) {
            sb.append(upper ? Character.toUpperCase(ch) : ch);
            upper = ch == ' ';
        }
        return sb.toString();
    }

    // ========================================================================
    // Aggregates
    // ========================================================================

    private static final class CashierAgg {
        int shifts;
        int drafts;
        double cash, card, delivery, difference, expenses;
        double total() { return cash + card + delivery; }
    }

    private static final class Totals {
        double grossSales, netSales, returns;
        double cashSales, cardSales, platformSales;
        double woltSales, boltSales, uberSales, glovoSales, otherPlatform;
        double payouts, expenseLines;
        double expectedCash, actualCash, difference, cardBalance;
        int draftCount, lockedCount;

        @SuppressWarnings("unchecked")
        static Totals from(Map<String, Object> summary, List<Map<String, Object>> rowsForPlatformSplit) {
            Totals t = new Totals();
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
                t.expenseLines = Math.max(0, rawExpenses - t.payouts);
                // Prefer top-level 'sales' when available (includes manual delivery)
                double summarySales = num(totals.get("sales"));
                if (summarySales > 0) {
                    t.grossSales = summarySales;
                }
            }
            List<Map<String, Object>> rows = rowsForPlatformSplit;
            if (rows == null && summary != null) {
                Object r = summary.get("rows");
                if (r instanceof List<?> lr) {
                    rows = (List<Map<String, Object>>) lr;
                }
            }
            if (rows != null) {
                for (Map<String, Object> e : rows) {
                    t.woltSales += num(e.get("woltSales"));
                    t.boltSales += num(e.get("boltSales"));
                    t.uberSales += num(e.get("uberEatsSales"));
                    t.glovoSales += num(e.get("glovoSales"));
                    t.otherPlatform += num(e.get("otherPlatformSales"));
                }
                t.platformSales = t.woltSales + t.boltSales + t.uberSales + t.glovoSales + t.otherPlatform;
            }
            if (t.grossSales < 0.005) {
                t.grossSales = t.cashSales + t.cardSales + t.platformSales;
            }
            t.netSales = t.grossSales - t.returns;
            return t;
        }
    }

    // ========================================================================
    // Footer
    // ========================================================================

    private static final class FooterEvent extends PdfPageEventHelper {
        private final String reportTitle;
        FooterEvent(String reportTitle) { this.reportTitle = reportTitle; }
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle pageSize = document.getPageSize();
            Font f = font(SANS_REG, 8, MUTED);
            float y = pageSize.getBottom() + 22;
            ColumnText.showTextAligned(writer.getDirectContent(),
                    Element.ALIGN_LEFT,
                    new Phrase("Saffron · " + reportTitle, f),
                    pageSize.getLeft() + document.leftMargin(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(),
                    Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), f),
                    pageSize.getRight() - document.rightMargin(), y, 0);
        }
    }
}
