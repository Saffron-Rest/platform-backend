package com.saffron.cashflow.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.saffron.cashflow.domain.MenuCategory;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates a designer-style PDF menu for printing.
 *
 * <p>Three layouts are supported via the {@code layout} parameter:
 * <ul>
 *   <li><b>grid</b> – 2-column photo cards (default; most visual).</li>
 *   <li><b>list</b> – single column with small thumbnails and full descriptions.</li>
 *   <li><b>compact</b> – two-column text-only, perfect for table tents and
 *       table-side menus.</li>
 * </ul>
 *
 * <p>The cover page is always included. Items without photos render a clean
 * placeholder so layouts stay balanced.
 */
@Service
public class MenuPrintService {

    // Brand palette — kept in sync with the analytics PDF for visual coherence.
    private static final Color BRAND_INK = new Color(0x1D, 0x1B, 0x16);
    private static final Color BRAND_SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color BRAND_CREAM = new Color(0xFA, 0xF4, 0xE8);
    private static final Color MUTED = new Color(0x6B, 0x63, 0x57);
    private static final Color HAIRLINE = new Color(0xE2, 0xDD, 0xD2);
    private static final Color CARD_BG = new Color(0xFF, 0xFF, 0xFF);

    private static final DateTimeFormatter MENU_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final MenuService menuService;
    private final FileStorageService fileStorage;

    public MenuPrintService(MenuService menuService, FileStorageService fileStorage) {
        this.menuService = menuService;
        this.fileStorage = fileStorage;
    }

