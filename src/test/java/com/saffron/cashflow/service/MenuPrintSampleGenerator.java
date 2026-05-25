package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.MenuCategory;
import com.saffron.cashflow.domain.MenuItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Generates a sample menu PDF for each layout into target/sample-menus/ so the
 * redesigned print pipeline can be inspected without running the full backend.
 * Run with: mvn -Dtest=MenuPrintSampleGenerator test
 */
class MenuPrintSampleGenerator {

    @Test
    void writeSampleMenus() throws IOException {
        Path uploadDir = Files.createTempDirectory("menu-print-sample");
        FileStorageService storage = new FileStorageService(uploadDir.toString(), null, null) {};

        List<MenuCategory> categories = List.of(
                cat("c1", "Starters", 10),
                cat("c2", "Mains", 20),
                cat("c3", "Desserts", 30),
                cat("c4", "Drinks", 40));

        Map<String, List<MenuItem>> items = Map.of(
                "c1", List.of(
                        item("c1", "Kuku", "Traditional Azerbaijani herb omelette, walnut, dill", 24, true,
                                "vegetarian", "egg,nuts"),
                        item("c1", "Dolma", "Vine leaves stuffed with lamb, rice, mint", 28, false,
                                null, "gluten,dairy"),
                        item("c1", "Badimjan Salati", "Roasted aubergine, tomato, sumac", 22, false,
                                "vegan,vegetarian", "")),
                "c2", List.of(
                        item("c2", "Lamb Plov", "Slow-braised lamb shoulder, saffron rice, chestnuts, raisins",
                                58, true, null, "dairy"),
                        item("c2", "Lavangi", "Roasted chicken stuffed with walnut and onion paste",
                                52, false, null, "nuts,gluten"),
                        item("c2", "Sturgeon Kebab", "Caspian sturgeon, charcoal grill, pomegranate molasses",
                                72, true, null, "fish")),
                "c3", List.of(
                        item("c3", "Shekerbura", "Walnut and cardamom pastry, dusted sugar", 18, false,
                                "vegetarian", "gluten,nuts,dairy,egg"),
                        item("c3", "Paxlava", "Honey-soaked walnut layers, cinnamon", 18, false,
                                "vegetarian", "gluten,nuts,dairy")),
                "c4", List.of(
                        item("c4", "Azerbaijani Black Tea", "Loose-leaf in armudu glass, served with rock sugar",
                                8, false, "vegan,vegetarian", ""),
                        item("c4", "Sherbet of Saffron & Rose", "House-made, lightly sparkling",
                                14, false, "vegan,vegetarian", "")));

        MenuService menu = new MenuService(null, null, null) {
            @Override public List<MenuCategory> activeCategoriesInOrder() { return categories; }
            @Override public List<MenuItem> activeItemsForCategory(String id) {
                return items.getOrDefault(id, List.of());
            }
        };

        MenuPrintService service = new MenuPrintService(menu, storage);
        Path out = Paths.get("target/sample-menus");
        Files.createDirectories(out);

        String storyTitle = "Our story";
        String storyBody = "From the silk-road kitchens of Bakı, Şəki and Lənkəran we bring you Azerbaijan — "
                + "a cuisine shaped by saffron, smoked tea and slow time. Every plate on this menu started "
                + "in a family kitchen long before it reached ours.\n\n"
                + "We grow our own saffron on a small Mazovian field; we cure our pomegranates by hand; our "
                + "tandir oven is fired with apple wood. Some recipes — like our dolma — are 700 years old. "
                + "Some — like the sturgeon kebab — are only ours. Either way, we hope you stay a little "
                + "longer than you planned.";
        String contactBlock = "Saffron Restaurant\nul. Marszałkowska 1, 00-001 Warsaw\n+48 22 000 00 00  ·  saffron.waw.pl";

        for (String layout : new String[]{"grid", "list", "compact"}) {
            byte[] pdf = service.buildMenu(layout, "Saffron",
                    "Authentic Azerbaijani Restaurant — Warsaw", true, "en",
                    storyTitle, storyBody, contactBlock);
            Path file = out.resolve("menu-" + layout + ".pdf");
            Files.write(file, pdf);
            System.out.println("Wrote " + file.toAbsolutePath() + " (" + pdf.length + " bytes)");
        }
    }

    private static MenuCategory cat(String id, String name, int sort) {
        MenuCategory c = new MenuCategory();
        c.setId(id); c.setName(name); c.setSortOrder(sort); c.setActive(true);
        return c;
    }

    private static MenuItem item(String catId, String name, String desc, int price,
                                 boolean featured, String diet, String allergens) {
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
        if (allergens != null) i.setAllergens(allergens);
        return i;
    }
}
