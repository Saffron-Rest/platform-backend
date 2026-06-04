package com.saffron.cashflow.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
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
import com.lowagie.text.pdf.draw.LineSeparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.saffron.cashflow.domain.MenuCategory;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
import java.util.stream.Collectors;

/**
 * Editorial, asymmetric, typography-led restaurant menu PDF.
 *
 * <p>No photographs, no drawn icons — but with sharper editorial structure
 * than the previous minimalist pass. The owner asked for "attractive but
 * professional, without images or icons or big category titles". The design
 * leans on four typographic moves:</p>
 *
 * <ol>
 *   <li><b>Asymmetric composition</b> — cover, section dividers and closing
 *       are left-aligned with a vertical motif strap on the outer edge,
 *       instead of being center-aligned.</li>
 *   <li><b>Roman numerals as section anchors</b> — each category opens with
 *       a large saffron Roman numeral (II, III, IV…) acting as a typographic
 *       flourish. The category name itself stays small so the title never
 *       dominates the page.</li>
 *   <li><b>Numbered items</b> — every dish gets a small saffron index number
 *       (01, 02…) that resets per section, giving the page a measured cadence
 *       and making the menu easy to read aloud ("the 03, please").</li>
 *   <li><b>Side-margin running label</b> — the current section name reads
 *       vertically up the outer edge of every body page, like the running
 *       head of an art book. The folio bottom-centre is a single numeral
 *       between two saffron dots.</li>
 * </ol>
 */
@Service
public class MenuPrintService {

    private static final Logger LOG = LoggerFactory.getLogger(MenuPrintService.class);

    // ---- Unicode-capable fonts loaded once at class init ----
    //
    // The 14 built-in PDF fonts only cover WinAnsi, which silently drops
    // Azerbaijani schwa (ə), letters like Ş/ş, and a few other glyphs we
    // rely on. We bundle Noto Serif + Noto Sans (Apache 2.0) and embed
    // them as subsets — adds ~25 KB to a typical menu PDF.
    private static final BaseFont SERIF_REG = loadFont("NotoSerif-Regular.ttf", BaseFont.HELVETICA);
    private static final BaseFont SERIF_BOLD = loadFont("NotoSerif-Bold.ttf", BaseFont.HELVETICA_BOLD);
    private static final BaseFont SERIF_ITALIC = loadFont("NotoSerif-Italic.ttf", BaseFont.HELVETICA_OBLIQUE);
    private static final BaseFont SERIF_BOLD_ITALIC =
            loadFont("NotoSerif-BoldItalic.ttf", BaseFont.HELVETICA_BOLDOBLIQUE);
    private static final BaseFont SANS_REG = loadFont("NotoSans-Regular.ttf", BaseFont.HELVETICA);
    private static final BaseFont SANS_BOLD = loadFont("NotoSans-Bold.ttf", BaseFont.HELVETICA_BOLD);
    private static final BaseFont SANS_ITALIC = loadFont("NotoSans-Italic.ttf", BaseFont.HELVETICA_OBLIQUE);

