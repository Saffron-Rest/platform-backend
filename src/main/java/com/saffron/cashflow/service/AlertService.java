package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.AlertRepository;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertService {

    private static final BigDecimal LARGE_REFUND = new BigDecimal("500");
    private static final BigDecimal UNUSUAL_EXPENSE = new BigDecimal("1000");
    private static final BigDecimal SHORTAGE = new BigDecimal("-10");

    private final AlertRepository alertRepository;
    private final DailyEntryRepository entryRepository;
    private final UserRepository userRepository;

    public AlertService(AlertRepository alertRepository, DailyEntryRepository entryRepository, UserRepository userRepository) {
        this.alertRepository = alertRepository;
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AuthUser user = AuthHelper.currentUser();
        List<Alert> alerts = AuthHelper.isOperationsRole()
                ? alertRepository.findAllOrderByCreatedAtDesc()
                : alertRepository.findByUserIdOrderByCreatedAtDesc(user.id());
        return alerts.stream().limit(50).map(this::toMap).toList();
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

        if (diff.compareTo(SHORTAGE) < 0) {
            create(AlertType.CASH_SHORTAGE,
                    "Cash shortage of " + diff.abs().setScale(2) + " detected",
                    entry.getId(), entry.getCashierId());
        }
        if (returns.compareTo(LARGE_REFUND) >= 0) {
            create(AlertType.LARGE_REFUND, "Large refunds total: " + returns.setScale(2), entry.getId(), entry.getCashierId());
        }
        if (expenses.compareTo(UNUSUAL_EXPENSE) >= 0) {
            create(AlertType.UNUSUAL_EXPENSE, "High expenses total: " + expenses.setScale(2), entry.getId(), entry.getCashierId());
        }
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
    }

    @Transactional
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    public void checkMissingSubmissions() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (User cashier : userRepository.findByRoleAndActiveTrue(Role.CASHIER)) {
            boolean submitted = entryRepository.existsByCashierIdAndDateAndDeletedAtIsNullAndStatus(
                    cashier.getId(), today, EntryStatus.LOCKED);
            if (!submitted && alertRepository
                    .findFirstByTypeAndUserIdAndCreatedAtGreaterThanEqual(
                            AlertType.MISSING_SUBMISSION, cashier.getId(), startOfDay)
                    .isEmpty()) {
                create(AlertType.MISSING_SUBMISSION,
                        cashier.getName() + " has not submitted today's report",
                        null, cashier.getId());
            }
        }
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
