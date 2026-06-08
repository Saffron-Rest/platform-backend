package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.ManualDeliveryIncome;
import com.saffron.cashflow.domain.SystemSetting;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.EntryMapper;
import com.saffron.cashflow.util.TreasurySettings;
import java.math.BigDecimal;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Locale;

@Service
public class AnalyticsService {

    private final DailyEntryRepository entryRepository;
    private final SystemSettingRepository settingRepository;
    private final ManualDeliveryService manualDeliveryService;

    public AnalyticsService(
            DailyEntryRepository entryRepository,
            SystemSettingRepository settingRepository,
            ManualDeliveryService manualDeliveryService) {
        this.entryRepository = entryRepository;
        this.settingRepository = settingRepository;
        this.manualDeliveryService = manualDeliveryService;
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

        TreasurySettings treasury = loadTreasurySettings();
        Totals totals = new Totals();
        Map<LocalDate, List<DailyEntry>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (DailyEntry e : loaded) {
            totals.add(e, treasury);
            byDate.computeIfAbsent(e.getDate(), d -> new ArrayList<>()).add(e);
        }
        // Manual delivery income (Finance page) is also revenue — group by date so
        // each day's totals reflect ALL income recorded for that day, not just shift
        // reports. We bucket once and reuse for both period totals and per-day rows.
        List<ManualDeliveryIncome> allManual = manualDeliveryService.findBetween(from, to);
        Map<LocalDate, List<ManualDeliveryIncome>> manualByDate = new HashMap<>();
        for (ManualDeliveryIncome m : allManual) {
            totals.addManualDeliveryRow(m, treasury);
            manualByDate.computeIfAbsent(m.getEffectiveDate(), d -> new ArrayList<>()).add(m);
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
                dayTotals.add(e, treasury);
            }
            for (ManualDeliveryIncome m : manualByDate.getOrDefault(date, List.of())) {
                dayTotals.addManualDeliveryRow(m, treasury);
            }

            List<Map<String, Object>> reports = new ArrayList<>();
            for (DailyEntry e : dayList) {
                Map<String, Object> row = new LinkedHashMap<>(EntryMapper.toMap(e, treasury));
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

    private TreasurySettings loadTreasurySettings() {
        return settingRepository.findById(TreasurySettings.SETTINGS_KEY)
                .map(SystemSetting::getValue)
                .map(TreasurySettings::fromMap)
                .orElseGet(TreasurySettings::new);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> forecastDays(int days) {
        AuthHelper.requireOperations();
        if (days < 1 || days > 14) days = 7;

        LocalDate today = LocalDate.now();
        LocalDate lookbackFrom = today.minusWeeks(8);
        LocalDate lookbackTo = today.minusDays(1);

        // Single query for all locked entries + all manual delivery in the window
        List<DailyEntry> locked = entryRepository.findLockedBetween(lookbackFrom, lookbackTo, EntryStatus.LOCKED);
        List<ManualDeliveryIncome> manual = manualDeliveryService.findBetween(lookbackFrom, lookbackTo);

        // Aggregate total + channel sales by date
        Map<LocalDate, Double> salesByDate = new HashMap<>();
        Map<LocalDate, Double> cashByDate  = new HashMap<>();
        Map<LocalDate, Double> cardByDate  = new HashMap<>();
        Map<LocalDate, Double> delivByDate = new HashMap<>();
        for (DailyEntry e : locked) {
            LocalDate d = e.getDate();
            salesByDate.merge(d, EntryCalculator.toDouble(EntryCalculator.totalSales(e)), Double::sum);
            cashByDate .merge(d, EntryCalculator.toDouble(e.getCashSales()),                Double::sum);
            cardByDate .merge(d, EntryCalculator.toDouble(e.getCardSales()),                Double::sum);
            double plat = EntryCalculator.toDouble(e.getWoltSales())
                        + EntryCalculator.toDouble(e.getBoltSales())
                        + EntryCalculator.toDouble(e.getUberEatsSales())
                        + EntryCalculator.toDouble(e.getGlovoSales())
                        + EntryCalculator.toDouble(e.getOtherPlatformSales());
            delivByDate.merge(d, plat, Double::sum);
        }
        for (ManualDeliveryIncome m : manual) {
            LocalDate d = m.getEffectiveDate();
            if (salesByDate.containsKey(d)) {
                double gross = EntryCalculator.toDouble(m.getGrossAmount());
                salesByDate.merge(d, gross, Double::sum);
                delivByDate.merge(d, gross, Double::sum);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate target = today.plusDays(i);
            DayOfWeek dow = target.getDayOfWeek();

            // Same-weekday entries sorted newest-first
            List<LocalDate> sameDates = salesByDate.entrySet().stream()
                    .filter(e -> e.getKey().getDayOfWeek() == dow)
                    .sorted(Map.Entry.<LocalDate, Double>comparingByKey().reversed())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            List<Double> samples = sameDates.stream()
                    .map(salesByDate::get)
                    .collect(Collectors.toList());

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", target.toString());
            day.put("dayName", dow.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            day.put("isToday", i == 0);
            day.put("sampleSize", samples.size());

            if (samples.size() < 3) {
                result.add(day);
                continue;
            }

            double low  = samples.stream().mapToDouble(d -> d).min().orElse(0);
            double high = samples.stream().mapToDouble(d -> d).max().orElse(0);

            // Exponential weighted average — most recent week gets 2^(n-1) weight
            double totalWeight = 0, weightedSum = 0;
            for (int k = 0; k < samples.size(); k++) {
                double w = Math.pow(2, samples.size() - 1 - k);
                weightedSum += samples.get(k) * w;
                totalWeight += w;
            }
            double predictedSales = totalWeight > 0 ? weightedSum / totalWeight : 0;

            // Trend: newest half avg vs oldest half avg
            int half = samples.size() / 2;
            double recentAvg = samples.subList(0, half).stream().mapToDouble(d -> d).average().orElse(0);
            double olderAvg  = samples.subList(samples.size() - half, samples.size()).stream().mapToDouble(d -> d).average().orElse(0);
            String trend = "FLAT";
            if (olderAvg > 0) {
                double change = (recentAvg - olderAvg) / olderAvg;
                trend = change > 0.05 ? "UP" : change < -0.05 ? "DOWN" : "FLAT";
            }

            // Confidence: coefficient of variation on the raw samples
            double mean = samples.stream().mapToDouble(d -> d).average().orElse(0);
            double variance = samples.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
            double cv = mean > 0 ? Math.sqrt(variance) / mean : 1.0;
            String confidence = cv < 0.10 ? "HIGH" : cv < 0.20 ? "MEDIUM" : "LOW";

            // Channel split: average cash/card/delivery percentages across same-weekday dates
            double avgCash  = sameDates.stream().mapToDouble(d -> cashByDate .getOrDefault(d, 0.0)).average().orElse(0);
            double avgCard  = sameDates.stream().mapToDouble(d -> cardByDate .getOrDefault(d, 0.0)).average().orElse(0);
            double avgDeliv = sameDates.stream().mapToDouble(d -> delivByDate.getOrDefault(d, 0.0)).average().orElse(0);
            double chanTotal = avgCash + avgCard + avgDeliv;
            if (chanTotal > 0) {
                day.put("cashPct",     (int) Math.round(avgCash  / chanTotal * 100));
                day.put("cardPct",     (int) Math.round(avgCard  / chanTotal * 100));
                day.put("deliveryPct", (int) Math.round(avgDeliv / chanTotal * 100));
            }

            day.put("predictedSales", predictedSales);
            day.put("low",        low);
            day.put("high",       high);
            day.put("trend",      trend);
            day.put("confidence", confidence);
            result.add(day);
        }

        return Map.of("days", result);
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
        double manualDeliverySales;
        double returns;
        double expenses;
        double payouts;
        double expectedCash;
        double actualCash;
        double difference;
        double cardBalance;
        int draftCount;
        int lockedCount;

        void add(DailyEntry e, TreasurySettings treasury) {
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
            cardBalance += EntryCalculator.toDouble(EntryCalculator.cardNetForTreasury(e, treasury));
            if (e.getStatus() == EntryStatus.DRAFT) {
                draftCount++;
            } else if (e.getStatus() == EntryStatus.LOCKED) {
                lockedCount++;
            }
        }

        /** Apply a manual delivery row to every relevant bucket. Gross feeds
         *  revenue totals (totalSales / platformSales); settled portion feeds
         *  the card balance (when the bank actually credits the money). */
        void addManualDeliveryRow(ManualDeliveryIncome m, TreasurySettings treasury) {
            double gross = EntryCalculator.toDouble(m.getGrossAmount());
            totalSales += gross;
            platformSales += gross;
            manualDeliverySales += gross;
            cardBalance += EntryCalculator.toDouble(
                    com.saffron.cashflow.util.ManualDeliverySettlement.settledToCard(m, treasury));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalSales", totalSales);
            m.put("cashSales", cashSales);
            m.put("cardSales", cardSales);
            m.put("platformSales", platformSales);
            m.put("manualDeliverySales", manualDeliverySales);
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
        double cardBalance;

        void add(DailyEntry e, TreasurySettings treasury) {
            totalSales += EntryCalculator.toDouble(EntryCalculator.totalSales(e));
            cashSales += EntryCalculator.toDouble(e.getCashSales());
            cardSales += EntryCalculator.toDouble(e.getCardSales());
            expenses += EntryCalculator.toDouble(EntryCalculator.totalExpenses(e));
            difference += EntryCalculator.toDouble(e.getDifference());
            cardBalance += EntryCalculator.toDouble(EntryCalculator.cardNetForTreasury(e, treasury));
        }

        void addManualDeliveryRow(ManualDeliveryIncome m, TreasurySettings treasury) {
            double gross = EntryCalculator.toDouble(m.getGrossAmount());
            totalSales += gross;
            cardBalance += EntryCalculator.toDouble(
                    com.saffron.cashflow.util.ManualDeliverySettlement.settledToCard(m, treasury));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalSales", totalSales);
            m.put("cashSales", cashSales);
            m.put("cardSales", cardSales);
            m.put("expenses", expenses);
            m.put("difference", difference);
            m.put("cardBalance", cardBalance);
            return m;
        }
    }
}
