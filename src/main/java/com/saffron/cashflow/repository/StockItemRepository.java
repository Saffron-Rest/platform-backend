package com.saffron.cashflow.repository;

import com.saffron.cashflow.domain.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StockItemRepository extends JpaRepository<StockItem, String> {

    /** Listing for the admin page. Active items first, name asc. */
    @Query("SELECT s FROM StockItem s ORDER BY s.active DESC, LOWER(s.name) ASC")
    List<StockItem> findAllOrdered();

    /** Used by the POS ingest hook — first try menu item id, then SKU
     *  case-insensitive. Returns at most one row (SKU has a unique idx). */
    Optional<StockItem> findFirstByMenuItemIdAndActiveTrue(String menuItemId);

    @Query("SELECT s FROM StockItem s WHERE s.active = true AND LOWER(s.sku) = LOWER(?1)")
    Optional<StockItem> findFirstBySkuIgnoreCase(String sku);
}
