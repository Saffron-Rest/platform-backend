package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.repository.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
    private final PosSaleRepository posSaleRepository;
    private final DailyEntryRepository entryRepository;
    private final EntryService entryService;

    public PosSalePostHandler(StockItemRepository stockItemRepository,
                              StockService stockService,
                              PosSaleRepository posSaleRepository,
                              DailyEntryRepository entryRepository,
                              @Lazy EntryService entryService) {
        this.stockItemRepository = stockItemRepository;
        this.stockService = stockService;
        this.posSaleRepository = posSaleRepository;
        this.entryRepository = entryRepository;
        this.entryService = entryService;
    }

    /**
     * Aggregates all POS sales for a given business day and auto-populates the
     * corresponding cashier's DailyEntry with cash and card totals.
     *
     * <p>Called at POS shift close. Only populates DRAFT entries — a locked or
     * submitted entry is never overwritten. Sets {@code posAutoPopulated = true}
     * so the cashier report UI can show "Pre-filled from POS" and ask for
     * confirmation rather than manual entry.</p>
     */
    @Transactional
    public void autoPopulateDailyEntry(String cashierId, LocalDate businessDay) {
        List<PosSale> sales = posSaleRepository.findByBusinessDay(businessDay);
        if (sales.isEmpty()) return;

        BigDecimal cashTotal = BigDecimal.ZERO;
        BigDecimal cardTotal = BigDecimal.ZERO;
        for (PosSale s : sales) {
            BigDecimal gross = s.getUnitPrice()
                    .multiply(s.getQuantity())
                    .subtract(s.getDiscountAmount() != null ? s.getDiscountAmount().multiply(s.getQuantity()) : BigDecimal.ZERO);
            String method = s.getPaymentMethod() != null ? s.getPaymentMethod().toUpperCase() : "CASH";
            if (method.contains("CARD") || method.contains("CREDIT") || method.contains("DEBIT")) {
                cardTotal = cardTotal.add(gross);
            } else {
                cashTotal = cashTotal.add(gross);
            }
        }

        Optional<DailyEntry> entryOpt = entryRepository.findByCashierIdAndDateAndDeletedAtIsNull(cashierId, businessDay);
        if (entryOpt.isEmpty()) return;
        DailyEntry entry = entryOpt.get();
        if (entry.getStatus() != EntryStatus.DRAFT) return;

        entry.setCashSales(cashTotal.setScale(2, java.math.RoundingMode.HALF_UP));
        entry.setCardSales(cardTotal.setScale(2, java.math.RoundingMode.HALF_UP));
        entry.setPosAutoPopulated(true);
        entryRepository.save(entry);
        try {
            entryService.recalculateEntry(entry.getId());
        } catch (Exception ex) {
            LOG.warn("Failed to recalculate entry {} after POS auto-populate: {}", entry.getId(), ex.getMessage());
        }
        LOG.info("Auto-populated DailyEntry {} from POS: cash={} card={}", entry.getId(), cashTotal, cardTotal);
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
