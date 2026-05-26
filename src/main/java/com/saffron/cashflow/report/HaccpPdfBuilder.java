package com.saffron.cashflow.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Builds the HACCP inspection PDF — one row per log entry, ordered most
 * recent first.
 *
 * <p>Kept deliberately plain: black on white, monospaced numbers, no
 * decorative fonts. Sanepid inspectors don't want a marketing brochure —
 * they want columns they can read out loud.</p>
 */
public final class HaccpPdfBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private HaccpPdfBuilder() {}

    public static byte[] build(List<Map<String, Object>> rows, LocalDate from, LocalDate to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 28, 28, 32, 32);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font titleFont = new Font(Font.HELVETICA, 16, Font.BOLD);
            Font subFont = new Font(Font.HELVETICA, 10, Font.NORMAL, Color.DARK_GRAY);
            Font headerFont = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
            Font cellFont = new Font(Font.HELVETICA, 9, Font.NORMAL);
            Font cellMuted = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.GRAY);

            Paragraph title = new Paragraph("HACCP Log", titleFont);
            doc.add(title);
            Paragraph period = new Paragraph(
                    String.format("Period: %s – %s · %d entr%s · generated %s",
                            from.format(DATE), to.format(DATE), rows.size(),
                            rows.size() == 1 ? "y" : "ies",
                            LocalDate.now().format(DATE)),
                    subFont);
            period.setSpacingAfter(12f);
            doc.add(period);

            if (rows.isEmpty()) {
                doc.add(new Paragraph("No entries recorded in this period.", cellFont));
            } else {
                PdfPTable table = new PdfPTable(new float[]{0.9f, 0.7f, 1.2f, 1.6f, 0.6f, 1.2f, 1.8f});
                table.setWidthPercentage(100f);
                table.getDefaultCell().setBorderColor(Color.LIGHT_GRAY);

                // Header
                addHeader(table, "Date", headerFont);
                addHeader(table, "Time", headerFont);
                addHeader(table, "Kind", headerFont);
                addHeader(table, "Where", headerFont);
                addHeader(table, "°C", headerFont);
                addHeader(table, "Status", headerFont);
                addHeader(table, "Notes / by", headerFont);

                for (Map<String, Object> r : rows) {
                    addCell(table, str(r.get("recordedOn")), cellFont);
                    addCell(table, formatTime(r.get("recordedAt")), cellFont);
                    addCell(table, str(r.get("kind")), cellFont);
                    addCell(table, str(r.get("location")), cellFont);
                    addCell(table, formatTemp(r.get("temperatureC")), cellFont);
                    addCell(table, str(r.get("status")), cellFont);
                    String notes = str(r.get("notes"));
                    String by = str(r.get("recordedByName"));
                    String composed = (notes == null || notes.isBlank() ? "" : notes)
                            + (by == null || by.isBlank() ? "" : "\n— " + by);
                    PdfPCell cell = new PdfPCell(new Phrase(composed, cellMuted));
                    cell.setBorderColor(Color.LIGHT_GRAY);
                    cell.setPadding(4f);
                    table.addCell(cell);
                }
                doc.add(table);
            }

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to build HACCP PDF", e);
        }
    }

    private static void addHeader(PdfPTable table, String label, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(label, font));
        cell.setBackgroundColor(new Color(0x33, 0x33, 0x33));
        cell.setPadding(5f);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "—" : text, font));
        cell.setBorderColor(Color.LIGHT_GRAY);
        cell.setPadding(4f);
        table.addCell(cell);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String formatTemp(Object v) {
        if (v == null) return "—";
        try {
            double d = Double.parseDouble(v.toString());
            return String.format("%.1f", d);
        } catch (NumberFormatException ignored) {
            return v.toString();
        }
    }

    private static String formatTime(Object v) {
        if (v == null) return "—";
        try {
            return java.time.OffsetDateTime
                    .parse(v.toString())
                    .toLocalTime()
                    .format(TIME);
        } catch (Exception ignored) {
            try {
                return java.time.Instant.parse(v.toString())
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalTime()
                        .format(TIME);
            } catch (Exception ignored2) {
                return "—";
            }
        }
    }
}
