package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.*;
import com.saffron.cashflow.security.AuthHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Lightweight global search across the major record types. Implementation
 * uses in-memory matching for simplicity — the data volume here (a single
 * restaurant) easily fits in memory and removes the need for tsvector
 * indexes or a separate search service.
 *
 * Ranking heuristic:
 *  - exact match in title/name > word-start match > substring match
 *  - recency boost: rows within the last 30 days get a small bonus so a
 *    fresh draft beats a similarly-relevant year-old report.
 *
 * Each result has a stable `url` the frontend can navigate to directly.
 */
@Service
public class SearchService {

    /** Hard cap to keep payload small + UI responsive. */
    private static final int MAX_RESULTS_PER_TYPE = 8;
    private static final int MAX_TOTAL = 30;

    private final DailyEntryRepository entryRepository;
    private final ExpenseItemRepository expenseRepository;
    private final SalaryPaymentRepository salaryPaymentRepository;
    private final ManualDeliveryIncomeRepository manualDeliveryRepository;
    private final UserRepository userRepository;
    private final TagRepository tagRepository;
    private final CommentRepository commentRepository;
    private final AuditLogRepository auditLogRepository;

    public SearchService(
            DailyEntryRepository entryRepository,
            ExpenseItemRepository expenseRepository,
            SalaryPaymentRepository salaryPaymentRepository,
            ManualDeliveryIncomeRepository manualDeliveryRepository,
            UserRepository userRepository,
            TagRepository tagRepository,
            CommentRepository commentRepository,
            AuditLogRepository auditLogRepository) {
        this.entryRepository = entryRepository;
        this.expenseRepository = expenseRepository;
        this.salaryPaymentRepository = salaryPaymentRepository;
        this.manualDeliveryRepository = manualDeliveryRepository;
        this.userRepository = userRepository;
        this.tagRepository = tagRepository;
        this.commentRepository = commentRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> search(String query, Set<String> types) {
        AuthHelper.requireOperations();
        String q = query == null ? "" : query.trim().toLowerCase();
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();

        if (q.isEmpty()) {
            groups.put("results", List.of());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", query);
            out.put("groups", groups);
            out.put("total", 0);
            return out;
        }

        boolean all = types == null || types.isEmpty();
        int total = 0;

        if ((all || types.contains("entry")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchEntries(q);
            if (!rows.isEmpty()) groups.put("entries", rows);
            total += rows.size();
        }
        if ((all || types.contains("expense")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchExpenses(q);
            if (!rows.isEmpty()) groups.put("expenses", rows);
            total += rows.size();
        }
        if ((all || types.contains("payout")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchPayouts(q);
            if (!rows.isEmpty()) groups.put("payouts", rows);
            total += rows.size();
        }
        if ((all || types.contains("delivery")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchDeliveries(q);
            if (!rows.isEmpty()) groups.put("deliveries", rows);
            total += rows.size();
        }
        if ((all || types.contains("user")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchUsers(q);
            if (!rows.isEmpty()) groups.put("people", rows);
            total += rows.size();
        }
        if ((all || types.contains("tag")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchTags(q);
            if (!rows.isEmpty()) groups.put("tags", rows);
            total += rows.size();
        }
        if ((all || types.contains("comment")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchComments(q);
            if (!rows.isEmpty()) groups.put("comments", rows);
            total += rows.size();
        }
        if ((all || types.contains("audit")) && total < MAX_TOTAL) {
            List<Map<String, Object>> rows = searchAuditLog(q);
            if (!rows.isEmpty()) groups.put("audit", rows);
            total += rows.size();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("groups", groups);
        out.put("total", total);
        return out;
    }

    // ---------- per-type searches ----------

    private List<Map<String, Object>> searchEntries(String q) {
        // Try date parse first so "2026-04-15" jumps you straight to a day.
        LocalDate exactDate = tryParseDate(q);
        List<Hit<DailyEntry>> hits = new ArrayList<>();
        for (DailyEntry e : entryRepository.findAll()) {
            if (e.getDeletedAt() != null) continue;
            int score = 0;
            String haystack = (e.getCashier() != null ? e.getCashier().getName() : "")
                    + " " + e.getDate() + " " + e.getStatus().name();
            score += scoreText(haystack.toLowerCase(), q);
            if (exactDate != null && e.getDate().equals(exactDate)) score += 50;
            // Recency boost based on submission time when available, otherwise the
            // entry date converted to an Instant at noon UTC (rough but close enough).
            Instant when = e.getSubmittedAt();
            if (when == null) when = e.getDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            score += recencyBoost(when);
            if (score > 0) hits.add(new Hit<>(e, score));
        }
        return topHits(hits).stream().map(this::entryToHit).toList();
    }

    private List<Map<String, Object>> searchExpenses(String q) {
        List<Hit<ExpenseItem>> hits = new ArrayList<>();
        for (ExpenseItem e : expenseRepository.findAll()) {
            String haystack = ((e.getDescription() == null ? "" : e.getDescription()) + " "
                    + (e.getCategory() == null ? "" : e.getCategory().name())).toLowerCase();
            int score = scoreText(haystack, q);
            // Amount-as-text matches too — "150" matches a 150 PLN expense.
            if (e.getAmount() != null && e.getAmount().toPlainString().contains(q)) score += 6;
            if (score > 0) hits.add(new Hit<>(e, score));
        }
        return topHits(hits).stream().map(this::expenseToHit).toList();
    }

    private List<Map<String, Object>> searchPayouts(String q) {
        Map<String, String> userNames = new HashMap<>();
        for (User u : userRepository.findAll()) userNames.put(u.getId(), u.getName() == null ? "" : u.getName());
        List<Hit<SalaryPayment>> hits = new ArrayList<>();
        for (SalaryPayment p : salaryPaymentRepository.findAll()) {
            String haystack = (userNames.getOrDefault(p.getUserId(), "") + " "
                    + (p.getNotes() == null ? "" : p.getNotes()) + " "
                    + p.getPaymentSource().name()).toLowerCase();
            int score = scoreText(haystack, q);
            if (p.getAmount() != null && p.getAmount().toPlainString().contains(q)) score += 6;
            if (score > 0) hits.add(new Hit<>(p, score));
        }
        return topHits(hits).stream().map(p -> payoutToHit(p, userNames)).toList();
    }

    private List<Map<String, Object>> searchDeliveries(String q) {
        List<Hit<ManualDeliveryIncome>> hits = new ArrayList<>();
        for (ManualDeliveryIncome d : manualDeliveryRepository.findAll()) {
            String haystack = (d.getPlatform().name() + " "
                    + (d.getNotes() == null ? "" : d.getNotes())).toLowerCase();
            int score = scoreText(haystack, q);
            if (d.getGrossAmount() != null && d.getGrossAmount().toPlainString().contains(q)) score += 6;
            if (score > 0) hits.add(new Hit<>(d, score));
        }
        return topHits(hits).stream().map(this::deliveryToHit).toList();
    }

    private List<Map<String, Object>> searchUsers(String q) {
        List<Hit<User>> hits = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            String haystack = (
                    (u.getName() == null ? "" : u.getName()) + " "
                            + (u.getUsername() == null ? "" : u.getUsername()) + " "
                            + (u.getEmail() == null ? "" : u.getEmail()) + " "
                            + u.getRole().name()).toLowerCase();
            int score = scoreText(haystack, q);
            if (score > 0) hits.add(new Hit<>(u, score));
        }
        return topHits(hits).stream().map(this::userToHit).toList();
    }

    private List<Map<String, Object>> searchTags(String q) {
        List<Hit<Tag>> hits = new ArrayList<>();
        for (Tag t : tagRepository.findAll()) {
            String haystack = (t.getName() + " "
                    + (t.getDescription() == null ? "" : t.getDescription())).toLowerCase();
            int score = scoreText(haystack, q);
            if (score > 0) hits.add(new Hit<>(t, score));
        }
        return topHits(hits).stream().map(this::tagToHit).toList();
    }

    private List<Map<String, Object>> searchComments(String q) {
        List<Hit<Comment>> hits = new ArrayList<>();
        for (Comment c : commentRepository.findAll()) {
            if (c.getDeletedAt() != null) continue;
            String haystack = c.getBody().toLowerCase();
            int score = scoreText(haystack, q);
            if (score > 0) hits.add(new Hit<>(c, score + recencyBoost(c.getCreatedAt())));
        }
        return topHits(hits).stream().map(this::commentToHit).toList();
    }

    private List<Map<String, Object>> searchAuditLog(String q) {
        List<Hit<AuditLog>> hits = new ArrayList<>();
        // Limit to recent audit entries — the table can grow large and
        // older history is rarely interesting in ad-hoc search.
        int cap = 0;
        for (AuditLog log : auditLogRepository.findTop100ByOrderByCreatedAtDesc()) {
            String haystack = ((log.getSummary() == null ? "" : log.getSummary()) + " "
                    + (log.getEntityType() == null ? "" : log.getEntityType()) + " "
                    + (log.getAction() == null ? "" : log.getAction().name())).toLowerCase();
            int score = scoreText(haystack, q);
            if (score > 0) {
                hits.add(new Hit<>(log, score + recencyBoost(log.getCreatedAt())));
                if (++cap >= 200) break;
            }
        }
        return topHits(hits).stream().map(this::auditToHit).toList();
    }

    // ---------- hit mappers ----------

    private Map<String, Object> entryToHit(Hit<DailyEntry> h) {
        DailyEntry e = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "entry");
        m.put("id", e.getId());
        m.put("title", (e.getCashier() != null ? e.getCashier().getName() : "Cashier")
                + " · " + e.getDate());
        m.put("subtitle", e.getStatus() == EntryStatus.LOCKED ? "Submitted shift report" : "Draft shift report");
        m.put("url", "/entry/" + e.getId());
        m.put("score", h.score);
        m.put("when", e.getDate().toString());
        return m;
    }

    private Map<String, Object> expenseToHit(Hit<ExpenseItem> h) {
        ExpenseItem e = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "expense");
        m.put("id", e.getId());
        m.put("title", e.getDescription() == null || e.getDescription().isBlank()
                ? (e.getCategory() == null ? "Expense" : e.getCategory().name())
                : e.getDescription());
        m.put("subtitle", (e.getCategory() == null ? "Expense" : e.getCategory().name())
                + " · " + e.getAmount() + " PLN");
        // Standalone expenses live on the finance page; entry-attached ones link to their report
        m.put("url", e.getEntry() != null ? "/entry/" + e.getEntry().getId() : "/finance");
        m.put("score", h.score);
        m.put("when", e.getEffectiveDate() != null ? e.getEffectiveDate().toString() : null);
        return m;
    }

    private Map<String, Object> payoutToHit(Hit<SalaryPayment> h, Map<String, String> names) {
        SalaryPayment p = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "payout");
        m.put("id", p.getId());
        m.put("title", names.getOrDefault(p.getUserId(), "Payout") + " · " + p.getAmount() + " PLN");
        m.put("subtitle", "Paid " + p.getPaidDate() + " · " + p.getPaymentSource().name());
        m.put("url", "/admin/payouts");
        m.put("score", h.score);
        m.put("when", p.getPaidDate().toString());
        return m;
    }

    private Map<String, Object> deliveryToHit(Hit<ManualDeliveryIncome> h) {
        ManualDeliveryIncome d = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "delivery");
        m.put("id", d.getId());
        m.put("title", d.getPlatform().name() + " · " + d.getGrossAmount() + " PLN");
        m.put("subtitle", "Manual delivery income · " + d.getEffectiveDate());
        m.put("url", "/finance");
        m.put("score", h.score);
        m.put("when", d.getEffectiveDate().toString());
        return m;
    }

    private Map<String, Object> userToHit(Hit<User> h) {
        User u = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "user");
        m.put("id", u.getId());
        m.put("title", u.getName() == null ? u.getEmail() : u.getName());
        m.put("subtitle", u.getRole().name() + (u.getEmail() != null ? " · " + u.getEmail() : ""));
        m.put("url", "/admin/team");
        m.put("score", h.score);
        return m;
    }

    private Map<String, Object> tagToHit(Hit<Tag> h) {
        Tag t = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "tag");
        m.put("id", t.getId());
        m.put("title", t.getName());
        m.put("subtitle", t.getDescription() == null ? "Tag" : t.getDescription());
        m.put("url", "/admin/tags");
        m.put("score", h.score);
        m.put("color", t.getColor());
        return m;
    }

    private Map<String, Object> commentToHit(Hit<Comment> h) {
        Comment c = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "comment");
        m.put("id", c.getId());
        String preview = c.getBody().length() > 80 ? c.getBody().substring(0, 77) + "…" : c.getBody();
        m.put("title", preview);
        m.put("subtitle", "Comment on " + c.getEntityType().name().toLowerCase().replace('_', ' '));
        m.put("url", urlFor(c.getEntityType(), c.getEntityId()));
        m.put("score", h.score);
        m.put("when", c.getCreatedAt().toString());
        return m;
    }

    private Map<String, Object> auditToHit(Hit<AuditLog> h) {
        AuditLog a = h.value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "audit");
        m.put("id", a.getId());
        m.put("title", a.getSummary() == null ? a.getAction().name() : a.getSummary());
        m.put("subtitle", a.getEntityType() + " · " + a.getAction().name());
        m.put("url", "/audit");
        m.put("score", h.score);
        m.put("when", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        return m;
    }

    // ---------- helpers ----------

    /** Score how well `haystack` matches `q`. Returns 0 when there's no hit
     *  at all; otherwise weights exact word matches higher than substrings. */
    private static int scoreText(String haystack, String q) {
        if (haystack == null || haystack.isEmpty() || q.isEmpty()) return 0;
        if (haystack.equals(q)) return 100;
        if (haystack.startsWith(q)) return 60;
        // Word-boundary match (space-prefixed) gets a slightly lower boost.
        if (haystack.contains(" " + q)) return 40;
        if (haystack.contains(q)) return 20;
        return 0;
    }

    /** Tiny boost for recent records so a draft from today beats a similar
     *  one from last year. Caps out at ~10 within the last 24 hours. */
    private static int recencyBoost(Instant when) {
        if (when == null) return 0;
        long days = Math.abs(ChronoUnit.DAYS.between(when, Instant.now()));
        if (days <= 1) return 10;
        if (days <= 7) return 6;
        if (days <= 30) return 3;
        return 0;
    }

    private static LocalDate tryParseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String urlFor(TaggedEntityType type, String id) {
        return switch (type) {
            case ENTRY -> "/entry/" + id;
            case EXPENSE, MANUAL_DELIVERY -> "/finance";
            case SALARY_PAYMENT -> "/admin/payouts";
        };
    }

    private static <T> List<Hit<T>> topHits(List<Hit<T>> hits) {
        hits.sort((a, b) -> Integer.compare(b.score, a.score));
        return hits.size() > MAX_RESULTS_PER_TYPE ? hits.subList(0, MAX_RESULTS_PER_TYPE) : hits;
    }

    private static final class Hit<T> {
        final T value;
        final int score;
        Hit(T value, int score) {
            this.value = value;
            this.score = score;
        }
    }
}
