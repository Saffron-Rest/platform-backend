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
 * Editorial, restaurant-grade PDF menu builder.
 *
 * <p>The book reads like a small catalogue:</p>
 * <ol>
 *   <li><b>Cover</b> — wordmark, eyebrow, ornament.</li>
 *   <li><b>Welcome / Our story</b> — house narrative (caller-supplied or a
 *       default Azerbaijani-heritage paragraph) with a pull-quote.</li>
 *   <li><b>Heritage</b> — short notes on saffron, plov, dolma, tea — so guests
 *       browse while waiting and feel the culture.</li>
 *   <li><b>Contents</b> — numbered chapters with dotted leaders and real
 *       page numbers (resolved in a two-pass render).</li>
 *   <li><b>Chapter openers</b> — oversize numeral, English title, Azerbaijani
 *       translation in italic small caps, saffron rule, blurb.</li>
 *   <li><b>Item cards / rows</b> — refined photo treatment, "Chef's
 *       signature" pill, name + Azerbaijani romanisation, price, description,
 *       dietary + allergen notes.</li>
 *   <li><b>Allergens &amp; thanks</b> — closing page.</li>
 * </ol>
 *
 * <p>Design palette: deep ink, saffron accents, cream paper. Typography pairs
 * Times (serif headings + body in narrative pages) with Helvetica (eyebrows,
 * prices, micro-copy). Three layout choices for the item pages:</p>
 * <ul>
 *   <li><b>GRID</b> — photo cards in a 2-column catalogue grid (default).</li>
 *   <li><b>LIST</b> — single column with thumbnails and long descriptions.</li>
 *   <li><b>COMPACT</b> — text-only, two columns — great for table tents.</li>
 * </ul>
 */
@Service
public class MenuPrintService {

    // ---- Brand palette ----
    private static final Color INK = new Color(0x1A, 0x18, 0x14);
    private static final Color SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color SAFFRON_DEEP = new Color(0xA4, 0x52, 0x12);
    private static final Color CREAM = new Color(0xFA, 0xF4, 0xE8);
    private static final Color CREAM_DEEP = new Color(0xF3, 0xEA, 0xD6);
    private static final Color MUTED = new Color(0x6B, 0x63, 0x57);
    private static final Color HAIRLINE = new Color(0xE2, 0xDD, 0xD2);

    private static final DateTimeFormatter MENU_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /**
     * English → Azerbaijani translation hints used on the category opener and
     * (lightly) for individual item names. Conservative — we only translate
     * categories whose match is uncontroversial. Custom categories pass
     * through unchanged.
     */
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

    /** Default heritage paragraphs surfaced on the story page when the caller
     *  doesn't supply their own. Written in editorial English. */
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

