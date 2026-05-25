package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.RegisterPushTokenRequest;
import com.saffron.cashflow.service.CashierNotificationService;
import com.saffron.cashflow.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * Two-flavoured notifications API:
 *  - Mobile cashier inbox: uses the legacy {@link CashierNotificationService}
 *    backed by the NotificationDispatch table (schedule reminders, missing
 *    report nags, etc).
 *  - Web in-app inbox: backed by {@link NotificationService} and the new
 *    Notification entity. Powers @mention follow-ups and other ad-hoc
 *    events admins see in the dropdown.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CashierNotificationService cashierNotificationService;

    public NotificationController(
            NotificationService notificationService,
            CashierNotificationService cashierNotificationService) {
        this.notificationService = notificationService;
        this.cashierNotificationService = cashierNotificationService;
    }

    // ---------- mobile cashier endpoints (unchanged contract) ----------

    @PostMapping("/register-token")
    public Map<String, Object> registerToken(@Valid @RequestBody RegisterPushTokenRequest req) {
        return cashierNotificationService.registerPushToken(req);
    }

    @GetMapping("/inbox")
    public List<Map<String, Object>> mobileInbox() {
        return cashierNotificationService.listInbox();
    }

    // ---------- web inbox endpoints ----------

    /** Web admin inbox — paginated list with unread counter. */
    @GetMapping("/me")
    public Map<String, Object> inbox() {
        return notificationService.inbox();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("unread", notificationService.unreadCount());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable String id) {
        notificationService.markRead(id);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notificationService.markAllRead());
    }
}
