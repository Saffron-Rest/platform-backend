package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.AlertRepository;
import com.saffron.cashflow.repository.BankDepositLinkRepository;
import com.saffron.cashflow.repository.CardSettlementRepository;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.TreasurySettings;
import com.saffron.cashflow.util.WeeklyOperatingHours;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AlertService {

    private static final BigDecimal LARGE_REFUND = new BigDecimal("500");
    private static final BigDecimal UNUSUAL_EXPENSE = new BigDecimal("1000");
    private static final BigDecimal SHORTAGE = new BigDecimal("-10");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final ZoneId zoneId;
    private final int missingReportHour;
    private final int unsettledDeliveryDays;
    private final AlertRepository alertRepository;
    private final DailyEntryRepository entryRepository;
    private final UserRepository userRepository;
    private final WorkShiftService workShiftService;
    private final SettingsService settingsService;
    private final TelegramNotificationService telegram;
    private final BankDepositLinkRepository bankDepositLinkRepository;
    private final CardSettlementRepository cardSettlementRepository;
    private final TreasuryService treasuryService;

    public AlertService(
            @Value("${app.timezone:Europe/Warsaw}") String timezone,
            @Value("${app.notifications.missing-report-hour:12}") int missingReportHour,
            @Value("${app.notifications.unsettled-delivery-days:3}") int unsettledDeliveryDays,
            AlertRepository alertRepository,
            DailyEntryRepository entryRepository,
            UserRepository userRepository,
            WorkShiftService workShiftService,
            SettingsService settingsService,
            TelegramNotificationService telegram,
            BankDepositLinkRepository bankDepositLinkRepository,
            CardSettlementRepository cardSettlementRepository,
            TreasuryService treasuryService) {
        this.zoneId = ZoneId.of(timezone);
        this.missingReportHour = missingReportHour;
        this.unsettledDeliveryDays = unsettledDeliveryDays;
        this.alertRepository = alertRepository;
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
        this.workShiftService = workShiftService;
        this.settingsService = settingsService;
        this.telegram = telegram;
        this.bankDepositLinkRepository = bankDepositLinkRepository;
        this.cardSettlementRepository = cardSettlementRepository;
        this.treasuryService = treasuryService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AuthUser user = AuthHelper.currentUser();
        List<Alert> alerts = AuthHelper.isOperationsRole()
                ? alertRepository.findAllOrderByCreatedAtDesc()
                : alertRepository.findByUserIdOrderByCreatedAtDesc(user.id());
        return alerts.stream().limit(50).map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> telegramStatus() {
        AuthHelper.requireAdmin();
        Map<String, Object> status = new LinkedHashMap<>(telegram.status());
        long unread = alertRepository.findAllOrderByCreatedAtDesc().stream().filter(a -> !a.isRead()).count();
        status.put("unreadAlerts", unread);
        return status;
    }

    @Transactional
    public Map<String, Object> sendTelegramTest() {
        AuthHelper.requireAdmin();
        boolean sent = telegram.sendTestMessage();
        return Map.of("ok", sent, "configured", telegram.isConfigured());
    }

    @Transactional
    public Map<String, Object> markRead(String id) {
        AuthHelper.requireAdmin();
        Alert alert = alertRepository.findByIdWithUser(id).orElseThrow(() -> new NotFoundException("Not found"));
        alert.setRead(true);
        alertRepository.save(alert);
        return toMap(alert);
    }

    @Transactional
    public void checkEntryAlerts(DailyEntry entry) {
        BigDecimal returns = EntryCalculator.totalReturns(entry);
        BigDecimal expenses = EntryCalculator.totalExpenses(entry);
        BigDecimal diff = entry.getDifference();
        String cashierName = entry.getCashier() != null ? entry.getCashier().getName() : "Cashier";

        if (diff.compareTo(SHORTAGE) < 0) {
            create(
                    AlertType.CASH_SHORTAGE,
                    "Cash shortage of " + diff.abs().setScale(2) + " PLN — " + cashierName,
                    entry.getId(),
                    entry.getCashierId());
        }
        if (returns.compareTo(LARGE_REFUND) >= 0) {
            create(
                    AlertType.LARGE_REFUND,
                    "Large refunds total: " + returns.setScale(2) + " PLN — " + cashierName,
                    entry.getId(),
                    entry.getCashierId());
        }
        if (expenses.compareTo(UNUSUAL_EXPENSE) >= 0) {
            create(
                    AlertType.UNUSUAL_EXPENSE,
                    "High expenses total: " + expenses.setScale(2) + " PLN — " + cashierName,
                    entry.getId(),
                    entry.getCashierId());
        }
    }

    /** Manual trigger from Admin → Settings. */
    @Transactional
    public Map<String, Object> checkMissingSubmissions() {
        AuthHelper.requireAdmin();
        return runMissingSubmissionCheck(true);
    }

    /** Midday missing-report scan (after cashier push at missing-report-hour). */
    @Scheduled(cron = "0 15 ${app.notifications.missing-report-hour} * * *", zone = "${app.timezone:Europe/Warsaw}")
    @Transactional
    public void scheduledMissingSubmissionCheck() {
        runMissingSubmissionCheck(true);
    }

    /** Second scan before close. */
    @Scheduled(cron = "0 0 18 * * *", zone = "${app.timezone:Europe/Warsaw}")
    @Transactional
    public void scheduledMissingSubmissionCheckEvening() {
        runMissingSubmissionCheck(true);
    }

    /** Evening digest of unread in-app alerts. */
    @Scheduled(cron = "0 0 21 * * *", zone = "${app.timezone:Europe/Warsaw}")
    @Transactional
    public void scheduledEveningDigest() {
        sendEveningDigest();
    }

    /** Morning scan for delivery sales that haven't been reconciled to a bank deposit. */
    @Scheduled(cron = "0 30 9 * * *", zone = "${app.timezone:Europe/Warsaw}")
    @Transactional
    public void scheduledUnsettledDeliveryCheck() {
        runUnsettledDeliveryCheck(true);
    }

    /** Manual trigger from Admin → Settings. */
    @Transactional
    public Map<String, Object> checkUnsettledDelivery() {
        AuthHelper.requireAdmin();
        return runUnsettledDeliveryCheck(true);
    }

    /**
     * Scans locked shift reports older than the configured threshold for unreconciled
     * delivery sales (no BankDepositLink and no CardSettlement attached). Sends a single
     * Telegram digest grouped by platform and creates an in-app alert.
     */
    @Transactional
    public Map<String, Object> runUnsettledDeliveryCheck(boolean notifyTelegram) {
        LocalDate today = LocalDate.now(zoneId);
        LocalDate cutoff = today.minusDays(unsettledDeliveryDays);
        LocalDate earliest = LocalDate.of(2000, 1, 1);

        // Aggregate by platform → { totalProjected, oldestDate, dayCount }
        Map<String, PlatformPending> byPlatform = new TreeMap<>();
        int rowCount = 0;

        List<DailyEntry> entries =
                entryRepository.findLockedBetweenWithExpenses(earliest, cutoff, EntryStatus.LOCKED);
        for (DailyEntry e : entries) {
            rowCount += addPendingDelivery(byPlatform, e, "wolt", "Wolt", e.getWoltSales(), e.getWoltSettledToCard());
            rowCount += addPendingDelivery(byPlatform, e, "bolt", "Bolt", e.getBoltSales(), e.getBoltSettledToCard());
            rowCount += addPendingDelivery(byPlatform, e, "uberEats", "Uber Eats", e.getUberEatsSales(), e.getUberEatsSettledToCard());
            rowCount += addPendingDelivery(byPlatform, e, "glovo", "Glovo", e.getGlovoSales(), e.getGlovoSettledToCard());
            rowCount += addPendingDelivery(byPlatform, e, "other", "Other delivery", e.getOtherPlatformSales(), e.getOtherSettledToCard());
        }

        if (byPlatform.isEmpty()) {
            if (notifyTelegram) {
                telegram.sendHtmlOnce(
                        "unsettled-delivery-clear:" + today,
                        "<b>✅ Delivery fully reconciled</b>\n" + DATE_FMT.format(today)
                                + " — no unsettled delivery older than " + unsettledDeliveryDays + " day(s).");
            }
            return Map.of(
                    "ok", true,
                    "created", 0,
                    "thresholdDays", unsettledDeliveryDays,
                    "platforms", List.of(),
                    "date", today.toString());
        }

        BigDecimal grandTotal = BigDecimal.ZERO;
        for (PlatformPending p : byPlatform.values()) grandTotal = grandTotal.add(p.totalProjected);

        // In-app alert (one per check)
        String summary = byPlatform.values().stream()
                .map(p -> p.label + " " + p.totalProjected.setScale(2, RoundingMode.HALF_UP)
                        + " PLN (" + p.dayCount + "d)")
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
        create(
                AlertType.UNSETTLED_DELIVERY,
                "Unsettled delivery > " + unsettledDeliveryDays + "d: " + summary,
                null,
                null);

        if (notifyTelegram) {
            String dedupe = "unsettled-delivery:" + today + ":" + unsettledDeliveryDays;
            StringBuilder body = new StringBuilder();
            body.append("<b>🛵 Delivery awaiting bank settlement</b>\n");
            body.append(DATE_FMT.format(today)).append(" · older than ")
                    .append(unsettledDeliveryDays).append(" day(s)\n\n");
            for (PlatformPending p : byPlatform.values()) {
                body.append("• <b>").append(escapeHtml(p.label)).append("</b>: ")
                        .append(p.totalProjected.setScale(2, RoundingMode.HALF_UP))
                        .append(" PLN across ").append(p.dayCount).append(" day(s)")
                        .append(" — oldest ").append(DATE_FMT.format(p.oldestDate)).append("\n");
            }
            body.append("\n<b>Total projected:</b> ")
                    .append(grandTotal.setScale(2, RoundingMode.HALF_UP)).append(" PLN");
            body.append("\n\n<i>Open <code>/treasury/history?source=card</code> → filter ");
            body.append("\"Pending\" → reconcile as bank deposit.</i>");
            telegram.sendHtmlOnce(dedupe, body.toString());
        }

        // Build response payload
        List<Map<String, Object>> platforms = new ArrayList<>();
        for (PlatformPending p : byPlatform.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platform", p.key);
            m.put("label", p.label);
            m.put("projected", p.totalProjected.setScale(2, RoundingMode.HALF_UP).doubleValue());
            m.put("dayCount", p.dayCount);
            m.put("oldestDate", p.oldestDate.toString());
            platforms.add(m);
        }
        return Map.of(
                "ok", true,
                "created", 1,
                "rowCount", rowCount,
                "thresholdDays", unsettledDeliveryDays,
                "totalProjected", grandTotal.setScale(2, RoundingMode.HALF_UP).doubleValue(),
                "platforms", platforms,
                "date", today.toString());
    }

    private int addPendingDelivery(
            Map<String, PlatformPending> byPlatform,
            DailyEntry e,
            String key,
            String label,
            BigDecimal sales,
            BigDecimal manualOverride) {
        if (sales == null || sales.signum() <= 0) return 0;
        // If a BankDeposit or CardSettlement covers this entry's delivery row, skip it.
        if (bankDepositLinkRepository
                .findByLinkedKindAndLinkedRefId("SHIFT_DELIVERY_SETTLED", e.getId())
                .isPresent()) return 0;
        if (cardSettlementRepository
                .findByLinkedKindAndLinkedRefId("SHIFT_DELIVERY_SETTLED", e.getId())
                .isPresent()) return 0;
        // Projected card amount: manual override on the entry wins, otherwise the configured
        // platform settlement rate. We surface this number so the admin knows the magnitude.
        TreasurySettings settings = treasuryService.loadSettings();
        BigDecimal projected = manualOverride != null
                ? manualOverride.max(BigDecimal.ZERO)
                : sales.multiply(settings.platformRate(key));
        if (projected.signum() <= 0) return 0;
        PlatformPending pp = byPlatform.computeIfAbsent(key, k -> new PlatformPending(k, label));
        pp.totalProjected = pp.totalProjected.add(projected);
        pp.dayCount++;
        if (pp.oldestDate == null || e.getDate().isBefore(pp.oldestDate)) {
            pp.oldestDate = e.getDate();
        }
        return 1;
    }

    private static class PlatformPending {
        final String key;
        final String label;
        BigDecimal totalProjected = BigDecimal.ZERO;
        int dayCount = 0;
        LocalDate oldestDate;

        PlatformPending(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    @Transactional
    public Map<String, Object> runMissingSubmissionCheck(boolean notifyTelegram) {
        LocalDate today = LocalDate.now(zoneId);
        WeeklyOperatingHours hours = settingsService.loadWeeklyHours();
        if (hours.isClosed(today)) {
            return Map.of("ok", true, "skipped", "closed", "created", 0, "missing", List.of());
        }

        Instant startOfDay = today.atStartOfDay(zoneId).toInstant();
        List<String> missingNames = new ArrayList<>();
        int created = 0;

        for (User cashier : userRepository.findByRoleAndActiveTrue(Role.CASHIER)) {
            if (!workShiftService.isScheduledToWork(cashier.getId(), today)) {
                continue;
            }
            boolean submitted = entryRepository.existsByCashierIdAndDateAndDeletedAtIsNullAndStatus(
                    cashier.getId(), today, EntryStatus.LOCKED);
            if (submitted) {
                continue;
            }
            if (alertRepository
                    .findFirstByTypeAndUserIdAndCreatedAtGreaterThanEqual(
                            AlertType.MISSING_SUBMISSION, cashier.getId(), startOfDay)
                    .isPresent()) {
                missingNames.add(cashier.getName());
                continue;
            }
            create(
                    AlertType.MISSING_SUBMISSION,
                    cashier.getName() + " has not submitted today's report",
                    null,
                    cashier.getId());
            missingNames.add(cashier.getName());
            created++;
        }

        if (notifyTelegram && !missingNames.isEmpty()) {
            String dedupe = "missing:" + today;
            StringBuilder body = new StringBuilder();
            body.append("<b>📋 Missing shift reports</b>\n");
            body.append("Date: ").append(DATE_FMT.format(today)).append("\n\n");
            for (String name : missingNames) {
                body.append("• ").append(escapeHtml(name)).append("\n");
            }
            body.append("\n<i>Tap “Check missing reports” in Admin → Settings to refresh.</i>");
            telegram.sendHtmlOnce(dedupe, body.toString());
        } else if (notifyTelegram && created == 0 && missingNames.isEmpty()) {
            telegram.sendHtmlOnce(
                    "missing-clear:" + today,
                    "<b>✅ All reports submitted</b>\n" + DATE_FMT.format(today) + " — every scheduled cashier has locked their report.");
        }

        return Map.of("ok", true, "created", created, "missing", missingNames, "date", today.toString());
    }

    private void sendEveningDigest() {
        List<Alert> unread =
                alertRepository.findAllOrderByCreatedAtDesc().stream().filter(a -> !a.isRead()).limit(15).toList();
        if (unread.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(zoneId);
        String dedupe = "digest:" + today;
        StringBuilder body = new StringBuilder();
        body.append("<b>🔔 Saffron — open alerts</b>\n");
        body.append(DATE_FMT.format(today)).append(" · ").append(unread.size());
        if (unread.size() >= 15) {
            body.append("+");
        }
        body.append(" unread\n\n");
        for (Alert a : unread) {
            body.append(emojiFor(a.getType()))
                    .append(" ")
                    .append(escapeHtml(shortLabel(a.getType())))
                    .append(": ")
                    .append(escapeHtml(a.getMessage()))
                    .append("\n");
        }
        telegram.sendHtmlOnce(dedupe, body.toString());
    }

    private void create(AlertType type, String message, String entryId, String userId) {
        Alert alert = new Alert();
        alert.setType(type);
        alert.setMessage(message);
        alert.setEntryId(entryId);
        if (userId != null) {
            alert.setUser(userRepository.getReferenceById(userId));
        }
        alertRepository.save(alert);
        notifyTelegramForAlert(type, message);
    }

    private void notifyTelegramForAlert(AlertType type, String message) {
        String html =
                emojiFor(type) + " <b>" + escapeHtml(shortLabel(type)) + "</b>\n" + escapeHtml(message);
        telegram.sendHtml(html);
    }

    private static String emojiFor(AlertType type) {
        return switch (type) {
            case MISSING_SUBMISSION -> "📋";
            case CASH_SHORTAGE -> "💰";
            case LARGE_REFUND -> "↩️";
            case UNUSUAL_EXPENSE -> "📦";
            case UNSETTLED_DELIVERY -> "🛵";
        };
    }

    private static String shortLabel(AlertType type) {
        return switch (type) {
            case MISSING_SUBMISSION -> "Missing report";
            case CASH_SHORTAGE -> "Cash shortage";
            case LARGE_REFUND -> "Large refund";
            case UNUSUAL_EXPENSE -> "High expenses";
            case UNSETTLED_DELIVERY -> "Unsettled delivery";
        };
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Map<String, Object> toMap(Alert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("type", a.getType().name());
        m.put("message", a.getMessage());
        m.put("read", a.isRead());
        m.put("createdAt", a.getCreatedAt().toString());
        if (a.getUser() != null) {
            m.put("user", Map.of("name", a.getUser().getName()));
        }
        return m;
    }
}
