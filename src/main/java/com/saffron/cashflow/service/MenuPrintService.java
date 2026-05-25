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
 * Editorial-grade restaurant menu PDF.
 *
 * <p>The output reads like a small printed book. The composition is built on a
 * 4-column type grid with generous outer margins, paired with a thin saffron
 * inner frame on every page. Each chapter (category) opens on its own page —
 * the chapter opener is a full-bleed editorial composition that pulls a hero
 * item photo (when available) into the bottom of the page, the way print
 * lookbooks do.</p>
 *
 * <p>Pages, in order:</p>
 * <ol>
 *   <li><b>Cover</b> — bordered frame, eyebrow, wordmark, italic subtitle,
 *       drawn pomegranate-flower ornament, edition footer.</li>
 *   <li><b>Half-title / dedication</b> — a short editorial blurb.</li>
 *   <li><b>Our story</b> — body copy with a drop cap and a pull quote.</li>
 *   <li><b>Notes from the kitchen</b> — four heritage cards.</li>
 *   <li><b>Contents</b> — chapter number, English + Azerbaijani, page.</li>
 *   <li><b>Chapter openers + item pages</b> — one opener per category, then
 *       grid / list / compact items, depending on layout.</li>
 *   <li><b>Symbols &amp; allergens</b> — dietary key page.</li>
 *   <li><b>Çox sağ olun</b> — closing thank-you with the optional contact
 *       block.</li>
 * </ol>
 *
 * <p>Implementation notes:</p>
 * <ul>
 *   <li>Two-pass render: the first pass discovers where each chapter actually
 *       lands so the contents page can show real page numbers in pass 2.</li>
 *   <li>Page chrome is drawn in {@link MenuChrome} so the inner saffron frame,
 *       running head and page numerals stay consistent across all body
 *       pages. The cover and closing page skip the chrome on purpose.</li>
 *   <li>Decorative elements are drawn with PDF primitives (lines, beziers,
 *       circles) so we don't depend on bundled glyph fonts — fewer surprises
 *       across OS font installs.</li>
 * </ul>
 */
@Service
public class MenuPrintService {

    // ---- Brand palette (warmer, slightly desaturated for editorial feel) ----
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

    /** English → Azerbaijani translations used on contents + chapter openers. */
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

    private static final String DEFAULT_HALF_TITLE =
            "A small book of dishes from Azerbaijan — written for guests, cooks, and "
                    + "anyone curious about a cuisine the world has not finished discovering.";

    private final MenuService menuService;
    private final FileStorageService fileStorage;

    public MenuPrintService(MenuService menuService, FileStorageService fileStorage) {
        this.menuService = menuService;
        this.fileStorage = fileStorage;
    }

    // ---------- Public API ----------

    public byte[] buildMenu(
            String layoutKey,
            String customTitle,
            String customSubtitle,
            boolean showPrices,
            String language) {
        return buildMenu(layoutKey, customTitle, customSubtitle, showPrices, language, null, null, null);
    }

    public byte[] buildMenu(
            String layoutKey,
            String customTitle,
            String customSubtitle,
            boolean showPrices,
            String language,
            String storyTitle,
            String storyBody,
            String contactBlock) {
        return build(new Options(
                Layout.from(layoutKey),
                customTitle,
                customSubtitle,
                showPrices,
                "pl".equalsIgnoreCase(language) ? Locale.forLanguageTag("pl-PL") : Locale.ENGLISH,
                storyTitle,
                storyBody,
                contactBlock));
    }

    public record Options(
            Layout layout,
            String customTitle,
            String customSubtitle,
            boolean showPrices,
            Locale locale,
            String storyTitle,
            String storyBody,
            String contactBlock) {}

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

