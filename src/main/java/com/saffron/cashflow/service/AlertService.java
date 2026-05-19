package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.AlertRepository;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.WeeklyOperatingHours;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertService {

    private static final BigDecimal LARGE_REFUND = new BigDecimal("500");
    private static final BigDecimal UNUSUAL_EXPENSE = new BigDecimal("1000");
    private static final BigDecimal SHORTAGE = new BigDecimal("-10");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final ZoneId zoneId;
    private final int missingReportHour;
    private final AlertRepository alertRepository;
    private final DailyEntryRepository entryRepository;
    private final UserRepository userRepository;
    private final WorkShiftService workShiftService;
    private final SettingsService settingsService;
    private final TelegramNotificationService telegram;

    public AlertService(
            @Value("${app.timezone:Europe/Warsaw}") String timezone,
            @Value("${app.notifications.missing-report-hour:12}") int missingReportHour,
            AlertRepository alertRepository,
            DailyEntryRepository entryRepository,
            UserRepository userRepository,
            WorkShiftService workShiftService,
            SettingsService settingsService,
            TelegramNotificationService telegram) {
        this.zoneId = ZoneId.of(timezone);
        this.missingReportHour = missingReportHour;
        this.alertRepository = alertRepository;
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
        this.workShiftService = workShiftService;
        this.settingsService = settingsService;
        this.telegram = telegram;
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
        };
    }

    private static String shortLabel(AlertType type) {
        return switch (type) {
            case MISSING_SUBMISSION -> "Missing report";
            case CASH_SHORTAGE -> "Cash shortage";
            case LARGE_REFUND -> "Large refund";
            case UNUSUAL_EXPENSE -> "High expenses";
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
