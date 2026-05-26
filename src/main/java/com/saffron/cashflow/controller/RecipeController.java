package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.RecipeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for recipes / cost cards.
 *
 * <p>Mounted at {@code /api/recipes}. All endpoints are
 * permission-gated inside {@link RecipeService} — the controller is a
 * thin pass-through.</p>
 *
 * <p>The {@code POST /preview} endpoint lets the admin modal show a
 * live cost + suggested price as it's edited, without saving anything
 * to the database. It accepts the same payload shape as create/update
 * so the client can re-use a single state shape.</p>
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(name = "includeInactive", required = false, defaultValue = "false")
            boolean includeInactive) {
        return recipeService.list(includeInactive);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return recipeService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return recipeService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        return recipeService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> archive(@PathVariable String id) {
        recipeService.archive(id);
        return Map.of("archived", id);
    }

    /**
     * Compute cost + suggestion for a draft recipe payload. Same body
     * shape as create/update. Idempotent and DB-read-only — used by
     * the live-preview pane in the modal.
     */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        return recipeService.preview(body);
    }

    /**
     * Push the recipe's computed cost (and optionally its suggested
     * sales price) onto the linked {@code MenuItem}. Body:
     * <pre>{ "applySuggestedPrice": true|false }</pre>
     * Requires {@code MENU_MANAGE} in addition to
     * {@code MENU_RECIPES_MANAGE} on the service side.
     */
    @PostMapping("/{id}/apply-to-menu")
    public Map<String, Object> applyToMenu(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        boolean applyPrice = body != null
                && Boolean.parseBoolean(String.valueOf(body.getOrDefault("applySuggestedPrice", "false")));
        return recipeService.applyToMenu(id, applyPrice);
    }

    /**
     * Append-only history of cost snapshots for a recipe. Used by the
     * editor's "price drift" pane.
     */
    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable String id) {
        return recipeService.history(id);
    }

    /**
     * Recipes that consume a given stock item. Used by the stock page
     * to warn that changing a unit cost will reprice N dishes.
     */
    @GetMapping("/affected-by-stock/{stockItemId}")
    public List<Map<String, Object>> affectedByStock(@PathVariable String stockItemId) {
        return recipeService.affectedByStockItem(stockItemId);
    }
}
