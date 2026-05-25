package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.FileStorageService;
import com.saffron.cashflow.service.MenuPrintService;
import com.saffron.cashflow.service.MenuService;
import com.saffron.cashflow.service.MenuService.MenuItemRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuService menuService;
    private final FileStorageService fileStorageService;
    private final MenuPrintService menuPrintService;

    public MenuController(
            MenuService menuService,
            FileStorageService fileStorageService,
            MenuPrintService menuPrintService) {
        this.menuService = menuService;
        this.fileStorageService = fileStorageService;
        this.menuPrintService = menuPrintService;
    }

    // ---------- Categories ----------

    @GetMapping("/categories")
    public List<Map<String, Object>> listCategories(
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return menuService.listCategories(includeArchived);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCategory(@RequestBody CategoryRequest req) {
        return menuService.createCategory(req.name(), req.sortOrder());
    }

    @PutMapping("/categories/{id}")
    public Map<String, Object> updateCategory(@PathVariable String id, @RequestBody CategoryRequest req) {
        return menuService.updateCategory(id, req.name(), req.sortOrder(), req.active());
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable String id) {
        menuService.deleteCategory(id);
    }

    // ---------- Items ----------

    @GetMapping("/items")
    public List<Map<String, Object>> listItems(
            @RequestParam(value = "categoryId", required = false) String categoryId,
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return menuService.listItems(categoryId, includeArchived);
    }

    @GetMapping("/items/{id}")
    public Map<String, Object> getItem(@PathVariable String id) {
        return menuService.getItem(id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createItem(@RequestBody MenuItemRequest req) {
        return menuService.createItem(req);
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(@PathVariable String id, @RequestBody MenuItemRequest req) {
        return menuService.updateItem(id, req);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable String id) {
        menuService.deleteItem(id);
    }

    // ---------- Photo upload ----------

    @PostMapping("/items/{id}/photo")
    public Map<String, Object> uploadPhoto(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file) throws IOException {
        String path = fileStorageService.storeMenuImage(file);
        return menuService.setItemImage(id, path);
    }

    @DeleteMapping("/items/{id}/photo")
    public Map<String, Object> deletePhoto(@PathVariable String id) {
        return menuService.clearItemImage(id);
    }

    // ---------- CSV import ----------

    @PostMapping("/items/import")
    public Map<String, Object> importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return menuService.importCsv(file.getInputStream());
    }

    // ---------- Printable PDF ----------

    /**
     * Generate a designer-style PDF menu for printing. Layouts:
     *   - {@code grid}    : photo cards in a 2-column grid (the default — modern and visual)
     *   - {@code list}    : single column with small thumbnails and full descriptions
     *   - {@code compact} : two-column text-only, perfect for table tents
     */
    @GetMapping(value = "/print", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printMenu(
            @RequestParam(value = "layout", defaultValue = "grid") String layout,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "subtitle", required = false) String subtitle,
            @RequestParam(value = "showPrices", defaultValue = "true") boolean showPrices,
            @RequestParam(value = "language", defaultValue = "en") String language,
            // Optional editorial copy — the "Our story" page and footer contact
            // block. All blank by default, in which case the renderer falls back
            // to a curated Azerbaijani-heritage narrative.
            @RequestParam(value = "storyTitle", required = false) String storyTitle,
            @RequestParam(value = "storyBody", required = false) String storyBody,
            @RequestParam(value = "contactBlock", required = false) String contactBlock) {
        byte[] pdf = menuPrintService.buildMenu(
                layout, title, subtitle, showPrices, language, storyTitle, storyBody, contactBlock);
        String filename = "saffron-menu-" + java.time.LocalDate.now() + ".pdf";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    public record CategoryRequest(String name, Integer sortOrder, Boolean active) {}
}
