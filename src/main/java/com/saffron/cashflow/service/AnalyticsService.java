package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.EntryMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class AnalyticsService {

    private final DailyEntryRepository entryRepository;

    public AnalyticsService(DailyEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> cashflow(String fromParam, String toParam, String cashierId, String statusParam) {
        AuthHelper.requireOperations();
        LocalDate to = parseDate(toParam, LocalDate.now());
        LocalDate from = parseDate(fromParam, to.withDayOfMonth(1));
        if (from.isAfter(to)) {
            throw new com.saffron.cashflow.web.BadRequestException("'from' must be on or before 'to'");
        }

        EntryStatus status = statusParam != null && !statusParam.isBlank()
                ? EntryStatus.valueOf(statusParam)
                : null;
        String filterCashier = cashierId != null && !cashierId.isBlank() ? cashierId : null;
        Specification<DailyEntry> spec = EntrySpecification.filter(filterCashier, from, to, status);
        List<DailyEntry> entries = entryRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "date"));

        List<DailyEntry> loaded = new ArrayList<>();
        for (DailyEntry e : entries) {
            loaded.add(entryRepository.findActiveByIdWithExpenses(e.getId()).orElse(e));
        }

        Totals totals = new Totals();
        Map<LocalDate, List<DailyEntry>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (DailyEntry e : loaded) {
            totals.add(e);
            byDate.computeIfAbsent(e.getDate(), d -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> days = new ArrayList<>();
        for (var dayEntry : byDate.entrySet()) {
            LocalDate date = dayEntry.getKey();
            List<DailyEntry> dayList = dayEntry.getValue();
            dayList.sort(Comparator
                    .comparing((DailyEntry e) -> e.getSubmittedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(DailyEntry::getId, Comparator.reverseOrder()));

            DayTotals dayTotals = new DayTotals();
            for (DailyEntry e : dayList) {
                dayTotals.add(e);
            }

            List<Map<String, Object>> reports = new ArrayList<>();
            for (DailyEntry e : dayList) {
                Map<String, Object> row = new LinkedHashMap<>(EntryMapper.toMap(e));
                row.put("totalSales", EntryCalculator.toDouble(EntryCalculator.totalSales(e)));
                row.put("totalReturns", EntryCalculator.toDouble(EntryCalculator.totalReturns(e)));
                row.put("totalExpenses", EntryCalculator.toDouble(EntryCalculator.totalExpenses(e)));
                reports.add(row);
            }

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("reportCount", dayList.size());
            day.put("draftCount", dayList.stream().filter(e -> e.getStatus() == EntryStatus.DRAFT).count());
            day.put("lockedCount", dayList.stream().filter(e -> e.getStatus() == EntryStatus.LOCKED).count());
            day.put("drawerActual", dayDrawerActual(dayList));
            day.put("totals", dayTotals.toMap());
            day.put("reports", reports);
            days.add(day);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("reportCount", loaded.size());
        result.put("dayCount", days.size());
        result.put("totals", totals.toMap());
        result.put("days", days);
        return result;
    }

    private static double dayDrawerActual(List<DailyEntry> dayEntries) {
        return dayEntries.stream()
                .filter(e -> e.getActualCashCounted() != null
                        && e.getActualCashCounted().compareTo(BigDecimal.ZERO) > 0)
                .max(Comparator.comparing(AnalyticsService::sortKey, Comparator.naturalOrder()))
                .map(e -> EntryCalculator.toDouble(e.getActualCashCounted()))
                .orElse(0.0);
    }

    private static String sortKey(DailyEntry e) {
        if (e.getSubmittedAt() != null) {
            return e.getSubmittedAt().toString();
        }
        return e.getId();
    }

    private static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(s);
    }

    private static final class Totals {
        double totalSales;
        double cashSales;
        double cardSales;
        double platformSales;
        double returns;
        double expenses;
        double payouts;
        double expectedCash;
        double actualCash;
        double difference;
        double cardBalance;
        int draftCount;
        int lockedCount;

        void add(DailyEntry e) {
            totalSales += EntryCalculator.toDouble(EntryCalculator.totalSales(e));
            cashSales += EntryCalculator.toDouble(e.getCashSales());
            cardSales += EntryCalculator.toDouble(e.getCardSales());
            platformSales += EntryCalculator.toDouble(e.getWoltSales())
                    + EntryCalculator.toDouble(e.getBoltSales())
                    + EntryCalculator.toDouble(e.getUberEatsSales())
                    + EntryCalculator.toDouble(e.getGlovoSales())
                    + EntryCalculator.toDouble(e.getOtherPlatformSales());
            returns += EntryCalculator.toDouble(EntryCalculator.totalReturns(e));
            expenses += EntryCalculator.toDouble(EntryCalculator.totalExpenses(e));
            payouts += EntryCalculator.toDouble(EntryCalculator.totalPayouts(e));
            expectedCash += EntryCalculator.toDouble(e.getClosingBalance());
            actualCash += EntryCalculator.toDouble(e.getActualCashCounted());
            difference += EntryCalculator.toDouble(e.getDifference());
            cardBalance += EntryCalculator.toDouble(EntryCalculator.cardBalance(e));
            if (e.getStatus() == EntryStatus.DRAFT) {
                draftCount++;
            } else if (e.getStatus() == EntryStatus.LOCKED) {
                lockedCount++;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalSales", totalSales);
            m.put("cashSales", cashSales);
            m.put("cardSales", cardSales);
            m.put("platformSales", platformSales);
            m.put("returns", returns);
            m.put("expenses", expenses);
            m.put("payouts", payouts);
            m.put("expectedCash", expectedCash);
            m.put("actualCash", actualCash);
            m.put("difference", difference);
            m.put("cardBalance", cardBalance);
            m.put("draftCount", draftCount);
            m.put("lockedCount", lockedCount);
            return m;
        }
    }

    private static final class DayTotals {
        double totalSales;
        double cashSales;
        double cardSales;
        double expenses;
        double difference;

        void add(DailyEntry e) {
            totalSales += EntryCalculator.toDouble(EntryCalculator.totalSales(e));
            cashSales += EntryCalculator.toDouble(e.getCashSales());
            cardSales += EntryCalculator.toDouble(e.getCardSales());
            expenses += EntryCalculator.toDouble(EntryCalculator.totalExpenses(e));
            difference += EntryCalculator.toDouble(e.getDifference());
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "totalSales", totalSales,
                    "cashSales", cashSales,
                    "cardSales", cardSales,
                    "expenses", expenses,
                    "difference", difference);
        }
    }
}
