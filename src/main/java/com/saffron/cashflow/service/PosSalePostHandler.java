package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.repository.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Side-effects to run after a {@link PosSale} has been saved.
 *
 * <p>Today there is exactly one consumer — the stock-management feature.
 * Concentrating the wiring in one component means both POS ingest paths
 * ({@link PosIngestService} and {@code DotykackaSyncService}) only need
 * to call {@link #afterSaleSaved(PosSale)} once and we never miss a
 * decrement.</p>
 *
 * <p>The hook itself is best-effort: any failure is logged and swallowed
 * so a misconfigured stock item never blocks a POS sale from being
 * recorded. The sale itself is the source of truth — if the decrement
 * needs to be reapplied later we can do it manually from the audit log.</p>
 */
@Component
public class PosSalePostHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PosSalePostHandler.class);

    private final StockItemRepository stockItemRepository;
    private final StockService stockService;

    public PosSalePostHandler(StockItemRepository stockItemRepository, StockService stockService) {
        this.stockItemRepository = stockItemRepository;
        this.stockService = stockService;
    }

    /**
     * Resolve the {@link StockItem} for this sale and apply a SALE
     * movement. No-op when no stock item is linked.
     *
     * <p>Matching strategy:</p>
     * <ol>
     *   <li>Prefer {@code menuItemId} (set when the POS payload matched a
     *       known menu item by SKU/name).</li>
     *   <li>Fall back to the bare {@code sku} so admins can map stock
     *       directly to POS-only items (e.g. a raw ingredient billed via
     *       its own product code).</li>
     * </ol>
     */
    public void afterSaleSaved(PosSale sale) {
        if (sale == null || sale.getQuantity() == null) return;
        try {
            Optional<StockItem> match = Optional.empty();
            if (sale.getMenuItemId() != null) {
                match = stockItemRepository.findFirstByMenuItemIdAndActiveTrue(sale.getMenuItemId());
            }
            if (match.isEmpty() && sale.getSku() != null && !sale.getSku().isBlank()) {
                match = stockItemRepository.findFirstBySkuIgnoreCase(sale.getSku());
            }
            if (match.isEmpty()) return; // no linked stock — nothing to do
            stockService.recordSale(
                    match.get().getId(),
                    sale.getQuantity(),
                    sale.getId(),
                    sale.getItemName());
        } catch (Exception ex) {
            // Stock decrement should never block a POS ingest. Log and move on.
            LOG.warn("Stock decrement failed for POS sale {} ({}): {}",
                    sale.getId(), sale.getItemName(), ex.getMessage());
        }
    }
}