    public byte[] buildMenu(
            String layoutKey,
            String customTitle,
            String customSubtitle,
            boolean showPrices,
            String language) {
        Layout layout = Layout.from(layoutKey);
        String title = customTitle != null && !customTitle.isBlank()
                ? customTitle.trim()
                : "Saffron";
        String subtitle = customSubtitle != null && !customSubtitle.isBlank()
                ? customSubtitle.trim()
                : "Authentic Azerbaijani Restaurant";
        Locale locale = "pl".equalsIgnoreCase(language) ? Locale.forLanguageTag("pl-PL") : Locale.ENGLISH;

        List<MenuCategory> categories = menuService.activeCategoriesInOrder();
        if (categories.isEmpty()) {
            throw new BadRequestException(
                    "No active categories — add at least one in /admin/menu before printing.");
        }

        Document doc = new Document(PageSize.A4, 36, 36, 64, 56);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new MenuFooter(title));
            doc.open();
            drawCover(doc, title, subtitle, categories);
            for (MenuCategory cat : categories) {
                List<MenuItem> items = menuService.activeItemsForCategory(cat.getId());
                if (items.isEmpty()) continue;
                doc.newPage();
                drawCategoryHeader(doc, cat.getName());
                switch (layout) {
                    case GRID -> drawGrid(doc, items, showPrices, locale);
                    case LIST -> drawList(doc, items, showPrices, locale);
                    case COMPACT -> drawCompact(doc, items, showPrices, locale);
                }
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to build menu PDF: " + e.getMessage(), e);
        }
    }

    // ---------- Layouts ----------

    private enum Layout {
        GRID, LIST, COMPACT;

        static Layout from(String key) {
            if (key == null) return GRID;
            return switch (key.trim().toLowerCase(Locale.ROOT)) {
                case "list" -> LIST;
                case "compact" -> COMPACT;
                default -> GRID;
            };
        }
    }

    // ---------- Cover ----------

    private void drawCover(Document doc, String title, String subtitle, List<MenuCategory> categories)
            throws DocumentException {
        Font brand = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 52, BRAND_INK);
        Font sub = FontFactory.getFont(FontFactory.HELVETICA, 16, MUTED);
        Font caps = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, BRAND_SAFFRON);
        Font catList = FontFactory.getFont(FontFactory.HELVETICA, 12, BRAND_INK);
        Font date = FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED);

        // Saffron accent bar at top
        PdfPTable accent = new PdfPTable(1);
        accent.setWidthPercentage(100);
        PdfPCell bar = new PdfPCell();
        bar.setBackgroundColor(BRAND_SAFFRON);
        bar.setFixedHeight(6f);
        bar.setBorder(Rectangle.NO_BORDER);
        accent.addCell(bar);
        doc.add(accent);

        Paragraph eyebrow = new Paragraph("M E N U", caps);
        eyebrow.setSpacingBefore(140);
        eyebrow.setAlignment(Element.ALIGN_CENTER);
        doc.add(eyebrow);

        Paragraph brandPara = new Paragraph(title, brand);
        brandPara.setSpacingBefore(14);
        brandPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(brandPara);

        Paragraph subPara = new Paragraph(subtitle, sub);
        subPara.setSpacingBefore(8);
        subPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(subPara);

        // Hairline divider
        PdfPTable hair = new PdfPTable(1);
        hair.setWidthPercentage(20);
        hair.setHorizontalAlignment(Element.ALIGN_CENTER);
        PdfPCell hairCell = new PdfPCell();
        hairCell.setFixedHeight(2f);
        hairCell.setBackgroundColor(BRAND_SAFFRON);
        hairCell.setBorder(Rectangle.NO_BORDER);
        hair.addCell(hairCell);
        Paragraph hairWrap = new Paragraph();
        hairWrap.setSpacingBefore(28);
        hairWrap.add(new Phrase(""));
        doc.add(hairWrap);
        doc.add(hair);

        // Category list ("contents")
        Paragraph contents = new Paragraph();
        contents.setAlignment(Element.ALIGN_CENTER);
        contents.setSpacingBefore(38);
        for (int i = 0; i < categories.size(); i++) {
            contents.add(new Phrase(categories.get(i).getName(), catList));
            if (i < categories.size() - 1) {
                contents.add(new Phrase("   ·   ", FontFactory.getFont(FontFactory.HELVETICA, 12, MUTED)));
            }
        }
        doc.add(contents);

        // Date at the bottom
        Paragraph datePara = new Paragraph(LocalDate.now().format(MENU_DATE), date);
        datePara.setSpacingBefore(220);
        datePara.setAlignment(Element.ALIGN_CENTER);
        doc.add(datePara);
    }

    // ---------- Category header ----------

    private void drawCategoryHeader(Document doc, String name) throws DocumentException {
        Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, BRAND_SAFFRON);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 28, BRAND_INK);

        Paragraph eyebrow = new Paragraph(name.toUpperCase(Locale.ROOT), label);
        eyebrow.setSpacingBefore(0);
        eyebrow.setAlignment(Element.ALIGN_LEFT);
        doc.add(eyebrow);

        Paragraph head1 = new Paragraph(name, head);
        head1.setSpacingBefore(4);
        head1.setSpacingAfter(2);
        doc.add(head1);

        // Saffron underline
        PdfPTable hair = new PdfPTable(1);
        hair.setWidthPercentage(8);
        hair.setHorizontalAlignment(Element.ALIGN_LEFT);
        PdfPCell hairCell = new PdfPCell();
        hairCell.setFixedHeight(3f);
        hairCell.setBackgroundColor(BRAND_SAFFRON);
        hairCell.setBorder(Rectangle.NO_BORDER);
        hair.addCell(hairCell);
        doc.add(hair);

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(14);
        doc.add(spacer);
    }

    // ---------- GRID layout (2-col photo cards) ----------

    private void drawGrid(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSplitLate(false);

        for (MenuItem item : items) {
            table.addCell(gridCard(item, showPrices, locale));
        }
        // If odd count, balance with an empty cell so the last row looks tidy.
        if (items.size() % 2 == 1) {
            PdfPCell filler = new PdfPCell();
            filler.setBorder(Rectangle.NO_BORDER);
            table.addCell(filler);
        }
        doc.add(table);
    }

    private PdfPCell gridCard(MenuItem item, boolean showPrices, Locale locale) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(0);
        cell.setPaddingBottom(8);
        cell.setPaddingRight(8);
        cell.setBorder(Rectangle.NO_BORDER);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Photo or placeholder
        PdfPCell photoCell = new PdfPCell();
        photoCell.setBackgroundColor(BRAND_CREAM);
        photoCell.setBorder(Rectangle.NO_BORDER);
        photoCell.setFixedHeight(140f);
        photoCell.setPadding(0);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.setAlignment(Image.ALIGN_CENTER | Image.ALIGN_MIDDLE);
            img.scaleToFit(260, 130);
            photoCell.setImage(img);
        }
        card.addCell(photoCell);

        // Name + price row
        Font nameFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, BRAND_INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, BRAND_SAFFRON);
        Font descFont = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED);
        Font tagsFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, MUTED);

        PdfPTable nameRow = new PdfPTable(showPrices ? 2 : 1);
        nameRow.setWidthPercentage(100);
        if (showPrices) {
            try {
                nameRow.setWidths(new float[]{6, 2});
            } catch (DocumentException ignored) {}
        }
        Phrase namePhrase = new Phrase(item.getName(), nameFont);
        if (item.isFeatured()) {
            namePhrase.add(new Phrase("  ★", FontFactory.getFont(FontFactory.HELVETICA, 11, BRAND_SAFFRON)));
        }
        nameRow.addCell(textCell(namePhrase, Element.ALIGN_LEFT));
        if (showPrices) {
            nameRow.addCell(textCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont),
                    Element.ALIGN_RIGHT));
        }

        PdfPCell nameCell = new PdfPCell(nameRow);
        nameCell.setBorder(Rectangle.NO_BORDER);
        nameCell.setBackgroundColor(CARD_BG);
        nameCell.setPaddingTop(10);
        nameCell.setPaddingLeft(2);
        nameCell.setPaddingRight(2);
        nameCell.setPaddingBottom(2);
        card.addCell(nameCell);

        // Description (prefer longDescription, fall back to short description)
        String desc = item.getLongDescription();
        if (desc == null || desc.isBlank()) desc = item.getDescription();
        if (desc != null && !desc.isBlank()) {
            PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
            d.setBorder(Rectangle.NO_BORDER);
            d.setPaddingLeft(2);
            d.setPaddingRight(2);
            d.setPaddingBottom(2);
            card.addCell(d);
        }

        // Diet + allergen chips line
        String chipsLine = renderChips(item);
        if (chipsLine != null) {
            PdfPCell chips = new PdfPCell(new Phrase(chipsLine, tagsFont));
            chips.setBorder(Rectangle.NO_BORDER);
            chips.setPaddingLeft(2);
            chips.setPaddingTop(2);
            chips.setPaddingBottom(0);
            card.addCell(chips);
        }

        cell.addElement(card);
        return cell;
    }

    // ---------- LIST layout (single column, full descriptions) ----------

    private void drawList(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        for (MenuItem item : items) {
            doc.add(listRow(item, showPrices, locale));
            PdfPTable hair = new PdfPTable(1);
            hair.setWidthPercentage(100);
            PdfPCell h = new PdfPCell();
            h.setFixedHeight(1f);
            h.setBackgroundColor(HAIRLINE);
            h.setBorder(Rectangle.NO_BORDER);
            hair.addCell(h);
            hair.setSpacingBefore(8);
            hair.setSpacingAfter(8);
            doc.add(hair);
        }
    }

    private PdfPTable listRow(MenuItem item, boolean showPrices, Locale locale) {
        PdfPTable row = new PdfPTable(2);
        row.setWidthPercentage(100);
        try {
            row.setWidths(new float[]{1.1f, 4f});
        } catch (DocumentException ignored) {}

        // Thumbnail (or empty cell if no photo)
        PdfPCell photo = new PdfPCell();
        photo.setBorder(Rectangle.NO_BORDER);
        photo.setFixedHeight(90f);
        photo.setPadding(0);
        photo.setPaddingRight(12);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.scaleToFit(110, 80);
            photo.setImage(img);
        } else {
            photo.setBackgroundColor(BRAND_CREAM);
        }
        row.addCell(photo);

        // Text column
        Font nameFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BRAND_INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BRAND_SAFFRON);
        Font descFont = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, MUTED);
        Font tagsFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, MUTED);

        PdfPTable text = new PdfPTable(showPrices ? 2 : 1);
        text.setWidthPercentage(100);
        if (showPrices) {
            try { text.setWidths(new float[]{6, 2}); } catch (DocumentException ignored) {}
        }
        Phrase namePhrase = new Phrase(item.getName(), nameFont);
        if (item.isFeatured()) {
            namePhrase.add(new Phrase("  ★", FontFactory.getFont(FontFactory.HELVETICA, 11, BRAND_SAFFRON)));
        }
        text.addCell(textCell(namePhrase, Element.ALIGN_LEFT));
        if (showPrices) {
            text.addCell(textCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont),
                    Element.ALIGN_RIGHT));
        }
        PdfPCell textWrap = new PdfPCell(text);
        textWrap.setBorder(Rectangle.NO_BORDER);
        textWrap.setPaddingBottom(2);

        String desc = item.getLongDescription();
        if (desc == null || desc.isBlank()) desc = item.getDescription();
        Paragraph para = new Paragraph();
        if (desc != null && !desc.isBlank()) {
            para.add(new Phrase(desc, descFont));
        }
        String chipsLine = renderChips(item);
        if (chipsLine != null) {
            if (desc != null && !desc.isBlank()) para.add(new Phrase("\n"));
            para.add(new Phrase(chipsLine, tagsFont));
        }

        PdfPCell descWrap = new PdfPCell(para);
        descWrap.setBorder(Rectangle.NO_BORDER);
        descWrap.setPaddingTop(3);

        PdfPTable textCol = new PdfPTable(1);
        textCol.setWidthPercentage(100);
        textCol.addCell(textWrap);
        textCol.addCell(descWrap);

        PdfPCell textColWrap = new PdfPCell(textCol);
        textColWrap.setBorder(Rectangle.NO_BORDER);
        textColWrap.setPaddingLeft(0);
        row.addCell(textColWrap);
        return row;
    }

    // ---------- COMPACT layout (2-col text only, no photos) ----------

    private void drawCompact(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        // Split items into two columns alternating to balance heights.
        List<MenuItem> left = new ArrayList<>();
        List<MenuItem> right = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            (i % 2 == 0 ? left : right).add(items.get(i));
        }
        PdfPTable two = new PdfPTable(2);
        two.setWidthPercentage(100);
        try { two.setWidths(new float[]{1, 1}); } catch (DocumentException ignored) {}

        PdfPCell leftCell = new PdfPCell(columnTable(left, showPrices, locale));
        PdfPCell rightCell = new PdfPCell(columnTable(right, showPrices, locale));
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.setPaddingRight(18);
        rightCell.setBorder(Rectangle.NO_BORDER);
        rightCell.setPaddingLeft(18);
        two.addCell(leftCell);
        two.addCell(rightCell);
        doc.add(two);
    }

    private PdfPTable columnTable(List<MenuItem> items, boolean showPrices, Locale locale) {
        PdfPTable col = new PdfPTable(1);
        col.setWidthPercentage(100);
        Font nameFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BRAND_INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BRAND_SAFFRON);
        Font descFont = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED);
        Font tagsFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, MUTED);

        for (MenuItem item : items) {
            PdfPTable head = new PdfPTable(showPrices ? 2 : 1);
            head.setWidthPercentage(100);
            if (showPrices) {
                try { head.setWidths(new float[]{5, 2}); } catch (DocumentException ignored) {}
            }
            Phrase namePhrase = new Phrase(item.getName(), nameFont);
            if (item.isFeatured()) {
                namePhrase.add(new Phrase("  ★", FontFactory.getFont(FontFactory.HELVETICA, 10, BRAND_SAFFRON)));
            }
            head.addCell(textCell(namePhrase, Element.ALIGN_LEFT));
            if (showPrices) {
                head.addCell(textCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont),
                        Element.ALIGN_RIGHT));
            }
            PdfPCell headWrap = new PdfPCell(head);
            headWrap.setBorder(Rectangle.NO_BORDER);
            col.addCell(headWrap);

            String desc = item.getLongDescription();
            if (desc == null || desc.isBlank()) desc = item.getDescription();
            if (desc != null && !desc.isBlank()) {
                PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
                d.setBorder(Rectangle.NO_BORDER);
                d.setPaddingBottom(2);
                col.addCell(d);
            }
            String chips = renderChips(item);
            if (chips != null) {
                PdfPCell c = new PdfPCell(new Phrase(chips, tagsFont));
                c.setBorder(Rectangle.NO_BORDER);
                c.setPaddingBottom(10);
                col.addCell(c);
            } else {
                PdfPCell pad = new PdfPCell(new Phrase(" "));
                pad.setBorder(Rectangle.NO_BORDER);
                pad.setFixedHeight(8f);
                col.addCell(pad);
            }
        }
        return col;
    }

    // ---------- Helpers ----------

    private PdfPCell textCell(Phrase p, int align) {
        PdfPCell cell = new PdfPCell(p);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private Image tryLoadImage(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        try {
            Path p = fileStorage.getUploadDir().resolve(relativePath).normalize();
            if (!p.startsWith(fileStorage.getUploadDir())) return null;
            if (!Files.exists(p)) return null;
            return Image.getInstance(p.toUri().toURL());
        } catch (Exception e) {
            return null;
        }
    }

    private String renderChips(MenuItem item) {
        List<String> parts = new ArrayList<>();
        if (item.getDietaryTags() != null) {
            for (String t : item.getDietaryTags().split(",")) {
                if (!t.isBlank()) parts.add(prettyTag(t));
            }
        }
        if (item.getAllergens() != null) {
            List<String> al = new ArrayList<>();
            for (String t : item.getAllergens().split(",")) {
                if (!t.isBlank()) al.add(prettyTag(t));
            }
            if (!al.isEmpty()) parts.add("contains: " + String.join(", ", al));
        }
        return parts.isEmpty() ? null : String.join("  ·  ", parts);
    }

    private static String prettyTag(String slug) {
        return switch (slug.toLowerCase(Locale.ROOT)) {
            case "vegetarian", "vege", "veggie" -> "vegetarian";
            case "vegan" -> "vegan";
            case "gluten-free", "gf" -> "gluten-free";
            case "spicy", "hot" -> "spicy";
            case "signature", "chef", "chefs" -> "chef's signature";
            default -> slug.replace('-', ' ');
        };
    }

    private static String formatPrice(BigDecimal price, Locale locale) {
        if (price == null) return "";
        BigDecimal scaled = price.setScale(2, RoundingMode.HALF_UP);
        // Polish menus typically show "32,00 zł"; English ones "32.00 zł" or "PLN 32.00".
        if (locale != null && "pl".equalsIgnoreCase(locale.getLanguage())) {
            return scaled.toPlainString().replace('.', ',') + " zł";
        }
        return scaled.toPlainString() + " zł";
    }

    // ---------- Footer event ----------

    private static class MenuFooter extends PdfPageEventHelper {
        private final String brand;
        MenuFooter(String brand) { this.brand = brand; }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            // Skip footer on cover (page 1).
            if (writer.getPageNumber() <= 1) return;
            try {
                Font f = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
                Phrase left = new Phrase(brand, f);
                Phrase right = new Phrase("Page " + (writer.getPageNumber() - 1), f);
                com.lowagie.text.pdf.ColumnText.showTextAligned(
                        writer.getDirectContent(), Element.ALIGN_LEFT, left,
                        doc.leftMargin(), doc.bottomMargin() - 12, 0);
                com.lowagie.text.pdf.ColumnText.showTextAligned(
                        writer.getDirectContent(), Element.ALIGN_RIGHT, right,
                        doc.right(), doc.bottomMargin() - 12, 0);
                // Saffron rule on each content page
                com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
                cb.saveState();
                cb.setColorStroke(BRAND_SAFFRON);
                cb.setLineWidth(0.6f);
                cb.moveTo(doc.leftMargin(), doc.bottomMargin() - 4);
                cb.lineTo(doc.right(), doc.bottomMargin() - 4);
                cb.stroke();
                cb.restoreState();
            } catch (Exception ignored) { }
        }
    }
}