    private static BaseFont loadFont(String name, String fallbackBuiltIn) {
        try (InputStream in =
                     MenuPrintService.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) throw new IllegalStateException("font not on classpath: " + name);
            byte[] bytes = in.readAllBytes();
            // Identity-H + embedded = full Unicode coverage in the PDF.
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    BaseFont.CACHED, bytes, null);
        } catch (Exception e) {
            LOG.warn("Failed to load {} ({}). Falling back to built-in font {}. "
                            + "Azerbaijani characters may not render correctly.",
                    name, e.getMessage(), fallbackBuiltIn);
            try {
                return BaseFont.createFont(fallbackBuiltIn,
                        BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot load any font for menu PDF", ex);
            }
        }
    }

    private static Font font(BaseFont bf, float size, Color color) {
        Font f = new Font(bf, size);
        f.setColor(color);
        return f;
    }

    private static final Color INK = new Color(0x1A, 0x18, 0x14);
    private static final Color INK_SOFT = new Color(0x3A, 0x33, 0x29);
    private static final Color SAFFRON = new Color(0xC9, 0x6A, 0x1A);
    private static final Color SAFFRON_DEEP = new Color(0xA4, 0x52, 0x12);
    private static final Color CREAM = new Color(0xFA, 0xF4, 0xE8);
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

        Document doc = new Document(PageSize.A4, 68, 68, 84, 84);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            MenuChrome chrome = new MenuChrome();
            writer.setPageEvent(chrome);
            doc.open();

            chrome.suppressNext();
            drawCover(doc, writer, title, subtitle);

            doc.newPage();
            drawStory(doc, writer, opt);

            doc.newPage();
            drawHeritage(doc);

            for (MenuCategory cat : categories) {
                List<MenuItem> items = itemsByCategory.get(cat.getId());
                if (items == null || items.isEmpty()) continue;
                doc.newPage();
                drawSectionDivider(doc, cat.getName());
                switch (opt.layout()) {
                    case GRID -> drawGrid(doc, items, opt.showPrices(), opt.locale());
                    case LIST -> drawList(doc, items, opt.showPrices(), opt.locale());
                    case COMPACT -> drawCompact(doc, items, opt.showPrices(), opt.locale());
                }
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

    // ---------- Cover (centered, welcoming) ----------

    /**
     * Centred, welcoming composition — what a guest expects when they pick a
     * menu up off the table. The previous editorial cover (asymmetric, with
     * a rotated motif strap on the spine) read more like an art book than a
     * restaurant menu, so we walked it back to something inviting and clear.
     */
    private void drawCover(Document doc, PdfWriter writer, String title, String subtitle)
            throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();
        float w = page.getWidth(), h = page.getHeight();
        float cx = w / 2f;

        // Two concentric saffron rules — the only structural ornament.
        float inset = 36f;
        drawThinFrame(cb, page, inset, SAFFRON, 0.55f);
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.25f);

        Font eyebrow = font(SANS_BOLD, 9, SAFFRON_DEEP);
        Font brand = font(SERIF_BOLD, 108, INK);
        Font sub = font(SERIF_ITALIC, 18, MUTED);
        Font menuLabel = font(SANS_BOLD, 9, MUTED);
        Font foot = font(SANS_REG, 8, MUTED);

        showCentered(cb, spacedCaps("Azerbaijani cuisine · Warszawa"), eyebrow,
                cx, h - inset - 80);

        showCentered(cb, title, brand, cx, h * 0.58f);

        diamondRule(cb, cx - 45f, h * 0.58f - 38, 90f);

        showCentered(cb, subtitle, sub, cx, h * 0.58f - 64);

        showCentered(cb,
                spacedCaps("Menu · " + LocalDate.now().format(MENU_DATE)),
                menuLabel, cx, inset + 64);
        showCentered(cb, "Saffron Restaurant · Warszawa · Poland",
                foot, cx, inset + 46);
    }

    // ---------- Story ----------

    private void drawStory(Document doc, PdfWriter writer, Options opt) throws DocumentException {
        Font eyebrow = font(SANS_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = font(SERIF_BOLD, 30, INK);
        Font dropCap = font(SERIF_BOLD, 64, SAFFRON_DEEP);
        Font body = font(SERIF_REG, 12, INK_SOFT);
        Font quote = font(SERIF_ITALIC, 16, SAFFRON_DEEP);
        Font attribution = font(SANS_REG, 8.5f, MUTED);

        Paragraph eb = new Paragraph(spacedCaps("Welcome"), eyebrow);
        eb.setAlignment(Element.ALIGN_CENTER);
        doc.add(eb);

        Paragraph h = new Paragraph(blankToDefault(opt.storyTitle(), "Our story"), head);
        h.setAlignment(Element.ALIGN_CENTER);
        h.setSpacingBefore(6);
        h.setSpacingAfter(0);
        doc.add(h);

        Paragraph ruleSpacer = new Paragraph(" ");
        ruleSpacer.setSpacingBefore(8);
        doc.add(ruleSpacer);
        LineSeparator centerRule = new LineSeparator(1.5f, 18, SAFFRON, Element.ALIGN_CENTER, 0);
        doc.add(new Chunk(centerRule));

        String[] paragraphs = blankToDefault(opt.storyBody(), DEFAULT_STORY_BODY).split("\\n\\s*\\n");
        if (paragraphs.length > 0 && !paragraphs[0].isBlank()) {
            String first = paragraphs[0].trim();
            String initial = first.substring(0, 1);
            String rest = first.substring(1);

            PdfPTable dc = new PdfPTable(2);
            dc.setWidthPercentage(100);
            dc.setSpacingBefore(26);
            try { dc.setWidths(new float[]{0.9f, 10f}); } catch (DocumentException ignored) {}

            PdfPCell capCell = new PdfPCell(new Phrase(initial, dropCap));
            capCell.setBorder(Rectangle.NO_BORDER);
            capCell.setPaddingTop(-6);
            capCell.setPaddingRight(10);
            capCell.setVerticalAlignment(Element.ALIGN_TOP);

            Paragraph bodyPara = new Paragraph(rest, body);
            bodyPara.setLeading(18.5f);
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
            p.setLeading(18.5f);
            p.setSpacingBefore(12);
            p.setAlignment(Element.ALIGN_JUSTIFIED);
            doc.add(p);
        }

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingBefore(22);
        doc.add(spacer);
        diamondRule(writer.getDirectContent(), doc.getPageSize().getWidth() / 2f,
                writer.getVerticalPosition(true) - 4, 90f);

        Paragraph q = new Paragraph("\u201CA guest is the gift of God.\u201D", quote);
        q.setAlignment(Element.ALIGN_CENTER);
        q.setSpacingBefore(28);
        doc.add(q);
        Paragraph a = new Paragraph("— Azerbaijani proverb", attribution);
        a.setAlignment(Element.ALIGN_CENTER);
        a.setSpacingBefore(4);
        doc.add(a);
    }

    // ---------- Heritage (numbered single-column notes) ----------

    private void drawHeritage(Document doc) throws DocumentException {
        Font eyebrow = font(SANS_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = font(SERIF_BOLD, 28, INK);
        Font noteHead = font(SERIF_BOLD, 14, INK);
        Font noteAz = font(SERIF_ITALIC, 10.5f, MUTED);
        Font noteBody = font(SERIF_REG, 11, INK_SOFT);

        // Centered title block — keeps the page feeling welcoming rather
        // than editorial. Previous "NOTE Nº 1/2/3/4" labels are gone.
        Paragraph eb = new Paragraph(spacedCaps("A taste of Azerbaijan"), eyebrow);
        eb.setAlignment(Element.ALIGN_CENTER);
        doc.add(eb);

        Paragraph h = new Paragraph("Notes from our kitchen", head);
        h.setAlignment(Element.ALIGN_CENTER);
        h.setSpacingBefore(6);
        h.setSpacingAfter(0);
        doc.add(h);

        Paragraph ruleSpacer = new Paragraph(" ");
        ruleSpacer.setSpacingBefore(8);
        doc.add(ruleSpacer);
        LineSeparator sep = new LineSeparator(1.5f, 18, SAFFRON, Element.ALIGN_CENTER, 0);
        doc.add(new Chunk(sep));

        String[][] notes = new String[][] {
                {"Saffron", "Zəfəran",
                        "More precious than gold by weight. A few strands bloomed in warm milk turn rice "
                                + "the colour of late afternoon sun. We grow our own crocuses outside Warsaw."},
                {"Plov", "Plov",
                        "Slow-coaxed, region-specific, never the same twice. The qazmaq crust at the bottom "
                                + "of the pot is always the most contested piece of the night."},
                {"Dolma", "Dolma",
                        "Vine leaves, cabbage, peppers — sometimes quince — wrapped around lamb, rice and herbs. "
                                + "UNESCO recognises it as Intangible Cultural Heritage."},
                {"Çay", "Çay",
                        "Loose-leaf tea poured into pear-shaped armudu glasses. Sweets, rock sugar, a sprig of "
                                + "cardamom — and conversation that doesn't end with the pot."},
        };

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(28);
        doc.add(spacer);

        for (int i = 0; i < notes.length; i++) {
            String[] n = notes[i];

            Paragraph title = new Paragraph(n[0], noteHead);
            title.setSpacingBefore(i == 0 ? 0 : 22);
            title.setSpacingAfter(0);
            doc.add(title);

            Paragraph az = new Paragraph(n[1], noteAz);
            az.setSpacingBefore(2);
            az.setSpacingAfter(4);
            doc.add(az);

            LineSeparator hair = new LineSeparator(0.6f, 80, SAFFRON, Element.ALIGN_LEFT, 0);
            doc.add(new Chunk(hair));

            Paragraph body = new Paragraph(n[2], noteBody);
            body.setLeading(15.5f);
            body.setSpacingBefore(8);
            body.setAlignment(Element.ALIGN_JUSTIFIED);
            doc.add(body);
        }
    }

    // ---------- Section divider (centered, natural eyebrow) ----------

    /**
     * Friendly centered divider — natural language eyebrow ("TO BEGIN",
     * "FROM THE CHARCOAL"), medium-size category name, italic translation,
     * thin saffron rule, short blurb. Replaces the editorial Roman-numeral
     * layout, which read as too "book-like" for diners.
     */
    private void drawSectionDivider(Document doc, String name) throws DocumentException {
        Font eyebrow = font(SANS_BOLD, 9, SAFFRON_DEEP);
        Font head = font(SERIF_BOLD, 26, INK);
        Font az = font(SERIF_ITALIC, 12.5f, MUTED);
        Font blurb = font(SERIF_ITALIC, 11, MUTED);

        Paragraph eb = new Paragraph(spacedCaps(eyebrowFor(name)), eyebrow);
        eb.setAlignment(Element.ALIGN_CENTER);
        doc.add(eb);

        Paragraph h = new Paragraph(name, head);
        h.setAlignment(Element.ALIGN_CENTER);
        h.setSpacingBefore(6);
        h.setSpacingAfter(0);
        doc.add(h);

        String azName = azFor(name);
        if (azName != null) {
            Paragraph azP = new Paragraph(azName, az);
            azP.setAlignment(Element.ALIGN_CENTER);
            azP.setSpacingBefore(2);
            doc.add(azP);
        }

        Paragraph ruleSpacer = new Paragraph(" ");
        ruleSpacer.setSpacingBefore(8);
        doc.add(ruleSpacer);
        LineSeparator sep = new LineSeparator(1.5f, 18, SAFFRON, Element.ALIGN_CENTER, 0);
        doc.add(new Chunk(sep));

        String blurbText = blurbFor(name);
        if (blurbText != null) {
            Paragraph bp = new Paragraph(blurbText, blurb);
            bp.setAlignment(Element.ALIGN_CENTER);
            bp.setLeading(15.5f);
            bp.setSpacingBefore(14);
            bp.setIndentationLeft(48f);
            bp.setIndentationRight(48f);
            doc.add(bp);
        }

        Paragraph after = new Paragraph(" ");
        after.setSpacingAfter(28);
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
        wrap.setPaddingBottom(28);
        wrap.setPaddingRight(14);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Photos remain optional — only included when admin uploads one.
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
                    font(SANS_BOLD, 7.5f, SAFFRON_DEEP)));
            pill.setBorder(Rectangle.NO_BORDER);
            pill.setPaddingTop(img != null ? 14 : 0);
            pill.setPaddingBottom(0);
            card.addCell(pill);
        }

        // ── Fonts ────────────────────────────────────────────────────────────
        Font nameFont      = font(SERIF_BOLD,   14,   INK);
        Font portionFont   = font(SANS_REG,      9f,  MUTED);       // portion size inline
        Font priceFont     = font(SANS_BOLD,    12.5f, SAFFRON_DEEP);
        Font descFont      = font(SERIF_ITALIC, 10,   MUTED);        // italic — story-telling
        Font tagsFont      = font(SANS_ITALIC,   8,   MUTED);
        Font allergenFont  = font(SANS_REG,      7.5f, MUTED);
        Font optLabelFont  = font(SANS_BOLD,     7,   SAFFRON_DEEP); // "OPTIONS"
        Font varNameFont   = font(SANS_REG,      9.5f, INK_SOFT);
        Font varPriceFont  = font(SANS_BOLD,     9.5f, SAFFRON_DEEP);
        Font varSameFont   = font(SANS_ITALIC,   9f,  MUTED);        // "Small · Regular"

        List<VariantEntry> variants   = parseVariants(item);
        boolean varPrices             = showPrices && hasVariantPrices(variants);
        boolean showBasePrice         = showPrices && !varPrices;

        // ── 1. Name row (name + portionSize mixed phrase) + base price ────────
        PdfPTable nameRow = new PdfPTable(showBasePrice ? 2 : 1);
        nameRow.setWidthPercentage(100);
        try { if (showBasePrice) nameRow.setWidths(new float[]{5.4f, 2.6f}); }
        catch (DocumentException ignored) {}
        nameRow.addCell(textCell(namePhrase(item, nameFont, portionFont), Element.ALIGN_LEFT));
        if (showBasePrice) {
            PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
            pc.setBorder(Rectangle.NO_BORDER);
            pc.setHorizontalAlignment(Element.ALIGN_RIGHT);
            pc.setNoWrap(true);
            nameRow.addCell(pc);
        }
        PdfPCell nameWrap = new PdfPCell(nameRow);
        nameWrap.setBorder(Rectangle.NO_BORDER);
        nameWrap.setPaddingTop(item.isFeatured() ? 4 : (img != null ? 14 : 0));
        nameWrap.setPaddingBottom(2);
        card.addCell(nameWrap);

        // thin hairline under the name
        PdfPCell hair = new PdfPCell();
        hair.setFixedHeight(0.6f);
        hair.setBackgroundColor(HAIRLINE);
        hair.setBorder(Rectangle.NO_BORDER);
        card.addCell(hair);

        // ── 2. Description (BEFORE variants — guest reads dish first) ─────────
        String desc = chooseDescription(item);
        if (desc != null) {
            PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
            d.setBorder(Rectangle.NO_BORDER);
            d.setPaddingTop(7);
            d.setPaddingBottom(2);
            d.setLeading(0, 1.35f);
            card.addCell(d);
        }

        // ── 3. Dietary + allergens ────────────────────────────────────────────
        String dietary = renderDietary(item);
        if (dietary != null) {
            PdfPCell c = new PdfPCell(new Phrase(dietary, tagsFont));
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingTop(5);
            card.addCell(c);
        }
        String allergen = renderAllergens(item);
        if (allergen != null) {
            PdfPCell c = new PdfPCell(new Phrase(allergen, allergenFont));
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingTop(2);
            card.addCell(c);
        }

        // ── 4. Options section (AFTER description — selectable, not descriptive)
        if (!variants.isEmpty()) {
            // "OPTIONS" label with a partial saffron rule
            PdfPCell optLabel = new PdfPCell(new Phrase(spacedCaps("Options"), optLabelFont));
            optLabel.setBorder(Rectangle.NO_BORDER);
            optLabel.setPaddingTop(10);
            optLabel.setPaddingBottom(2);
            card.addCell(optLabel);

            PdfPCell optHair = new PdfPCell();
            optHair.setFixedHeight(0.5f);
            optHair.setBackgroundColor(SAFFRON);
            optHair.setBorder(Rectangle.NO_BORDER);
            card.addCell(optHair);

            if (varPrices) {
                // Each option: name (left) + price (right)
                for (VariantEntry v : variants) {
                    PdfPTable vRow = new PdfPTable(2);
                    vRow.setWidthPercentage(100);
                    try { vRow.setWidths(new float[]{5.4f, 2.6f}); } catch (DocumentException ignored) {}

                    PdfPCell vnc = new PdfPCell(new Phrase(v.name(), varNameFont));
                    vnc.setBorder(Rectangle.NO_BORDER);
                    vnc.setPaddingTop(4);
                    vnc.setPaddingLeft(4);
                    vRow.addCell(vnc);

                    BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                    PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPriceFont));
                    vpc.setBorder(Rectangle.NO_BORDER);
                    vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    vpc.setNoWrap(true);
                    vpc.setPaddingTop(4);
                    vRow.addCell(vpc);

                    PdfPCell vWrap = new PdfPCell(vRow);
                    vWrap.setBorder(Rectangle.NO_BORDER);
                    card.addCell(vWrap);
                }
            } else {
                // Same price — name chips on one italic line
                PdfPCell vLine = new PdfPCell(new Phrase(variantNamesLine(variants), varSameFont));
                vLine.setBorder(Rectangle.NO_BORDER);
                vLine.setPaddingTop(5);
                card.addCell(vLine);
            }
        }

        wrap.addElement(card);
        return wrap;
    }

    // ---------- LIST ----------

    private void drawList(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        // ── Fonts ────────────────────────────────────────────────────────────
        Font nameFont      = font(SERIF_BOLD,   14.5f, INK);
        Font portionFont   = font(SANS_REG,      9.5f, MUTED);
        Font priceFont     = font(SANS_BOLD,    13,    SAFFRON_DEEP);
        Font pillFont      = font(SANS_BOLD,     7.5f, SAFFRON_DEEP);
        Font descFont      = font(SERIF_ITALIC, 11,    MUTED);        // italic serif — most readable in list
        Font tagsFont      = font(SANS_ITALIC,   9,    MUTED);
        Font allergenFont  = font(SANS_REG,      8,    MUTED);
        Font optLabelFont  = font(SANS_BOLD,     7.5f, SAFFRON_DEEP);
        Font varNameFont   = font(SANS_REG,     10.5f, INK_SOFT);
        Font varPriceFont  = font(SANS_BOLD,    10.5f, SAFFRON_DEEP);
        Font varSameFont   = font(SANS_ITALIC,  10f,   MUTED);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item       = items.get(i);
            List<VariantEntry> variants = parseVariants(item);
            boolean varPrices   = showPrices && hasVariantPrices(variants);
            boolean showBasePrice = showPrices && !varPrices;

            // ── 1. Featured pill ──────────────────────────────────────────────
            if (item.isFeatured()) {
                Paragraph pill = new Paragraph(spacedCaps("Chef's signature"), pillFont);
                pill.setSpacingBefore(i == 0 ? 0 : 4);
                doc.add(pill);
            }

            // ── 2. Name row (mixed phrase) + base price ───────────────────────
            PdfPTable head = new PdfPTable(showBasePrice ? 2 : 1);
            head.setWidthPercentage(100);
            try { if (showBasePrice) head.setWidths(new float[]{6.4f, 1.6f}); }
            catch (DocumentException ignored) {}
            head.addCell(textCell(namePhrase(item, nameFont, portionFont), Element.ALIGN_LEFT));
            if (showBasePrice) {
                PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
                pc.setBorder(Rectangle.NO_BORDER);
                pc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                pc.setNoWrap(true);
                head.addCell(pc);
            }
            head.setSpacingBefore(item.isFeatured() ? 2 : 6);
            doc.add(head);

            // ── 3. Description (before options — read the dish, then choose) ──
            String desc = chooseDescription(item);
            if (desc != null) {
                Paragraph d = new Paragraph(desc, descFont);
                d.setLeading(15.5f);
                d.setSpacingBefore(4);
                doc.add(d);
            }

            // ── 4. Dietary + allergens ────────────────────────────────────────
            String dietary = renderDietary(item);
            if (dietary != null) {
                Paragraph d = new Paragraph(dietary, tagsFont);
                d.setSpacingBefore(4);
                doc.add(d);
            }
            String allergen = renderAllergens(item);
            if (allergen != null) {
                Paragraph d = new Paragraph(allergen, allergenFont);
                d.setSpacingBefore(2);
                doc.add(d);
            }

            // ── 5. Options section (after description — price choices last) ────
            if (!variants.isEmpty()) {
                // "OPTIONS" label
                Paragraph optLabel = new Paragraph(spacedCaps("Options"), optLabelFont);
                optLabel.setSpacingBefore(9);
                doc.add(optLabel);
                // thin saffron rule
                doc.add(new Chunk(new LineSeparator(0.5f, 40, SAFFRON, Element.ALIGN_LEFT, 0)));

                if (varPrices) {
                    for (VariantEntry v : variants) {
                        PdfPTable vRow = new PdfPTable(2);
                        vRow.setWidthPercentage(100);
                        try { vRow.setWidths(new float[]{6.4f, 1.6f}); } catch (DocumentException ignored) {}

                        PdfPCell vnc = new PdfPCell(new Phrase(v.name(), varNameFont));
                        vnc.setBorder(Rectangle.NO_BORDER);
                        vnc.setPaddingTop(4);
                        vnc.setPaddingLeft(6);
                        vRow.addCell(vnc);

                        BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                        PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPriceFont));
                        vpc.setBorder(Rectangle.NO_BORDER);
                        vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        vpc.setNoWrap(true);
                        vpc.setPaddingTop(4);
                        vRow.addCell(vpc);
                        doc.add(vRow);
                    }
                } else {
                    Paragraph vLine = new Paragraph(variantNamesLine(variants), varSameFont);
                    vLine.setSpacingBefore(4);
                    doc.add(vLine);
                }
            }

            // ── Item separator ────────────────────────────────────────────────
            if (i < items.size() - 1) {
                Paragraph pad = new Paragraph(" ");
                pad.setSpacingBefore(10);
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

        // ── Fonts ────────────────────────────────────────────────────────────
        Font nameFont      = font(SERIF_BOLD,   12.5f, INK);
        Font portionFont   = font(SANS_REG,      8.5f, MUTED);
        Font priceFont     = font(SANS_BOLD,    11.5f, SAFFRON_DEEP);
        Font pillFont      = font(SANS_BOLD,     7,    SAFFRON_DEEP);
        Font descFont      = font(SERIF_ITALIC,  9.5f, MUTED);
        Font tagsFont      = font(SANS_ITALIC,   8,    MUTED);
        Font optLabelFont  = font(SANS_BOLD,     6.5f, SAFFRON_DEEP);
        Font varNameFont   = font(SANS_REG,      9f,   INK_SOFT);
        Font varPriceFont  = font(SANS_BOLD,     9f,   SAFFRON_DEEP);
        Font varSameFont   = font(SANS_ITALIC,   8.5f, MUTED);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item       = items.get(i);
            List<VariantEntry> variants = parseVariants(item);
            boolean varPrices   = showPrices && hasVariantPrices(variants);
            boolean showBasePrice = showPrices && !varPrices;

            // ── 1. Featured pill ──────────────────────────────────────────────
            if (item.isFeatured()) {
                PdfPCell p = new PdfPCell(new Phrase(spacedCaps("Chef's signature"), pillFont));
                p.setBorder(Rectangle.NO_BORDER);
                col.addCell(p);
            }

            // ── 2. Name row + base price ──────────────────────────────────────
            PdfPTable head = new PdfPTable(showBasePrice ? 2 : 1);
            head.setWidthPercentage(100);
            try { if (showBasePrice) head.setWidths(new float[]{4.6f, 2.4f}); }
            catch (DocumentException ignored) {}
            head.addCell(textCell(namePhrase(item, nameFont, portionFont), Element.ALIGN_LEFT));
            if (showBasePrice) {
                PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
                pc.setBorder(Rectangle.NO_BORDER);
                pc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                pc.setNoWrap(true);
                head.addCell(pc);
            }
            PdfPCell headWrap = new PdfPCell(head);
            headWrap.setBorder(Rectangle.NO_BORDER);
            col.addCell(headWrap);

            // ── 3. Description first ──────────────────────────────────────────
            String desc = chooseDescription(item);
            if (desc != null) {
                PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
                d.setBorder(Rectangle.NO_BORDER);
                d.setPaddingTop(2);
                d.setPaddingBottom(2);
                d.setLeading(0, 1.3f);
                col.addCell(d);
            }

            // ── 4. Dietary ────────────────────────────────────────────────────
            String dietary = renderDietary(item);
            if (dietary != null) {
                PdfPCell c = new PdfPCell(new Phrase(dietary, tagsFont));
                c.setBorder(Rectangle.NO_BORDER);
                c.setPaddingBottom(2);
                col.addCell(c);
            }

            // ── 5. Options after description ──────────────────────────────────
            if (!variants.isEmpty()) {
                PdfPCell optLabel = new PdfPCell(new Phrase(spacedCaps("Options"), optLabelFont));
                optLabel.setBorder(Rectangle.NO_BORDER);
                optLabel.setPaddingTop(5);
                optLabel.setPaddingBottom(2);
                col.addCell(optLabel);

                if (varPrices) {
                    for (VariantEntry v : variants) {
                        PdfPTable vRow = new PdfPTable(2);
                        vRow.setWidthPercentage(100);
                        try { vRow.setWidths(new float[]{4.6f, 2.4f}); } catch (DocumentException ignored) {}
                        PdfPCell vnc = new PdfPCell(new Phrase(v.name(), varNameFont));
                        vnc.setBorder(Rectangle.NO_BORDER);
                        vnc.setPaddingLeft(4);
                        vRow.addCell(vnc);
                        BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                        PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPriceFont));
                        vpc.setBorder(Rectangle.NO_BORDER);
                        vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        vpc.setNoWrap(true);
                        vRow.addCell(vpc);
                        PdfPCell vWrap = new PdfPCell(vRow);
                        vWrap.setBorder(Rectangle.NO_BORDER);
                        col.addCell(vWrap);
                    }
                } else {
                    PdfPCell vLine = new PdfPCell(new Phrase(variantNamesLine(variants), varSameFont));
                    vLine.setBorder(Rectangle.NO_BORDER);
                    col.addCell(vLine);
                }
            }

            // ── Item gap ──────────────────────────────────────────────────────
            if (i < items.size() - 1) {
                PdfPCell pad = new PdfPCell(new Phrase(" "));
                pad.setBorder(Rectangle.NO_BORDER);
                pad.setFixedHeight(16f);
                col.addCell(pad);
            }
        }
        return col;
    }

    // ---------- Allergens ----------

    private void drawAllergens(Document doc) throws DocumentException {
        Font eyebrow = font(SANS_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = font(SERIF_BOLD, 30, INK);
        Font key = font(SANS_BOLD, 10.5f, INK);
        Font keyDesc = font(SANS_REG, 10, MUTED);
        Font body = font(SANS_REG, 10.5f, MUTED);

        Paragraph eb = new Paragraph(spacedCaps("How to read this menu"), eyebrow);
        eb.setAlignment(Element.ALIGN_CENTER);
        doc.add(eb);

        Paragraph h = new Paragraph("Allergens & advisories", head);
        h.setAlignment(Element.ALIGN_CENTER);
        h.setSpacingBefore(6);
        h.setSpacingAfter(0);
        doc.add(h);

        Paragraph ruleSpacer = new Paragraph(" ");
        ruleSpacer.setSpacingBefore(8);
        doc.add(ruleSpacer);
        LineSeparator sep = new LineSeparator(1.5f, 18, SAFFRON, Element.ALIGN_CENTER, 0);
        doc.add(new Chunk(sep));

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
                font(SERIF_BOLD, 13, INK));
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

    // ---------- Closing (centered, welcoming) ----------

    private void drawClosing(Document doc, PdfWriter writer, Options opt) throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();
        float w = page.getWidth(), h = page.getHeight();
        float cx = w / 2f;

        float inset = 36f;
        drawThinFrame(cb, page, inset, SAFFRON, 0.55f);
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.25f);

        Font eyebrow = font(SANS_BOLD, 9, SAFFRON_DEEP);
        Font hero = font(SERIF_BOLD, 56, INK);
        Font subItalic = font(SERIF_ITALIC, 16, MUTED);
        Font addr = font(SANS_REG, 9.5f, INK);
        Font year = font(SANS_REG, 8.5f, MUTED);

        showCentered(cb, spacedCaps("Until we see you again"), eyebrow,
                cx, h - inset - 80);

        showCentered(cb, "Çox sağ olun", hero, cx, h * 0.60f);
        diamondRule(cb, cx - 45f, h * 0.60f - 30, 90f);
        showCentered(cb, "Thank you for dining with us.", subItalic,
                cx, h * 0.60f - 56);

        if (opt.contactBlock() != null && !opt.contactBlock().isBlank()) {
            String[] lines = opt.contactBlock().trim().split("\\r?\\n");
            float y = h * 0.42f;
            for (String line : lines) {
                showCentered(cb, line, addr, cx, y);
                y -= 16f;
            }
        }

        showCentered(cb, spacedCaps("Menu · " + LocalDate.now().format(MENU_DATE)),
                year, cx, inset + 50);
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

    /** Two short rules with a small diamond between them — the only ornament. */
    private void diamondRule(PdfContentByte cb, float anchorX, float cy, float armLen) {
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setColorFill(SAFFRON);
        cb.setLineWidth(0.6f);
        float startX = anchorX;
        cb.moveTo(startX, cy);
        cb.lineTo(startX + armLen / 2f - 8, cy);
        cb.moveTo(startX + armLen / 2f + 8, cy);
        cb.lineTo(startX + armLen, cy);
        cb.stroke();
        float mx = startX + armLen / 2f;
        cb.moveTo(mx, cy + 3.5f);
        cb.lineTo(mx + 3.5f, cy);
        cb.lineTo(mx, cy - 3.5f);
        cb.lineTo(mx - 3.5f, cy);
        cb.closePathFillStroke();
        cb.restoreState();
    }

    private void showCentered(PdfContentByte cb, String text, Font font, float cx, float baselineY) {
        try {
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(text, font), cx, baselineY, 0);
        } catch (Exception ignored) {}
    }

    // ---------- Helpers ----------

    private static String azFor(String englishName) {
        if (englishName == null) return null;
        String key = englishName.trim().toLowerCase(Locale.ROOT);
        return CATEGORY_TRANSLATIONS.get(key);
    }

    /**
     * Inviting, plain-English eyebrow per category. Replaces the editorial
     * "SECTION 02" label with something a guest can read at a glance.
     */
    private static String eyebrowFor(String name) {
        if (name == null) return "From our menu";
        String key = name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "starters", "appetisers", "appetizers" -> "To begin";
            case "salads" -> "Fresh & vibrant";
            case "soups" -> "To warm";
            case "mains", "main courses" -> "From the kitchen";
            case "plov", "plov & rice" -> "The national dish";
            case "kebabs", "kebab", "grill" -> "From the charcoal";
            case "sides" -> "Alongside";
            case "breads" -> "Freshly baked";
            case "desserts", "sweets" -> "Sweet endings";
            case "drinks", "beverages" -> "To drink";
            case "tea" -> "At the end of the meal";
            case "hot drinks" -> "Warm drinks";
            case "cold drinks" -> "Refreshments";
            case "wine" -> "Wine list";
            case "beer" -> "Beers";
            case "cocktails" -> "Cocktails";
            default -> "From our menu";
        };
    }

    private static String blurbFor(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "starters", "appetisers", "appetizers" ->
                    "Small plates to open the meal — eaten slowly, ideally with bread, while the table is being set.";
            case "salads" ->
                    "Fresh herbs, sumac, walnuts and pomegranate — the everyday Azerbaijani table at its most generous.";
            case "soups" ->
                    "Slow-cooked broths and yoghurt soups — what grandmothers in Şəki call medicine and what we call lunch.";
            case "mains", "main courses" ->
                    "Plov, kebabs, slow-braised lamb. Dishes that take their time, and reward yours.";
            case "plov", "plov & rice" ->
                    "The crown of Azerbaijani cuisine — saffron-stained rice with lamb, chestnuts, dried apricot and herbs.";
            case "kebabs", "kebab", "grill" ->
                    "Charcoal-grilled lamb, chicken and sturgeon — marinated overnight, served with sumac and onion.";
            case "sides" ->
                    "Pickles, herbs, breads — the side stage where the main dishes meet, and where the table fills up.";
            case "breads" -> "Tandir-baked, torn and shared — never sliced.";
            case "desserts", "sweets" ->
                    "Pakhlava, şəkərbura, halva — pastries that taste of holidays, weddings and patience.";
            case "drinks", "beverages" -> "Şərbət, ayran, compote, tea — pairings for every season and every dish.";
            case "tea" -> "Loose-leaf, in armudu glasses — refilled until you tell us to stop.";
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

    // ── Variant & portion helpers ─────────────────────────────────────────────

    private record VariantEntry(String name, BigDecimal price) {}

    private static final ObjectMapper VARIANT_MAPPER = new ObjectMapper();

    private static List<VariantEntry> parseVariants(MenuItem item) {
        String json = item.getVariants();
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode arr = VARIANT_MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();
            List<VariantEntry> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String name = node.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                JsonNode pNode = node.path("price");
                BigDecimal price = (!pNode.isMissingNode() && !pNode.isNull())
                        ? new BigDecimal(pNode.asText()) : null;
                out.add(new VariantEntry(name.trim(), price));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** True if at least one variant carries its own price. */
    private static boolean hasVariantPrices(List<VariantEntry> variants) {
        return variants.stream().anyMatch(v -> v.price() != null);
    }

    /**
     * Mixed phrase: dish name in bold serif + portion size in small muted sans.
     * e.g. "Lamb Plov" [14pt bold] + "  500g" [9pt muted regular]
     */
    private static Phrase namePhrase(MenuItem item, Font nameFont, Font portionFont) {
        Phrase p = new Phrase(item.getName(), nameFont);
        String ps = item.getPortionSize();
        if (ps != null && !ps.isBlank()) {
            p.add(new Chunk("   " + ps.trim(), portionFont));
        }
        return p;
    }

    /** "Small  ·  Regular" — used when all variants share the parent's price. */
    private static String variantNamesLine(List<VariantEntry> variants) {
        return variants.stream().map(VariantEntry::name).collect(Collectors.joining("  ·  "));
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
     * Body pages get a single piece of chrome: a small page number centred
     * at the bottom in a muted serif. The previous rotated section label on
     * the outer edge was a designer flourish that diners found confusing —
     * gone now in favour of restraint.
     *
     * <p>Cover and closing pages opt out by calling {@link #suppressNext}
     * before their first paint.</p>
     */
    private static class MenuChrome extends PdfPageEventHelper {
        private boolean suppressNext = false;

        void suppressNext() { this.suppressNext = true; }
        // No-op retained so the build pipeline can keep setting a label
        // without having to special-case the new chrome.
        void setSection(@SuppressWarnings("unused") String s) {}

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            if (suppressNext) { suppressNext = false; return; }
            int p = writer.getPageNumber();
            if (p <= 1) return;

            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = doc.getPageSize();

            try {
                Font fNum = font(SERIF_REG, 9.5f, MUTED);
                ColumnText.showTextAligned(
                        cb, Element.ALIGN_CENTER, new Phrase(String.valueOf(p), fNum),
                        page.getWidth() / 2f, 38, 0);
            } catch (Exception ignored) {}
        }
    }
}
