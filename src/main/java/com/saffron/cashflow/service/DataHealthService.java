package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.*;
import com.saffron.cashflow.security.AuthHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Aggregates "needs attention" items across the system into a single
 * inbox-style payload. Each item has an `id`, `severity`, `title`,
 * `description`, and an action URL the user can click to resolve it.
 *
 * Pure read service — never modifies state. Designed to be called once
 * per dashboard refresh (cheap enough today; future caching can be added
 * without changing the contract).
 */
@Service
public class DataHealthService {

    /** Drafts older than this many days are flagged as stale. */
    private static final long STALE_DRAFT_DAYS = 2;
    /** Cash mismatch threshold to flag as a high-severity issue. */
    private static final BigDecimal SIGNIFICANT_MISMATCH = new BigDecimal("50.00");
    /** How far back to scan when looking for missing reports. */
    private static final long MISSING_REPORT_LOOKBACK_DAYS = 7;
    /** Cap on how many items each category emits. */
    private static final int MAX_PER_BUCKET = 25;

    private final DailyEntryRepository entryRepository;
    private final WorkShiftRepository workShiftRepository;
    private final UserRepository userRepository;
    private final ZoneId zoneId;

    public DataHealthService(
            DailyEntryRepository entryRepository,
            WorkShiftRepository workShiftRepository,
            UserRepository userRepository,
            @Value("${app.timezone:Europe/Warsaw}") String timezone) {
        this.entryRepository = entryRepository;
        this.workShiftRepository = workShiftRepository;
        this.userRepository = userRepository;
        this.zoneId = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> compute() {
        AuthHelper.requireOperations();
        LocalDate today = LocalDate.now(zoneId);

        List<Map<String, Object>> staleDrafts = findStaleDrafts(today);
        List<Map<String, Object>> missingFiles = findReportsWithoutFiles(today);
        List<Map<String, Object>> mismatches = findCashMismatches();
        List<Map<String, Object>> missingReports = findScheduledButNoReport(today);
        List<Map<String, Object>> inactiveWithDrafts = findInactiveCashiersWithDrafts();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        if (!staleDrafts.isEmpty()) groups.put("staleDrafts", staleDrafts);
        if (!missingFiles.isEmpty()) groups.put("missingPosFiles", missingFiles);
        if (!mismatches.isEmpty()) groups.put("cashMismatches", mismatches);
        if (!missingReports.isEmpty()) groups.put("missingReports", missingReports);
        if (!inactiveWithDrafts.isEmpty()) groups.put("inactiveCashiers", inactiveWithDrafts);
        out.put("groups", groups);

        int total = staleDrafts.size() + missingFiles.size() + mismatches.size()
                + missingReports.size() + inactiveWithDrafts.size();
        out.put("total", total);

        // Per-severity counts for the nav badge — high gets a red dot, the
        // rest aggregate into the inbox counter.
        int high = (int) mismatches.stream()
                .filter(m -> "high".equals(m.get("severity"))).count()
                + (int) staleDrafts.stream()
                .filter(m -> "high".equals(m.get("severity"))).count();
        out.put("highSeverity", high);

        return out;
    }

    // ---------- detectors ----------

    private List<Map<String, Object>> findStaleDrafts(LocalDate today) {
        LocalDate cutoff = today.minusDays(STALE_DRAFT_DAYS);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DailyEntry e : entryRepository.findAll()) {
            if (e.getDeletedAt() != null) continue;
            if (e.getStatus() != EntryStatus.DRAFT) continue;
            if (!e.getDate().isBefore(cutoff)) continue;
            long days = ChronoUnit.DAYS.between(e.getDate(), today);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "stale-draft:" + e.getId());
            m.put("severity", days >= 7 ? "high" : "medium");
            m.put("title", (e.getCashier() != null ? e.getCashier().getName() : "Cashier")
                    + " · draft from " + e.getDate());
            m.put("description", "Draft has been open for " + days + " days. Submit or remove it.");
            m.put("url", "/entry/" + e.getId());
            m.put("when", e.getDate().toString());
            items.add(m);
            if (items.size() >= MAX_PER_BUCKET) break;
        }
        items.sort((a, b) -> String.valueOf(b.get("when")).compareTo(String.valueOf(a.get("when"))));
        return items;
    }

    /** Submitted shifts without any uploaded file (POS Z-report etc.). Limited
     *  to the last 14 days so we don't keep nagging about ancient gaps. */
    private List<Map<String, Object>> findReportsWithoutFiles(LocalDate today) {
        LocalDate cutoff = today.minusDays(14);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DailyEntry e : entryRepository.findAll()) {
            if (e.getDeletedAt() != null) continue;
            if (e.getStatus() != EntryStatus.LOCKED) continue;
            if (e.getDate().isBefore(cutoff)) continue;
            if (e.getFiles() != null && !e.getFiles().isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "no-pos:" + e.getId());
            m.put("severity", "low");
            m.put("title", (e.getCashier() != null ? e.getCashier().getName() : "Cashier")
                    + " · " + e.getDate());
            m.put("description", "Submitted with no POS receipt attached. Verify the card sales total.");
            m.put("url", "/entry/" + e.getId());
            m.put("when", e.getDate().toString());
            items.add(m);
            if (items.size() >= MAX_PER_BUCKET) break;
        }
        items.sort((a, b) -> String.valueOf(b.get("when")).compareTo(String.valueOf(a.get("when"))));
        return items;
    }

    /** Submitted entries where the cashier's count differs from the system
     *  expectation by more than the threshold. Last 30 days only. */
    private List<Map<String, Object>> findCashMismatches() {
        LocalDate cutoff = LocalDate.now(zoneId).minusDays(30);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DailyEntry e : entryRepository.findAll()) {
            if (e.getDeletedAt() != null) continue;
            if (e.getStatus() != EntryStatus.LOCKED) continue;
            if (e.getDate().isBefore(cutoff)) continue;
            BigDecimal diff = e.getDifference();
            if (diff == null) continue;
            BigDecimal abs = diff.abs();
            if (abs.compareTo(SIGNIFICANT_MISMATCH) < 0) continue;
            String severity = abs.compareTo(SIGNIFICANT_MISMATCH.multiply(new BigDecimal("4"))) >= 0
                    ? "high" : "medium";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "cash-mismatch:" + e.getId());
            m.put("severity", severity);
            m.put("title", (e.getCashier() != null ? e.getCashier().getName() : "Cashier")
                    + " · " + (diff.signum() > 0 ? "+" : "") + diff + " PLN");
            m.put("description", "Cash difference on " + e.getDate() + " is "
                    + (diff.signum() > 0 ? "above" : "below") + " expected by "
                    + abs + " PLN.");
            m.put("url", "/entry/" + e.getId());
            m.put("when", e.getDate().toString());
            items.add(m);
            if (items.size() >= MAX_PER_BUCKET) break;
        }
        items.sort((a, b) -> String.valueOf(b.get("when")).compareTo(String.valueOf(a.get("when"))));
        return items;
    }

    /** Look for cashiers who were scheduled in the last week but no report
     *  exists for that day. Skips today (still in progress) and the future. */
    private List<Map<String, Object>> findScheduledButNoReport(LocalDate today) {
        LocalDate from = today.minusDays(MISSING_REPORT_LOOKBACK_DAYS);
        LocalDate to = today.minusDays(1);
        if (to.isBefore(from)) return List.of();

        List<WorkShift> shifts = workShiftRepository.findWorkingBetween(from, to);
        if (shifts.isEmpty()) return List.of();

        // Build a quick lookup of existing reports
        Set<String> existingKeys = new HashSet<>();
        for (DailyEntry e : entryRepository.findAll()) {
            if (e.getDeletedAt() != null) continue;
            if (e.getDate().isBefore(from) || e.getDate().isAfter(to)) continue;
            existingKeys.add(e.getCashierId() + "|" + e.getDate());
        }

        Map<String, String> userNames = new HashMap<>();
        for (User u : userRepository.findAll()) {
            userNames.put(u.getId(), u.getName() == null ? "Cashier" : u.getName());
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (WorkShift s : shifts) {
            String key = s.getUserId() + "|" + s.getDate();
            if (existingKeys.contains(key)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "missing-report:" + key);
            m.put("severity", "medium");
            m.put("title", userNames.getOrDefault(s.getUserId(), "Cashier")
                    + " · scheduled " + s.getDate());
            m.put("description", "Cashier was scheduled but didn't open a shift report.");
            m.put("url", "/reports");
            m.put("when", s.getDate().toString());
            items.add(m);
            if (items.size() >= MAX_PER_BUCKET) break;
        }
        items.sort((a, b) -> String.valueOf(b.get("when")).compareTo(String.valueOf(a.get("when"))));
        return items;
    }

    /** Defensive check: a deactivated cashier shouldn't be sitting on an
     *  active draft. Surface so the admin can submit or clean up. */
    private List<Map<String, Object>> findInactiveCashiersWithDrafts() {
        Map<String, User> users = new HashMap<>();
        for (User u : userRepository.findAll()) users.put(u.getId(), u);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DailyEntry e : entryRepository.findAll()) {
            if (e.getDeletedAt() != null) continue;
            if (e.getStatus() != EntryStatus.DRAFT) continue;
            User u = users.get(e.getCashierId());
            if (u == null || u.isActive()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "inactive-draft:" + e.getId());
            m.put("severity", "low");
            m.put("title", u.getName() + " · inactive cashier still has a draft");
            m.put("description", "Submit or remove this draft so reports stay clean.");
            m.put("url", "/entry/" + e.getId());
            m.put("when", e.getDate().toString());
            items.add(m);
            if (items.size() >= MAX_PER_BUCKET) break;
        }
        return items;
    }
}
