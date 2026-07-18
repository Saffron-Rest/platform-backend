package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.MenuCategory;
import com.saffron.cashflow.domain.MenuItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the printable menu PDF. Verifies the two-pass renderer
 * completes for each layout and produces a valid PDF — guards against
 * regressions in OpenPDF usage / the recent "professional" redesign.
 *
 * Uses hand-rolled stubs instead of Mockito because Byte Buddy lags JDK
 * releases and breaks on Java 25.
 */
class MenuPrintServiceSmokeTest {

    @Test
    void generatesPdfForAllLayouts() throws IOException {
        Path uploadDir = Files.createTempDirectory("menu-print-test");
        FileStorageService storage = new StubFileStorage(uploadDir);

        List<MenuCategory> categories = List.of(category("c1", "Mains", 10), category("c2", "Drinks", 20));
        Map<String, List<MenuItem>> items = Map.of(
                "c1", List.of(
                        item("c1", "Lamb Plov", "Slow-braised lamb shoulder, saffron rice", 38, true, "vegetarian"),
                        item("c1", "Dovga", "Yogurt and herb soup, dill, chickpeas", 18, false, null)),
                "c2", List.of(item("c2", "Black Tea", "Loose Azerbaijani black tea in a glass", 8, false, null)));
        MenuService menu = new StubMenuService(categories, items);

        MenuPrintService service = new MenuPrintService(menu, storage);

        for (String layout : new String[]{"grid", "list", "photolist", "compact", "fine", "tasting",
                                          "dark", "bold", "columns", "a3", "deco"}) {
            byte[] bytes = service.buildMenu(layout, "Saffron Test", "Smoke test menu", true, "en");
            assertThat(bytes).as("non-empty PDF for layout %s", layout).isNotEmpty();
            assertThat(new String(bytes, 0, 4)).as("PDF header for layout %s", layout).isEqualTo("%PDF");
        }

        // Verify A3 produces wider pages than A4 (A3 portrait width ≈ 841pt vs A4 ≈ 595pt)
        byte[] a3Pdf   = service.buildMenu("a3",   "Saffron", null, true, "en");
        byte[] decoPdf = service.buildMenu("deco",  "Saffron", null, true, "en");
        byte[] listPdf = service.buildMenu("list",  "Saffron", null, true, "en");

        float a3Width   = extractPageWidth(a3Pdf);
        float decoWidth = extractPageWidth(decoPdf);
        float listWidth = extractPageWidth(listPdf);

        // Both A3 and DECO are landscape A3 (≈1190pt wide vs A4 ≈595pt wide)
        assertThat(a3Width).as("A3 landscape width should exceed A4 width")
                           .isGreaterThan(listWidth + 100f);
        assertThat(decoWidth).as("DECO width should match A3 landscape width")
                             .isEqualTo(a3Width);
    }

    /** Reads the /MediaBox width from the first page by scanning raw PDF bytes. */
    private static float extractPageWidth(byte[] pdf) {
        String text = new String(pdf);
        int idx = text.indexOf("/MediaBox");
        if (idx < 0) return 0;
        // /MediaBox [llx lly urx ury]
        int open = text.indexOf('[', idx);
        int close = text.indexOf(']', open);
        if (open < 0 || close < 0) return 0;
        String[] parts = text.substring(open + 1, close).trim().split("\\s+");
        if (parts.length < 3) return 0;
        try { return Float.parseFloat(parts[2]); } catch (NumberFormatException e) { return 0; }
    }

    private static MenuCategory category(String id, String name, int sort) {
        MenuCategory c = new MenuCategory();
        c.setId(id);
        c.setName(name);
        c.setSortOrder(sort);
        c.setActive(true);
        return c;
    }

    private static MenuItem item(String catId, String name, String desc, int price, boolean featured, String diet) {
        MenuItem i = new MenuItem();
        i.setId(name.toLowerCase().replace(' ', '-'));
        i.setCategoryId(catId);
        i.setName(name);
        i.setLongDescription(desc);
        i.setSellPrice(new BigDecimal(price));
        i.setVatRatePct(new BigDecimal("8.00"));
        i.setActive(true);
        i.setFeatured(featured);
        if (diet != null) i.setDietaryTags(diet);
        i.setAllergens("gluten,dairy");
        return i;
    }

    /** Minimal stub that only exposes the upload dir — no real I/O. */
    private static final class StubFileStorage extends FileStorageService {
        private final Path uploadDir;
        StubFileStorage(Path dir) throws IOException {
            super(dir.toString(), null, null);
            this.uploadDir = dir;
        }
        @Override public Path getUploadDir() { return uploadDir; }
    }

    /** Returns the precomputed categories/items the test wants the renderer to consume. */
    private static final class StubMenuService extends MenuService {
        private final List<MenuCategory> categories;
        private final Map<String, List<MenuItem>> items;
        StubMenuService(List<MenuCategory> categories, Map<String, List<MenuItem>> items) {
            super(null, null, null);
            this.categories = categories;
            this.items = items;
        }
        @Override public List<MenuCategory> activeCategoriesInOrder() { return categories; }
        @Override public List<MenuItem> activeItemsForCategory(String id) {
            return items.getOrDefault(id, List.of());
        }
    }
}
