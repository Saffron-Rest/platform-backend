package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.ManualDeliveryIncome;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.util.EntryCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class DashboardService {

    private final DailyEntryRepository entryRepository;
    private final ManualDeliveryService manualDeliveryService;

    public DashboardService(
            DailyEntryRepository entryRepository,
            ManualDeliveryService manualDeliveryService) {
        this.entryRepository = entryRepository;
        this.manualDeliveryService = manualDeliveryService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> today() {
        AuthUser user = AuthHelper.currentUser();
        LocalDate today = LocalDate.now();
        List<DailyEntry> entries = entryRepository.findByDateAndDeletedAtIsNull(today);
        if (user.role() == Role.CASHIER) {
            entries = entries.stream().filter(e -> user.id().equals(e.getCashierId())).toList();
        }

        double totalSales = 0, cashSales = 0, cardSales = 0, expenses = 0, netCashFlow = 0, difference = 0;
        double wolt = 0, bolt = 0, uber = 0, glovo = 0, other = 0;
        List<Map<String, Object>> entrySummaries = new ArrayList<>();

        for (DailyEntry e : entries) {
            var loaded = entryRepository.findActiveById(e.getId()).orElse(e);
            totalSales += EntryCalculator.toDouble(EntryCalculator.totalSales(loaded));
            cashSales += EntryCalculator.toDouble(loaded.getCashSales());
            cardSales += EntryCalculator.toDouble(loaded.getCardSales());
            wolt += EntryCalculator.toDouble(loaded.getWoltSales());
            bolt += EntryCalculator.toDouble(loaded.getBoltSales());
            uber += EntryCalculator.toDouble(loaded.getUberEatsSales());
            glovo += EntryCalculator.toDouble(loaded.getGlovoSales());
            other += EntryCalculator.toDouble(loaded.getOtherPlatformSales());
            expenses += EntryCalculator.toDouble(EntryCalculator.totalExpenses(loaded));
            netCashFlow += EntryCalculator.toDouble(
                    loaded.getOpeningBalance()
                            .add(EntryCalculator.totalSales(loaded))
                            .subtract(EntryCalculator.totalReturns(loaded))
                            .subtract(EntryCalculator.totalExpenses(loaded)));
            difference += EntryCalculator.toDouble(loaded.getDifference());

            if (loaded.getCashier() != null) {
                entrySummaries.add(Map.of(
                        "id", loaded.getId(),
                        "cashierId", loaded.getCashierId(),
                        "cashier", loaded.getCashier().getName(),
                        "status", loaded.getStatus().name(),
                        "difference", EntryCalculator.toDouble(loaded.getDifference())));
            }
        }

        // Roll today's manual delivery income (Finance page) into the same buckets
        // so the dashboard shows ALL revenue captured for today, not just sales
        // entered through a shift report. Manual entries are platform-source by
        // definition (cash/card unaffected); we add gross to platform + total.
        double manualDeliverySales = 0;
        if (user.role() != Role.CASHIER) {
            for (ManualDeliveryIncome m : manualDeliveryService.findBetween(today, today)) {
                double gross = EntryCalculator.toDouble(m.getGrossAmount());
                totalSales += gross;
                manualDeliverySales += gross;
                switch (m.getPlatform()) {
                    case WOLT -> wolt += gross;
                    case BOLT -> bolt += gross;
                    case UBER_EATS -> uber += gross;
                    case GLOVO -> glovo += gross;
                    case OTHER -> other += gross;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("entryCount", entries.size());
        result.put("totalSales", totalSales);
        result.put("cashSales", cashSales);
        result.put("cardSales", cardSales);
        result.put("manualDeliverySales", manualDeliverySales);
        result.put("platforms", Map.of("wolt", wolt, "bolt", bolt, "uber", uber, "glovo", glovo, "other", other));
        result.put("expenses", expenses);
        result.put("netCashFlow", netCashFlow);
        result.put("difference", difference);
        result.put("entries", entrySummaries);
        return result;
    }
}
