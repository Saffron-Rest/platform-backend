package com.saffron.cashflow.service;

import com.lowagie.text.Chunk;
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
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates a designer-quality, restaurant-grade PDF menu for printing.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Serif headings (Times) paired with clean sans (Helvetica) body — the
 *       same pairing used by most upmarket restaurants.</li>
 *   <li>A small saffron palette (deep ink + saffron accent + cream surface)
 *       gives the menu warmth without looking busy.</li>
 *   <li>A proper "Contents" page lists categories with real page numbers,
 *       resolved in a two-pass render.</li>
 *   <li>Photos render with a thin border + soft drop shadow; missing photos
 *       fall back to a textured cream tile.</li>
 *   <li>Each category page opens with an oversize numeral, an eyebrow label,
 *       the category title in serif, and a saffron rule.</li>
 *   <li>Featured items get a "Chef's signature" pill above the name.</li>
 * </ul>
 *
 * <p>Three layouts: GRID (photo cards), LIST (single column with thumbnail),
 * COMPACT (text only, two columns — for table tents / takeaway menus).
 */
@Service
public class MenuPrintService {

    // Brand palette
    private static final Color INK = new Color(0x1A, 0x18, 0x14);
    private static final Color SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color SAFFRON_DEEP = new Color(0xA4, 0x52, 0x12);
    private static final Color CREAM = new Color(0xFA, 0xF4, 0xE8);
    private static final Color CREAM_DEEP = new Color(0xF3, 0xEA, 0xD6);
    private static final Color MUTED = new Color(0x6B, 0x63, 0x57);
    private static final Color HAIRLINE = new Color(0xE2, 0xDD, 0xD2);
    private static final Color SHADOW = new Color(0x00, 0x00, 0x00, 30);

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
        String title = (customTitle != null && !customTitle.isBlank()) ? customTitle.trim() : "Saffron";
        String subtitle = (customSubtitle != null && !customSubtitle.isBlank())
                ? customSubtitle.trim()
                : "Authentic Azerbaijani Restaurant";
        Locale locale = "pl".equalsIgnoreCase(language) ? Locale.forLanguageTag("pl-PL") : Locale.ENGLISH;

        List<MenuCategory> allCategories = menuService.activeCategoriesInOrder();
        List<MenuCategory> categories = new ArrayList<>();
        Map<String, List<MenuItem>> itemsByCategory = new HashMap<>();
        for (MenuCategory c : allCategories) {
            List<MenuItem> its = menuService.activeItemsForCategory(c.getId());
            if (!its.isEmpty()) {
                categories.add(c);
                itemsByCategory.put(c.getId(), its);
            }
        }
        if (categories.isEmpty()) {
            throw new BadRequestException(
                    "No active categories with items — add items in /admin/menu before printing.");
        }

        // PASS 1 — render once to discover the real page numbers each category
        // starts on. We need that for the contents page.
        RenderResult first = renderInternal(
                layout, title, subtitle, showPrices, locale, categories, itemsByCategory, null);

