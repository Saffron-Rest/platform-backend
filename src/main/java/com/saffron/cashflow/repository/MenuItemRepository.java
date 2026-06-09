package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, String> {

    List<MenuItem> findAllByOrderByDisplayOrderAscNameAsc();

    List<MenuItem> findAllByCategoryIdOrderByDisplayOrderAscNameAsc(String categoryId);

    List<MenuItem> findAllByActiveTrueOrderByDisplayOrderAscNameAsc();

    // Legacy name-only queries (used by analytics / import helpers that don't need display order)
    List<MenuItem> findAllByOrderByNameAsc();

    List<MenuItem> findAllByCategoryIdOrderByNameAsc(String categoryId);

    List<MenuItem> findAllByActiveTrueOrderByNameAsc();

    Optional<MenuItem> findFirstBySkuIgnoreCase(String sku);

    Optional<MenuItem> findFirstByBarcodeAndActiveTrue(String barcode);

    Optional<MenuItem> findFirstByNameIgnoreCase(String name);
}
