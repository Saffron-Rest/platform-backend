package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.domain.SupplierInvoice;
import com.saffron.cashflow.domain.SupplierInvoiceStatus;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.OwnerExpenseRepository;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.repository.StockItemRepository;
import com.saffron.cashflow.repository.SupplierInvoiceRepository;
import com.saffron.cashflow.security.AuthHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates all accounting signals into one response so the admin has
 * a single "what do I need to do today?" view.
 *
 * <p>Every field is computed from live data on each call — no caching —
 * so the checklist always reflects the current state.</p>
 */
@Service
public class AccountingHubService {

    private final SupplierInvoiceRepository invoiceRepository;
    private final DailyEntryRepository entryRepository;
    private final OwnerExpenseRepository ownerExpenseRepository;
    private final PosSaleRepository saleRepository;
    private final StockItemRepository stockItemRepository;
    private final ProfitLossService profitLossService;

    public AccountingHubService(
            SupplierInvoiceRepository invoiceRepository,
            DailyEntryRepository entryRepository,
            OwnerExpenseRepository ownerExpenseRepository,
            PosSaleRepository saleRepository,
            StockItemRepository stockItemRepository,
            ProfitLossService profitLossService) {
        this.invoiceRepository = invoiceRepository;
        this.entryRepository = entryRepository;
        this.ownerExpenseRepository = ownerExpenseRepository;
        this.saleRepository = saleRepository;
        this.stockItemRepository = stockItemRepository;
        this.profitLossService = profitLossService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> hub() {
        AuthHelper.requireOperations();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        // ── Payables ──────────────────────────────────────────────────────────
        List<SupplierInvoice> outstanding = invoiceRepository.findByStatuses(
                List.of(SupplierInvoiceStatus.UNPAID, SupplierInvoiceStatus.PARTIAL));

        List<SupplierInvoice> overdue = outstanding.stream()
                .filter(i -> i.getDueDate().isBefore(today))
                .toList();
        List<SupplierInvoice> dueSoon = outstanding.stream()
                .filter(i -> !i.getDueDate().isBefore(today)
                        && !i.getDueDate().isAfter(today.plusDays(7)))
                .toList();

        BigDecimal overdueAmt = overdue.stream().map(SupplierInvoice::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dueSoonAmt = dueSoon.stream().map(SupplierInvoice::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Draft cashier entries from previous days ───────────────────────────
        long oldDrafts = entryRepository.countDraftBefore(today);

        // ── Owner expenses pending reimbursement ──────────────────────────────
        List<Object[]> ownerRows = ownerExpenseRepository.countAndSumOutstanding();
        Object[] ownerRow = (ownerRows != null && !ownerRows.isEmpty()) ? ownerRows.get(0) : null;
        long ownerCount = ownerRow != null && ownerRow[0] != null
                ? ((Number) ownerRow[0]).longValue() : 0;
        BigDecimal ownerAmt = ownerRow != null && ownerRow[1] != null
                ? (BigDecimal) ownerRow[1] : BigDecimal.ZERO;

        // ── Unmatched POS items (last 60 days) ────────────────────────────────
        long unmatchedPos = saleRepository.countUnmatchedInRange(
                today.minusDays(60), today);

        // ── Stock status ──────────────────────────────────────────────────────
        List<StockItem> allActive = stockItemRepository.findAll().stream()
                .filter(StockItem::isActive).toList();
        long outOfStock = allActive.stream().filter(AccountingHubService::isOut).count();
        long lowStock = allActive.stream()
                .filter(s -> !isOut(s) && isLow(s)).count();

        // ── This-month P&L snapshot ───────────────────────────────────────────
        Map<String, Object> pnl = null;
        try {
            Map<String, Object> full = profitLossService.profitAndLoss(
                    monthStart.toString(), today.toString(), "GENERIC", "ALL", true);
            pnl = new LinkedHashMap<>();
            pnl.put("period", monthStart.toString());
            pnl.put("grossRevenue", full.get("grossRevenue"));
            pnl.put("netRevenue", full.get("netRevenue"));
            pnl.put("grossProfit", full.get("grossProfit"));
            pnl.put("operatingProfit", full.get("operatingProfit"));
            pnl.put("netProfit", full.get("netProfit"));
            pnl.put("grossMarginPct", full.get("grossMarginPct"));
            pnl.put("netMarginPct", full.get("netMarginPct"));
        } catch (Exception ignored) {
            // P&L is best-effort; the rest of the hub still loads.
        }

        // ── Assemble ──────────────────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("overduePayables", Map.of(
                "count", overdue.size(),
                "totalOutstanding", overdueAmt));

        result.put("dueSoonPayables", Map.of(
                "count", dueSoon.size(),
                "totalOutstanding", dueSoonAmt,
                "withinDays", 7));

        result.put("oldDraftEntries", Map.of(
                "count", oldDrafts));

        result.put("pendingOwnerExpenses", Map.of(
                "count", ownerCount,
                "totalOutstanding", ownerAmt));

        result.put("unmatchedPosItems", Map.of(
                "count", unmatchedPos));

        result.put("stockAlerts", Map.of(
                "outOfStock", outOfStock,
                "lowStock", lowStock));

        if (pnl != null) {
            result.put("thisMonth", pnl);
        }

        return result;
    }

    private static boolean isOut(StockItem s) {
        return s.getOnHand() != null && s.getOnHand().compareTo(BigDecimal.ZERO) <= 0;
    }

    private static boolean isLow(StockItem s) {
        if (s.getLowStockThreshold() == null || s.getOnHand() == null) return false;
        return s.getOnHand().compareTo(s.getLowStockThreshold()) <= 0;
    }
}
