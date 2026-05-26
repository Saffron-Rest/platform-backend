package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.MenuRecipeCostSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuRecipeCostSnapshotRepository extends JpaRepository<MenuRecipeCostSnapshot, String> {

    List<MenuRecipeCostSnapshot> findByRecipeIdOrderByTakenAtDesc(String recipeId);

    long deleteByRecipeId(String recipeId);
}
