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

        for (String layout : new String[]{"grid", "list", "compact"}) {
            byte[] bytes = service.buildMenu(layout, "Saffron Test", "Smoke test menu", true, "en");
            assertThat(bytes).isNotEmpty();
            assertThat(new String(bytes, 0, 4)).as("PDF header for layout %s", layout).isEqualTo("%PDF");
        }
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
