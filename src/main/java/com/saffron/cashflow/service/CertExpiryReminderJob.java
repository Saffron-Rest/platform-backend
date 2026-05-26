package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.EmployeeCert;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.EmployeeCertRepository;
import com.saffron.cashflow.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Daily scan that pings admins and managers about employee certifications
 * close to expiry.
 *
 * <p>Cadence:</p>
 * <ul>
 *   <li>30 days out — heads-up</li>
 *   <li>14 days out — reminder</li>
 *   <li>1 day out — urgent</li>
 *   <li>Day-of and beyond — overdue (re-fires every 7 days until resolved)</li>
 * </ul>
 *
 * <p>We watermark each cert with {@code lastWarningAt} so we don't spam
 * users every morning. The watermark resets when an admin changes
 * {@code expiresOn} (handled in {@link EmployeeCertService}).</p>
 */
@Component
public class CertExpiryReminderJob {

    private static final Logger LOG = LoggerFactory.getLogger(CertExpiryReminderJob.class);

    private final EmployeeCertRepository certRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public CertExpiryReminderJob(
            EmployeeCertRepository certRepository,
            UserRepository userRepository,
            NotificationService notificationService) {
        this.certRepository = certRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /**
     * Runs once a day at 08:00 local time. The fixed delay form keeps it
     * simple — production schedules to cron-tab if needed via
     * {@code app.cert.expiry-cron}.
     */
    @Scheduled(cron = "${app.cert.expiry-cron:0 0 8 * * *}", zone = "${app.timezone:Europe/Warsaw}")
    @Transactional
    public void scan() {
        LocalDate horizon = LocalDate.now().plusDays(30);
        List<EmployeeCert> due = certRepository.findExpiringBy(horizon);
        if (due.isEmpty()) return;

        Set<String> adminIds = userRepository.findAll().stream()
                .filter(u -> u.isActive() && (u.getRole() == Role.ADMIN || u.getRole() == Role.MANAGER))
                .map(User::getId)
                .collect(Collectors.toSet());
        if (adminIds.isEmpty()) {
            LOG.info("Cert expiry scan: no admins to notify, skipping ({} certs)", due.size());
            return;
        }

        int notified = 0;
        for (EmployeeCert c : due) {
            String tier = tierFor(c.getExpiresOn());
            if (tier == null) continue; // not yet at a warning threshold
            // Skip if we already warned recently for this tier — we re-fire
            // at most every 7 days, which keeps overdue items visible without
            // creating an inbox storm.
            if (c.getLastWarningAt() != null
                    && ChronoUnit.DAYS.between(c.getLastWarningAt(), Instant.now()) < 7) {
                continue;
            }
            User holder = userRepository.findById(c.getUserId()).orElse(null);
            String holderName = holder != null ? holder.getName() : "Someone";
            long days = ChronoUnit.DAYS.between(LocalDate.now(), c.getExpiresOn());
            String title;
            if (days < 0) {
                title = String.format("%s · %s expired %d day%s ago", holderName, c.getType(),
                        Math.abs(days), Math.abs(days) == 1 ? "" : "s");
            } else if (days == 0) {
                title = String.format("%s · %s expires today", holderName, c.getType());
            } else {
                title = String.format("%s · %s expires in %d day%s", holderName, c.getType(),
                        days, days == 1 ? "" : "s");
            }
            for (String adminId : adminIds) {
                notificationService.create(
                        adminId,
                        "cert_expiring",
                        title,
                        "Open Admin → Certifications to renew or update the record.",
                        "/admin/certifications",
                        "EmployeeCert",
                        c.getId(),
                        null);
            }
            c.setLastWarningAt(Instant.now());
            certRepository.save(c);
            notified++;
        }
        if (notified > 0) {
            LOG.info("Cert expiry scan: notified {} admin(s) about {} cert(s)", adminIds.size(), notified);
        }
    }

    /** Returns the tier label or null if this cert isn't yet at a warning point. */
    private static String tierFor(LocalDate expires) {
        if (expires == null) return null;
        long days = ChronoUnit.DAYS.between(LocalDate.now(), expires);
        if (days < 0) return "OVERDUE";
        if (days == 0) return "TODAY";
        if (days <= 1) return "URGENT";
        if (days <= 14) return "TWO_WEEKS";
        if (days <= 30) return "MONTH";
        return null;
    }
}