        // PASS 2 — full render with the contents page populated from pass 1.
        return renderInternal(layout, title, subtitle, showPrices, locale, categories, itemsByCategory,
                first.pageStarts()).bytes();
    }

    private RenderResult renderInternal(
            Layout layout,
            String title,
            String subtitle,
            boolean showPrices,
            Locale locale,
            List<MenuCategory> categories,
            Map<String, List<MenuItem>> itemsByCategory,
            Map<String, Integer> contentsPageStarts) {

        Document doc = new Document(PageSize.A4, 48, 48, 70, 60);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, Integer> pageStarts = new HashMap<>();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new MenuChrome(title));
            doc.open();

            // Cover (page 1)
            drawCover(doc, title, subtitle);

            // Contents page (page 2) — only on pass 2 when we have real numbers.
            if (contentsPageStarts != null) {
                doc.newPage();
                drawContents(doc, categories, contentsPageStarts);
            }

            int catIdx = 1;
            for (MenuCategory cat : categories) {
                List<MenuItem> items = itemsByCategory.get(cat.getId());
                if (items == null || items.isEmpty()) continue;
                doc.newPage();
                // Capture the actual page number this category starts on.
                pageStarts.put(cat.getId(), writer.getPageNumber());
                drawCategoryHero(doc, cat.getName(), catIdx);
                switch (layout) {
                    case GRID -> drawGrid(doc, items, showPrices, locale);
                    case LIST -> drawList(doc, items, showPrices, locale);
                    case COMPACT -> drawCompact(doc, items, showPrices, locale);
                }
                catIdx++;
            }

            // Closing page — allergens key + thank-you
            doc.newPage();
            drawClosing(doc, locale);

            doc.close();
            return new RenderResult(out.toByteArray(), pageStarts);
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to build menu PDF: " + e.getMessage(), e);
        }
    }

    private record RenderResult(byte[] bytes, Map<String, Integer> pageStarts) {}

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

    private void drawCover(Document doc, String title, String subtitle) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, SAFFRON_DEEP);
        Font brand = FontFactory.getFont(FontFactory.TIMES_BOLD, 76, INK);
        Font sub = FontFactory.getFont(FontFactory.TIMES_ITALIC, 18, MUTED);
        Font date = FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED);

        // Top saffron rule
        accentBar(doc, 6f, SAFFRON);

        Paragraph eyebrowPara = new Paragraph(spacedCaps("La Carte"), eyebrow);
        eyebrowPara.setSpacingBefore(180);
        eyebrowPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(eyebrowPara);

        Paragraph brandPara = new Paragraph(title, brand);
        brandPara.setSpacingBefore(18);
        brandPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(brandPara);

        // Ornament: two small saffron rules around an italic subtitle
        drawOrnament(doc);

        Paragraph subPara = new Paragraph(subtitle, sub);
        subPara.setSpacingBefore(14);
        subPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(subPara);

        Paragraph datePara = new Paragraph(LocalDate.now().format(MENU_DATE), date);
        datePara.setSpacingBefore(220);
        datePara.setAlignment(Element.ALIGN_CENTER);
        doc.add(datePara);

        // Bottom saffron rule
        Paragraph bottomSpacer = new Paragraph(" ");
        bottomSpacer.setSpacingBefore(30);
        doc.add(bottomSpacer);
        accentBar(doc, 6f, SAFFRON);
    }

    private void drawOrnament(Document doc) throws DocumentException {
        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(50);
        t.setHorizontalAlignment(Element.ALIGN_CENTER);
        try { t.setWidths(new float[]{4, 1, 4}); } catch (DocumentException ignored) {}
        PdfPCell left = new PdfPCell();
        left.setFixedHeight(1.5f);
        left.setBackgroundColor(SAFFRON);
        left.setBorder(Rectangle.NO_BORDER);
        PdfPCell diamond = new PdfPCell(new Phrase("◆", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, SAFFRON)));
        diamond.setBorder(Rectangle.NO_BORDER);
        diamond.setHorizontalAlignment(Element.ALIGN_CENTER);
        diamond.setVerticalAlignment(Element.ALIGN_MIDDLE);
        diamond.setFixedHeight(12f);
        PdfPCell right = new PdfPCell();
        right.setFixedHeight(1.5f);
        right.setBackgroundColor(SAFFRON);
        right.setBorder(Rectangle.NO_BORDER);
        t.addCell(left);
        t.addCell(diamond);
        t.addCell(right);
        Paragraph wrap = new Paragraph(" ");
        wrap.setSpacingBefore(18);
        doc.add(wrap);
        doc.add(t);
    }

    // ---------- Contents ----------

    private void drawContents(Document doc, List<MenuCategory> cats, Map<String, Integer> starts)
            throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 32, INK);
        Font row = FontFactory.getFont(FontFactory.TIMES_ROMAN, 14, INK);
        Font pageNum = FontFactory.getFont(FontFactory.HELVETICA, 11, MUTED);
        Font index = FontFactory.getFont(FontFactory.HELVETICA, 9, SAFFRON);

        Paragraph eb = new Paragraph(spacedCaps("Contents"), eyebrow);
        eb.setSpacingBefore(10);
        doc.add(eb);

        Paragraph h = new Paragraph("Table of contents", head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(16);
        doc.add(h);

        LineSeparator sep = new LineSeparator(0.5f, 100, SAFFRON, Element.ALIGN_LEFT, 0);
        doc.add(new Chunk(sep));

        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        try { t.setWidths(new float[]{0.6f, 8, 1}); } catch (DocumentException ignored) {}
        t.setSpacingBefore(24);

        int i = 1;
        for (MenuCategory c : cats) {
            Integer pg = starts.get(c.getId());
            // Page number — show "—" if not yet known (pass 1).
            String pgText = pg != null ? String.valueOf(pg) : "—";

            PdfPCell numCell = new PdfPCell(new Phrase(twoDigit(i), index));
            numCell.setBorder(Rectangle.NO_BORDER);
            numCell.setPaddingTop(14);
            numCell.setPaddingBottom(14);

            // Name + dotted leader
            Phrase np = new Phrase();
            np.add(new Phrase(c.getName(), row));
            PdfPCell nameCell = new PdfPCell(np);
            nameCell.setBorder(Rectangle.BOTTOM);
            nameCell.setBorderColor(HAIRLINE);
            nameCell.setBorderWidthBottom(0.5f);
            nameCell.setPaddingTop(14);
            nameCell.setPaddingBottom(14);

            PdfPCell pgCell = new PdfPCell(new Phrase(pgText, pageNum));
            pgCell.setBorder(Rectangle.BOTTOM);
            pgCell.setBorderColor(HAIRLINE);
            pgCell.setBorderWidthBottom(0.5f);
            pgCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            pgCell.setPaddingTop(14);
            pgCell.setPaddingBottom(14);

            t.addCell(numCell);
            t.addCell(nameCell);
            t.addCell(pgCell);
            i++;
        }
        doc.add(t);
    }

    // ---------- Category hero ----------

    private void drawCategoryHero(Document doc, String name, int idx) throws DocumentException {
        Font numeral = FontFactory.getFont(FontFactory.TIMES_BOLD, 64, CREAM_DEEP);
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 34, INK);

        PdfPTable hero = new PdfPTable(2);
        hero.setWidthPercentage(100);
        try { hero.setWidths(new float[]{1.4f, 8}); } catch (DocumentException ignored) {}

        PdfPCell numCell = new PdfPCell(new Phrase(twoDigit(idx), numeral));
        numCell.setBorder(Rectangle.NO_BORDER);
        numCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        numCell.setPaddingTop(0);

        // Right side: eyebrow + name + small saffron rule
        PdfPTable textBlock = new PdfPTable(1);
        textBlock.setWidthPercentage(100);
        PdfPCell ebCell = new PdfPCell(new Phrase(spacedCaps("Chapter " + idx), eyebrow));
        ebCell.setBorder(Rectangle.NO_BORDER);
        ebCell.setPaddingBottom(0);
        textBlock.addCell(ebCell);

        PdfPCell nameCell = new PdfPCell(new Phrase(name, head));
        nameCell.setBorder(Rectangle.NO_BORDER);
        nameCell.setPaddingTop(2);
        nameCell.setPaddingBottom(8);
        textBlock.addCell(nameCell);

        PdfPTable ruleWrap = new PdfPTable(1);
        ruleWrap.setWidthPercentage(12);
        ruleWrap.setHorizontalAlignment(Element.ALIGN_LEFT);
        PdfPCell ruleCell = new PdfPCell();
        ruleCell.setFixedHeight(3f);
        ruleCell.setBackgroundColor(SAFFRON);
        ruleCell.setBorder(Rectangle.NO_BORDER);
        ruleWrap.addCell(ruleCell);
        PdfPCell ruleHolder = new PdfPCell(ruleWrap);
        ruleHolder.setBorder(Rectangle.NO_BORDER);
        ruleHolder.setPaddingTop(2);
        textBlock.addCell(ruleHolder);

        PdfPCell textCell = new PdfPCell(textBlock);
        textCell.setBorder(Rectangle.NO_BORDER);
        textCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        hero.addCell(numCell);
        hero.addCell(textCell);
        doc.add(hero);

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(22);
        doc.add(spacer);
    }

    // ---------- GRID ----------

    private void drawGrid(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSplitLate(false);
        table.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        for (MenuItem item : items) table.addCell(gridCard(item, showPrices, locale));
        if (items.size() % 2 == 1) {
            PdfPCell filler = new PdfPCell();
            filler.setBorder(Rectangle.NO_BORDER);
            table.addCell(filler);
        }
        doc.add(table);
    }

    private PdfPCell gridCard(MenuItem item, boolean showPrices, Locale locale) {
        PdfPCell wrap = new PdfPCell();
        wrap.setBorder(Rectangle.NO_BORDER);
        wrap.setPadding(0);
        wrap.setPaddingBottom(16);
        wrap.setPaddingRight(10);
        wrap.setPaddingLeft(0);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Photo
        PdfPCell photo = new PdfPCell();
        photo.setBackgroundColor(CREAM);
        photo.setBorder(Rectangle.BOX);
        photo.setBorderColor(HAIRLINE);
        photo.setBorderWidth(0.6f);
        photo.setFixedHeight(150f);
        photo.setPadding(2);
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.setAlignment(Image.ALIGN_CENTER | Image.ALIGN_MIDDLE);
            img.scaleToFit(258, 140);
            photo.setImage(img);
        } else {
            photo.setPhrase(new Phrase(" ", FontFactory.getFont(FontFactory.HELVETICA, 8, CREAM_DEEP)));
        }
        card.addCell(photo);

        // Featured pill (only if applicable)
        if (item.isFeatured()) {
            PdfPCell pill = new PdfPCell(new Phrase(spacedCaps("Chef's signature"),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, SAFFRON_DEEP)));
            pill.setBorder(Rectangle.NO_BORDER);
            pill.setPaddingTop(10);
            pill.setPaddingBottom(0);
            card.addCell(pill);
        }

        // Name + price row
        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 14, INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, SAFFRON_DEEP);
        Font descFont = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED);
        Font tagsFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, MUTED);
        Font allergenFont = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, MUTED);

        PdfPTable nameRow = new PdfPTable(showPrices ? 2 : 1);
        nameRow.setWidthPercentage(100);
        if (showPrices) {
            try { nameRow.setWidths(new float[]{6, 2}); } catch (DocumentException ignored) {}
        }
        nameRow.addCell(textCell(new Phrase(item.getName(), nameFont), Element.ALIGN_LEFT));
        if (showPrices) {
            nameRow.addCell(textCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont),
                    Element.ALIGN_RIGHT));
        }
        PdfPCell nameWrap = new PdfPCell(nameRow);
        nameWrap.setBorder(Rectangle.NO_BORDER);
        nameWrap.setPaddingTop(item.isFeatured() ? 4 : 12);
        nameWrap.setPaddingBottom(2);
        card.addCell(nameWrap);

        // Hairline under name
        PdfPCell hair = new PdfPCell();
        hair.setFixedHeight(0.8f);
        hair.setBackgroundColor(HAIRLINE);
        hair.setBorder(Rectangle.NO_BORDER);
        card.addCell(hair);

        String desc = chooseDescription(item);
        if (desc != null) {
            PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
            d.setBorder(Rectangle.NO_BORDER);
            d.setPaddingTop(7);
            d.setPaddingBottom(2);
            card.addCell(d);
        }

        String dietary = renderDietary(item);
        if (dietary != null) {
            PdfPCell c = new PdfPCell(new Phrase(dietary, tagsFont));
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingTop(3);
            card.addCell(c);
        }
        String allergen = renderAllergens(item);
        if (allergen != null) {
            PdfPCell c = new PdfPCell(new Phrase(allergen, allergenFont));
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingTop(2);
            card.addCell(c);
        }

        wrap.addElement(card);
        return wrap;
    }

    // ---------- LIST ----------

    private void drawList(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            doc.add(listRow(item, showPrices, locale));
            if (i < items.size() - 1) {
                Paragraph pad = new Paragraph(" ");
                pad.setSpacingBefore(6);
                pad.setSpacingAfter(6);
                doc.add(pad);
                LineSeparator sep = new LineSeparator(0.4f, 100, HAIRLINE, Element.ALIGN_LEFT, 0);
                doc.add(new Chunk(sep));
            }
        }
    }

    private PdfPTable listRow(MenuItem item, boolean showPrices, Locale locale) {
        PdfPTable row = new PdfPTable(2);
        row.setWidthPercentage(100);
        try { row.setWidths(new float[]{1.2f, 4f}); } catch (DocumentException ignored) {}

        PdfPCell photo = new PdfPCell();
        photo.setBorder(Rectangle.BOX);
        photo.setBorderColor(HAIRLINE);
        photo.setBorderWidth(0.6f);
        photo.setFixedHeight(96f);
        photo.setPaddingRight(14);
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.scaleToFit(118, 88);
            photo.setImage(img);
        } else {
            photo.setBackgroundColor(CREAM);
        }
        row.addCell(photo);

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 15, INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, SAFFRON_DEEP);
        Font pillFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, SAFFRON_DEEP);
        Font descFont = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, MUTED);
        Font tagsFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, MUTED);
        Font allergenFont = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);

        PdfPTable text = new PdfPTable(1);
        text.setWidthPercentage(100);

        if (item.isFeatured()) {
            PdfPCell p = new PdfPCell(new Phrase(spacedCaps("Chef's signature"), pillFont));
            p.setBorder(Rectangle.NO_BORDER);
            p.setPaddingBottom(2);
            text.addCell(p);
        }

        PdfPTable head = new PdfPTable(showPrices ? 2 : 1);
        head.setWidthPercentage(100);
        if (showPrices) {
            try { head.setWidths(new float[]{6, 2}); } catch (DocumentException ignored) {}
        }
        head.addCell(textCell(new Phrase(item.getName(), nameFont), Element.ALIGN_LEFT));
        if (showPrices) {
            head.addCell(textCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont),
                    Element.ALIGN_RIGHT));
        }
        PdfPCell headWrap = new PdfPCell(head);
        headWrap.setBorder(Rectangle.NO_BORDER);
        headWrap.setPaddingBottom(4);
        text.addCell(headWrap);

        String desc = chooseDescription(item);
        if (desc != null) {
            PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
            d.setBorder(Rectangle.NO_BORDER);
            d.setPaddingBottom(3);
            text.addCell(d);
        }
        String dietary = renderDietary(item);
        if (dietary != null) {
            PdfPCell c = new PdfPCell(new Phrase(dietary, tagsFont));
            c.setBorder(Rectangle.NO_BORDER);
            text.addCell(c);
        }
        String allergen = renderAllergens(item);
        if (allergen != null) {
            PdfPCell c = new PdfPCell(new Phrase(allergen, allergenFont));
            c.setBorder(Rectangle.NO_BORDER);
            text.addCell(c);
        }

        PdfPCell textCol = new PdfPCell(text);
        textCol.setBorder(Rectangle.NO_BORDER);
        textCol.setVerticalAlignment(Element.ALIGN_TOP);
        row.addCell(textCol);
        return row;
    }

    // ---------- COMPACT ----------

    private void drawCompact(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        List<MenuItem> left = new ArrayList<>();
        List<MenuItem> right = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) (i % 2 == 0 ? left : right).add(items.get(i));

        PdfPTable two = new PdfPTable(2);
        two.setWidthPercentage(100);
        try { two.setWidths(new float[]{1, 1}); } catch (DocumentException ignored) {}

        PdfPCell l = new PdfPCell(columnTable(left, showPrices, locale));
        PdfPCell r = new PdfPCell(columnTable(right, showPrices, locale));
        l.setBorder(Rectangle.NO_BORDER);
        l.setPaddingRight(22);
        r.setBorder(Rectangle.NO_BORDER);
        r.setPaddingLeft(22);
        two.addCell(l);
        two.addCell(r);
        doc.add(two);
    }

    private PdfPTable columnTable(List<MenuItem> items, boolean showPrices, Locale locale) {
        PdfPTable col = new PdfPTable(1);
        col.setWidthPercentage(100);

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 13, INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SAFFRON_DEEP);
        Font pillFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, SAFFRON_DEEP);
        Font descFont = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED);
        Font tagsFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, MUTED);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);

            if (item.isFeatured()) {
                PdfPCell p = new PdfPCell(new Phrase(spacedCaps("Chef's signature"), pillFont));
                p.setBorder(Rectangle.NO_BORDER);
                col.addCell(p);
            }

            PdfPTable head = new PdfPTable(showPrices ? 2 : 1);
            head.setWidthPercentage(100);
            if (showPrices) {
                try { head.setWidths(new float[]{5, 2}); } catch (DocumentException ignored) {}
            }
            head.addCell(textCell(new Phrase(item.getName(), nameFont), Element.ALIGN_LEFT));
            if (showPrices) {
                head.addCell(textCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont),
                        Element.ALIGN_RIGHT));
            }
            PdfPCell headWrap = new PdfPCell(head);
            headWrap.setBorder(Rectangle.NO_BORDER);
            col.addCell(headWrap);

            String desc = chooseDescription(item);
            if (desc != null) {
                PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
                d.setBorder(Rectangle.NO_BORDER);
                d.setPaddingBottom(2);
                col.addCell(d);
            }
            String dietary = renderDietary(item);
            if (dietary != null) {
                PdfPCell c = new PdfPCell(new Phrase(dietary, tagsFont));
                c.setBorder(Rectangle.NO_BORDER);
                col.addCell(c);
            }

            if (i < items.size() - 1) {
                PdfPCell pad = new PdfPCell(new Phrase(" "));
                pad.setBorder(Rectangle.NO_BORDER);
                pad.setFixedHeight(12f);
                col.addCell(pad);
            }
        }
        return col;
    }

    // ---------- Closing page ----------

    private void drawClosing(Document doc, Locale locale) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 28, INK);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, MUTED);

        Paragraph eb = new Paragraph(spacedCaps("Important to know"), eyebrow);
        eb.setSpacingBefore(0);
        doc.add(eb);

        Paragraph h = new Paragraph("Allergens & advisories", head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(20);
        doc.add(h);

        Paragraph p = new Paragraph(
                "Please notify a member of our team of any allergies or dietary requirements before ordering. "
                        + "All dishes are prepared in a kitchen that also handles gluten, dairy, eggs, nuts, sesame, soya, "
                        + "fish, shellfish, celery, mustard and sulphites — cross-contact cannot be entirely excluded.",
                body);
        p.setSpacingAfter(12);
        doc.add(p);

        Paragraph p2 = new Paragraph(
                "Photographs are for presentation purposes. Plating, garnishes and side accompaniments may vary "
                        + "based on seasonal availability. Prices include VAT.",
                body);
        p2.setSpacingAfter(40);
        doc.add(p2);

        // Thank-you ornament
        Paragraph thanks = new Paragraph(
                "Thank you for dining with us.",
                FontFactory.getFont(FontFactory.TIMES_ITALIC, 16, INK));
        thanks.setAlignment(Element.ALIGN_CENTER);
        thanks.setSpacingBefore(60);
        doc.add(thanks);

        drawOrnament(doc);
    }

    // ---------- Helpers ----------

    private void accentBar(Document doc, float height, Color color) throws DocumentException {
        PdfPTable bar = new PdfPTable(1);
        bar.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(color);
        c.setFixedHeight(height);
        c.setBorder(Rectangle.NO_BORDER);
        bar.addCell(c);
        doc.add(bar);
    }

    private static String spacedCaps(String s) {
        // Render as widely-tracked caps for an eyebrow label.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(Character.toUpperCase(s.charAt(i)));
            if (i < s.length() - 1) sb.append(' ');
        }
        return sb.toString();
    }

    private static String twoDigit(int i) {
        return i < 10 ? "0" + i : String.valueOf(i);
    }

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

    private static String chooseDescription(MenuItem item) {
        String desc = item.getLongDescription();
        if (desc == null || desc.isBlank()) desc = item.getDescription();
        return (desc == null || desc.isBlank()) ? null : desc;
    }

    private static String renderDietary(MenuItem item) {
        if (item.getDietaryTags() == null) return null;
        List<String> parts = new ArrayList<>();
        for (String t : item.getDietaryTags().split(",")) {
            if (!t.isBlank()) parts.add(prettyTag(t));
        }
        return parts.isEmpty() ? null : String.join("  ·  ", parts);
    }

    private static String renderAllergens(MenuItem item) {
        if (item.getAllergens() == null) return null;
        List<String> al = new ArrayList<>();
        for (String t : item.getAllergens().split(",")) {
            if (!t.isBlank()) al.add(prettyTag(t));
        }
        return al.isEmpty() ? null : "Contains: " + String.join(", ", al);
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
        if (locale != null && "pl".equalsIgnoreCase(locale.getLanguage())) {
            return scaled.toPlainString().replace('.', ',') + " zł";
        }
        return scaled.toPlainString() + " zł";
    }

    // ---------- Page chrome (header + footer) ----------

    private static class MenuChrome extends PdfPageEventHelper {
        private final String brand;
        MenuChrome(String brand) { this.brand = brand; }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            int p = writer.getPageNumber();
            if (p <= 1) return; // no chrome on cover

            PdfContentByte cb = writer.getDirectContent();

            // Header — small saffron rule + brand wordmark
            cb.saveState();
            cb.setColorStroke(SAFFRON);
            cb.setLineWidth(0.6f);
            cb.moveTo(doc.leftMargin(), doc.top() + 28);
            cb.lineTo(doc.leftMargin() + 18, doc.top() + 28);
            cb.stroke();
            cb.restoreState();
            try {
                Font brandFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 9.5f, INK);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_LEFT,
                        new Phrase(brand.toUpperCase(Locale.ROOT), brandFont),
                        doc.leftMargin() + 26, doc.top() + 26, 0);
                Font menuFont = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_RIGHT,
                        new Phrase("Menu · " + LocalDate.now().format(MENU_DATE), menuFont),
                        doc.right(), doc.top() + 26, 0);
            } catch (Exception ignored) {}

            // Footer — saffron rule + page numbers
            cb.saveState();
            cb.setColorStroke(SAFFRON);
            cb.setLineWidth(0.6f);
            cb.moveTo(doc.leftMargin(), doc.bottomMargin() - 8);
            cb.lineTo(doc.leftMargin() + 18, doc.bottomMargin() - 8);
            cb.stroke();
            cb.restoreState();
            try {
                Font fNum = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_RIGHT,
                        new Phrase(String.valueOf(p), fNum),
                        doc.right(), doc.bottomMargin() - 15, 0);
                Font fBrand = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_LEFT,
                        new Phrase(brand, fBrand),
                        doc.leftMargin() + 26, doc.bottomMargin() - 15, 0);
            } catch (Exception ignored) {}
        }
    }
}
