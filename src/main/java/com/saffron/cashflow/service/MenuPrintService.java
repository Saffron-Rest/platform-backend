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
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.springframework.core.io.ClassPathResource;
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

            // 5 — Symbols of Azerbaijan
            doc.newPage();
            chrome.setRunningHead("Symbols of Azerbaijan");
            drawSymbolsPage(doc, writer);

            // 6 — Contents
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
        float w = page.getWidth(), h = page.getHeight();
        float cx = w / 2f;

        // 1. Full-bleed editorial food photograph as the cover background.
        // We always render the photo behind everything else; ornaments and the
        // title block sit on top. If the bundled image is missing for some
        // reason we degrade gracefully to a cream surface — the saffron
        // ornaments still hold the page together.
        Image hero = loadBundledImage("cover-hero-feast.jpg");
        if (hero != null) {
            // scaleAbsolute fills the page edge-to-edge; we accept some
            // cropping for the sake of true full bleed.
            hero.scaleAbsolute(w, h);
            hero.setAbsolutePosition(0, 0);
            try { doc.add(hero); } catch (DocumentException ignored) {}
        } else {
            cb.saveState();
            cb.setColorFill(CREAM);
            cb.rectangle(0, 0, w, h);
            cb.fill();
            cb.restoreState();
        }

        // 2. A soft darken at the very top so the eyebrow + frame read on
        // the photo, and a stronger darken on the bottom half where the
        // title sits. This is the classic magazine-cover treatment.
        applyOverlay(cb, 0, h * 0.86f, w, h * 0.14f, INK, 0.30f);
        applyOverlay(cb, 0, 0, w, h * 0.54f, INK, 0.62f);

        // 3. Double saffron frame + corner ornaments — sit over the photo
        //    and give the cover its bookplate character.
        float inset = 36f;
        drawThinFrame(cb, page, inset, CREAM, 0.7f);
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.5f);
        drawCornerOrnaments(cb, page, inset + 14f, 14f, CREAM);

        // 4. Typography — cream / saffron over the dark areas so it pops.
        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON);
        Font year = FontFactory.getFont(FontFactory.HELVETICA, 9, CREAM_DEEP);
        Font brand = FontFactory.getFont(FontFactory.TIMES_BOLD, 102, CREAM);
        Font sub = FontFactory.getFont(FontFactory.TIMES_ITALIC, 19, CREAM_DEEP);
        Font cite = FontFactory.getFont(FontFactory.TIMES_ITALIC, 12, SAFFRON);
        Font foot = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, CREAM_DEEP);
        Font est = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, SAFFRON);

        // Top eyebrow + 8-star marker — keep it light over the photo.
        drawEightStar(cb, cx, h - inset - 38, 7f, SAFFRON, true);
        showCentered(cb, spacedCaps("La carte · A book of dishes"), eyebrow,
                cx, h - inset - 56);

        // Bottom title block. Sits in the darkened area — wordmark large.
        showCentered(cb, title, brand, cx, h * 0.42f);

        // Ornamental rule + tiny diamond beneath the wordmark.
        float ruleY = h * 0.42f - 36f;
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(1.2f);
        cb.moveTo(cx - 70, ruleY);
        cb.lineTo(cx - 8, ruleY);
        cb.moveTo(cx + 8, ruleY);
        cb.lineTo(cx + 70, ruleY);
        cb.stroke();
        cb.setColorFill(SAFFRON);
        cb.moveTo(cx, ruleY + 4);
        cb.lineTo(cx + 4, ruleY);
        cb.lineTo(cx, ruleY - 4);
        cb.lineTo(cx - 4, ruleY);
        cb.closePathFillStroke();
        cb.restoreState();

        showCentered(cb, subtitle, sub, cx, ruleY - 26);

        // Buta between two short rules — anchor near the bottom of the panel.
        float butaY = h * 0.22f;
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(0.6f);
        cb.moveTo(cx - 110, butaY);
        cb.lineTo(cx - 28, butaY);
        cb.moveTo(cx + 28, butaY);
        cb.lineTo(cx + 110, butaY);
        cb.stroke();
        cb.restoreState();
        drawButa(cb, cx, butaY, 18f, SAFFRON, false);

        // Italic motif under the buta.
        showCentered(cb, "Şirniyyat · Plov · Kabab · Çay", cite, cx, h * 0.14f);

        // Bottom edition block — restrained.
        showCentered(cb, "EST. SAFFRON · WARSZAWA · AZƏRBAYCAN MƏTBƏXİ",
                est, cx, inset + 56);
        showCentered(cb, "EDITION · " + LocalDate.now().format(MENU_DATE).toUpperCase(Locale.ROOT),
                year, cx, inset + 38);
    }

    /** Translucent fill — used to darken parts of the cover photo so the
     *  cream-coloured title text reads. OpenPDF supports opacity via
     *  PdfGState; we save/restore so the surrounding text isn't affected. */
    private void applyOverlay(PdfContentByte cb, float x, float y, float w, float h,
                              Color color, float opacity) {
        cb.saveState();
        PdfGState gs = new PdfGState();
        gs.setFillOpacity(opacity);
        cb.setGState(gs);
        cb.setColorFill(color);
        cb.rectangle(x, y, w, h);
        cb.fill();
        cb.restoreState();
    }

    /** Load a curated cookbook-style photo bundled with the JAR. Returns
     *  null if the resource isn't on the classpath — callers must fall back
     *  to a generated placeholder so a stripped distribution doesn't crash. */
    private Image loadBundledImage(String name) {
        try {
            byte[] bytes = new ClassPathResource("menu-assets/" + name).getInputStream().readAllBytes();
            return Image.getInstance(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Hand-drawn buta (paisley / "flame") — the most recognisable Azerbaijani
     * motif. Found on carpets, embroidery, manuscript margins, architecture.
     * Symbol of fire, eternity, and the Zoroastrian sun. Built from a single
     * closed cubic-bezier path so it scales cleanly at any size, plus an
     * inner-leaf accent for depth.
     *
     * @param size  approx. width in PDF points (the leaf is taller than wide).
     * @param filled fill with the colour as well as stroking the outline.
     */
    private void drawButa(PdfContentByte cb, float cx, float cy, float size,
                           Color color, boolean filled) {
        cb.saveState();
        cb.setColorStroke(color);
        if (filled) cb.setColorFill(color);
        cb.setLineWidth(0.8f);
        float s = size;

        // Outer paisley outline. The shape: bulges out on the right, curls
        // back to a point at the top, narrows down to a stem at the bottom.
        // Tweaked by hand until it read as a "flame" silhouette at small sizes.
        cb.moveTo(cx, cy - s);
        cb.curveTo(cx - s * 0.95f, cy - s * 0.6f,
                   cx - s * 1.05f, cy + s * 0.4f,
                   cx - s * 0.55f, cy + s * 0.95f);
        cb.curveTo(cx - s * 0.15f, cy + s * 1.35f,
                   cx + s * 0.45f, cy + s * 1.30f,
                   cx + s * 0.50f, cy + s * 0.70f);
        cb.curveTo(cx + s * 0.55f, cy + s * 0.25f,
                   cx + s * 0.10f, cy + s * 0.55f,
                   cx + s * 0.05f, cy + s * 0.20f);
        cb.curveTo(cx + s * 0.02f, cy - s * 0.10f,
                   cx + s * 0.90f, cy - s * 0.20f,
                   cx + s * 0.40f, cy - s * 0.70f);
        cb.curveTo(cx + s * 0.10f, cy - s * 1.00f,
                   cx - s * 0.05f, cy - s * 0.95f,
                   cx, cy - s);
        if (filled) cb.closePathFillStroke();
        else { cb.closePathStroke(); }

        // Inner accent leaf — a smaller buta nested inside for ornament depth.
        cb.setLineWidth(0.55f);
        cb.moveTo(cx - s * 0.05f, cy - s * 0.45f);
        cb.curveTo(cx - s * 0.55f, cy - s * 0.10f,
                   cx - s * 0.60f, cy + s * 0.45f,
                   cx - s * 0.25f, cy + s * 0.65f);
        cb.curveTo(cx + s * 0.10f, cy + s * 0.85f,
                   cx + s * 0.20f, cy + s * 0.40f,
                   cx - s * 0.05f, cy - s * 0.45f);
        cb.stroke();

        // Three petite seed dots inside — a flourish from Azerbaijani textile work.
        if (filled) {
            cb.setColorFill(color);
            cb.circle(cx - s * 0.10f, cy + s * 0.25f, 0.8f);
            cb.fill();
            cb.circle(cx - s * 0.20f, cy - s * 0.05f, 0.8f);
            cb.fill();
            cb.circle(cx - s * 0.05f, cy - s * 0.30f, 0.8f);
            cb.fill();
        }
        cb.restoreState();
    }

    /**
     * Eight-pointed Şirvan star — appears all over Azerbaijani carpets and
     * historical architecture. Drawn as a 16-vertex polygon alternating between
     * an outer and an inner radius so the points are sharp.
     */
    private void drawEightStar(PdfContentByte cb, float cx, float cy, float outerR,
                                Color color, boolean filled) {
        cb.saveState();
        cb.setColorStroke(color);
        if (filled) cb.setColorFill(color);
        cb.setLineWidth(0.6f);
        float inner = outerR * 0.40f;
        for (int i = 0; i < 16; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 8.0;
            float r = (i % 2 == 0) ? outerR : inner;
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            if (i == 0) cb.moveTo(x, y); else cb.lineTo(x, y);
        }
        if (filled) cb.closePathFillStroke(); else cb.closePathStroke();
        cb.restoreState();
    }

    /**
     * Stylised saffron crocus — three petals fanning up from a centre, with
     * three saffron stigmas at the very top. The actual plant we owe the
     * restaurant's name to.
     */
    private void drawSaffronCrocus(PdfContentByte cb, float cx, float cy, float size, Color color) {
        cb.saveState();
        cb.setColorStroke(color);
        cb.setColorFill(color);
        cb.setLineWidth(0.7f);

        // Three petals
        for (int i = -1; i <= 1; i++) {
            double a = -Math.PI / 2 + i * (Math.PI / 5);
            float dx = (float) Math.cos(a) * size * 0.45f;
            float dy = (float) Math.sin(a) * size * 0.6f;
            cb.moveTo(cx, cy);
            cb.curveTo(cx + dx * 0.4f - dy * 0.3f, cy + dy * 0.4f + dx * 0.3f,
                       cx + dx * 0.9f - dy * 0.2f, cy + dy * 0.9f + dx * 0.2f,
                       cx + dx, cy + dy);
            cb.curveTo(cx + dx * 0.9f + dy * 0.2f, cy + dy * 0.9f - dx * 0.2f,
                       cx + dx * 0.4f + dy * 0.3f, cy + dy * 0.4f - dx * 0.3f,
                       cx, cy);
            cb.stroke();
        }
        // Three stigmas (the actual saffron threads)
        cb.setColorStroke(SAFFRON_DEEP);
        cb.setLineWidth(1.2f);
        for (int i = -1; i <= 1; i++) {
            float dx = i * size * 0.10f;
            cb.moveTo(cx + dx, cy);
            cb.curveTo(cx + dx, cy + size * 0.35f,
                       cx + dx * 1.5f, cy + size * 0.55f,
                       cx + dx * 2.0f, cy + size * 0.75f);
            cb.stroke();
        }
        cb.restoreState();
    }

    /**
     * Small "L"-shaped saffron corner ornament — drawn at each of the four
     * inner corners of the cover/closing frame so the page feels stamped, like
     * a stationery border.
     */
    private void drawCornerOrnaments(PdfContentByte cb, Rectangle page, float inset, float size,
                                      Color color) {
        cb.saveState();
        cb.setColorStroke(color);
        cb.setColorFill(color);
        cb.setLineWidth(0.5f);
        float w = page.getWidth(), h = page.getHeight();
        float[][] anchors = {
                {inset, inset, +1, +1},                       // bottom-left
                {w - inset, inset, -1, +1},                   // bottom-right
                {inset, h - inset, +1, -1},                   // top-left
                {w - inset, h - inset, -1, -1},               // top-right
        };
        for (float[] a : anchors) {
            float x = a[0], y = a[1], sx = a[2], sy = a[3];
            cb.moveTo(x, y + sy * size);
            cb.lineTo(x, y);
            cb.lineTo(x + sx * size, y);
            cb.stroke();
            cb.circle(x + sx * size * 0.55f, y + sy * size * 0.55f, 1.4f);
            cb.fill();
        }
        cb.restoreState();
    }

    /**
     * Decorative band — a row of small diamonds with thin rules either side.
     * Echoes the borders of Azerbaijani carpets. Used under chapter blurbs and
     * inside the glossary page.
     */
    private void drawCarpetBand(PdfContentByte cb, float x, float y, float width, Color color) {
        cb.saveState();
        cb.setColorStroke(color);
        cb.setColorFill(color);
        cb.setLineWidth(0.5f);
        // Two thin rules.
        cb.moveTo(x, y + 4);
        cb.lineTo(x + width, y + 4);
        cb.moveTo(x, y - 4);
        cb.lineTo(x + width, y - 4);
        cb.stroke();
        // Diamonds and dots across the centre.
        float step = 14f;
        float d = 2.2f;
        for (float xi = x + step / 2; xi < x + width; xi += step) {
            cb.moveTo(xi, y + d);
            cb.lineTo(xi + d, y);
            cb.lineTo(xi, y - d);
            cb.lineTo(xi - d, y);
            cb.closePathFillStroke();
            // tiny dot between diamonds
            if (xi + step / 2 < x + width) {
                cb.circle(xi + step / 2, y, 0.7f);
                cb.fill();
            }
        }
        cb.restoreState();
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
        Font caption = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8.5f, MUTED);
        Font dropCap = FontFactory.getFont(FontFactory.TIMES_BOLD, 64, SAFFRON_DEEP);
        Font body = FontFactory.getFont(FontFactory.TIMES_ROMAN, 12.5f, INK_SOFT);
        Font quote = FontFactory.getFont(FontFactory.TIMES_ITALIC, 17, SAFFRON_DEEP);
        Font attribution = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);

        // Editorial hero photo at the top — saffron threads on linen with a
        // brass spoon. Bleeds slightly past the right margin for a more
        // generous magazine-spread feel.
        PdfContentByte cb = writer.getDirectContent();
        Image storyPhoto = loadBundledImage("story-saffron-closeup.jpg");
        if (storyPhoto != null) {
            float maxW = (doc.right() - doc.left()) + 30f;
            float maxH = doc.getPageSize().getHeight() * 0.30f;
            storyPhoto.scaleToFit(maxW, maxH);
            storyPhoto.setAbsolutePosition(
                    doc.left() - 15f,
                    doc.getPageSize().getHeight() - doc.topMargin() - storyPhoto.getScaledHeight() - 6);
            try { doc.add(storyPhoto); } catch (DocumentException ignored) {}

            // Photo caption under the image — like in a printed cookbook.
            try {
                ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                        new Phrase("Saffron — Zəfəran. Hand-pulled stigmas, the most precious spice on earth.",
                                caption),
                        doc.left(),
                        doc.getPageSize().getHeight() - doc.topMargin() - storyPhoto.getScaledHeight() - 18,
                        0);
            } catch (Exception ignored) {}

            // Push the body content below the photo by adding spacing.
            Paragraph push = new Paragraph(" ");
            push.setSpacingBefore(storyPhoto.getScaledHeight() + 12);
            doc.add(push);
        }

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

    // ---------- Symbols of Azerbaijan ----------

    /**
     * A small visual essay: four motifs that appear again and again in
     * Azerbaijani art — buta, eight-pointed star, pomegranate, saffron
     * crocus — each rendered with our drawing primitives so they feel
     * intentional rather than clip-arty.
     */
    private void drawSymbolsPage(Document doc, PdfWriter writer) throws DocumentException {
        PdfContentByte cb = writer.getDirectContent();
        Rectangle page = doc.getPageSize();

        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, SAFFRON_DEEP);
        Font head = FontFactory.getFont(FontFactory.TIMES_BOLD, 32, INK);
        Font cardEn = FontFactory.getFont(FontFactory.TIMES_BOLD, 14, INK);
        Font cardAz = FontFactory.getFont(FontFactory.TIMES_ITALIC, 10.5f, MUTED);
        Font cardBody = FontFactory.getFont(FontFactory.TIMES_ROMAN, 10.5f, INK_SOFT);

        doc.add(new Paragraph(spacedCaps("A book of motifs"), eyebrow));
        Paragraph h = new Paragraph("Symbols of Azerbaijan", head);
        h.setSpacingBefore(6);
        h.setSpacingAfter(4);
        doc.add(h);
        doc.add(saffronRule(60f));

        // Intro line in italic
        Font intro = FontFactory.getFont(FontFactory.TIMES_ITALIC, 11.5f, INK_SOFT);
        Paragraph p = new Paragraph(
                "Some shapes follow us through Azerbaijani art for a thousand years — onto carpets, "
                        + "tiles, manuscripts and tea-glasses. You will see them again throughout this book.",
                intro);
        p.setLeading(17f);
        p.setSpacingBefore(20);
        p.setSpacingAfter(8);
        p.setAlignment(Element.ALIGN_LEFT);
        doc.add(p);

        // Lay out four motif cards in a 2×2 grid. We position the drawn symbols
        // absolutely (via the content byte) over invisible spacer rows so the
        // text underneath flows in normal document order — that way page-break
        // safety isn't compromised even if the page margin changes.
        float gridTop = page.getHeight() * 0.66f;
        float gridBottom = page.getHeight() * 0.16f;
        float colW = (doc.right() - doc.left()) / 2f;
        float rowH = (gridTop - gridBottom) / 2f;

        String[][] motifs = new String[][] {
                {"Buta", "Buta", "An eternal flame shaped like a paisley leaf. Found on Azerbaijani carpets and embroidery from Nakhchivan to Bakı — symbol of fire, life and renewal."},
                {"Eight-pointed Şirvan star", "Səkkizguşəli ulduz", "The eight-pointed star of the Şirvan school of carpet weaving — protection, balance, and the cardinal directions of the Azerbaijani plateau."},
                {"Pomegranate", "Nar", "A fruit with hundreds of seeds — the Azerbaijani symbol of fertility, unity, and an abundant table. Goychay even hosts a yearly Pomegranate Festival."},
                {"Saffron crocus", "Zəfəran", "Three crimson stigmas hand-pulled from each flower at dawn. The plant that bound the silk roads — and the namesake of this kitchen."},
        };

        // Four absolute-positioned symbol drawings.
        float[][] centres = new float[][] {
                {doc.left() + colW * 0.25f, gridTop - rowH * 0.40f},
                {doc.left() + colW * 1.25f, gridTop - rowH * 0.40f},
                {doc.left() + colW * 0.25f, gridTop - rowH * 1.40f},
                {doc.left() + colW * 1.25f, gridTop - rowH * 1.40f},
        };
        drawButa(cb, centres[0][0], centres[0][1], 18f, SAFFRON_DEEP, false);
        drawEightStar(cb, centres[1][0], centres[1][1], 18f, SAFFRON_DEEP, false);
        drawPomegranateOrnament(cb, centres[2][0], centres[2][1]);
        drawSaffronCrocus(cb, centres[3][0], centres[3][1], 22f, SAFFRON);

        // The four caption blocks, positioned just below each motif.
        PdfPTable grid = new PdfPTable(2);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(28);
        grid.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        try { grid.setWidths(new float[]{1, 1}); } catch (DocumentException ignored) {}

        for (String[] m : motifs) {
            PdfPTable card = new PdfPTable(1);
            card.setWidthPercentage(100);

            // Empty top cell holds vertical space for the absolute-positioned glyph.
            PdfPCell space = new PdfPCell(new Phrase(" "));
            space.setBorder(Rectangle.NO_BORDER);
            space.setFixedHeight(68f);
            card.addCell(space);

            PdfPCell name = new PdfPCell(new Phrase(m[0], cardEn));
            name.setBorder(Rectangle.NO_BORDER);
            name.setHorizontalAlignment(Element.ALIGN_CENTER);
            card.addCell(name);

            PdfPCell az = new PdfPCell(new Phrase(m[1], cardAz));
            az.setBorder(Rectangle.NO_BORDER);
            az.setHorizontalAlignment(Element.ALIGN_CENTER);
            az.setPaddingBottom(6);
            card.addCell(az);

            Paragraph bodyP = new Paragraph(m[2], cardBody);
            bodyP.setLeading(14.5f);
            bodyP.setAlignment(Element.ALIGN_CENTER);
            PdfPCell body = new PdfPCell();
            body.setBorder(Rectangle.NO_BORDER);
            body.addElement(bodyP);
            body.setPaddingTop(4);
            body.setPaddingBottom(8);
            body.setPaddingLeft(16);
            body.setPaddingRight(16);
            card.addCell(body);

            PdfPCell wrap = new PdfPCell(card);
            wrap.setBorder(Rectangle.NO_BORDER);
            wrap.setPaddingBottom(10);
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

        // Eight-pointed Şirvan star sits above the eyebrow — small but it
        // immediately roots the page in Azerbaijani carpet vocabulary.
        drawEightStar(cb, colLeft + 6, ribbonTop + 22, 7f, SAFFRON, true);

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

        // Watermark buta in the left margin — very faint cream, so it reads as
        // texture rather than illustration. Mirrors carpet "ground" patterns.
        drawButa(cb, doc.left() - 8, page.getHeight() * 0.30f, 32f, CREAM_DEEP, true);

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
            bBlock.setSimpleColumn(colLeft, page.getHeight() * 0.22f,
                    colRight, ruleY - 12);
            Paragraph blurbP = new Paragraph(blurbText, blurb);
            blurbP.setLeading(19f);
            blurbP.setAlignment(Element.ALIGN_LEFT);
            bBlock.addElement(blurbP);
            try { bBlock.go(); } catch (Exception ignored) {}
        }

        // Decorative Azerbaijani carpet-band between the blurb and the hero
        // image — small diamonds + rules, drawn at the right column width.
        drawCarpetBand(cb, colLeft, page.getHeight() * 0.18f,
                Math.min(220f, colRight - colLeft - 200f), SAFFRON);

        // Hero image — preferred order:
        //   1. The featured item in this category, if its photo loads.
        //   2. Any item with a photo.
        //   3. The bundled "Azerbaijani feast" fallback so the page never
        //      ends up empty in the corner where the eye expects an image.
        MenuItem hero = null;
        for (MenuItem it : items) {
            if (it.isFeatured() && it.getImagePath() != null) { hero = it; break; }
        }
        if (hero == null) {
            for (MenuItem it : items) {
                if (it.getImagePath() != null) { hero = it; break; }
            }
        }
        Image img = null;
        String heroLabel = null;
        String heroName = null;
        if (hero != null) {
            img = tryLoadImage(hero.getImagePath());
            if (img != null) {
                heroLabel = "Featured";
                heroName = hero.getName();
            }
        }
        if (img == null) {
            // No admin-uploaded photo yet — use the bundled editorial image
            // so the page still feels styled. The caption shifts to a
            // category-neutral phrase that doesn't lie about the dish shown.
            img = loadBundledImage("chapter-fallback-plov.jpg");
            if (img != null) {
                heroLabel = "From the kitchen";
                heroName = null;
            }
        }
        if (img != null) {
            float maxW = (doc.right() - doc.left()) * 0.50f;
            float maxH = page.getHeight() * 0.34f;
            img.scaleToFit(maxW, maxH);
            img.setAbsolutePosition(
                    doc.right() - img.getScaledWidth(),
                    doc.bottom());
            try { doc.add(img); } catch (DocumentException ignored) {}

            Font cap = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, SAFFRON_DEEP);
            Font capName = FontFactory.getFont(FontFactory.TIMES_ITALIC, 10.5f, INK);
            try {
                float captionY = doc.bottom() - 6;
                if (heroLabel != null) {
                    ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                            new Phrase(spacedCaps(heroLabel), cap),
                            doc.right(), captionY, 0);
                }
                if (heroName != null) {
                    ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                            new Phrase(heroName, capName),
                            doc.right(), captionY - 12, 0);
                }
            } catch (Exception ignored) {}
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
        float cx = page.getWidth() / 2f;

        // Mirror the cover: double frame + corner ornaments. The book closes
        // with the same visual stamp it opened with.
        float inset = 36f;
        drawThinFrame(cb, page, inset, SAFFRON, 0.6f);
        drawThinFrame(cb, page, inset + 8, SAFFRON, 0.25f);
        drawCornerOrnaments(cb, page, inset + 14f, 14f, SAFFRON);

        Font eyebrow = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SAFFRON_DEEP);
        Font hero = FontFactory.getFont(FontFactory.TIMES_BOLD, 60, INK);
        Font az = FontFactory.getFont(FontFactory.TIMES_ITALIC, 20, MUTED);
        Font addr = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
        Font foot = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);

        // Tiny 8-pointed star above the eyebrow mirrors the cover marker.
        drawEightStar(cb, cx, page.getHeight() * 0.80f, 7f, SAFFRON, true);

        showCentered(cb, spacedCaps("Until we see you again"), eyebrow,
                cx, page.getHeight() * 0.76f);
        showCentered(cb, "Çox sağ olun", hero, cx, page.getHeight() * 0.66f);
        showCentered(cb, "Thank you for dining with us.", az, cx, page.getHeight() * 0.58f);

        // Buta flanked by short saffron rules — the cover ornament, returning.
        float butaY = page.getHeight() * 0.46f;
        cb.saveState();
        cb.setColorStroke(SAFFRON);
        cb.setLineWidth(0.6f);
        cb.moveTo(cx - 120, butaY);
        cb.lineTo(cx - 32, butaY);
        cb.moveTo(cx + 32, butaY);
        cb.lineTo(cx + 120, butaY);
        cb.stroke();
        cb.restoreState();
        drawButa(cb, cx, butaY, 22f, SAFFRON_DEEP, false);

        // Decorative carpet band under the ornament.
        drawCarpetBand(cb, cx - 110, page.getHeight() * 0.38f, 220f, SAFFRON);

        if (opt.contactBlock() != null && !opt.contactBlock().isBlank()) {
            String[] lines = opt.contactBlock().trim().split("\\r?\\n");
            float y = page.getHeight() * 0.30f;
            for (String line : lines) {
                showCentered(cb, line, addr, cx, y);
                y -= 16f;
            }
        }

        showCentered(cb, "S A F F R O N · W A R S Z A W A · A Z Ə R B A Y C A N",
                foot, cx, inset + 56);
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
