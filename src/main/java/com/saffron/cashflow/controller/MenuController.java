package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.MenuService;
import com.saffron.cashflow.service.MenuService.MenuItemRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
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

    // ---------- CSV import ----------

    @PostMapping("/items/import")
    public Map<String, Object> importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return menuService.importCsv(file.getInputStream());
    }

    public record CategoryRequest(String name, Integer sortOrder, Boolean active) {}
}
