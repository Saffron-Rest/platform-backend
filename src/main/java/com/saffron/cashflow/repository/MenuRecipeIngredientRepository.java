package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.MenuRecipeIngredient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuRecipeIngredientRepository extends JpaRepository<MenuRecipeIngredient, String> {

    List<MenuRecipeIngredient> findByRecipeIdOrderBySortOrderAsc(String recipeId);

    /** Find every recipe that consumes a given stock item. Powers
     *  "recipes affected by this ingredient" hints when stock prices
     *  change. */
    List<MenuRecipeIngredient> findByStockItemId(String stockItemId);

    void deleteByRecipeId(String recipeId);
}