        // Two-pass render so the contents page lists real page numbers.
        RenderResult first = render(opt, title, subtitle, categories, itemsByCategory, null);
        return render(opt, title, subtitle, categories, itemsByCategory, first.pageStarts()).bytes();
    }

    // ---------- Render ----------

    private RenderResult render(
            Options opt,
            String title,
            String subtitle,
            List<MenuCategory> categories,
            Map<String, List<MenuItem>> itemsByCategory,
            Map<String, Integer> contentsPageStarts) {

        // Generous outer margins so the page breathes — luxury menus carry a
        // lot of negative space. Top margin leaves room for the running head.
        Document doc = new Document(PageSize.A4, 64, 64, 96, 80);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, Integer> pageStarts = new HashMap<>();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            MenuChrome chrome = new MenuChrome(title);
            writer.setPageEvent(chrome);
            doc.open();

            // 1 — Cover (no chrome)
            chrome.suppressNext();
            drawCover(doc, writer, title, subtitle);

            // 2 — Half-title / dedication
            doc.newPage();
            chrome.setRunningHead("");
            drawHalfTitle(doc, writer, title, subtitle);

            // 3 — Our story
            doc.newPage();
            chrome.setRunningHead("Our story");
            drawStory(doc, writer, opt);

            // 4 — Notes from the kitchen (heritage)
            doc.newPage();
            chrome.setRunningHead("Notes from the kitchen");
            drawHeritage(doc, writer);

            // 5 — Contents
            doc.newPage();
            chrome.setRunningHead("Contents");
            drawContents(doc, categories, contentsPageStarts);

            // 6+ — Chapter openers and items
            int catIdx = 1;
            for (MenuCategory cat : categories) {
                List<MenuItem> items = itemsByCategory.get(cat.getId());
                if (items == null || items.isEmpty()) continue;

                doc.newPage();
                pageStarts.put(cat.getId(), writer.getPageNumber());
                chrome.setRunningHead(cat.getName());
                drawChapterOpener(doc, writer, cat.getName(), catIdx, items);

                doc.newPage();
                switch (opt.layout()) {
                    case GRID -> drawGrid(doc, items, opt.showPrices(), opt.locale());
                    case LIST -> drawList(doc, items, opt.showPrices(), opt.locale());
                    case COMPACT -> drawCompact(doc, items, opt.showPrices(), opt.locale());
                }
                catIdx++;
            }

            // Last but one — Symbols & allergens
            doc.newPage();
            chrome.setRunningHead("Symbols & allergens");
            drawGlossary(doc, writer);

            // Last — Thank-you (no chrome)
            doc.newPage();
            chrome.suppressNext();
            drawClosing(doc, writer, opt);

            doc.close();
            return new RenderResult(out.toByteArray(), pageStarts);
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to build menu PDF: " + e.getMessage(), e);
        }
    }

    private record RenderResult(byte[] bytes, Map<String, Integer> pageStarts) {}

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
        float inset = 36f;
        // Outer thin saffron frame (luxury hotel menus do this)
        drawThinFrame(cb, page, inset, SAFFRON, 0.6f);
        // Second, even-thinner frame nudged inward gives a bookplate feel.
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.25f);

        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font year = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
        Font brand = FontFactory.getFont(FontFactory.TIMES_BOLD, 96, INK);
        Font sub = FontFactory.getFont(FontFactory.TIMES_ITALIC, 18, MUTED);
        Font cite = FontFactory.getFont(FontFactory.TIMES_ITALIC, 12, SAFFRON_DEEP);
        Font foot = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);

        // Top eyebrow — printed centered just below the inner frame.
        showCentered(cb, spacedCaps("La carte · A book of dishes"), eyebrow,
                page.getWidth() / 2f, page.getHeight() - inset - 32);

        // Brand wordmark in the upper third.
        showCentered(cb, title, brand, page.getWidth() / 2f, page.getHeight() * 0.62f);

        // Hairline rule under the wordmark.
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(1.2f);
        float ruleY = page.getHeight() * 0.62f - 32f;
        cb.moveTo(page.getWidth() / 2f - 60f, ruleY);
        cb.lineTo(page.getWidth() / 2f + 60f, ruleY);
        cb.stroke();
        cb.restoreState();

        // Subtitle in italic
        showCentered(cb, subtitle, sub, page.getWidth() / 2f, ruleY - 24);

        // Drawn pomegranate-flower ornament between subtitle and quote.
        drawPomegranateOrnament(cb, page.getWidth() / 2f, page.getHeight() * 0.38f);

        // Italic motif under the ornament.
        showCentered(cb, "Şirniyyat · Plov · Kabab · Çay", cite,
                page.getWidth() / 2f, page.getHeight() * 0.30f);

        // Bottom block — edition and address line
        showCentered(cb, "EDITION · " + LocalDate.now().format(MENU_DATE).toUpperCase(Locale.ROOT),
                year, page.getWidth() / 2f, inset + 56);
        showCentered(cb, "S A F F R O N · W A R S Z A W A · P O L A N D",
                foot, page.getWidth() / 2f, inset + 38);
    }

    /** Hand-drawn pomegranate-flower ornament — a stylised six-petal motif we
     *  use as the cover and closing ornament. Drawn with PDF primitives so we
     *  don't depend on font glyphs. */
    private void drawPomegranateOrnament(PdfContentByte cb, float cx, float cy) {
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setColorFill(SAFFRON);
        cb.setLineWidth(0.8f);
        float r = 9f;
        // Six petals around the centre
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3.0;
            float x = cx + (float) Math.cos(a) * r * 1.6f;
            float y = cy + (float) Math.sin(a) * r * 1.6f;
            cb.circle(x, y, r * 0.55f);
            cb.stroke();
        }
        // Centre dot
        cb.setColorFill(SAFFRON_DEEP);
        cb.circle(cx, cy, 2.4f);
        cb.fill();
        // Stem
        cb.setLineWidth(0.6f);
        cb.moveTo(cx, cy - r * 1.4f);
        cb.curveTo(cx + 4, cy - r * 2.6f, cx - 4, cy - r * 3.6f, cx, cy - r * 4.4f);
        cb.stroke();
        // Two thin saffron flanking rules so the motif lives on a horizontal beat
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(0.6f);
        cb.moveTo(cx - 100, cy);
        cb.lineTo(cx - 22, cy);
        cb.moveTo(cx + 22, cy);
        cb.lineTo(cx + 100, cy);
        cb.stroke();
        cb.restoreState();
    }

    // ---------- Half-title / dedication ----------

    private void drawHalfTitle(Document doc, PdfWriter writer, String title, String subtitle)
            throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();

        Font wordmark = FontFactory.getFont(FontFactory.TIMES_BOLD, 24, INK);
        Font tag = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
        Font body = FontFactory.getFont(FontFactory.TIMES_ITALIC, 13.5f, INK_SOFT);

        float cx = page.getWidth() / 2f;
        showCentered(cb, title.toUpperCase(Locale.ROOT), wordmark, cx, page.getHeight() * 0.74f);
        showCentered(cb, subtitle, tag, cx, page.getHeight() * 0.74f - 22);

        // Editorial blurb (italic, centred, tight column)
        Paragraph blurb = new Paragraph(DEFAULT_HALF_TITLE, body);
        blurb.setAlignment(Element.ALIGN_CENTER);
        blurb.setLeading(20f);
        blurb.setIndentationLeft(60f);
        blurb.setIndentationRight(60f);
        ColumnText ct = new ColumnText(cb);
        ct.setSimpleColumn(doc.left() + 60, page.getHeight() * 0.42f,
                doc.right() - 60, page.getHeight() * 0.58f);
        ct.addElement(blurb);
        try { ct.go(); } catch (DocumentException ignored) {}

        // Decorative drawn divider
        drawDiamondDivider(cb, cx, page.getHeight() * 0.34f);
    }

    // ---------- Story page ----------

    private void drawStory(Document doc, PdfWriter writer, Options opt) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 42, INK);
        Font dropCap = FontFactory.getFont(FontFactory.TIMES_BOLD, 64, SAFFRON_DEEP);
        Font body = FontFactory.getFont(FontFactory.TIMES_ROMAN, 12.5f, INK_SOFT);
        Font quote = FontFactory.getFont(FontFactory.TIMES_ITALIC, 17, SAFFRON_DEEP);
        Font attribution = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);

        Paragraph eb = new Paragraph(spacedCaps("Welcome"), eyebrow);
        eb.setSpacingBefore(0);
        doc.add(eb);

        Paragraph h = new Paragraph(blankToDefault(opt.storyTitle(), "Our story"), head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(4);
        doc.add(h);

        doc.add(saffronRule(60f));

        String[] paragraphs = blankToDefault(opt.storyBody(), DEFAULT_STORY_BODY).split("\\n\\s*\\n");
        // First paragraph: drop cap. We render the initial letter into a small
        // table cell on the left so the body wraps next to it.
        if (paragraphs.length > 0) {
            String first = paragraphs[0].trim();
            if (!first.isEmpty()) {
                String initial = first.substring(0, 1);
                String rest = first.substring(1);

                PdfPTable dc = new PdfPTable(2);
                dc.setWidthPercentage(100);
                dc.setSpacingBefore(22);
                try { dc.setWidths(new float[]{0.9f, 9f}); } catch (DocumentException ignored) {}

                PdfPCell capCell = new PdfPCell(new Phrase(initial, dropCap));
                capCell.setBorder(Rectangle.NO_BORDER);
                capCell.setPaddingTop(-6);
                capCell.setPaddingRight(8);
                capCell.setVerticalAlignment(Element.ALIGN_TOP);

                Paragraph bodyPara = new Paragraph(rest, body);
                bodyPara.setLeading(19f);
                bodyPara.setAlignment(Element.ALIGN_JUSTIFIED);
                PdfPCell bodyCell = new PdfPCell();
                bodyCell.setBorder(Rectangle.NO_BORDER);
                bodyCell.addElement(bodyPara);
                bodyCell.setPaddingTop(0);

                dc.addCell(capCell);
                dc.addCell(bodyCell);
                doc.add(dc);
            }
        }
        for (int i = 1; i < paragraphs.length; i++) {
            Paragraph p = new Paragraph(paragraphs[i].trim(), body);
            p.setLeading(19f);
            p.setSpacingBefore(12);
            p.setAlignment(Element.ALIGN_JUSTIFIED);
            doc.add(p);
        }

        // Decorative break with diamond
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingBefore(20);
        doc.add(spacer);
        drawDiamondDivider(writer.getDirectContent(), doc.getPageSize().getWidth() / 2f,
                writer.getVerticalPosition(true) - 4);

        Paragraph q = new Paragraph("\"A guest is the gift of God.\"", quote);
        q.setAlignment(Element.ALIGN_CENTER);
        q.setSpacingBefore(28);
        doc.add(q);
        Paragraph a = new Paragraph("— Azerbaijani proverb", attribution);
        a.setAlignment(Element.ALIGN_CENTER);
        a.setSpacingBefore(4);
        doc.add(a);
    }

    // ---------- Heritage page ----------

    private void drawHeritage(Document doc, PdfWriter writer) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 34, INK);
        Font noteHead = FontFactory.getFont(FontFactory.TIMES_BOLD, 13.5f, INK);
        Font noteAz = FontFactory.getFont(FontFactory.TIMES_ITALIC, 10, MUTED);
        Font noteBody = FontFactory.getFont(FontFactory.TIMES_ROMAN, 11, INK_SOFT);

        doc.add(new Paragraph(spacedCaps("A taste of Azerbaijan"), eyebrow));

        Paragraph h = new Paragraph("Notes from the kitchen", head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(4);
        doc.add(h);
        doc.add(saffronRule(60f));

        String[][] notes = new String[][] {
                {"Saffron", "Zəfəran",
                        "More precious than gold by weight, saffron threads are pulled from crocus flowers by "
                                + "hand. We use it sparingly in plov and şirniyyat — a few strands bloomed in warm "
                                + "milk turns rice the colour of late afternoon sun."},
                {"Plov — the table's centrepiece", "Plov",
                        "A celebration dish that varies by region: lamb-stuffed in Bakı, chestnut-and-dried-fruit "
                                + "in Şəki, fish in Lənkəran. The crust at the bottom of the pot — qazmaq — is "
                                + "always the most contested piece."},
                {"Dolma — leaves that hold tradition", "Dolma",
                        "Vine leaves, cabbage, peppers, or sometimes quince — wrapped around lamb, rice and "
                                + "herbs. UNESCO recognises Azerbaijani dolma as Intangible Cultural Heritage."},
                {"Çay — the rhythm of hospitality", "Çay",
                        "Tea is poured into pear-shaped armudu glasses and almost never refused. Sweets, rock "
                                + "sugar, a sprig of cardamom — and conversation that doesn't end with the pot."},
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
            azCell.setPaddingBottom(8);
            card.addCell(azCell);

            PdfPCell hair = new PdfPCell();
            hair.setFixedHeight(0.8f);
            hair.setBackgroundColor(SAFFRON);
            hair.setBorder(Rectangle.NO_BORDER);
            card.addCell(hair);

            Paragraph bodyPara = new Paragraph(n[2], noteBody);
            bodyPara.setLeading(15.5f);
            bodyPara.setAlignment(Element.ALIGN_JUSTIFIED);
            PdfPCell bodyCell = new PdfPCell();
            bodyCell.setBorder(Rectangle.NO_BORDER);
            bodyCell.addElement(bodyPara);
            bodyCell.setPaddingTop(10);
            bodyCell.setPaddingBottom(2);
            card.addCell(bodyCell);

            PdfPCell wrap = new PdfPCell(card);
            wrap.setBorder(Rectangle.NO_BORDER);
            wrap.setPaddingBottom(28);
            wrap.setPaddingRight(20);
            wrap.setPaddingLeft(20);
            grid.addCell(wrap);
        }
        doc.add(grid);
    }

    // ---------- Contents ----------

    private void drawContents(Document doc, List<MenuCategory> cats, Map<String, Integer> starts)
            throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 36, INK);
        Font row = FontFactory.getFont(FontFactory.TIMES_ROMAN, 14, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 11, MUTED);
        Font pageNum = FontFactory.getFont(FontFactory.HELVETICA, 11, MUTED);
        Font index = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);

        doc.add(new Paragraph(spacedCaps("Contents"), eyebrow));

        Paragraph h = new Paragraph("Table of contents", head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(4);
        doc.add(h);
        doc.add(saffronRule(60f));

        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        try { t.setWidths(new float[]{0.7f, 8, 1}); } catch (DocumentException ignored) {}
        t.setSpacingBefore(28);

        int i = 1;
        for (MenuCategory c : cats) {
            // On pass 1 we don't have page numbers yet — show a dash so the
            // page layout still matches the final pass exactly.
            Integer pg = starts == null ? null : starts.get(c.getId());
            String pgText = pg != null ? String.valueOf(pg) : "—";

            PdfPCell numCell = new PdfPCell(new Phrase(threeDigit(i), index));
            numCell.setBorder(Rectangle.NO_BORDER);
            numCell.setPaddingTop(16);
            numCell.setPaddingBottom(16);

            String az_ = azFor(c.getName());
            Phrase np = new Phrase();
            np.add(new Phrase(c.getName(), row));
            if (az_ != null) np.add(new Phrase("   " + az_, az));
            PdfPCell nameCell = new PdfPCell(np);
            nameCell.setBorder(Rectangle.BOTTOM);
            nameCell.setBorderColor(HAIRLINE);
            nameCell.setBorderWidthBottom(0.5f);
            nameCell.setPaddingTop(16);
            nameCell.setPaddingBottom(16);

            PdfPCell pgCell = new PdfPCell(new Phrase(pgText, pageNum));
            pgCell.setBorder(Rectangle.BOTTOM);
            pgCell.setBorderColor(HAIRLINE);
            pgCell.setBorderWidthBottom(0.5f);
            pgCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            pgCell.setPaddingTop(16);
            pgCell.setPaddingBottom(16);

            t.addCell(numCell);
            t.addCell(nameCell);
            t.addCell(pgCell);
            i++;
        }
        doc.add(t);
    }

    // ---------- Chapter opener (full page composition) ----------

    private void drawChapterOpener(Document doc, PdfWriter writer, String name, int idx,
                                   List<MenuItem> items) throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();

        // Massive faded numeral on the left margin — typographic anchor.
        Font numeral = FontFactory.getFont(FontFactory.TIMES_BOLD, 220, CREAM_DEEP);
        try {
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase(threeDigit(idx), numeral),
                    doc.left() - 6, page.getHeight() * 0.5f, 0);
        } catch (Exception ignored) {}

        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 46, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 17, MUTED);
        Font blurb = FontFactory.getFont(FontFactory.TIMES_ROMAN, 12.5f, INK_SOFT);
        Font countLabel = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);

        // Right column starts ~38% from left so it sits visually next to the numeral.
        float colLeft = doc.left() + (doc.right() - doc.left()) * 0.30f;
        float colRight = doc.right();
        float ribbonTop = page.getHeight() * 0.74f;

        // Eyebrow row with right-aligned chapter index
        try {
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase(spacedCaps("Chapter " + threeDigit(idx)), eyebrow),
                    colLeft, ribbonTop, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase(items.size() + " dishes", countLabel),
                    colRight, ribbonTop, 0);
        } catch (Exception ignored) {}

        // Saffron rule under eyebrow
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(1.2f);
        cb.moveTo(colLeft, ribbonTop - 8);
        cb.lineTo(colLeft + 38, ribbonTop - 8);
        cb.stroke();
        cb.restoreState();

        // Title block via ColumnText so it wraps cleanly on long names
        ColumnText tBlock = new ColumnText(cb);
        tBlock.setSimpleColumn(colLeft, page.getHeight() * 0.40f,
                colRight, ribbonTop - 18);
        Paragraph headP = new Paragraph(name, head);
        headP.setLeading(50f);
        tBlock.addElement(headP);
        String az_ = azFor(name);
        if (az_ != null) {
            Paragraph azP = new Paragraph(az_, az);
            azP.setLeading(22f);
            azP.setSpacingBefore(2);
            tBlock.addElement(azP);
        }
        // Short saffron rule
        Paragraph rule = new Paragraph(" ");
        rule.setSpacingBefore(6);
        tBlock.addElement(rule);
        try { tBlock.go(); } catch (Exception ignored) {}

        // Saffron underline rule
        float ruleY = page.getHeight() * 0.40f - 4f;
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(2.5f);
        cb.moveTo(colLeft, ruleY);
        cb.lineTo(colLeft + 80, ruleY);
        cb.stroke();
        cb.restoreState();

        String blurbText = blurbFor(name);
        if (blurbText != null) {
            ColumnText bBlock = new ColumnText(cb);
            bBlock.setSimpleColumn(colLeft, page.getHeight() * 0.18f,
                    colRight, ruleY - 12);
            Paragraph blurbP = new Paragraph(blurbText, blurb);
            blurbP.setLeading(19f);
            blurbP.setAlignment(Element.ALIGN_LEFT);
            bBlock.addElement(blurbP);
            try { bBlock.go(); } catch (Exception ignored) {}
        }

        // Hero image: pick the featured item with a photo, otherwise the first item with a photo.
        MenuItem hero = null;
        for (MenuItem it : items) {
            if (it.isFeatured() && it.getImagePath() != null) { hero = it; break; }
        }
        if (hero == null) {
            for (MenuItem it : items) {
                if (it.getImagePath() != null) { hero = it; break; }
            }
        }
        if (hero != null) {
            Image img = tryLoadImage(hero.getImagePath());
            if (img != null) {
                float maxW = (doc.right() - doc.left()) * 0.45f;
                float maxH = page.getHeight() * 0.30f;
                img.scaleToFit(maxW, maxH);
                img.setAbsolutePosition(
                        doc.right() - img.getScaledWidth(),
                        doc.bottom());
                try { doc.add(img); } catch (DocumentException ignored) {}

                // Caption "Featured · <name>" under the image
                Font cap = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, SAFFRON_DEEP);
                Font capName = FontFactory.getFont(FontFactory.TIMES_ITALIC, 10, INK);
                try {
                    float captionY = doc.bottom() - 4;
                    ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                            new Phrase(spacedCaps("Featured"), cap),
                            doc.right(), captionY, 0);
                    ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                            new Phrase(hero.getName(), capName),
                            doc.right(), captionY - 12, 0);
                } catch (Exception ignored) {}
            }
        }

        // "Continued →" hint top-right
        Font hint = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
        try {
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("DISHES OVERLEAF →", hint),
                    doc.right(), page.getHeight() * 0.86f, 0);
        } catch (Exception ignored) {}
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
        wrap.setPaddingBottom(28);
        wrap.setPaddingRight(14);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Photo card with a tall ratio for a more refined look.
        PdfPCell photo = new PdfPCell();
        photo.setBackgroundColor(CREAM);
        photo.setBorder(Rectangle.BOX);
        photo.setBorderColor(HAIRLINE);
        photo.setBorderWidth(0.6f);
        photo.setFixedHeight(180f);
        photo.setPadding(2);
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.setAlignment(Image.ALIGN_CENTER | Image.ALIGN_MIDDLE);
            img.scaleToFit(254, 168);
            photo.setImage(img);
        } else {
            // Soft placeholder — a single saffron mark on cream.
            photo.setPhrase(new Phrase("◆",
                    FontFactory.getFont(FontFactory.HELVETICA, 16, CREAM_DEEP)));
        }
        card.addCell(photo);

        if (item.isFeatured()) {
            PdfPCell pill = new PdfPCell(new Phrase(spacedCaps("Chef's signature"),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, SAFFRON_DEEP)));
            pill.setBorder(Rectangle.NO_BORDER);
            pill.setPaddingTop(14);
            pill.setPaddingBottom(0);
            card.addCell(pill);
        }

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 15.5f, INK);
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
        nameWrap.setPaddingTop(item.isFeatured() ? 4 : 14);
        nameWrap.setPaddingBottom(2);
        card.addCell(nameWrap);

        PdfPCell hair = new PdfPCell();
        hair.setFixedHeight(0.8f);
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
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            // Alternate photo position so the eye zigzags down the page —
            // a small move that lifts the design out of "spreadsheet" territory.
            doc.add(listRow(item, showPrices, locale, i % 2 == 0));
            if (i < items.size() - 1) {
                Paragraph pad = new Paragraph(" ");
                pad.setSpacingBefore(10);
                pad.setSpacingAfter(10);
                doc.add(pad);
                LineSeparator sep = new LineSeparator(0.4f, 100, HAIRLINE, Element.ALIGN_LEFT, 0);
                doc.add(new Chunk(sep));
            }
        }
    }

    private PdfPTable listRow(MenuItem item, boolean showPrices, Locale locale, boolean photoLeft) {
        PdfPTable row = new PdfPTable(2);
        row.setWidthPercentage(100);
        float[] widths = photoLeft ? new float[]{1.3f, 4f} : new float[]{4f, 1.3f};
        try { row.setWidths(widths); } catch (DocumentException ignored) {}

        PdfPCell photo = new PdfPCell();
        photo.setBorder(Rectangle.BOX);
        photo.setBorderColor(HAIRLINE);
        photo.setBorderWidth(0.6f);
        photo.setFixedHeight(116f);
        photo.setPadding(2);
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.scaleToFit(150, 108);
            photo.setImage(img);
        } else {
            photo.setBackgroundColor(CREAM);
            photo.setPhrase(new Phrase("◆",
                    FontFactory.getFont(FontFactory.HELVETICA, 14, CREAM_DEEP)));
        }
        if (photoLeft) photo.setPaddingRight(20); else photo.setPaddingLeft(20);

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 17, INK);
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

        if (photoLeft) {
            row.addCell(photo);
            row.addCell(textCol);
        } else {
            row.addCell(textCol);
            row.addCell(photo);
        }
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
                pad.setFixedHeight(14f);
                col.addCell(pad);
            }
        }
        return col;
    }

    // ---------- Glossary / dietary key page ----------

    private void drawGlossary(Document doc, PdfWriter writer) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 32, INK);
        Font key = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, INK);
        Font keyDesc = FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, MUTED);

        doc.add(new Paragraph(spacedCaps("How to read this menu"), eyebrow));

        Paragraph h = new Paragraph("Symbols & allergens", head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(4);
        doc.add(h);
        doc.add(saffronRule(60f));

        // Dietary key — 2 column key table
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
            desc.setBorderWidthBottom(0.5f);
            desc.setPaddingTop(8);
            desc.setPaddingBottom(8);
            desc.setPaddingLeft(12);

            kt.addCell(label);
            kt.addCell(desc);
        }
        doc.add(kt);

        // Allergen advisory
        Paragraph allergenHead = new Paragraph("Allergens & cross-contact", FontFactory.getFont(
                FontFactory.TIMES_BOLD, 14, INK));
        allergenHead.setSpacingBefore(28);
        allergenHead.setSpacingAfter(8);
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
                        + "Photographs are for presentation purposes and seasonal garnishes may vary.",
                body);
        priceP.setLeading(15.5f);
        priceP.setSpacingBefore(14);
        priceP.setAlignment(Element.ALIGN_JUSTIFIED);
        doc.add(priceP);
    }

    // ---------- Closing page ----------

    private void drawClosing(Document doc, PdfWriter writer, Options opt) throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();

        // Same bordered frame as the cover for a deliberate book-end feel.
        float inset = 36f;
        drawThinFrame(cb, page, inset, SAFFRON, 0.6f);
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.25f);

        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font hero = FontFactory.getFont(FontFactory.TIMES_BOLD, 56, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 20, MUTED);
        Font addr = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
        Font foot = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);

        float cx = page.getWidth() / 2f;

        showCentered(cb, spacedCaps("Until we see you again"), eyebrow, cx, page.getHeight() * 0.78f);
        showCentered(cb, "Çox sağ olun", hero, cx, page.getHeight() * 0.66f);
        showCentered(cb, "Thank you for dining with us.", az, cx, page.getHeight() * 0.58f);

        drawPomegranateOrnament(cb, cx, page.getHeight() * 0.48f);

        if (opt.contactBlock() != null && !opt.contactBlock().isBlank()) {
            String[] lines = opt.contactBlock().trim().split("\\r?\\n");
            float y = page.getHeight() * 0.30f;
            for (String line : lines) {
                showCentered(cb, line, addr, cx, y);
                y -= 16f;
            }
        }

        showCentered(cb, "EDITION · " + LocalDate.now().format(MENU_DATE).toUpperCase(Locale.ROOT),
                foot, cx, inset + 38);
    }

    // ---------- Decorative primitives ----------

    private void drawThinFrame(PdfContentByte cb, Rectangle page, float inset, Color color, float width) {
        cb.saveState();
        cb.setColorStroke(color);
        cb.setLineWidth(width);
        cb.rectangle(inset, inset, page.getWidth() - inset * 2f, page.getHeight() - inset * 2f);
        cb.stroke();
        cb.restoreState();
    }

    private void drawDiamondDivider(PdfContentByte cb, float cx, float cy) {
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setColorFill(SAFFRON);
        cb.setLineWidth(0.6f);
        // Two thin rules with a diamond in the middle.
        cb.moveTo(cx - 90, cy);
        cb.lineTo(cx - 12, cy);
        cb.moveTo(cx + 12, cy);
        cb.lineTo(cx + 90, cy);
        cb.stroke();
        cb.moveTo(cx, cy + 4.5f);
        cb.lineTo(cx + 4.5f, cy);
        cb.lineTo(cx, cy - 4.5f);
        cb.lineTo(cx - 4.5f, cy);
        cb.closePathFillStroke();
        cb.restoreState();
    }

    private void showCentered(PdfContentByte cb, String text, Font font, float cx, float baselineY) {
        try {
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(text, font), cx, baselineY, 0);
        } catch (Exception ignored) {}
    }

    private Chunk saffronRule(float width) {
        LineSeparator sep = new LineSeparator(2.2f, width, SAFFRON, Element.ALIGN_LEFT, 0);
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
                    "Small plates to open the meal — eaten slowly, ideally with bread and a slow conversation.";
            case "salads" ->
                    "Fresh herbs, sumac, walnuts and pomegranate — the everyday taste of an Azerbaijani table.";
            case "soups" ->
                    "Slow-cooked broths and yogurt soups — the kind grandmothers used to call medicine.";
            case "mains", "main courses" ->
                    "Plov, kebabs, slow-braised lamb. The dishes that take their time, and reward yours.";
            case "plov", "plov & rice" ->
                    "The crown of Azerbaijani cuisine — saffron-stained rice with lamb, chestnuts, and herbs.";
            case "kebabs", "kebab", "grill" ->
                    "Charcoal-grilled lamb, chicken and sturgeon — marinated overnight, served with sumac.";
            case "sides" -> "Pickles, herbs, breads — the side stage where the main dishes meet.";
            case "breads" -> "Tandir-baked, torn and shared — never sliced.";
            case "desserts", "sweets" ->
                    "Pakhlava, şəkərbura, halva — pastries that taste of holidays and patience.";
            case "drinks", "beverages" -> "Şərbət, ayran, compote, tea — pairings for every season.";
            case "tea" -> "Loose-leaf black tea in armudu glasses — refilled until you tell us to stop.";
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

    private static String threeDigit(int i) {
        if (i < 10) return "00" + i;
        if (i < 100) return "0" + i;
        return String.valueOf(i);
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
     * Per-page header and footer. We thread a small bit of state in here:
     *   - {@link #suppressNext} skips chrome on the next page (cover / closing).
     *   - {@link #runningHead} is the section name shown in the running head.
     *
     * Both are mutated from the body of {@link #render(Options, String, String,
     * java.util.List, java.util.Map, java.util.Map)} just before each page so
     * the chrome reflects what the reader is actually looking at.
     */
    private static class MenuChrome extends PdfPageEventHelper {
        private final String brand;
        private String runningHead = "";
        private boolean suppressNext = false;

        MenuChrome(String brand) { this.brand = brand; }

        void setRunningHead(String head) { this.runningHead = head == null ? "" : head; }
        void suppressNext() { this.suppressNext = true; }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            if (suppressNext) { suppressNext = false; return; }
            int p = writer.getPageNumber();
            if (p <= 1) return;

            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = doc.getPageSize();

            // Thin inner frame — keeps every body page visually consistent.
            float inset = 30f;
            cb.saveState();
            cb.setColorStroke(SAFFRON);
            cb.setLineWidth(0.25f);
            cb.rectangle(inset, inset, page.getWidth() - inset * 2f, page.getHeight() - inset * 2f);
            cb.stroke();
            cb.restoreState();

            // Top running head — brand on the left, section name on the right.
            try {
                Font brandFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 9, INK);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_LEFT,
                        new Phrase(brand.toUpperCase(Locale.ROOT), brandFont),
                        doc.leftMargin(), page.getHeight() - inset - 16, 0);
                if (!runningHead.isBlank()) {
                    Font headFont = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);
                    StringBuilder spaced = new StringBuilder();
                    String h = runningHead.toUpperCase(Locale.ROOT);
                    for (int i = 0; i < h.length(); i++) {
                        spaced.append(h.charAt(i));
                        if (i < h.length() - 1) spaced.append(' ');
                    }
                    ColumnText.showTextAligned(
                            cb, Element.ALIGN_RIGHT,
                            new Phrase(spaced.toString(), headFont),
                            doc.right(), page.getHeight() - inset - 16, 0);
                }
            } catch (Exception ignored) {}

            // Small saffron tick under the running head — anchors the eye.
            cb.saveState();
            cb.setColorStroke(SAFFRON);
            cb.setLineWidth(0.6f);
            cb.moveTo(doc.leftMargin(), page.getHeight() - inset - 22);
            cb.lineTo(doc.leftMargin() + 14, page.getHeight() - inset - 22);
            cb.stroke();
            cb.restoreState();

            // Footer page numeral inside a thin circle, centred.
            try {
                Font fNum = FontFactory.getFont(FontFactory.TIMES_BOLD, 9, INK);
                float cx = page.getWidth() / 2f;
                float cy = inset + 22;
                cb.saveState();
                cb.setColorStroke(SAFFRON);
                cb.setLineWidth(0.6f);
                cb.circle(cx, cy, 10f);
                cb.stroke();
                cb.restoreState();
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_CENTER,
                        new Phrase(String.valueOf(p), fNum),
                        cx, cy - 3, 0);
            } catch (Exception ignored) {}
        }
    }
}
