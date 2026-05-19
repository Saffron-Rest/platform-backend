package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.dto.RegisterPushTokenRequest;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.NotificationDispatchRepository;
import com.saffron.cashflow.repository.PushTokenRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.repository.WorkShiftRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.ForbiddenException;
import com.saffron.cashflow.util.WeeklyOperatingHours;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CashierNotificationService {

    private final ZoneId zoneId;
    private final int tomorrowReminderHour;
    private final int missingReportHour;
    private final UserRepository userRepository;
    private final DailyEntryRepository entryRepository;
    private final WorkShiftRepository workShiftRepository;
    private final PushTokenRepository pushTokenRepository;
    private final NotificationDispatchRepository dispatchRepository;
    private final SettingsService settingsService;
    private final WorkShiftService workShiftService;
    private final PushNotificationService pushNotificationService;

    public CashierNotificationService(
            @Value("${app.timezone:Europe/Warsaw}") String timezone,
            @Value("${app.notifications.tomorrow-reminder-hour:18}") int tomorrowReminderHour,
            @Value("${app.notifications.missing-report-hour:12}") int missingReportHour,
            UserRepository userRepository,
            DailyEntryRepository entryRepository,
            WorkShiftRepository workShiftRepository,
            PushTokenRepository pushTokenRepository,
            NotificationDispatchRepository dispatchRepository,
            SettingsService settingsService,
            WorkShiftService workShiftService,
            PushNotificationService pushNotificationService) {
        this.zoneId = ZoneId.of(timezone);
        this.tomorrowReminderHour = tomorrowReminderHour;
        this.missingReportHour = missingReportHour;
        this.userRepository = userRepository;
        this.entryRepository = entryRepository;
        this.workShiftRepository = workShiftRepository;
        this.pushTokenRepository = pushTokenRepository;
        this.dispatchRepository = dispatchRepository;
        this.settingsService = settingsService;
        this.workShiftService = workShiftService;
        this.pushNotificationService = pushNotificationService;
    }

    @Transactional
    public Map<String, Object> registerPushToken(RegisterPushTokenRequest req) {
        if (!AuthHelper.isCashier()) {
            throw new ForbiddenException("Cashiers only");
        }
        String token = req.expoPushToken().trim();
        if (!token.startsWith("ExponentPushToken[") && !token.startsWith("ExpoPushToken[")) {
            throw new BadRequestException("Invalid Expo push token");
        }
        String userId = AuthHelper.currentUser().id();
        PushToken row = pushTokenRepository.findByExpoPushToken(token).orElse(new PushToken());
        row.setExpoPushToken(token);
        row.setUser(userRepository.getReferenceById(userId));
        row.setDeviceName(req.deviceName());
        pushTokenRepository.save(row);
        return Map.of("ok", true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listInbox() {
        String userId = AuthHelper.currentUser().id();
        return dispatchRepository.findByUserIdOrderBySentAtDesc(userId).stream()
                .limit(30)
                .map(this::toInboxMap)
                .toList();
    }

    /** Runs every minute — closing reminder needs 5-minute precision. */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void runScheduledNotifications() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        LocalDate today = now.toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        WeeklyOperatingHours hours = settingsService.loadWeeklyHours();

        if (now.getHour() == tomorrowReminderHour && now.getMinute() == 0) {
            sendTomorrowShiftReminders(tomorrow);
        }

        if (now.getHour() == missingReportHour && now.getMinute() == 0 && !hours.isClosed(today)) {
            sendMissingReportReminders(today, "midday");
        }

        if (!hours.isClosed(today)) {
            checkClosingReminders(now, today, hours.closeFor(today));
        }
    }

    private void sendTomorrowShiftReminders(LocalDate tomorrow) {
        for (User cashier : userRepository.findByRoleAndActiveTrue(Role.CASHIER)) {
            Optional<WorkShift> shift = workShiftRepository.findByUser_IdAndDateWithUser(cashier.getId(), tomorrow);
            if (shift.isEmpty() || !shift.get().isWorking()) {
                continue;
            }
            if (alreadySent(cashier.getId(), CashierNotificationType.TOMORROW_SHIFT, tomorrow)) {
                continue;
            }
            String hoursLabel = WorkShiftService.hoursLabel(shift.get());
            String title = "Scheduled tomorrow";
            String body = "You are on the schedule tomorrow: " + hoursLabel + ".";
            dispatch(cashier.getId(), CashierNotificationType.TOMORROW_SHIFT, tomorrow, title, body,
                    Map.of("screen", "schedule"));
        }
    }

    private void sendMissingReportReminders(LocalDate today, String trigger) {
        for (User cashier : userRepository.findByRoleAndActiveTrue(Role.CASHIER)) {
            if (!isWorkingToday(cashier.getId(), today)) {
                continue;
            }
            if (hasSubmittedReport(cashier.getId(), today)) {
                continue;
            }
            if (alreadySent(cashier.getId(), CashierNotificationType.MISSING_REPORT, today)) {
                continue;
            }
            String title = "Report missing";
            String body = "Please submit today's cash report in the Saffron app.";
            dispatch(cashier.getId(), CashierNotificationType.MISSING_REPORT, today, title, body,
                    Map.of("screen", "report", "trigger", trigger));
        }
    }

    private void checkClosingReminders(ZonedDateTime now, LocalDate today, LocalTime closeTime) {
        LocalTime nowTime = now.toLocalTime().withSecond(0).withNano(0);
        long minutesUntilClose = Duration.between(nowTime, closeTime).toMinutes();
        if (minutesUntilClose != 5) {
            return;
        }
        for (User cashier : userRepository.findByRoleAndActiveTrue(Role.CASHIER)) {
            if (!isWorkingToday(cashier.getId(), today)) {
                continue;
            }
            if (hasSubmittedReport(cashier.getId(), today)) {
                continue;
            }
            if (alreadySent(cashier.getId(), CashierNotificationType.CLOSING_REMINDER, today)) {
                continue;
            }
            String closeStr = closeTime.toString().substring(0, 5);
            String title = "Closing in 5 minutes";
            String body = "Store closes at " + closeStr + " — submit your daily report now.";
            dispatch(cashier.getId(), CashierNotificationType.CLOSING_REMINDER, today, title, body,
                    Map.of("screen", "report", "minutesUntilClose", "5"));
        }
    }

    private boolean isWorkingToday(String userId, LocalDate today) {
        return workShiftService.isScheduledToWork(userId, today);
    }

    private boolean hasSubmittedReport(String userId, LocalDate today) {
        return entryRepository.existsByCashierIdAndDateAndDeletedAtIsNullAndStatus(
                userId, today, EntryStatus.LOCKED);
    }

    private boolean alreadySent(String userId, CashierNotificationType type, LocalDate referenceDate) {
        return dispatchRepository.findByUserIdAndTypeAndReferenceDate(userId, type, referenceDate).isPresent();
    }

    private void dispatch(
            String userId,
            CashierNotificationType type,
            LocalDate referenceDate,
            String title,
            String body,
            Map<String, String> data) {
        NotificationDispatch row = new NotificationDispatch();
        row.setUserId(userId);
        row.setType(type);
        row.setReferenceDate(referenceDate);
        row.setTitle(title);
        row.setBody(body);
        dispatchRepository.save(row);
        pushNotificationService.sendAfterDispatch(row, data);
    }

    private Map<String, Object> toInboxMap(NotificationDispatch n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("type", n.getType().name());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("referenceDate", n.getReferenceDate().toString());
        m.put("sentAt", n.getSentAt().toString());
        return m;
    }
}