    private static final String DEFAULT_HERITAGE_QUOTE =
            "\"A guest is the gift of God.\"  —  Azerbaijani proverb";

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
        return buildMenu(new Options(
                Layout.from(layoutKey),
                customTitle,
                customSubtitle,
                showPrices,
                "pl".equalsIgnoreCase(language) ? Locale.forLanguageTag("pl-PL") : Locale.ENGLISH,
                null,
                null,
                null));
    }

    /** Overload that lets the controller pass an optional story title / body
     *  and footer address — useful for owners who want to customise the
     *  menu's introduction without us hardcoding their copy. */
    public byte[] buildMenu(
            String layoutKey,
            String customTitle,
            String customSubtitle,
            boolean showPrices,
            String language,
            String storyTitle,
            String storyBody,
            String contactBlock) {
        return buildMenu(new Options(
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

    private byte[] buildMenu(Options opt) {
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

        // Two-pass render so the contents page can show real page numbers.
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

        Document doc = new Document(PageSize.A4, 54, 54, 84, 70);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, Integer> pageStarts = new HashMap<>();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new MenuChrome(title));
            doc.open();

            drawCover(doc, title, subtitle);

            doc.newPage();
            drawStory(doc, opt);

            doc.newPage();
            drawHeritage(doc);

            if (contentsPageStarts != null) {
                doc.newPage();
                drawContents(doc, categories, contentsPageStarts);
            }

            int catIdx = 1;
            for (MenuCategory cat : categories) {
                List<MenuItem> items = itemsByCategory.get(cat.getId());
                if (items == null || items.isEmpty()) continue;
                doc.newPage();
                pageStarts.put(cat.getId(), writer.getPageNumber());
                drawCategoryHero(doc, cat.getName(), catIdx);
                switch (opt.layout()) {
                    case GRID -> drawGrid(doc, items, opt.showPrices(), opt.locale());
                    case LIST -> drawList(doc, items, opt.showPrices(), opt.locale());
                    case COMPACT -> drawCompact(doc, items, opt.showPrices(), opt.locale());
                }
                catIdx++;
            }

            doc.newPage();
            drawClosing(doc, opt);

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

    private void drawCover(Document doc, String title, String subtitle) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, SAFFRON_DEEP);
        Font brand = FontFactory.getFont(FontFactory.TIMES_BOLD, 84, INK);
        Font sub = FontFactory.getFont(FontFactory.TIMES_ITALIC, 18, MUTED);
        Font date = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
        Font motif = FontFactory.getFont(FontFactory.TIMES_ITALIC, 12, SAFFRON_DEEP);

        accentBar(doc, 6f, SAFFRON);

        Paragraph eyebrowPara = new Paragraph(spacedCaps("La Carte · Catalogue of dishes"), eyebrow);
        eyebrowPara.setSpacingBefore(170);
        eyebrowPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(eyebrowPara);

        Paragraph brandPara = new Paragraph(title, brand);
        brandPara.setSpacingBefore(22);
        brandPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(brandPara);

        drawOrnament(doc);

        Paragraph subPara = new Paragraph(subtitle, sub);
        subPara.setSpacingBefore(14);
        subPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(subPara);

        Paragraph motifPara = new Paragraph("Şirniyyat · Plov · Kabab · Çay", motif);
        motifPara.setSpacingBefore(36);
        motifPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(motifPara);

        Paragraph datePara = new Paragraph("Edition · " + LocalDate.now().format(MENU_DATE), date);
        datePara.setSpacingBefore(160);
        datePara.setAlignment(Element.ALIGN_CENTER);
        doc.add(datePara);

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
        PdfPCell diamond = new PdfPCell(new Phrase("◆",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, SAFFRON)));
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

    // ---------- Story page ----------

    private void drawStory(Document doc, Options opt) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 36, INK);
        Font body = FontFactory.getFont(FontFactory.TIMES_ROMAN, 12.5f, INK);
        Font quote = FontFactory.getFont(FontFactory.TIMES_ITALIC, 16, SAFFRON_DEEP);

        Paragraph eb = new Paragraph(spacedCaps("Welcome"), eyebrow);
        eb.setSpacingBefore(10);
        doc.add(eb);

        Paragraph h = new Paragraph(blankToDefault(opt.storyTitle(), "Our story"), head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(8);
        doc.add(h);

        // Short saffron rule under the title for an editorial hint.
        doc.add(saffronRule(48f));

        String[] paragraphs = blankToDefault(opt.storyBody(), DEFAULT_STORY_BODY).split("\\n\\s*\\n");
        for (int i = 0; i < paragraphs.length; i++) {
            Paragraph p = new Paragraph(paragraphs[i].trim(), body);
            p.setLeading(18f);
            p.setSpacingBefore(i == 0 ? 22 : 12);
            p.setAlignment(Element.ALIGN_JUSTIFIED);
            doc.add(p);
        }

        Paragraph q = new Paragraph(DEFAULT_HERITAGE_QUOTE, quote);
        q.setAlignment(Element.ALIGN_CENTER);
        q.setSpacingBefore(28);
        doc.add(q);
    }

    // ---------- Heritage page ----------

    private void drawHeritage(Document doc) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 30, INK);
        Font noteHead = FontFactory.getFont(FontFactory.TIMES_BOLD, 13, INK);
        Font noteBody = FontFactory.getFont(FontFactory.TIMES_ROMAN, 11.5f, MUTED);

        Paragraph eb = new Paragraph(spacedCaps("A taste of Azerbaijan"), eyebrow);
        doc.add(eb);

        Paragraph h = new Paragraph("Notes from the kitchen", head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(8);
        doc.add(h);
        doc.add(saffronRule(48f));

        String[][] notes = new String[][] {
                {"Saffron — Zəfəran",
                        "More precious than gold by weight, saffron threads are pulled from crocus flowers by "
                                + "hand. We use it sparingly in plov and şirniyyat — a few strands bloomed in warm "
                                + "milk turns rice the colour of late afternoon sun."},
                {"Plov — the table's centrepiece",
                        "A celebration dish that varies by region: lamb-stuffed in Bakı, chestnut-and-dried-fruit "
                                + "in Şəki, fish in Lənkəran. The crust at the bottom of the pot — qazmaq — is "
                                + "always the most contested piece."},
                {"Dolma — leaves that hold tradition",
                        "Vine leaves, cabbage, peppers, or sometimes quince — wrapped around lamb, rice and "
                                + "herbs. UNESCO recognises Azerbaijani dolma as Intangible Cultural Heritage."},
                {"Çay — the rhythm of hospitality",
                        "Tea is poured into pear-shaped armudu glasses and almost never refused. Sweets, rock "
                                + "sugar, a sprig of cardamom — and conversation that doesn't end with the pot."},
        };

        PdfPTable grid = new PdfPTable(2);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(22);
        grid.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        try { grid.setWidths(new float[]{1, 1}); } catch (DocumentException ignored) {}

        for (String[] n : notes) {
            PdfPTable card = new PdfPTable(1);
            card.setWidthPercentage(100);
            PdfPCell titleCell = new PdfPCell(new Phrase(n[0], noteHead));
            titleCell.setBorder(Rectangle.NO_BORDER);
            titleCell.setPaddingBottom(4);
            card.addCell(titleCell);

            PdfPCell hair = new PdfPCell();
            hair.setFixedHeight(0.8f);
            hair.setBackgroundColor(SAFFRON);
            hair.setBorder(Rectangle.NO_BORDER);
            card.addCell(hair);

            PdfPCell bodyCell = new PdfPCell(new Phrase(n[1], noteBody));
            bodyCell.setBorder(Rectangle.NO_BORDER);
            bodyCell.setPaddingTop(8);
            bodyCell.setPaddingBottom(4);
            card.addCell(bodyCell);

            PdfPCell wrap = new PdfPCell(card);
            wrap.setBorder(Rectangle.NO_BORDER);
            wrap.setPadding(0);
            wrap.setPaddingBottom(22);
            wrap.setPaddingRight(14);
            wrap.setPaddingLeft(14);
            grid.addCell(wrap);
        }
        doc.add(grid);
    }

    // ---------- Contents ----------

    private void drawContents(Document doc, List<MenuCategory> cats, Map<String, Integer> starts)
            throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 32, INK);
        Font row = FontFactory.getFont(FontFactory.TIMES_ROMAN, 14, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 11, MUTED);
        Font pageNum = FontFactory.getFont(FontFactory.HELVETICA, 11, MUTED);
        Font index = FontFactory.getFont(FontFactory.HELVETICA, 9, SAFFRON);

        Paragraph eb = new Paragraph(spacedCaps("Contents"), eyebrow);
        doc.add(eb);

        Paragraph h = new Paragraph("Table of contents", head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(8);
        doc.add(h);
        doc.add(saffronRule(48f));

        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        try { t.setWidths(new float[]{0.6f, 8, 1}); } catch (DocumentException ignored) {}
        t.setSpacingBefore(24);

        int i = 1;
        for (MenuCategory c : cats) {
            Integer pg = starts.get(c.getId());
            String pgText = pg != null ? String.valueOf(pg) : "—";

            PdfPCell numCell = new PdfPCell(new Phrase(twoDigit(i), index));
            numCell.setBorder(Rectangle.NO_BORDER);
            numCell.setPaddingTop(14);
            numCell.setPaddingBottom(14);

            String az_ = azFor(c.getName());
            Phrase np = new Phrase();
            np.add(new Phrase(c.getName(), row));
            if (az_ != null) {
                np.add(new Phrase("   " + az_, az));
            }
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
        Font numeral = FontFactory.getFont(FontFactory.TIMES_BOLD, 72, CREAM_DEEP);
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 36, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 14, MUTED);
        Font blurb = FontFactory.getFont(FontFactory.TIMES_ROMAN, 11.5f, MUTED);

        PdfPTable hero = new PdfPTable(2);
        hero.setWidthPercentage(100);
        try { hero.setWidths(new float[]{1.4f, 8}); } catch (DocumentException ignored) {}

        PdfPCell numCell = new PdfPCell(new Phrase(twoDigit(idx), numeral));
        numCell.setBorder(Rectangle.NO_BORDER);
        numCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        PdfPTable textBlock = new PdfPTable(1);
        textBlock.setWidthPercentage(100);

        PdfPCell ebCell = new PdfPCell(new Phrase(spacedCaps("Chapter " + idx), eyebrow));
        ebCell.setBorder(Rectangle.NO_BORDER);
        ebCell.setPaddingBottom(0);
        textBlock.addCell(ebCell);

        PdfPCell nameCell = new PdfPCell(new Phrase(name, head));
        nameCell.setBorder(Rectangle.NO_BORDER);
        nameCell.setPaddingTop(2);
        nameCell.setPaddingBottom(4);
        textBlock.addCell(nameCell);

        String az_ = azFor(name);
        if (az_ != null) {
            PdfPCell azCell = new PdfPCell(new Phrase(az_, az));
            azCell.setBorder(Rectangle.NO_BORDER);
            azCell.setPaddingBottom(8);
            textBlock.addCell(azCell);
        }

        // Short saffron rule
        PdfPTable ruleWrap = new PdfPTable(1);
        ruleWrap.setWidthPercentage(14);
        ruleWrap.setHorizontalAlignment(Element.ALIGN_LEFT);
        PdfPCell ruleCell = new PdfPCell();
        ruleCell.setFixedHeight(3f);
        ruleCell.setBackgroundColor(SAFFRON);
        ruleCell.setBorder(Rectangle.NO_BORDER);
        ruleWrap.addCell(ruleCell);
        PdfPCell ruleHolder = new PdfPCell(ruleWrap);
        ruleHolder.setBorder(Rectangle.NO_BORDER);
        ruleHolder.setPaddingTop(2);
        ruleHolder.setPaddingBottom(6);
        textBlock.addCell(ruleHolder);

        String blurbText = blurbFor(name);
        if (blurbText != null) {
            PdfPCell blurbCell = new PdfPCell(new Phrase(blurbText, blurb));
            blurbCell.setBorder(Rectangle.NO_BORDER);
            blurbCell.setPaddingTop(6);
            textBlock.addCell(blurbCell);
        }

        PdfPCell textCell = new PdfPCell(textBlock);
        textCell.setBorder(Rectangle.NO_BORDER);
        textCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        hero.addCell(numCell);
        hero.addCell(textCell);
        doc.add(hero);

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(24);
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
        wrap.setPaddingBottom(20);
        wrap.setPaddingRight(12);
        wrap.setPaddingLeft(0);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Photo card with a slightly larger frame for visual impact.
        PdfPCell photo = new PdfPCell();
        photo.setBackgroundColor(CREAM);
        photo.setBorder(Rectangle.BOX);
        photo.setBorderColor(HAIRLINE);
        photo.setBorderWidth(0.6f);
        photo.setFixedHeight(170f);
        photo.setPadding(2);
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.setAlignment(Image.ALIGN_CENTER | Image.ALIGN_MIDDLE);
            img.scaleToFit(258, 160);
            photo.setImage(img);
        } else {
            // Subtle "Saffron" wordmark placeholder so empty photos don't look broken.
            photo.setPhrase(new Phrase("◆",
                    FontFactory.getFont(FontFactory.HELVETICA, 18, CREAM_DEEP)));
        }
        card.addCell(photo);

        if (item.isFeatured()) {
            PdfPCell pill = new PdfPCell(new Phrase(spacedCaps("Chef's signature"),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, SAFFRON_DEEP)));
            pill.setBorder(Rectangle.NO_BORDER);
            pill.setPaddingTop(12);
            pill.setPaddingBottom(0);
            card.addCell(pill);
        }

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 15, INK);
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
        try { row.setWidths(new float[]{1.3f, 4f}); } catch (DocumentException ignored) {}

        PdfPCell photo = new PdfPCell();
        photo.setBorder(Rectangle.BOX);
        photo.setBorderColor(HAIRLINE);
        photo.setBorderWidth(0.6f);
        photo.setFixedHeight(108f);
        photo.setPaddingRight(14);
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image img = tryLoadImage(item.getImagePath());
        if (img != null) {
            img.scaleToFit(130, 100);
            photo.setImage(img);
        } else {
            photo.setBackgroundColor(CREAM);
            photo.setPhrase(new Phrase("◆",
                    FontFactory.getFont(FontFactory.HELVETICA, 14, CREAM_DEEP)));
        }
        row.addCell(photo);

        Font nameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 16, INK);
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

    private void drawClosing(Document doc, Options opt) throws DocumentException {
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 28, INK);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, MUTED);
        Font tip = FontFactory.getFont(FontFactory.TIMES_ITALIC, 12, INK);
        Font contact = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);

        Paragraph eb = new Paragraph(spacedCaps("Important to know"), eyebrow);
        doc.add(eb);

        Paragraph h = new Paragraph("Allergens & advisories", head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(8);
        doc.add(h);
        doc.add(saffronRule(48f));

        Paragraph p = new Paragraph(
                "Please notify a member of our team of any allergies or dietary requirements before ordering. "
                        + "All dishes are prepared in a kitchen that also handles gluten, dairy, eggs, nuts, sesame, soya, "
                        + "fish, shellfish, celery, mustard and sulphites — cross-contact cannot be entirely excluded.",
                body);
        p.setLeading(16);
        p.setSpacingBefore(22);
        p.setSpacingAfter(12);
        p.setAlignment(Element.ALIGN_JUSTIFIED);
        doc.add(p);

        Paragraph p2 = new Paragraph(
                "Photographs are for presentation purposes; plating, garnishes and side accompaniments may vary "
                        + "with seasonal availability. Prices include VAT. Tea is served on the house with a meal "
                        + "of three or more courses — please ask.",
                body);
        p2.setLeading(16);
        p2.setSpacingAfter(28);
        p2.setAlignment(Element.ALIGN_JUSTIFIED);
        doc.add(p2);

        Paragraph thanks = new Paragraph("Çox sağ olun — Thank you for dining with us.", tip);
        thanks.setAlignment(Element.ALIGN_CENTER);
        thanks.setSpacingBefore(40);
        doc.add(thanks);

        drawOrnament(doc);

        if (opt.contactBlock() != null && !opt.contactBlock().isBlank()) {
            Paragraph c = new Paragraph(opt.contactBlock().trim(), contact);
            c.setAlignment(Element.ALIGN_CENTER);
            c.setLeading(15);
            c.setSpacingBefore(28);
            doc.add(c);
        }
    }

    // ---------- Helpers ----------

    private static String azFor(String englishName) {
        if (englishName == null) return null;
        String key = englishName.trim().toLowerCase(Locale.ROOT);
        return CATEGORY_TRANSLATIONS.get(key);
    }

    /** Short editorial blurb shown under a category title. We default by topic
     *  using a small lookup so categories like "Mains" / "Plov" / "Drinks" feel
     *  curated; unrecognised categories simply get no blurb. */
    private static String blurbFor(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "starters", "appetisers", "appetizers" ->
                    "Small plates to open the meal — eaten slowly, ideally with bread and conversation.";
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

    private Chunk saffronRule(float width) {
        LineSeparator sep = new LineSeparator(2.2f, width, SAFFRON, Element.ALIGN_LEFT, 0);
        return new Chunk(sep);
    }

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
