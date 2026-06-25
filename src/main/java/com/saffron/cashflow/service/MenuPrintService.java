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

    private static final Color INK          = new Color(0x1A, 0x18, 0x14);
    private static final Color INK_SOFT     = new Color(0x3A, 0x33, 0x29);
    private static final Color SAFFRON      = new Color(0xC9, 0x6A, 0x1A);
    private static final Color SAFFRON_DEEP = new Color(0xA4, 0x52, 0x12);
    private static final Color CREAM        = new Color(0xFA, 0xF5, 0xEB);
    private static final Color CARD_BG      = new Color(0xFF, 0xFF, 0xFF);
    private static final Color MUTED        = new Color(0x7A, 0x6B, 0x5A);
    private static final Color HAIRLINE     = new Color(0xDF, 0xD8, 0xCC);
    private static final Color SAFFRON_TINT = new Color(0xF5, 0xE8, 0xD5);
    private static final Color PRICE_COLOR  = new Color(0x7A, 0x48, 0x18);

    // ---------- Dark theme ----------
    private static final Color DARK_PAGE  = new Color(0x18, 0x14, 0x10);
    private static final Color DARK_INK   = new Color(0xED, 0xE4, 0xD0);
    private static final Color DARK_SOFT  = new Color(0xC5, 0xB8, 0x98);
    private static final Color DARK_MUTED = new Color(0x80, 0x72, 0x58);
    private static final Color DARK_SAF   = new Color(0xD8, 0x8A, 0x2C);
    private static final Color DARK_GOLD  = new Color(0xE2, 0xB8, 0x58);
    private static final Color DARK_LINE  = new Color(0x3C, 0x2E, 0x1E);

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

        Document doc = new Document(PageSize.A4, 50, 50, 64, 64);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            MenuChrome chrome = new MenuChrome();
            writer.setPageEvent(chrome);
            doc.open();

            boolean dark = opt.layout() == Layout.DARK;
            if (dark) chrome.setTheme(DARK_PAGE, DARK_SAF, DARK_MUTED);

            chrome.suppressNext();
            if (dark) drawDarkCover(doc, writer, title, subtitle);
            else      drawCover(doc, writer, title, subtitle);

            if (!dark) {
                doc.newPage();
                drawStory(doc, writer, opt);
                doc.newPage();
                drawHeritage(doc);
            }

            for (int ci = 0; ci < categories.size(); ci++) {
                MenuCategory cat     = categories.get(ci);
                List<MenuItem> items = itemsByCategory.get(cat.getId());
                if (items == null || items.isEmpty()) continue;

                float avail = ci == 0 ? 0 : writer.getVerticalPosition(false) - doc.bottomMargin();
                boolean freshPage = ci == 0 || avail < 260f;
                if (freshPage) doc.newPage();

                if (dark) {
                    if (freshPage) drawDarkSectionDivider(doc, cat.getName());
                    else           drawDarkCompactDivider(doc, cat.getName());
                } else if (opt.layout() == Layout.BOLD) {
                    drawBoldSectionHeader(doc, cat.getName());
                } else {
                    if (freshPage) drawSectionDivider(doc, cat.getName());
                    else           drawCompactSectionDivider(doc, cat.getName());
                }

                boolean hasVisualContent = items.stream().anyMatch(
                        i -> (i.getImagePath() != null && !i.getImagePath().isBlank())
                             || chooseDescription(i) != null);
                Layout effectiveLayout = (opt.layout() == Layout.GRID && !hasVisualContent)
                        ? Layout.LIST : opt.layout();

                switch (effectiveLayout) {
                    case GRID    -> drawGrid(doc, items, opt.showPrices(), opt.locale());
                    case LIST    -> drawList(doc, items, opt.showPrices(), opt.locale());
                    case COMPACT -> drawCompact(doc, items, opt.showPrices(), opt.locale());
                    case FINE    -> drawFine(doc, items, opt.showPrices(), opt.locale());
                    case TASTING -> drawTasting(doc, writer, items, opt.showPrices(), opt.locale());
                    case DARK    -> drawDarkList(doc, items, opt.showPrices(), opt.locale());
                    case BOLD    -> drawBoldList(doc, items, opt.showPrices(), opt.locale());
                    case COLUMNS -> drawColumns(doc, items, opt.showPrices(), opt.locale());
                }
            }

            if (!dark) {
                doc.newPage();
                drawAllergens(doc);
            }

            doc.newPage();
            chrome.suppressNext();
            if (dark) drawDarkClosing(doc, writer, opt);
            else      drawClosing(doc, writer, opt);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to build menu PDF: " + e.getMessage(), e);
        }
    }

    public enum Layout {
        GRID, LIST, COMPACT, FINE, TASTING, DARK, BOLD, COLUMNS;
        public static Layout from(String key) {
            if (key == null) return GRID;
            return switch (key.trim().toLowerCase(Locale.ROOT)) {
                case "list"    -> LIST;
                case "compact" -> COMPACT;
                case "fine"    -> FINE;
                case "tasting" -> TASTING;
                case "dark"    -> DARK;
                case "bold"    -> BOLD;
                case "columns" -> COLUMNS;
                default        -> GRID;
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
    /**
     * Redesigned cover — refined vertical composition.
     *
     * <p>Key changes from the original:
     * <ul>
     *   <li>Brand name reduced from 108pt to 84pt — heavy display type is
     *       reserved for stone-carved signage, not paper menus.</li>
     *   <li>Brand name raised to 62% height — golden-ratio focal point.</li>
     *   <li>Decorative horizontal rules above and below the title block give
     *       the page structure without cluttering it.</li>
     *   <li>A secondary identity line ("Warszawa · Poland") sits in the lower
     *       third, anchoring the bottom of the composition.</li>
     *   <li>Three frame lines instead of two: outer border (saffron 0.55pt),
     *       middle (saffron 0.2pt, 8pt inset), inner accent (saffron 0.1pt,
     *       16pt inset) creates a layered frame that feels handcrafted.</li>
     * </ul></p>
     */
    private void drawCover(Document doc, PdfWriter writer, String title, String subtitle)
            throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page   = doc.getPageSize();
        float w = page.getWidth(), h = page.getHeight();
        float cx = w / 2f;

        // ── Three-layer border ────────────────────────────────────────────────
        float inset = 34f;
        drawThinFrame(cb, page, inset,      SAFFRON,      0.6f);
        drawThinFrame(cb, page, inset + 8,  SAFFRON,      0.2f);
        drawThinFrame(cb, page, inset + 18, SAFFRON_TINT, 0.4f);

        // ── Fonts ─────────────────────────────────────────────────────────────
        Font eyebrow   = font(SANS_BOLD,   8.5f, SAFFRON_DEEP);
        Font brand     = font(SERIF_BOLD,  84,   INK);           // 84pt — elegant, not heavy
        Font sub       = font(SERIF_ITALIC,15,   MUTED);
        Font locLabel  = font(SANS_REG,    8.5f, MUTED);
        Font menuLabel = font(SANS_BOLD,   8.5f, MUTED);
        Font foot      = font(SANS_REG,    7.5f, new Color(0xB0, 0xA8, 0x9C));

        // ── Top: cuisine eyebrow ──────────────────────────────────────────────
        float topY = h - inset - 55f;
        showCentered(cb, spacedCaps("Azerbaijani cuisine  ·  Warszawa"), eyebrow, cx, topY);

        // ── Upper decorative rule (frames the title from above) ───────────────
        float titleY = h * 0.62f;          // golden-ratio focal point
        drawDecorativeLine(cb, cx, titleY + 88f, 110f);

        // ── Brand name ────────────────────────────────────────────────────────
        showCentered(cb, title, brand, cx, titleY);

        // ── Diamond ornament ──────────────────────────────────────────────────
        diamondRule(cb, cx - 42f, titleY - 34f, 84f);

        // ── Subtitle ─────────────────────────────────────────────────────────
        showCentered(cb, subtitle, sub, cx, titleY - 60f);

        // ── Lower decorative rule (closes the title block) ────────────────────
        drawDecorativeLine(cb, cx, titleY - 88f, 110f);

        // ── Lower-third anchor: location ─────────────────────────────────────
        float midY = h * 0.28f;
        showCentered(cb, "Warszawa  ·  Poland", locLabel, cx, midY);
        // very short saffron rule above the location text
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(0.5f);
        cb.moveTo(cx - 18f, midY + 14f);
        cb.lineTo(cx + 18f, midY + 14f);
        cb.stroke();
        cb.restoreState();

        // ── Footer: date ──────────────────────────────────────────────────────
        showCentered(cb,
                spacedCaps("Menu  ·  " + LocalDate.now().format(MENU_DATE)),
                menuLabel, cx, inset + 50f);
        showCentered(cb, "Saffron Restaurant", foot, cx, inset + 33f);
    }

    /**
     * Short double-line decorative rule:  ──────── ◇ ────────
     * Used above and below the cover title block.
     */
    private void drawDecorativeLine(PdfContentByte cb, float cx, float y, float totalLen) {
        drawDecorativeLine(cb, cx, y, totalLen, SAFFRON);
    }

    private void drawDecorativeLine(PdfContentByte cb, float cx, float y, float totalLen, Color color) {
        float gap = 6f;
        cb.saveState();
        cb.setColorStroke(color); cb.setColorFill(color); cb.setLineWidth(0.5f);
        cb.moveTo(cx - totalLen / 2f, y); cb.lineTo(cx - gap, y);
        cb.moveTo(cx + gap, y);           cb.lineTo(cx + totalLen / 2f, y);
        cb.stroke();
        cb.moveTo(cx, y + 2.5f); cb.lineTo(cx + 2.5f, y);
        cb.lineTo(cx, y - 2.5f); cb.lineTo(cx - 2.5f, y);
        cb.closePathFillStroke();
        cb.restoreState();
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

            LineSeparator hair = new LineSeparator(0.6f, 38, SAFFRON, Element.ALIGN_LEFT, 0);
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
    /**
     * Full section divider — used for the first category and whenever the
     * available page space is too small for a compact divider.
     *
     * <p>Redesigned hierarchy: eyebrow → short decorative rule → large name
     * → translation → blurb. The decorative rule is placed BETWEEN the eyebrow
     * and the category name (not after), so the name reads as the centrepiece.
     * The blurb font matches the body text on item cards for consistency.</p>
     */
    private void drawSectionDivider(Document doc, String name) throws DocumentException {
        Font eyebrow  = font(SANS_BOLD,    8f,   SAFFRON_DEEP);
        Font head     = font(SERIF_BOLD,   22,   INK);
        Font az       = font(SERIF_ITALIC, 11.5f, MUTED);
        Font blurb    = font(SERIF_ITALIC, 10f,  MUTED);

        // Eyebrow label
        Paragraph eb = new Paragraph(spacedCaps(eyebrowFor(name)), eyebrow);
        eb.setAlignment(Element.ALIGN_CENTER);
        doc.add(eb);

        // Short saffron rule between eyebrow and heading
        Paragraph ruleSpace = new Paragraph(" ");
        ruleSpace.setSpacingBefore(6);
        doc.add(ruleSpace);
        doc.add(new Chunk(new LineSeparator(1f, 16, SAFFRON, Element.ALIGN_CENTER, 0)));

        // Category heading — the hero of the section page
        Paragraph h = new Paragraph(name, head);
        h.setAlignment(Element.ALIGN_CENTER);
        h.setSpacingBefore(10);
        h.setSpacingAfter(0);
        doc.add(h);

        // Azerbaijani translation
        String azName = azFor(name);
        if (azName != null) {
            Paragraph azP = new Paragraph(azName, az);
            azP.setAlignment(Element.ALIGN_CENTER);
            azP.setSpacingBefore(4);
            doc.add(azP);
        }

        // Long decorative rule below heading block
        Paragraph longRuleSpace = new Paragraph(" ");
        longRuleSpace.setSpacingBefore(10);
        doc.add(longRuleSpace);
        doc.add(new Chunk(new LineSeparator(0.4f, 100, HAIRLINE, Element.ALIGN_CENTER, 0)));

        // Category blurb — smaller, centred, adds atmosphere
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
        after.setSpacingAfter(8);
        doc.add(after);
    }

    /**
     * Compact in-flow section header used for every category after the first.
     *
     * <p>The full {@link #drawSectionDivider} takes ~170pt of vertical space —
     * too much when a short category (4 items) follows directly on the same page.
     * This compact version takes ~70pt: a thin hairline, the category name in a
     * medium serif, an optional Azerbaijani subtitle, and a short saffron rule.
     * No eyebrow, no blurb. iText will automatically page-break this header if
     * it does not fit the remaining space.</p>
     */
    private void drawCompactSectionDivider(Document doc, String name) throws DocumentException {
        Font head    = font(SERIF_BOLD,    16,    INK);
        Font azFont  = font(SERIF_ITALIC,  10,    MUTED);
        Font eyebrow = font(SANS_BOLD,      7.5f, SAFFRON_DEEP);

        // Wide hairline spacer — clear visual break between previous section and this one
        Paragraph preSpacer = new Paragraph(" ");
        preSpacer.setSpacingBefore(14);
        doc.add(preSpacer);
        doc.add(new Chunk(new LineSeparator(0.5f, 100, HAIRLINE, Element.ALIGN_LEFT, 0)));

        // Compact eyebrow (e.g. "FRESH & VIBRANT")
        Paragraph eb = new Paragraph(spacedCaps(eyebrowFor(name)), eyebrow);
        eb.setSpacingBefore(8);
        doc.add(eb);

        // Category name — left-aligned, reads like a chapter title
        Paragraph h = new Paragraph(name, head);
        h.setSpacingBefore(4);
        h.setSpacingAfter(0);
        // keepWithNext not available in this iText version — rely on spacing to stay together
        doc.add(h);

        // Optional Azerbaijani translation
        String az = azFor(name);
        if (az != null) {
            Paragraph azP = new Paragraph(az, azFont);
            azP.setSpacingBefore(3);
            doc.add(azP);
        }

        // Short saffron rule — visual closure under the heading
        doc.add(new Chunk(new LineSeparator(1.2f, 32, SAFFRON, Element.ALIGN_LEFT, 0)));

        Paragraph post = new Paragraph(" ");
        post.setSpacingAfter(5);
        doc.add(post);
    }

    // ---------- GRID — 3 columns × 3 rows per page ----------

    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS_PER_PAGE = 3;
    private static final int GRID_PAGE_SIZE = GRID_COLS * GRID_ROWS_PER_PAGE; // 9

    private void drawGrid(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        // Chunk into pages of 9 (3 cols × 3 rows). Each chunk gets its own
        // table so we can force a page break between them.
        int total = items.size();
        for (int pageStart = 0; pageStart < total; pageStart += GRID_PAGE_SIZE) {
            if (pageStart > 0) doc.newPage();

            List<MenuItem> chunk = items.subList(pageStart, Math.min(pageStart + GRID_PAGE_SIZE, total));

            PdfPTable table = new PdfPTable(GRID_COLS);
            table.setWidthPercentage(100);
            table.setSpacingBefore(8);
            table.setSplitLate(false);
            table.setSplitRows(false);
            table.getDefaultCell().setBorder(Rectangle.NO_BORDER);
            // Equal-width columns
            float[] colWidths = new float[GRID_COLS];
            java.util.Arrays.fill(colWidths, 1f);
            try { table.setWidths(colWidths); } catch (DocumentException ignored) {}

            for (MenuItem item : chunk) {
                table.addCell(gridCard(item, showPrices, locale, true));
            }
            // Fill the last row with blank cells so the table stays aligned
            int remainder = chunk.size() % GRID_COLS;
            if (remainder != 0) {
                for (int f = remainder; f < GRID_COLS; f++) {
                    PdfPCell filler = new PdfPCell();
                    filler.setBorder(Rectangle.NO_BORDER);
                    filler.setPaddingRight(10);
                    filler.setPaddingBottom(16);
                    table.addCell(filler);
                }
            }
            doc.add(table);
        }
    }

    /**
     * Grid card — two distinct visual treatments depending on whether the item
     * has a food photo:
     *
     * <p><b>Visual card (photo exists):</b><br>
     * Full-bleed cover photo (170pt) → 2pt saffron stripe → white content area
     * with name, description, options. Clean photo-first composition.</p>
     *
     * <p><b>Editorial card (no photo):</b><br>
     * No empty placeholder — pure typography. A 4pt saffron left-border accent
     * (like a bookmark ribbon) distinguishes it from the background. Name at
     * 15pt (larger, fills the visual weight lost by no photo). Generous padding.
     * Inspired by how fine-dining menus handle photo-less items — the dish name
     * IS the visual.</p>
     *
     * <p>Cover scaling (Math.max) fills the photo cell without letterboxing.</p>
     */
    private PdfPCell gridCard(MenuItem item, boolean showPrices, Locale locale, boolean threeCol) {
        Image img     = tryLoadImage(item.getImagePath());
        boolean hasImg = img != null;

        // Scale factor for narrower 3-column cards
        float fs = threeCol ? 0.85f : 1f;

        // ── Fonts (shared by both card types) ─────────────────────────────────
        Font portionFont  = font(SANS_REG,     8.5f  * fs, MUTED);
        Font priceFont    = font(SANS_REG,    (hasImg ? 10.5f : 11.5f) * fs, PRICE_COLOR);
        Font pillFont     = font(SANS_BOLD,    7f    * fs, SAFFRON_DEEP);
        Font descFont     = font(SERIF_ITALIC,(hasImg ? 9.5f  : 10.5f) * fs, MUTED);
        Font tagsFont     = font(SANS_ITALIC,  8f    * fs, MUTED);
        Font allergenFont = font(SANS_REG,     7.5f  * fs, MUTED);
        Font optLabelFont = font(SANS_BOLD,    7f    * fs, SAFFRON_DEEP);
        Font varNameFont  = font(SANS_REG,     9.5f  * fs, INK_SOFT);
        Font varPriceFont = font(SANS_REG,     9f    * fs, PRICE_COLOR);
        Font varSameFont  = font(SANS_ITALIC,  9f    * fs, MUTED);
        // Name is larger on editorial cards — typography is the visual
        Font nameFont     = font(SERIF_BOLD,  (hasImg ? 13.5f : 15f) * fs, INK);

        List<VariantEntry> variants  = parseVariants(item);
        boolean varPrices            = showPrices && hasVariantPrices(variants);
        boolean showBasePrice        = showPrices && !varPrices;

        // Outer wrapper — gutter between cards (NO border — cards float on cream)
        PdfPCell wrap = new PdfPCell();
        wrap.setBorder(Rectangle.NO_BORDER);
        wrap.setPadding(0);
        wrap.setPaddingBottom(18);
        wrap.setPaddingRight(14);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        if (hasImg) {
            // ══════════════════════════════════════════════════════════════════
            // VISUAL CARD: photo → saffron stripe → white content
            // ══════════════════════════════════════════════════════════════════
            final float CARD_W = threeCol ? 147f : 221f, PHOTO_H = threeCol ? 110f : 170f;
            float scaleX = CARD_W / img.getWidth();
            float scaleY = PHOTO_H / img.getHeight();
            float scale  = Math.max(scaleX, scaleY);        // cover, not fit
            img.scaleAbsolute(img.getWidth() * scale, img.getHeight() * scale);
            img.setAlignment(Image.ALIGN_CENTER | Image.ALIGN_MIDDLE);

            PdfPCell photoCell = new PdfPCell();
            photoCell.setBorder(Rectangle.NO_BORDER);
            photoCell.setFixedHeight(PHOTO_H);
            photoCell.setPadding(0);
            photoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            photoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            photoCell.setImage(img);
            card.addCell(photoCell);

            // 2pt saffron photo/content separator
            PdfPCell stripe = new PdfPCell();
            stripe.setFixedHeight(2f);
            stripe.setBackgroundColor(SAFFRON);
            stripe.setBorder(Rectangle.NO_BORDER);
            card.addCell(stripe);

        } else {
            // ══════════════════════════════════════════════════════════════════
            // EDITORIAL CARD: saffron-tint top band + white card body
            // A warm 24pt band at the top acts as a colour accent, replacing
            // the photo area. Much lighter than the 90pt placeholder.
            // ══════════════════════════════════════════════════════════════════
            PdfPCell band = new PdfPCell();
            band.setFixedHeight(24f);
            band.setBackgroundColor(SAFFRON_TINT);
            band.setBorder(Rectangle.NO_BORDER);
            card.addCell(band);

            // 2pt saffron stripe (mirrors image card — visual consistency)
            PdfPCell stripe = new PdfPCell();
            stripe.setFixedHeight(2f);
            stripe.setBackgroundColor(SAFFRON);
            stripe.setBorder(Rectangle.NO_BORDER);
            card.addCell(stripe);
        }

        // Remaining content sits on white background
        // ── Featured pill ─────────────────────────────────────────────────────
        if (item.isFeatured()) {
            PdfPCell pill = new PdfPCell(new Phrase(spacedCaps("Chef's signature"), pillFont));
            pill.setBorder(Rectangle.NO_BORDER);
            pill.setBackgroundColor(CARD_BG);
            pill.setPaddingTop(10);
            pill.setPaddingLeft(12);
            pill.setPaddingBottom(2);
            card.addCell(pill);
        }

        // ── Name + price ──────────────────────────────────────────────────────
        float namePad = hasImg ? 10f : 14f;

        PdfPTable nameRow = new PdfPTable(showBasePrice ? 2 : 1);
        nameRow.setWidthPercentage(100);
        try { if (showBasePrice) nameRow.setWidths(new float[]{5f, 2f}); }
        catch (DocumentException ignored) {}

        PdfPCell nameCell = new PdfPCell(namePhrase(item, nameFont, portionFont));
        nameCell.setBorder(Rectangle.NO_BORDER);
        nameCell.setBackgroundColor(CARD_BG);
        nameCell.setPaddingTop(item.isFeatured() ? 6 : namePad + 2);
        nameCell.setPaddingLeft(namePad + 2);
        nameCell.setPaddingRight(6);
        nameCell.setPaddingBottom(5);
        nameRow.addCell(nameCell);

        if (showBasePrice) {
            PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
            pc.setBorder(Rectangle.NO_BORDER);
            pc.setBackgroundColor(CARD_BG);
            pc.setHorizontalAlignment(Element.ALIGN_RIGHT);
            pc.setNoWrap(true);
            pc.setPaddingTop(item.isFeatured() ? 6 : namePad + 2);
            pc.setPaddingRight(namePad + 2);
            pc.setPaddingBottom(5);
            nameRow.addCell(pc);
        }

        PdfPCell nameWrap = new PdfPCell(nameRow);
        nameWrap.setBorder(Rectangle.NO_BORDER);
        nameWrap.setBackgroundColor(CARD_BG);
        card.addCell(nameWrap);

        // thin hairline under name
        PdfPCell hair = new PdfPCell();
        hair.setFixedHeight(0.4f);
        hair.setBackgroundColor(HAIRLINE);
        hair.setBorder(Rectangle.NO_BORDER);
        card.addCell(hair);

        // ── Description ───────────────────────────────────────────────────────
        String desc = chooseDescription(item);
        if (desc != null) {
            PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
            d.setBorder(Rectangle.NO_BORDER);
            d.setBackgroundColor(CARD_BG);
            d.setPaddingTop(8);
            d.setPaddingLeft(namePad + 2);
            d.setPaddingRight(namePad + 2);
            d.setPaddingBottom(4);
            d.setLeading(0, 1.35f);
            card.addCell(d);
        }

        // ── Dietary + allergens ───────────────────────────────────────────────
        String dietary = renderDietary(item);
        if (dietary != null) {
            PdfPCell c = new PdfPCell(new Phrase(dietary, tagsFont));
            c.setBorder(Rectangle.NO_BORDER);
            c.setBackgroundColor(CARD_BG);
            c.setPaddingTop(4);
            c.setPaddingLeft(namePad + 2);
            c.setPaddingBottom(2);
            card.addCell(c);
        }
        String allergen = renderAllergens(item);
        if (allergen != null) {
            PdfPCell c = new PdfPCell(new Phrase(allergen, allergenFont));
            c.setBorder(Rectangle.NO_BORDER);
            c.setBackgroundColor(CARD_BG);
            c.setPaddingTop(2);
            c.setPaddingLeft(namePad + 2);
            c.setPaddingBottom(4);
            card.addCell(c);
        }

        // ── Options ───────────────────────────────────────────────────────────
        if (!variants.isEmpty()) {
            PdfPCell optLabel = new PdfPCell(new Phrase(spacedCaps("Options"), optLabelFont));
            optLabel.setBorder(Rectangle.NO_BORDER);
            optLabel.setBackgroundColor(CARD_BG);
            optLabel.setPaddingTop(8);
            optLabel.setPaddingLeft(namePad + 2);
            optLabel.setPaddingBottom(3);
            card.addCell(optLabel);

            PdfPCell optRule = new PdfPCell();
            optRule.setFixedHeight(0.5f);
            optRule.setBackgroundColor(SAFFRON);
            optRule.setBorder(Rectangle.NO_BORDER);
            card.addCell(optRule);

            if (varPrices) {
                for (VariantEntry v : variants) {
                    PdfPTable vRow = new PdfPTable(2);
                    vRow.setWidthPercentage(100);
                    try { vRow.setWidths(new float[]{5f, 2f}); } catch (DocumentException ignored) {}

                    PdfPCell vnc = new PdfPCell(new Phrase(v.name(), varNameFont));
                    vnc.setBorder(Rectangle.NO_BORDER);
                    vnc.setBackgroundColor(CARD_BG);
                    vnc.setPaddingTop(3);
                    vnc.setPaddingLeft(namePad + 8);
                    vnc.setPaddingBottom(2);
                    vRow.addCell(vnc);

                    BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                    PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPriceFont));
                    vpc.setBorder(Rectangle.NO_BORDER);
                    vpc.setBackgroundColor(CARD_BG);
                    vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    vpc.setNoWrap(true);
                    vpc.setPaddingTop(3);
                    vpc.setPaddingRight(namePad + 2);
                    vpc.setPaddingBottom(2);
                    vRow.addCell(vpc);

                    PdfPCell vWrap = new PdfPCell(vRow);
                    vWrap.setBorder(Rectangle.NO_BORDER);
                    vWrap.setBackgroundColor(CARD_BG);
                    card.addCell(vWrap);
                }
            } else {
                PdfPCell vLine = new PdfPCell(new Phrase(variantNamesLine(variants), varSameFont));
                vLine.setBorder(Rectangle.NO_BORDER);
                vLine.setBackgroundColor(CARD_BG);
                vLine.setPaddingTop(5);
                vLine.setPaddingLeft(namePad + 8);
                vLine.setPaddingBottom(4);
                card.addCell(vLine);
            }
        }

        // bottom white breathing room
        PdfPCell foot = new PdfPCell(new Phrase(" "));
        foot.setBorder(Rectangle.NO_BORDER);
        foot.setBackgroundColor(CARD_BG);
        foot.setFixedHeight(12f);
        card.addCell(foot);

        wrap.addElement(card);
        return wrap;
    }

    // ---------- LIST ----------

    private void drawList(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        // ── Fonts ────────────────────────────────────────────────────────────
        Font nameFont      = font(SERIF_BOLD,   12.5f, INK);
        Font portionFont   = font(SANS_REG,      8.5f, MUTED);
        Font priceFont     = font(SANS_BOLD,    12.5f, INK_SOFT);
        Font pillFont      = font(SANS_BOLD,     7f,   SAFFRON_DEEP);
        Font descFont      = font(SERIF_ITALIC, 10.5f, MUTED);
        Font tagsFont      = font(SANS_ITALIC,   8.5f, MUTED);
        Font allergenFont  = font(SANS_REG,      7.5f, MUTED);
        Font optLabelFont  = font(SANS_BOLD,     7f,   MUTED);
        Font varNameFont   = font(SANS_REG,     10f,   INK_SOFT);
        Font varPriceFont  = font(SANS_BOLD,    10f,   INK_SOFT);
        Font varSameFont   = font(SANS_ITALIC,   9.5f, MUTED);

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
            head.setSpacingBefore(item.isFeatured() ? 2 : 4);
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
                Paragraph optLabel = new Paragraph(spacedCaps("Options"), optLabelFont);
                optLabel.setSpacingBefore(6);
                doc.add(optLabel);

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
                pad.setSpacingBefore(4);
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
        Font priceFont     = font(SANS_REG,     10.5f, PRICE_COLOR);  // quiet — food sells, price follows
        Font pillFont      = font(SANS_BOLD,     7,    SAFFRON_DEEP);
        Font descFont      = font(SERIF_ITALIC,  9.5f, MUTED);
        Font tagsFont      = font(SANS_ITALIC,   8,    MUTED);
        Font optLabelFont  = font(SANS_BOLD,     6.5f, MUTED);
        Font varNameFont   = font(SANS_REG,      9f,   INK_SOFT);
        Font varPriceFont  = font(SANS_REG,      8.5f, PRICE_COLOR);
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
                pad.setFixedHeight(8f);
                col.addCell(pad);
            }
        }
        return col;
    }

    // ---------- FINE — ultra-minimal, name + price only ----------

    /**
     * Fine-dining layout: each item is a single row — name on the left,
     * price right-aligned, a very thin hairline between rows. No photos,
     * no descriptions, no OPTIONS label. Variants listed as a muted
     * comma line directly under the name. Category headers are just small
     * muted spaced-caps labels with no decoration.
     */
    private void drawFine(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        Font nameFont    = font(SERIF_REG,   12f,  INK);
        Font portionFont = font(SANS_REG,     8f,  MUTED);
        Font priceFont   = font(SANS_BOLD,   12f,  INK_SOFT);
        Font varFont     = font(SANS_ITALIC,  9f,  MUTED);
        Font pillFont    = font(SANS_BOLD,    6.5f, SAFFRON_DEEP);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            List<VariantEntry> variants = parseVariants(item);
            boolean varPrices    = showPrices && hasVariantPrices(variants);
            boolean showBasePrice = showPrices && !varPrices;

            if (item.isFeatured()) {
                Paragraph pill = new Paragraph(spacedCaps("Chef's signature"), pillFont);
                pill.setSpacingBefore(i == 0 ? 0 : 3);
                doc.add(pill);
            }

            // Name + base price row
            PdfPTable row = new PdfPTable(showBasePrice ? 2 : 1);
            row.setWidthPercentage(100);
            try { if (showBasePrice) row.setWidths(new float[]{6f, 2f}); }
            catch (DocumentException ignored) {}
            row.setSpacingBefore(item.isFeatured() ? 2 : (i == 0 ? 0 : 2));

            PdfPCell nc = new PdfPCell(namePhrase(item, nameFont, portionFont));
            nc.setBorder(Rectangle.NO_BORDER);
            nc.setPaddingBottom(3);
            row.addCell(nc);

            if (showBasePrice) {
                PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
                pc.setBorder(Rectangle.NO_BORDER);
                pc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                pc.setNoWrap(true);
                pc.setPaddingBottom(3);
                row.addCell(pc);
            }
            doc.add(row);

            // Variant prices (two-column sub-rows)
            if (varPrices) {
                for (VariantEntry v : variants) {
                    PdfPTable vr = new PdfPTable(2);
                    vr.setWidthPercentage(100);
                    try { vr.setWidths(new float[]{6f, 2f}); } catch (DocumentException ignored) {}
                    PdfPCell vn = new PdfPCell(new Phrase("   " + v.name(), varFont));
                    vn.setBorder(Rectangle.NO_BORDER);
                    vn.setPaddingBottom(2);
                    vr.addCell(vn);
                    BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                    PdfPCell vp2 = new PdfPCell(new Phrase(formatPrice(vp, locale), varFont));
                    vp2.setBorder(Rectangle.NO_BORDER);
                    vp2.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    vp2.setNoWrap(true);
                    vp2.setPaddingBottom(2);
                    vr.addCell(vp2);
                    doc.add(vr);
                }
            } else if (!variants.isEmpty()) {
                Paragraph vl = new Paragraph("   " + variantNamesLine(variants), varFont);
                vl.setSpacingBefore(1);
                doc.add(vl);
            }

            // Thin hairline separator
            if (i < items.size() - 1) {
                Paragraph gap = new Paragraph(" ");
                gap.setSpacingBefore(2);
                doc.add(gap);
                doc.add(new Chunk(new LineSeparator(0.3f, 100, HAIRLINE, Element.ALIGN_LEFT, 0)));
            }
        }
    }

    // ---------- TASTING — numbered, centered, description-forward ----------

    /**
     * Tasting-menu layout: each item is centred on the page with a large
     * saffron index number, the dish name in display serif, description in
     * italic, and price right-aligned at the bottom of the block.
     * Wide diamond rules separate courses — spacious and editorial.
     */
    private void drawTasting(Document doc, PdfWriter writer, List<MenuItem> items,
                              boolean showPrices, Locale locale) throws DocumentException {
        Font numFont    = font(SANS_BOLD,   9f,   SAFFRON_DEEP);
        Font nameFont   = font(SERIF_BOLD,  19f,  INK);
        Font portFont   = font(SANS_REG,    9f,   MUTED);
        Font descFont   = font(SERIF_ITALIC,11f,  MUTED);
        Font priceFont  = font(SANS_BOLD,   11.5f, INK_SOFT);
        Font varLabel   = font(SANS_BOLD,   7f,   MUTED);
        Font varName    = font(SANS_REG,    10f,  INK_SOFT);
        Font varPrice   = font(SANS_BOLD,   10f,  INK_SOFT);
        Font pillFont   = font(SANS_BOLD,   6.5f, SAFFRON_DEEP);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            List<VariantEntry> variants = parseVariants(item);
            boolean varPrices     = showPrices && hasVariantPrices(variants);
            boolean showBasePrice = showPrices && !varPrices;

            // Course number
            Paragraph num = new Paragraph(String.format("%02d", i + 1), numFont);
            num.setAlignment(Element.ALIGN_CENTER);
            num.setSpacingBefore(i == 0 ? 4 : 28);
            doc.add(num);

            // Chef badge
            if (item.isFeatured()) {
                Paragraph pill = new Paragraph(spacedCaps("Chef's signature"), pillFont);
                pill.setAlignment(Element.ALIGN_CENTER);
                pill.setSpacingBefore(4);
                doc.add(pill);
            }

            // Dish name (centered)
            Phrase namePhr = namePhrase(item, nameFont, portFont);
            Paragraph namePara = new Paragraph(namePhr);
            namePara.setAlignment(Element.ALIGN_CENTER);
            namePara.setSpacingBefore(3);
            doc.add(namePara);

            // Description (centered italic)
            String desc = chooseDescription(item);
            if (desc != null) {
                Paragraph d = new Paragraph(desc, descFont);
                d.setAlignment(Element.ALIGN_CENTER);
                d.setLeading(15.5f);
                d.setSpacingBefore(6);
                d.setIndentationLeft(60f);
                d.setIndentationRight(60f);
                doc.add(d);
            }

            // Base price (right-aligned)
            if (showBasePrice) {
                Paragraph p = new Paragraph(formatPrice(item.getSellPrice(), locale), priceFont);
                p.setAlignment(Element.ALIGN_RIGHT);
                p.setSpacingBefore(5);
                doc.add(p);
            }

            // Variants
            if (!variants.isEmpty()) {
                Paragraph optHead = new Paragraph(spacedCaps("Options"), varLabel);
                optHead.setAlignment(Element.ALIGN_CENTER);
                optHead.setSpacingBefore(8);
                doc.add(optHead);

                if (varPrices) {
                    PdfPTable vt = new PdfPTable(2);
                    vt.setWidthPercentage(60);
                    vt.setHorizontalAlignment(Element.ALIGN_CENTER);
                    try { vt.setWidths(new float[]{3f, 1.5f}); } catch (DocumentException ignored) {}
                    for (VariantEntry v : variants) {
                        PdfPCell vnc = new PdfPCell(new Phrase(v.name(), varName));
                        vnc.setBorder(Rectangle.NO_BORDER);
                        vnc.setHorizontalAlignment(Element.ALIGN_CENTER);
                        vnc.setPaddingBottom(3);
                        vt.addCell(vnc);
                        BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                        PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPrice));
                        vpc.setBorder(Rectangle.NO_BORDER);
                        vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        vpc.setPaddingBottom(3);
                        vt.addCell(vpc);
                    }
                    doc.add(vt);
                } else {
                    Paragraph vl = new Paragraph(variantNamesLine(variants), descFont);
                    vl.setAlignment(Element.ALIGN_CENTER);
                    vl.setSpacingBefore(3);
                    doc.add(vl);
                }
            }

            // Diamond rule between courses
            if (i < items.size() - 1) {
                Paragraph gap = new Paragraph(" ");
                gap.setSpacingBefore(14);
                doc.add(gap);
                float cx = doc.getPageSize().getWidth() / 2f;
                float y  = writer.getVerticalPosition(true) - 6f;
                diamondRule(writer.getDirectContent(), cx - 45f, y, 90f);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DARK — ink background, cream text, golden prices
    // ══════════════════════════════════════════════════════════════════════════

    private void drawDarkCover(Document doc, PdfWriter writer, String title, String subtitle)
            throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();
        float w = page.getWidth(), h = page.getHeight(), cx = w / 2f;
        float inset = 34f;

        drawThinFrame(cb, page, inset,      DARK_SAF,  0.6f);
        drawThinFrame(cb, page, inset + 10, DARK_LINE, 0.35f);

        Font eyebrow   = font(SANS_BOLD,   8.5f, DARK_SAF);
        Font brand     = font(SERIF_BOLD,  84,   DARK_INK);
        Font sub       = font(SERIF_ITALIC,15,   DARK_MUTED);
        Font locLabel  = font(SANS_REG,    8.5f, DARK_MUTED);
        Font menuLabel = font(SANS_BOLD,   8.5f, DARK_MUTED);
        Font foot      = font(SANS_REG,    7.5f, DARK_MUTED);

        float topY   = h - inset - 55f;
        showCentered(cb, spacedCaps("Azerbaijani cuisine  ·  Warszawa"), eyebrow, cx, topY);

        float titleY = h * 0.62f;
        drawDecorativeLine(cb, cx, titleY + 88f, 110f, DARK_SAF);
        showCentered(cb, title, brand, cx, titleY);
        diamondRule(cb, cx - 42f, titleY - 34f, 84f, DARK_SAF);
        showCentered(cb, subtitle, sub, cx, titleY - 60f);
        drawDecorativeLine(cb, cx, titleY - 88f, 110f, DARK_SAF);

        float midY = h * 0.28f;
        showCentered(cb, "Warszawa  ·  Poland", locLabel, cx, midY);
        cb.saveState();
        cb.setColorStroke(DARK_SAF); cb.setLineWidth(0.5f);
        cb.moveTo(cx - 18f, midY + 14f); cb.lineTo(cx + 18f, midY + 14f); cb.stroke();
        cb.restoreState();

        showCentered(cb, spacedCaps("Menu  ·  " + LocalDate.now().format(MENU_DATE)), menuLabel, cx, inset + 50f);
        showCentered(cb, "Saffron Restaurant", foot, cx, inset + 33f);
    }

    private void drawDarkSectionDivider(Document doc, String name) throws DocumentException {
        Font eyebrow = font(SANS_BOLD, 8f, DARK_SAF);
        Font head    = font(SERIF_BOLD, 22, DARK_INK);
        Font az      = font(SERIF_ITALIC, 11.5f, DARK_MUTED);
        Font blurb   = font(SERIF_ITALIC, 10f,   DARK_MUTED);

        Paragraph eb = new Paragraph(spacedCaps(eyebrowFor(name)), eyebrow);
        eb.setAlignment(Element.ALIGN_CENTER);
        doc.add(eb);
        Paragraph rs = new Paragraph(" "); rs.setSpacingBefore(6); doc.add(rs);
        doc.add(new Chunk(new LineSeparator(1f, 16, DARK_SAF, Element.ALIGN_CENTER, 0)));

        Paragraph h = new Paragraph(name, head);
        h.setAlignment(Element.ALIGN_CENTER); h.setSpacingBefore(10); h.setSpacingAfter(0);
        doc.add(h);

        String azName = azFor(name);
        if (azName != null) {
            Paragraph azP = new Paragraph(azName, az);
            azP.setAlignment(Element.ALIGN_CENTER); azP.setSpacingBefore(4); doc.add(azP);
        }
        Paragraph lr = new Paragraph(" "); lr.setSpacingBefore(10); doc.add(lr);
        doc.add(new Chunk(new LineSeparator(0.4f, 100, DARK_LINE, Element.ALIGN_CENTER, 0)));

        String blurbText = blurbFor(name);
        if (blurbText != null) {
            Paragraph bp = new Paragraph(blurbText, blurb);
            bp.setAlignment(Element.ALIGN_CENTER); bp.setLeading(15f);
            bp.setSpacingBefore(12); bp.setIndentationLeft(40f); bp.setIndentationRight(40f);
            doc.add(bp);
        }
        Paragraph after = new Paragraph(" "); after.setSpacingAfter(8); doc.add(after);
    }

    private void drawDarkCompactDivider(Document doc, String name) throws DocumentException {
        Font head    = font(SERIF_BOLD,   16,   DARK_INK);
        Font azFont  = font(SERIF_ITALIC, 10,   DARK_MUTED);
        Font eyebrow = font(SANS_BOLD,    7.5f, DARK_SAF);

        Paragraph pre = new Paragraph(" "); pre.setSpacingBefore(14); doc.add(pre);
        doc.add(new Chunk(new LineSeparator(0.5f, 100, DARK_LINE, Element.ALIGN_LEFT, 0)));
        Paragraph eb = new Paragraph(spacedCaps(eyebrowFor(name)), eyebrow);
        eb.setSpacingBefore(8); doc.add(eb);
        Paragraph h = new Paragraph(name, head); h.setSpacingBefore(4); h.setSpacingAfter(0); doc.add(h);
        String az = azFor(name);
        if (az != null) { Paragraph azP = new Paragraph(az, azFont); azP.setSpacingBefore(3); doc.add(azP); }
        doc.add(new Chunk(new LineSeparator(1.2f, 32, DARK_SAF, Element.ALIGN_LEFT, 0)));
        Paragraph post = new Paragraph(" "); post.setSpacingAfter(5); doc.add(post);
    }

    private void drawDarkList(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        Font nameFont     = font(SERIF_BOLD,   12.5f, DARK_INK);
        Font portionFont  = font(SANS_REG,      8.5f, DARK_MUTED);
        Font priceFont    = font(SANS_BOLD,    12.5f, DARK_GOLD);
        Font pillFont     = font(SANS_BOLD,     7f,   DARK_SAF);
        Font descFont     = font(SERIF_ITALIC, 10.5f, DARK_SOFT);
        Font tagsFont     = font(SANS_ITALIC,   8.5f, DARK_MUTED);
        Font optLabelFont = font(SANS_BOLD,     7f,   DARK_MUTED);
        Font varNameFont  = font(SANS_REG,     10f,   DARK_SOFT);
        Font varPriceFont = font(SANS_BOLD,    10f,   DARK_GOLD);
        Font varSameFont  = font(SANS_ITALIC,   9.5f, DARK_MUTED);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            List<VariantEntry> variants = parseVariants(item);
            boolean varPrices     = showPrices && hasVariantPrices(variants);
            boolean showBasePrice = showPrices && !varPrices;

            if (item.isFeatured()) {
                Paragraph pill = new Paragraph(spacedCaps("Chef's signature"), pillFont);
                pill.setSpacingBefore(i == 0 ? 0 : 4); doc.add(pill);
            }
            PdfPTable head = new PdfPTable(showBasePrice ? 2 : 1);
            head.setWidthPercentage(100);
            try { if (showBasePrice) head.setWidths(new float[]{6.4f, 1.6f}); } catch (DocumentException ignored) {}
            head.addCell(textCell(namePhrase(item, nameFont, portionFont), Element.ALIGN_LEFT));
            if (showBasePrice) {
                PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
                pc.setBorder(Rectangle.NO_BORDER); pc.setHorizontalAlignment(Element.ALIGN_RIGHT); pc.setNoWrap(true);
                head.addCell(pc);
            }
            head.setSpacingBefore(item.isFeatured() ? 2 : 4);
            doc.add(head);

            String desc = chooseDescription(item);
            if (desc != null) {
                Paragraph d = new Paragraph(desc, descFont); d.setLeading(15.5f); d.setSpacingBefore(4); doc.add(d);
            }
            String dietary = renderDietary(item);
            if (dietary != null) { Paragraph d = new Paragraph(dietary, tagsFont); d.setSpacingBefore(4); doc.add(d); }

            if (!variants.isEmpty()) {
                Paragraph ol = new Paragraph(spacedCaps("Options"), optLabelFont); ol.setSpacingBefore(6); doc.add(ol);
                if (varPrices) {
                    for (VariantEntry v : variants) {
                        PdfPTable vr = new PdfPTable(2);
                        vr.setWidthPercentage(100);
                        try { vr.setWidths(new float[]{6.4f, 1.6f}); } catch (DocumentException ignored) {}
                        PdfPCell vn = new PdfPCell(new Phrase(v.name(), varNameFont));
                        vn.setBorder(Rectangle.NO_BORDER); vn.setPaddingTop(4); vn.setPaddingLeft(6); vr.addCell(vn);
                        BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                        PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPriceFont));
                        vpc.setBorder(Rectangle.NO_BORDER); vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        vpc.setNoWrap(true); vpc.setPaddingTop(4); vr.addCell(vpc);
                        doc.add(vr);
                    }
                } else {
                    Paragraph vl = new Paragraph(variantNamesLine(variants), varSameFont); vl.setSpacingBefore(4); doc.add(vl);
                }
            }
            if (i < items.size() - 1) {
                Paragraph pad = new Paragraph(" "); pad.setSpacingBefore(4); doc.add(pad);
                doc.add(new Chunk(new LineSeparator(0.4f, 100, DARK_LINE, Element.ALIGN_LEFT, 0)));
            }
        }
    }

    private void drawDarkClosing(Document doc, PdfWriter writer, Options opt) throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();
        float w = page.getWidth(), h = page.getHeight(), cx = w / 2f;
        float inset = 36f;
        drawThinFrame(cb, page, inset,     DARK_SAF,  0.55f);
        drawThinFrame(cb, page, inset + 8, DARK_LINE, 0.25f);

        Font eyebrow   = font(SANS_BOLD,   9,    DARK_SAF);
        Font hero      = font(SERIF_BOLD,  56,   DARK_INK);
        Font subItalic = font(SERIF_ITALIC,16,   DARK_MUTED);
        Font addr      = font(SANS_REG,    9.5f, DARK_SOFT);
        Font year      = font(SANS_REG,    8.5f, DARK_MUTED);

        showCentered(cb, spacedCaps("Until we see you again"), eyebrow, cx, h - inset - 80);
        showCentered(cb, "Çox sağ olun", hero, cx, h * 0.60f);
        diamondRule(cb, cx - 45f, h * 0.60f - 30, 90f, DARK_SAF);
        showCentered(cb, "Thank you for dining with us.", subItalic, cx, h * 0.60f - 56);

        if (opt.contactBlock() != null && !opt.contactBlock().isBlank()) {
            String[] lines = opt.contactBlock().trim().split("\\r?\\n");
            float y = h * 0.42f;
            for (String line : lines) { showCentered(cb, line, addr, cx, y); y -= 16f; }
        }
        showCentered(cb, spacedCaps("Menu · " + LocalDate.now().format(MENU_DATE)), year, cx, inset + 50);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BOLD — full-width saffron category header blocks, larger typography
    // ══════════════════════════════════════════════════════════════════════════

    private void drawBoldSectionHeader(Document doc, String name) throws DocumentException {
        Font nameFont = font(SERIF_BOLD, 24, CREAM);
        Font azFont   = font(SERIF_ITALIC, 11, new Color(0xFF, 0xEC, 0xCC));
        Font eyeFont  = font(SANS_BOLD, 7.5f, new Color(0xFF, 0xEC, 0xCC));

        PdfPTable banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        banner.setSpacingBefore(10);
        banner.setSpacingAfter(0);

        // Eyebrow row
        PdfPCell eyeCell = new PdfPCell(new Phrase(spacedCaps(eyebrowFor(name)), eyeFont));
        eyeCell.setBackgroundColor(SAFFRON_DEEP);
        eyeCell.setBorder(Rectangle.NO_BORDER);
        eyeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        eyeCell.setPaddingTop(7); eyeCell.setPaddingBottom(4);
        banner.addCell(eyeCell);

        // Name row
        PdfPCell nameCell = new PdfPCell(new Phrase(name, nameFont));
        nameCell.setBackgroundColor(SAFFRON);
        nameCell.setBorder(Rectangle.NO_BORDER);
        nameCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        nameCell.setPaddingTop(12); nameCell.setPaddingBottom(12);
        banner.addCell(nameCell);

        // Optional AZ translation row
        String az = azFor(name);
        if (az != null) {
            PdfPCell azCell = new PdfPCell(new Phrase(az, azFont));
            azCell.setBackgroundColor(SAFFRON_DEEP);
            azCell.setBorder(Rectangle.NO_BORDER);
            azCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            azCell.setPaddingTop(5); azCell.setPaddingBottom(8);
            banner.addCell(azCell);
        }
        doc.add(banner);

        Paragraph after = new Paragraph(" "); after.setSpacingAfter(10); doc.add(after);
    }

    private void drawBoldList(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        Font nameFont     = font(SERIF_BOLD,   14f,   INK);
        Font portionFont  = font(SANS_REG,      9f,   MUTED);
        Font priceFont    = font(SANS_BOLD,    13f,   SAFFRON_DEEP);
        Font pillFont     = font(SANS_BOLD,     7f,   SAFFRON_DEEP);
        Font descFont     = font(SERIF_ITALIC, 10.5f, MUTED);
        Font tagsFont     = font(SANS_ITALIC,   8.5f, MUTED);
        Font optLabelFont = font(SANS_BOLD,     7f,   MUTED);
        Font varNameFont  = font(SANS_REG,     10.5f, INK_SOFT);
        Font varPriceFont = font(SANS_BOLD,    10.5f, SAFFRON_DEEP);
        Font varSameFont  = font(SANS_ITALIC,  10f,   MUTED);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            List<VariantEntry> variants = parseVariants(item);
            boolean varPrices     = showPrices && hasVariantPrices(variants);
            boolean showBasePrice = showPrices && !varPrices;

            if (item.isFeatured()) {
                Paragraph pill = new Paragraph(spacedCaps("Chef's signature"), pillFont);
                pill.setSpacingBefore(i == 0 ? 0 : 4); doc.add(pill);
            }
            PdfPTable head = new PdfPTable(showBasePrice ? 2 : 1);
            head.setWidthPercentage(100);
            try { if (showBasePrice) head.setWidths(new float[]{6f, 2f}); } catch (DocumentException ignored) {}
            head.addCell(textCell(namePhrase(item, nameFont, portionFont), Element.ALIGN_LEFT));
            if (showBasePrice) {
                PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
                pc.setBorder(Rectangle.NO_BORDER); pc.setHorizontalAlignment(Element.ALIGN_RIGHT); pc.setNoWrap(true);
                head.addCell(pc);
            }
            head.setSpacingBefore(item.isFeatured() ? 2 : 5);
            doc.add(head);

            String desc = chooseDescription(item);
            if (desc != null) {
                Paragraph d = new Paragraph(desc, descFont); d.setLeading(15f); d.setSpacingBefore(4); doc.add(d);
            }
            String dietary = renderDietary(item);
            if (dietary != null) { Paragraph d = new Paragraph(dietary, tagsFont); d.setSpacingBefore(4); doc.add(d); }

            if (!variants.isEmpty()) {
                Paragraph ol = new Paragraph(spacedCaps("Options"), optLabelFont); ol.setSpacingBefore(6); doc.add(ol);
                if (varPrices) {
                    for (VariantEntry v : variants) {
                        PdfPTable vr = new PdfPTable(2);
                        vr.setWidthPercentage(100);
                        try { vr.setWidths(new float[]{6f, 2f}); } catch (DocumentException ignored) {}
                        PdfPCell vn = new PdfPCell(new Phrase(v.name(), varNameFont));
                        vn.setBorder(Rectangle.NO_BORDER); vn.setPaddingTop(4); vn.setPaddingLeft(6); vr.addCell(vn);
                        BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                        PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPriceFont));
                        vpc.setBorder(Rectangle.NO_BORDER); vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        vpc.setNoWrap(true); vpc.setPaddingTop(4); vr.addCell(vpc);
                        doc.add(vr);
                    }
                } else {
                    Paragraph vl = new Paragraph(variantNamesLine(variants), varSameFont); vl.setSpacingBefore(4); doc.add(vl);
                }
            }
            if (i < items.size() - 1) {
                Paragraph pad = new Paragraph(" "); pad.setSpacingBefore(6); doc.add(pad);
                doc.add(new Chunk(new LineSeparator(0.8f, 100, SAFFRON_TINT, Element.ALIGN_LEFT, 0)));
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COLUMNS — 3-column newspaper flow
    // ══════════════════════════════════════════════════════════════════════════

    private void drawColumns(Document doc, List<MenuItem> items, boolean showPrices, Locale locale)
            throws DocumentException {
        int total = items.size();
        int perCol = (int) Math.ceil(total / 3.0);
        List<MenuItem> col1 = items.subList(0, Math.min(perCol, total));
        List<MenuItem> col2 = items.subList(Math.min(perCol, total), Math.min(perCol * 2, total));
        List<MenuItem> col3 = items.subList(Math.min(perCol * 2, total), total);

        PdfPTable three = new PdfPTable(3);
        three.setWidthPercentage(100);
        try { three.setWidths(new float[]{1, 1, 1}); } catch (DocumentException ignored) {}

        PdfPCell c1 = new PdfPCell(colTable3(col1, showPrices, locale));
        PdfPCell c2 = new PdfPCell(colTable3(col2, showPrices, locale));
        PdfPCell c3 = new PdfPCell(colTable3(col3, showPrices, locale));
        for (PdfPCell c : new PdfPCell[]{c1, c2, c3}) {
            c.setBorder(Rectangle.NO_BORDER);
            c.setPaddingLeft(10); c.setPaddingRight(10);
        }
        three.addCell(c1); three.addCell(c2); three.addCell(c3);
        doc.add(three);
    }

    private PdfPTable colTable3(List<MenuItem> items, boolean showPrices, Locale locale) {
        PdfPTable col = new PdfPTable(1);
        col.setWidthPercentage(100);

        Font nameFont     = font(SERIF_BOLD,   11f,  INK);
        Font portionFont  = font(SANS_REG,      7.5f, MUTED);
        Font priceFont    = font(SANS_BOLD,    10.5f, INK_SOFT);
        Font pillFont     = font(SANS_BOLD,     6f,   SAFFRON_DEEP);
        Font descFont     = font(SERIF_ITALIC,  8.5f, MUTED);
        Font optLabelFont = font(SANS_BOLD,     6f,   MUTED);
        Font varNameFont  = font(SANS_REG,      8.5f, INK_SOFT);
        Font varPriceFont = font(SANS_BOLD,     8.5f, INK_SOFT);
        Font varSameFont  = font(SANS_ITALIC,   8f,   MUTED);

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            List<VariantEntry> variants = parseVariants(item);
            boolean varPrices     = showPrices && hasVariantPrices(variants);
            boolean showBasePrice = showPrices && !varPrices;

            if (item.isFeatured()) {
                PdfPCell p = new PdfPCell(new Phrase(spacedCaps("Chef's signature"), pillFont));
                p.setBorder(Rectangle.NO_BORDER); col.addCell(p);
            }
            PdfPTable head = new PdfPTable(showBasePrice ? 2 : 1);
            head.setWidthPercentage(100);
            try { if (showBasePrice) head.setWidths(new float[]{4f, 2f}); } catch (DocumentException ignored) {}
            head.addCell(textCell(namePhrase(item, nameFont, portionFont), Element.ALIGN_LEFT));
            if (showBasePrice) {
                PdfPCell pc = new PdfPCell(new Phrase(formatPrice(item.getSellPrice(), locale), priceFont));
                pc.setBorder(Rectangle.NO_BORDER); pc.setHorizontalAlignment(Element.ALIGN_RIGHT); pc.setNoWrap(true);
                head.addCell(pc);
            }
            PdfPCell hw = new PdfPCell(head); hw.setBorder(Rectangle.NO_BORDER); col.addCell(hw);

            String desc = chooseDescription(item);
            if (desc != null) {
                PdfPCell d = new PdfPCell(new Phrase(desc, descFont));
                d.setBorder(Rectangle.NO_BORDER); d.setPaddingTop(2); d.setPaddingBottom(2); d.setLeading(0, 1.3f);
                col.addCell(d);
            }

            if (!variants.isEmpty()) {
                PdfPCell ol = new PdfPCell(new Phrase(spacedCaps("Options"), optLabelFont));
                ol.setBorder(Rectangle.NO_BORDER); ol.setPaddingTop(4); ol.setPaddingBottom(2); col.addCell(ol);
                if (varPrices) {
                    for (VariantEntry v : variants) {
                        PdfPTable vr = new PdfPTable(2);
                        vr.setWidthPercentage(100);
                        try { vr.setWidths(new float[]{4f, 2f}); } catch (DocumentException ignored) {}
                        PdfPCell vn = new PdfPCell(new Phrase(v.name(), varNameFont));
                        vn.setBorder(Rectangle.NO_BORDER); vn.setPaddingLeft(4); vr.addCell(vn);
                        BigDecimal vp = v.price() != null ? v.price() : item.getSellPrice();
                        PdfPCell vpc = new PdfPCell(new Phrase(formatPrice(vp, locale), varPriceFont));
                        vpc.setBorder(Rectangle.NO_BORDER); vpc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        vpc.setNoWrap(true); vr.addCell(vpc);
                        PdfPCell vw = new PdfPCell(vr); vw.setBorder(Rectangle.NO_BORDER); col.addCell(vw);
                    }
                } else {
                    PdfPCell vl = new PdfPCell(new Phrase(variantNamesLine(variants), varSameFont));
                    vl.setBorder(Rectangle.NO_BORDER); col.addCell(vl);
                }
            }
            if (i < items.size() - 1) {
                PdfPCell pad = new PdfPCell(new Phrase(" "));
                pad.setBorder(Rectangle.NO_BORDER); pad.setFixedHeight(6f); col.addCell(pad);
                PdfPCell line = new PdfPCell();
                line.setFixedHeight(0.4f); line.setBackgroundColor(HAIRLINE); line.setBorder(Rectangle.NO_BORDER);
                col.addCell(line);
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

    private void diamondRule(PdfContentByte cb, float anchorX, float cy, float armLen) {
        diamondRule(cb, anchorX, cy, armLen, SAFFRON);
    }

    private void diamondRule(PdfContentByte cb, float anchorX, float cy, float armLen, Color color) {
        cb.saveState();
        cb.setColorStroke(color); cb.setColorFill(color); cb.setLineWidth(0.6f);
        cb.moveTo(anchorX, cy);                     cb.lineTo(anchorX + armLen / 2f - 8, cy);
        cb.moveTo(anchorX + armLen / 2f + 8, cy);  cb.lineTo(anchorX + armLen, cy);
        cb.stroke();
        float mx = anchorX + armLen / 2f;
        cb.moveTo(mx, cy + 3.5f); cb.lineTo(mx + 3.5f, cy);
        cb.lineTo(mx, cy - 3.5f); cb.lineTo(mx - 3.5f, cy);
        cb.closePathFillStroke();
        cb.restoreState();
    }

    private void showCentered(PdfContentByte cb, String text, Font font, float cx, float baselineY) {
        try {
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(text, font), cx, baselineY, 0);
        } catch (Exception ignored) {}
    }

    // ---------- Helpers ----------

    private static String azFor(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        // Direct lookup first (covers both English and any exact-match Polish)
        String az = CATEGORY_TRANSLATIONS.get(key);
        if (az != null) return az;
        // Polish → English canonical → Azerbaijani
        String canonical = switch (key) {
            case "zupy", "zupa"                           -> "soups";
            case "sałatki", "sałatka", "salaty", "salata" -> "salads";
            case "przystawki", "przystawka", "prystawki", "prystawka" -> "starters";
            case "dania główne", "dania glowne"           -> "mains";
            case "desery", "deser"                        -> "desserts";
            case "napoje", "napój"                        -> "drinks";
            case "pieczywo", "chleb"                      -> "breads";
            case "dodatki"                                -> "sides";
            default                                       -> null;
        };
        return canonical != null ? CATEGORY_TRANSLATIONS.get(canonical) : null;
    }

    /**
     * Inviting, plain-English eyebrow per category. Replaces the editorial
     * "SECTION 02" label with something a guest can read at a glance.
     */
    private static String eyebrowFor(String name) {
        if (name == null) return "From our menu";
        String key = name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            // English
            case "starters", "appetisers", "appetizers" -> "To begin";
            case "salads", "salad"                      -> "Fresh & vibrant";
            case "soups", "soup"                        -> "To warm";
            case "mains", "main courses", "main course" -> "From the kitchen";
            case "plov", "plov & rice"                  -> "The national dish";
            case "kebabs", "kebab", "grill"             -> "From the charcoal";
            case "sides", "side dishes"                 -> "Alongside";
            case "breads", "bread"                      -> "Freshly baked";
            case "desserts", "sweets", "dessert"        -> "Sweet endings";
            case "drinks", "beverages", "drink"         -> "To drink";
            case "tea"                                  -> "At the end of the meal";
            case "hot drinks"                           -> "Warm drinks";
            case "cold drinks"                          -> "Refreshments";
            case "wine"                                 -> "Wine list";
            case "beer", "beers"                        -> "Beers";
            case "cocktails", "cocktail"                -> "Cocktails";
            // Polish
            case "zupy", "zupa"                         -> "Do rozgrzania";
            case "sałatki", "sałatka", "salaty", "salata" -> "Świeże & wyraziste";
            case "przystawki", "przystawka", "prystawki", "prystawka" -> "Na początek";
            case "dania główne", "dania glowne"         -> "Z kuchni";
            case "desery", "deser"                      -> "Słodkie zakończenie";
            case "napoje", "napój"                      -> "Do picia";
            case "pieczywo", "chleb"                    -> "Świeżo pieczone";
            case "dodatki"                              -> "Do tego";
            default                                     -> "From our menu";
        };
    }

    private static String blurbFor(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "starters", "appetisers", "appetizers", "przystawki", "przystawka", "prystawki", "prystawka" ->
                    "Small plates to open the meal — eaten slowly, ideally with bread, while the table is being set.";
            case "salads", "salad", "sałatki", "sałatka" ->
                    "Fresh herbs, sumac, walnuts and pomegranate — the everyday Azerbaijani table at its most generous.";
            case "soups", "soup", "zupy", "zupa" ->
                    "Slow-cooked broths and yoghurt soups — what grandmothers in Şəki call medicine and what we call lunch.";
            case "mains", "main courses", "dania główne", "dania glowne" ->
                    "Plov, kebabs, slow-braised lamb. Dishes that take their time, and reward yours.";
            case "plov", "plov & rice" ->
                    "The crown of Azerbaijani cuisine — saffron-stained rice with lamb, chestnuts, dried apricot and herbs.";
            case "kebabs", "kebab", "grill" ->
                    "Charcoal-grilled lamb, chicken and sturgeon — marinated overnight, served with sumac and onion.";
            case "sides", "side dishes", "dodatki" ->
                    "Pickles, herbs, breads — the side stage where the main dishes meet, and where the table fills up.";
            case "breads", "bread", "pieczywo" -> "Tandir-baked, torn and shared — never sliced.";
            case "desserts", "sweets", "dessert", "desery", "deser" ->
                    "Pakhlava, şəkərbura, halva — pastries that taste of holidays, weddings and patience.";
            case "drinks", "beverages", "drink", "napoje" ->
                    "Şərbət, ayran, compote, tea — pairings for every season and every dish.";
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

    /**
     * Formats a price for the printed menu.
     *
     * <p>Fine-dining convention: whole numbers omit the ".00" — "38 zł" not
     * "38.00 zł". Fractional prices (e.g. 26.50) still show two decimal
     * places. Using "zł" only (not "PLN") keeps the page clean and Polish.</p>
     */
    private static String formatPrice(BigDecimal price, Locale locale) {
        if (price == null) return "";
        BigDecimal scaled = price.setScale(2, RoundingMode.HALF_UP);
        // Whole number — drop the ".00"
        if (scaled.stripTrailingZeros().scale() <= 0) {
            return scaled.toBigInteger().toString() + " zł";
        }
        // Fractional — keep two places, use comma for Polish locale
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
        private boolean suppressPageNumber = false;
        private Color bgColor       = CREAM;
        private Color brandColor    = SAFFRON;
        private Color pageNumColor  = MUTED;

        void suppressNext() { this.suppressPageNumber = true; }
        void setSection(@SuppressWarnings("unused") String s) {}

        void setTheme(Color bg, Color brand, Color pageNum) {
            this.bgColor      = bg;
            this.brandColor   = brand;
            this.pageNumColor = pageNum;
        }

        @Override
        public void onStartPage(PdfWriter writer, Document doc) {
            try {
                PdfContentByte cb = writer.getDirectContentUnder();
                Rectangle page = doc.getPageSize();
                cb.saveState();
                cb.setColorFill(bgColor);
                cb.rectangle(0, 0, page.getWidth(), page.getHeight());
                cb.fill();
                cb.restoreState();
            } catch (Exception ignored) {}
        }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            if (suppressPageNumber) { suppressPageNumber = false; return; }
            int p = writer.getPageNumber();
            if (p <= 1) return;
            try {
                PdfContentByte cb = writer.getDirectContent();
                Rectangle page = doc.getPageSize();
                Font fBrand = font(SANS_BOLD, 7.5f, brandColor);
                ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                        new Phrase("SAFFRON", fBrand),
                        page.getWidth() - 68f, page.getHeight() - 52f, 0);
                Font fNum = font(SERIF_REG, 9.5f, pageNumColor);
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                        new Phrase(String.valueOf(p), fNum),
                        page.getWidth() / 2f, 38, 0);
            } catch (Exception ignored) {}
        }
    }
}
