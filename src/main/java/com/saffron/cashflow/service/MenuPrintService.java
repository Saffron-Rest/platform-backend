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
 * Minimalist, typography-led restaurant menu PDF.
 *
 * <p>Pure-typography composition — no photographs, no drawn icons. Every
 * page leans on hierarchy, whitespace, and a single saffron accent line.
 * The brief from the owner: "professional and attractive without big titles
 * or imagery". This implementation aims for the calm, restrained typography
 * of the Eleven Madison Park / Noma school of menu design.</p>
 *
 * <p>Pages (in order):</p>
 * <ol>
 *   <li><b>Cover</b> — saffron frame, wordmark, italic subtitle, diamond
 *       rule, motif strap-line, edition footer.</li>
 *   <li><b>Welcome / Our story</b> — drop cap body with a pull quote.</li>
 *   <li><b>Notes from the kitchen</b> — four heritage notes in a 2×2 grid.</li>
 *   <li><b>Section dividers + items</b> — every category starts on a new
 *       page with a compact divider (small section number, centred title,
 *       Azerbaijani translation, italic blurb), then the items.</li>
 *   <li><b>Allergens &amp; advisories</b> — the dietary key.</li>
 *   <li><b>Çox sağ olun</b> — minimal closing thank-you, optional contact
 *       block.</li>
 * </ol>
 *
 * <p>Per-item photos uploaded by the admin still appear on item cards (in
 * grid + list layouts). When no photo is set the placeholder is a quiet
 * cream tile, not an ornamental glyph.</p>
 */
@Service
public class MenuPrintService {

    // ---- Palette (warm ink + single saffron accent + cream surface) ----
    private static final Color INK = new Color(0x1A, 0x18, 0x14);
    private static final Color INK_SOFT = new Color(0x3A, 0x33, 0x29);
    private static final Color SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color SAFFRON_DEEP = new Color(0xA4, 0x52, 0x12);
    private static final Color CREAM = new Color(0xFA, 0xF4, 0xE8);
    private static final Color CREAM_DEEP = new Color(0xF3, 0xEA, 0xD6);
    private static final Color MUTED = new Color(0x6B, 0x63, 0x57);
    private static final Color HAIRLINE = new Color(0xE2, 0xDD, 0xD2);

    private static final DateTimeFormatter MENU_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** English → Azerbaijani translations used on category dividers. */
    private static final Map<String, String> CATEGORY_TRANSLATIONS = Map.ofEntries(
            Map.entry("starters", "Başlanğıclar"),
            Map.entry("appetisers", "Başlanğıclar"),
            Map.entry("appetizers", "Başlanğıclar"),
            Map.entry("salads", "Salatlar"),
            Map.entry("soups", "Şorbalar"),
            Map.entry("mains", "Əsas yeməklər"),
            Map.entry("main courses", "Əsas yeməklər"),
            Map.entry("plov", "Plov"),
            Map.entry("plov & rice", "Plov və düyü"),
            Map.entry("kebabs", "Kabablar"),
            Map.entry("kebab", "Kabablar"),
            Map.entry("grill", "Kabablar"),
            Map.entry("sides", "Yan yeməklər"),
            Map.entry("breads", "Çörəklər"),
            Map.entry("desserts", "Şirniyyatlar"),
            Map.entry("sweets", "Şirniyyatlar"),
            Map.entry("drinks", "İçkilər"),
            Map.entry("beverages", "İçkilər"),
            Map.entry("tea", "Çay"),
            Map.entry("hot drinks", "İsti içkilər"),
            Map.entry("cold drinks", "Soyuq içkilər"),
            Map.entry("wine", "Şərablar"),
            Map.entry("beer", "Pivə"),
            Map.entry("cocktails", "Kokteyllər"));

    private static final String DEFAULT_STORY_BODY =
            "Saffron is a love letter to Azerbaijan: a country where the spice routes once met the silk roads, "
                    + "where a single plov can take half a day to coax, and where every meal opens with a glass "
                    + "of armudu tea. Our kitchen brings that table to Warsaw — with recipes from Şəki, Lənkəran "
                    + "and Bakı, ingredients sourced as close to home as we can manage, and saffron crocuses "
                    + "we proudly grow ourselves.\n\n"
                    + "Each dish on this menu has a story. Some belong to weddings and Novruz — the spring "
                    + "festival when families gather around dolma and şəkərbura. Others are everyday food, made "
                    + "for sharing slowly, talking long, and leaving the table a little fuller than we planned to. "
                    + "We hope you do the same.";

