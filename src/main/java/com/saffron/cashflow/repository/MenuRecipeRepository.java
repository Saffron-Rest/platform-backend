package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.MenuRecipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuRecipeRepository extends JpaRepository<MenuRecipe, String> {

    List<MenuRecipe> findByActiveTrueOrderByNameAsc();

    List<MenuRecipe> findAllByOrderByNameAsc();

    /** Look up the recipe attached to a given menu item, if any. Used
     *  when the admin opens a menu item and wants to see its cost
     *  breakdown. */
    Optional<MenuRecipe> findFirstByMenuItemId(String menuItemId);

    /** All recipes that include {@code stockItemId} as an ingredient.
     *  Used (in a future iteration) to broadcast cost-impact warnings
     *  when an admin changes a stock item's unit cost. */
    List<MenuRecipe> findByMenuItemId(String menuItemId);
}
