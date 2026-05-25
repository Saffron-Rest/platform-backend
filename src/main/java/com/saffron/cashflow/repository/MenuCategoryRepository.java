package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, String> {

    List<MenuCategory> findAllByOrderBySortOrderAscNameAsc();

    List<MenuCategory> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    Optional<MenuCategory> findFirstByNameIgnoreCase(String name);
}