    private final MenuService menuService;
    private final FileStorageService fileStorage;

    public MenuPrintService(MenuService menuService, FileStorageService fileStorage) {
        this.menuService = menuService;
        this.fileStorage = fileStorage;
    }

    // ---------- Public API ----------

    public byte[] buildMenu(String layoutKey, String customTitle, String customSubtitle,
                            boolean showPrices, String language) {
        return buildMenu(layoutKey, customTitle, customSubtitle, showPrices, language, null, null, null);
    }

    public byte[] buildMenu(String layoutKey, String customTitle, String customSubtitle,
                            boolean showPrices, String language,
                            String storyTitle, String storyBody, String contactBlock) {
        return build(new Options(
                Layout.from(layoutKey), customTitle, customSubtitle, showPrices,
                "pl".equalsIgnoreCase(language) ? Locale.forLanguageTag("pl-PL") : Locale.ENGLISH,
                storyTitle, storyBody, contactBlock));
    }

    public record Options(Layout layout, String customTitle, String customSubtitle,
                          boolean showPrices, Locale locale,
                          String storyTitle, String storyBody, String contactBlock) {}

    private byte[] build(Options opt) {
        String title = blankToDefault(opt.customTitle(), "Saffron");
        String subtitle = blankToDefault(opt.customSubtitle(), "Authentic Azerbaijani Restaurant");

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

        Document doc = new Document(PageSize.A4, 68, 68, 84, 78);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            MenuChrome chrome = new MenuChrome(title);
            writer.setPageEvent(chrome);
            doc.open();

            chrome.suppressNext();
            drawCover(doc, writer, title, subtitle);

            doc.newPage();
            drawStory(doc, writer, opt);

            doc.newPage();
            drawHeritage(doc);

            int idx = 1;
            for (MenuCategory cat : categories) {
                List<MenuItem> items = itemsByCategory.get(cat.getId());
                if (items == null || items.isEmpty()) continue;
                doc.newPage();
                drawSectionDivider(doc, cat.getName(), idx);
                switch (opt.layout()) {
                    case GRID -> drawGrid(doc, items, opt.showPrices(), opt.locale());
                    case LIST -> drawList(doc, items, opt.showPrices(), opt.locale());
                    case COMPACT -> drawCompact(doc, items, opt.showPrices(), opt.locale());
                }
                idx++;
            }

            doc.newPage();
            drawAllergens(doc);

            doc.newPage();
            chrome.suppressNext();
            drawClosing(doc, writer, opt);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to build menu PDF: " + e.getMessage(), e);
        }
    }

    public enum Layout {
        GRID, LIST, COMPACT;
        public static Layout from(String key) {
            if (key == null) return GRID;
            return switch (key.trim().toLowerCase(Locale.ROOT)) {
                case "list" -> LIST;
                case "compact" -> COMPACT;
                default -> GRID;
            };
        }
    }

    // ---------- Cover ----------

    private void drawCover(Document doc, PdfWriter writer, String title, String subtitle)
            throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();
        float w = page.getWidth(), h = page.getHeight();
        float cx = w / 2f;

        // Two concentric thin saffron rules — the only decorative element on
        // the page. The composition then trusts typography to carry weight.
        float inset = 36f;
        drawThinFrame(cb, page, inset, SAFFRON, 0.55f);
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.25f);

        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font brand = FontFactory.getFont(FontFactory.TIMES_BOLD, 108, INK);
        Font sub = FontFactory.getFont(FontFactory.TIMES_ITALIC, 18, MUTED);
        Font cite = FontFactory.getFont(FontFactory.TIMES_ITALIC, 11.5f, SAFFRON_DEEP);
        Font year = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
        Font foot = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);

        showCentered(cb, spacedCaps("La carte · A book of dishes"),
                eyebrow, cx, h - inset - 52);

        showCentered(cb, title, brand, cx, h * 0.60f);

        diamondRule(cb, cx, h * 0.60f - 38f, 70f);

        showCentered(cb, subtitle, sub, cx, h * 0.60f - 64f);

        showCentered(cb, "Şirniyyat · Plov · Kabab · Çay",
                cite, cx, h * 0.42f);

        showCentered(cb, "EDITION · " + LocalDate.now().format(MENU_DATE).toUpperCase(Locale.ROOT),
                year, cx, inset + 54);
        showCentered(cb, "S A F F R O N · W A R S Z A W A · P O L A N D",
                foot, cx, inset + 38);
    }

    // ---------- Story ----------

    private void drawStory(Document doc, PdfWriter writer, Options opt) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 28, INK);
        Font dropCap = FontFactory.getFont(FontFactory.TIMES_BOLD, 54, SAFFRON_DEEP);
        Font body = FontFactory.getFont(FontFactory.TIMES_ROMAN, 12, INK_SOFT);
        Font quote = FontFactory.getFont(FontFactory.TIMES_ITALIC, 15, SAFFRON_DEEP);
        Font attribution = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);

        doc.add(new Paragraph(spacedCaps("Welcome"), eyebrow));

        Paragraph h = new Paragraph(blankToDefault(opt.storyTitle(), "Our story"), head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(2);
        doc.add(h);

        doc.add(saffronRule(48f));

        String[] paragraphs = blankToDefault(opt.storyBody(), DEFAULT_STORY_BODY).split("\\n\\s*\\n");
        if (paragraphs.length > 0 && !paragraphs[0].isBlank()) {
            String first = paragraphs[0].trim();
            String initial = first.substring(0, 1);
            String rest = first.substring(1);

            PdfPTable dc = new PdfPTable(2);
            dc.setWidthPercentage(100);
            dc.setSpacingBefore(22);
            try { dc.setWidths(new float[]{0.8f, 10f}); } catch (DocumentException ignored) {}

            PdfPCell capCell = new PdfPCell(new Phrase(initial, dropCap));
            capCell.setBorder(Rectangle.NO_BORDER);
            capCell.setPaddingTop(-4);
            capCell.setPaddingRight(8);
            capCell.setVerticalAlignment(Element.ALIGN_TOP);

            Paragraph bodyPara = new Paragraph(rest, body);
            bodyPara.setLeading(18f);
            bodyPara.setAlignment(Element.ALIGN_JUSTIFIED);
            PdfPCell bodyCell = new PdfPCell();
            bodyCell.setBorder(Rectangle.NO_BORDER);
            bodyCell.addElement(bodyPara);

            dc.addCell(capCell);
            dc.addCell(bodyCell);
            doc.add(dc);
        }
        for (int i = 1; i < paragraphs.length; i++) {
            Paragraph p = new Paragraph(paragraphs[i].trim(), body);
            p.setLeading(18f);
            p.setSpacingBefore(12);
            p.setAlignment(Element.ALIGN_JUSTIFIED);
            doc.add(p);
        }

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingBefore(18);
        doc.add(spacer);
        diamondRule(writer.getDirectContent(), doc.getPageSize().getWidth() / 2f,
                writer.getVerticalPosition(true) - 4, 80f);

        Paragraph q = new Paragraph("\"A guest is the gift of God.\"", quote);
        q.setAlignment(Element.ALIGN_CENTER);
        q.setSpacingBefore(28);
        doc.add(q);
        Paragraph a = new Paragraph("— Azerbaijani proverb", attribution);
        a.setAlignment(Element.ALIGN_CENTER);
        a.setSpacingBefore(4);
        doc.add(a);
    }

    // ---------- Heritage ----------

    private void drawHeritage(Document doc) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 26, INK);
        Font noteHead = FontFactory.getFont(FontFactory.TIMES_BOLD, 12, INK);
        Font noteAz = FontFactory.getFont(FontFactory.TIMES_ITALIC, 9.5f, MUTED);
        Font noteBody = FontFactory.getFont(FontFactory.TIMES_ROMAN, 10.5f, INK_SOFT);

        doc.add(new Paragraph(spacedCaps("A taste of Azerbaijan"), eyebrow));

        Paragraph h = new Paragraph("Notes from the kitchen", head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(2);
        doc.add(h);
        doc.add(saffronRule(48f));

        String[][] notes = new String[][] {
                {"Saffron", "Zəfəran",
                        "More precious than gold by weight. A few strands bloomed in warm milk turn rice the colour of late afternoon sun."},
                {"Plov — the table's centrepiece", "Plov",
                        "Slow-coaxed, region-specific, never the same twice. The qazmaq crust at the bottom of the pot is always the most contested piece."},
                {"Dolma — leaves that hold tradition", "Dolma",
                        "Vine leaves, cabbage, peppers — sometimes quince — wrapped around lamb, rice and herbs. UNESCO recognises it as Intangible Cultural Heritage."},
                {"Çay — the rhythm of hospitality", "Çay",
                        "Loose-leaf tea poured into pear-shaped armudu glasses. Sweets, rock sugar, a sprig of cardamom — and conversation that doesn't end with the pot."},
        };

        PdfPTable grid = new PdfPTable(2);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(28);
        grid.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        try { grid.setWidths(new float[]{1, 1}); } catch (DocumentException ignored) {}

        for (String[] n : notes) {
            PdfPTable card = new PdfPTable(1);
            card.setWidthPercentage(100);

            PdfPCell titleCell = new PdfPCell(new Phrase(n[0], noteHead));
            titleCell.setBorder(Rectangle.NO_BORDER);
            titleCell.setPaddingBottom(2);
            card.addCell(titleCell);

            PdfPCell azCell = new PdfPCell(new Phrase(n[1], noteAz));
            azCell.setBorder(Rectangle.NO_BORDER);
            azCell.setPaddingBottom(6);
            card.addCell(azCell);

            PdfPCell hair = new PdfPCell();
            hair.setFixedHeight(0.6f);
            hair.setBackgroundColor(SAFFRON);
            hair.setBorder(Rectangle.NO_BORDER);
            card.addCell(hair);

            Paragraph bodyPara = new Paragraph(n[2], noteBody);
            bodyPara.setLeading(15f);
            bodyPara.setAlignment(Element.ALIGN_JUSTIFIED);
            PdfPCell bodyCell = new PdfPCell();
            bodyCell.setBorder(Rectangle.NO_BORDER);
            bodyCell.addElement(bodyPara);
            bodyCell.setPaddingTop(8);
            card.addCell(bodyCell);

            PdfPCell wrap = new PdfPCell(card);
            wrap.setBorder(Rectangle.NO_BORDER);
            wrap.setPaddingBottom(24);
            wrap.setPaddingRight(18);
            wrap.setPaddingLeft(18);
            grid.addCell(wrap);
        }
        doc.add(grid);
    }

    // ---------- Section divider (replaces chapter opener) ----------

    /**
     * Compact section header that sits inline at the top of the items list.
     * Replaces the previous full-page chapter opener — same information, far
     * less ink.
     */
    private void drawSectionDivider(Document doc, String name, int idx) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 22, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 11.5f, MUTED);
        Font blurb = FontFactory.getFont(FontFactory.TIMES_ITALIC, 10.5f, MUTED);

        Paragraph eb = new Paragraph(spacedCaps("Section " + twoDigit(idx)), eyebrow);
        eb.setAlignment(Element.ALIGN_CENTER);
        doc.add(eb);

        Paragraph h = new Paragraph(name, head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(0);
        h.setAlignment(Element.ALIGN_CENTER);
        doc.add(h);

        String az_ = azFor(name);
        if (az_ != null) {
            Paragraph p = new Paragraph(az_, az);
            p.setAlignment(Element.ALIGN_CENTER);
            p.setSpacingBefore(2);
            doc.add(p);
        }

        // Centered thin saffron rule under the title.
        Paragraph ruleWrap = new Paragraph(" ");
        ruleWrap.setSpacingBefore(8);
        doc.add(ruleWrap);
        LineSeparator sep = new LineSeparator(1.5f, 14, SAFFRON, Element.ALIGN_CENTER, 0);
        doc.add(new Chunk(sep));

        String blurbText = blurbFor(name);
        if (blurbText != null) {
            Paragraph bp = new Paragraph(blurbText, blurb);
            bp.setAlignment(Element.ALIGN_CENTER);
            bp.setLeading(15f);
            bp.setSpacingBefore(12);
            bp.setIndentationLeft(40f);
            bp.setIndentationRight(40f);
            doc.add(bp);
        }

        Paragraph after = new Paragraph(" ");
        after.setSpacingAfter(24);
        doc.add(after);
    }

    // ---------- GRID ----------

    private void drawGrid(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(0);
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
        wrap.setPaddingBottom(26);
        wrap.setPaddingRight(14);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Photo is included only if the admin has uploaded one for this item.
        // The placeholder cream tile is intentionally quiet — no ornament.
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            PdfPCell photo = new PdfPCell();
            photo.setBackgroundColor(CREAM);
            photo.setBorder(Rectangle.BOX);
            photo.setBorderColor(HAIRLINE);
            photo.setBorderWidth(0.5f);
            photo.setFixedHeight(170f);
            photo.setPadding(2);
            photo.setHorizontalAlignment(Element.ALIGN_CENTER);
            photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
            img.setAlignment(Image.ALIGN_CENTER | Image.ALIGN_MIDDLE);
            img.scaleToFit(254, 160);
            photo.setImage(img);
            card.addCell(photo);
        }

        if (item.isFeatured()) {
            PdfPCell pill = new PdfPCell(new Phrase(spacedCaps("Chef's signature"),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, SAFFRON_DEEP)));
            pill.setBorder(Rectangle.NO_BORDER);
            pill.setPaddingTop(img != null ? 14 : 0);
            pill.setPaddingBottom(0);
            card.addCell(pill);
        }

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 14, INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12.5f, SAFFRON_DEEP);
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
        nameWrap.setPaddingTop(item.isFeatured() ? 4 : (img != null ? 14 : 0));
        nameWrap.setPaddingBottom(2);
        card.addCell(nameWrap);

        PdfPCell hair = new PdfPCell();
        hair.setFixedHeight(0.6f);
        hair.setBackgroundColor(HAIRLINE);
        hair.setBorder(Rectangle.NO_BORDER);
        card.addCell(hair);

        String desc = chooseDescription(item);
        if (desc != null) {
            PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
            d.setBorder(Rectangle.NO_BORDER);
            d.setPaddingTop(8);
            d.setPaddingBottom(2);
            card.addCell(d);
        }
        String dietary = renderDietary(item);
        if (dietary != null) {
            PdfPCell c = new PdfPCell(new Phrase(dietary, tagsFont));
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingTop(4);
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
        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 14.5f, INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, SAFFRON_DEEP);
        Font pillFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, SAFFRON_DEEP);
        Font descFont = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, MUTED);
        Font tagsFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, MUTED);
        Font allergenFont = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);

            if (item.isFeatured()) {
                Paragraph pill = new Paragraph(spacedCaps("Chef's signature"), pillFont);
                pill.setSpacingBefore(i == 0 ? 0 : 4);
                doc.add(pill);
            }

            // Name + dotted leader + price row.
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
            head.setSpacingBefore(item.isFeatured() ? 2 : 6);
            doc.add(head);

            String desc = chooseDescription(item);
            if (desc != null) {
                Paragraph d = new Paragraph(desc, descFont);
                d.setLeading(14.5f);
                d.setSpacingBefore(3);
                doc.add(d);
            }

            String dietary = renderDietary(item);
            if (dietary != null) {
                Paragraph d = new Paragraph(dietary, tagsFont);
                d.setSpacingBefore(3);
                doc.add(d);
            }
            String allergen = renderAllergens(item);
            if (allergen != null) {
                Paragraph d = new Paragraph(allergen, allergenFont);
                d.setSpacingBefore(2);
                doc.add(d);
            }

            if (i < items.size() - 1) {
                Paragraph pad = new Paragraph(" ");
                pad.setSpacingBefore(8);
                doc.add(pad);
                LineSeparator sep = new LineSeparator(0.4f, 100, HAIRLINE, Element.ALIGN_LEFT, 0);
                doc.add(new Chunk(sep));
            }
        }
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
        l.setPaddingRight(28);
        r.setBorder(Rectangle.NO_BORDER);
        r.setPaddingLeft(28);
        two.addCell(l);
        two.addCell(r);
        doc.add(two);
    }

    private PdfPTable columnTable(List<MenuItem> items, boolean showPrices, Locale locale) {
        PdfPTable col = new PdfPTable(1);
        col.setWidthPercentage(100);

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 12.5f, INK);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11.5f, SAFFRON_DEEP);
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
                pad.setFixedHeight(14f);
                col.addCell(pad);
            }
        }
        return col;
    }

    // ---------- Allergens ----------

    private void drawAllergens(Document doc) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 26, INK);
        Font key = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, INK);
        Font keyDesc = FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, MUTED);

        doc.add(new Paragraph(spacedCaps("How to read this menu"), eyebrow));

        Paragraph h = new Paragraph("Allergens & advisories", head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(2);
        doc.add(h);
        doc.add(saffronRule(48f));

        String[][] dietKey = new String[][] {
                {"Chef's signature", "Hand-picked by our chef — try if you haven't before."},
                {"Vegetarian", "No meat, no fish, no shellfish."},
                {"Vegan", "No animal products of any kind."},
                {"Gluten-free", "Prepared without wheat, barley or rye ingredients."},
                {"Spicy", "Carries enough chili to register — please ask if unsure."},
        };

        PdfPTable kt = new PdfPTable(2);
        kt.setWidthPercentage(100);
        kt.setSpacingBefore(26);
        kt.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        try { kt.setWidths(new float[]{1, 3}); } catch (DocumentException ignored) {}

        for (String[] row : dietKey) {
            PdfPCell label = new PdfPCell(new Phrase(row[0], key));
            label.setBorder(Rectangle.NO_BORDER);
            label.setPaddingTop(8);
            label.setPaddingBottom(8);

            PdfPCell desc = new PdfPCell(new Phrase(row[1], keyDesc));
            desc.setBorder(Rectangle.BOTTOM);
            desc.setBorderColor(HAIRLINE);
            desc.setBorderWidthBottom(0.4f);
            desc.setPaddingTop(8);
            desc.setPaddingBottom(8);
            desc.setPaddingLeft(12);

            kt.addCell(label);
            kt.addCell(desc);
        }
        doc.add(kt);

        Paragraph allergenHead = new Paragraph("Allergens & cross-contact",
                FontFactory.getFont(FontFactory.TIMES_BOLD, 13, INK));
        allergenHead.setSpacingBefore(26);
        allergenHead.setSpacingAfter(6);
        doc.add(allergenHead);

        Paragraph allergenP = new Paragraph(
                "Items on this menu may contain or come into contact with: gluten, dairy, eggs, peanuts, "
                        + "tree nuts, sesame, soya, fish, shellfish, celery, mustard, and sulphites. Please tell a "
                        + "member of our team about any allergies before ordering so the kitchen can advise.",
                body);
        allergenP.setLeading(15.5f);
        allergenP.setAlignment(Element.ALIGN_JUSTIFIED);
        doc.add(allergenP);

        Paragraph priceP = new Paragraph(
                "Prices include VAT. A discretionary 10% service charge is added to tables of six or more. "
                        + "Seasonal garnishes may vary with availability.",
                body);
        priceP.setLeading(15.5f);
        priceP.setSpacingBefore(12);
        priceP.setAlignment(Element.ALIGN_JUSTIFIED);
        doc.add(priceP);
    }

    // ---------- Closing ----------

    private void drawClosing(Document doc, PdfWriter writer, Options opt) throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();
        float cx = page.getWidth() / 2f;

        // Same restrained frame as the cover.
        float inset = 36f;
        drawThinFrame(cb, page, inset, SAFFRON, 0.55f);
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.25f);

        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font hero = FontFactory.getFont(FontFactory.TIMES_BOLD, 48, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 16, MUTED);
        Font addr = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);
        Font foot = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);

        showCentered(cb, spacedCaps("Until we see you again"), eyebrow,
                cx, page.getHeight() * 0.72f);
        showCentered(cb, "Çox sağ olun", hero, cx, page.getHeight() * 0.62f);
        showCentered(cb, "Thank you for dining with us.", az, cx, page.getHeight() * 0.55f);

        diamondRule(cb, cx, page.getHeight() * 0.46f, 70f);

        if (opt.contactBlock() != null && !opt.contactBlock().isBlank()) {
            String[] lines = opt.contactBlock().trim().split("\\r?\\n");
            float y = page.getHeight() * 0.36f;
            for (String line : lines) {
                showCentered(cb, line, addr, cx, y);
                y -= 16f;
            }
        }

        showCentered(cb, "EDITION · " + LocalDate.now().format(MENU_DATE).toUpperCase(Locale.ROOT),
                foot, cx, inset + 38);
    }

    // ---------- Decorative primitives ----------

    private void drawThinFrame(PdfContentByte cb, Rectangle page, float inset,
                                Color color, float width) {
        cb.saveState();
        cb.setColorStroke(color);
        cb.setLineWidth(width);
        cb.rectangle(inset, inset, page.getWidth() - inset * 2f, page.getHeight() - inset * 2f);
        cb.stroke();
        cb.restoreState();
    }

    /** Two short rules with a small diamond between them — the only ornament
     *  used in the whole document. Echoes printed-book section breaks. */
    private void diamondRule(PdfContentByte cb, float cx, float cy, float armLen) {
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setColorFill(SAFFRON);
        cb.setLineWidth(0.6f);
        cb.moveTo(cx - armLen, cy);
        cb.lineTo(cx - 8, cy);
        cb.moveTo(cx + 8, cy);
        cb.lineTo(cx + armLen, cy);
        cb.stroke();
        cb.moveTo(cx, cy + 3.5f);
        cb.lineTo(cx + 3.5f, cy);
        cb.lineTo(cx, cy - 3.5f);
        cb.lineTo(cx - 3.5f, cy);
        cb.closePathFillStroke();
        cb.restoreState();
    }

    private void showCentered(PdfContentByte cb, String text, Font font, float cx, float baselineY) {
        try {
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(text, font), cx, baselineY, 0);
        } catch (Exception ignored) {}
    }

    private Chunk saffronRule(float width) {
        LineSeparator sep = new LineSeparator(1.8f, width, SAFFRON, Element.ALIGN_LEFT, 0);
        return new Chunk(sep);
    }

    // ---------- Helpers ----------

    private static String azFor(String englishName) {
        if (englishName == null) return null;
        String key = englishName.trim().toLowerCase(Locale.ROOT);
        return CATEGORY_TRANSLATIONS.get(key);
    }

    private static String blurbFor(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "starters", "appetisers", "appetizers" ->
                    "Small plates to open the meal — eaten slowly, ideally with bread.";
            case "salads" ->
                    "Fresh herbs, sumac, walnuts and pomegranate — the everyday Azerbaijani table.";
            case "soups" ->
                    "Slow-cooked broths and yogurt soups — what grandmothers called medicine.";
            case "mains", "main courses" ->
                    "Plov, kebabs, slow-braised lamb. Dishes that take their time, and reward yours.";
            case "plov", "plov & rice" ->
                    "The crown of Azerbaijani cuisine — saffron-stained rice with lamb, chestnuts and herbs.";
            case "kebabs", "kebab", "grill" ->
                    "Charcoal-grilled lamb, chicken and sturgeon — marinated overnight, served with sumac.";
            case "sides" -> "Pickles, herbs, breads — the side stage where the main dishes meet.";
            case "breads" -> "Tandir-baked, torn and shared — never sliced.";
            case "desserts", "sweets" ->
                    "Pakhlava, şəkərbura, halva — pastries that taste of holidays and patience.";
            case "drinks", "beverages" -> "Şərbət, ayran, compote, tea — pairings for every season.";
            case "tea" -> "Loose-leaf in armudu glasses — refilled until you tell us to stop.";
            default -> null;
        };
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null) return fallback;
        String t = value.trim();
        return t.isEmpty() ? fallback : t;
    }

    private static String spacedCaps(String s) {
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

    // ---------- Page chrome ----------

    /**
     * The chrome is deliberately quiet — one tiny page numeral bottom-centre,
     * nothing else. Cover and closing pages opt out via {@link #suppressNext}.
     */
    private static class MenuChrome extends PdfPageEventHelper {
        @SuppressWarnings("unused")
        private final String brand;
        private boolean suppressNext = false;

        MenuChrome(String brand) { this.brand = brand; }

        void suppressNext() { this.suppressNext = true; }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            if (suppressNext) { suppressNext = false; return; }
            int p = writer.getPageNumber();
            if (p <= 1) return;

            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = doc.getPageSize();

            try {
                Font fNum = FontFactory.getFont(FontFactory.TIMES_ROMAN, 9, MUTED);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_CENTER,
                        new Phrase(String.valueOf(p), fNum),
                        page.getWidth() / 2f, 36, 0);
            } catch (Exception ignored) {}
        }
    }
}
