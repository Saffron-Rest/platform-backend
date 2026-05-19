package com.saffron.cashflow.config;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.util.EntryCalculator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner seed(
            UserRepository users,
            DailyEntryRepository entries,
            SystemSettingRepository settings,
            PasswordEncoder encoder,
            JdbcTemplate jdbc) {
        return args -> {
            try {
                jdbc.update("UPDATE app_user SET pay_type = 'HOURLY' WHERE pay_type IS NULL");
            } catch (Exception ignored) {
                // pay_type column added on first Hibernate schema update
            }
            migrateRoleConstraint(jdbc);
            migrateAuditLogActionConstraint(jdbc);
            migrateExpenseInvoices(jdbc);
            if (settings.findById("platforms").isEmpty()) {
                SystemSetting s = new SystemSetting();
                s.setKey("platforms");
                s.setValue(Map.of("wolt", true, "bolt", true, "uberEats", true, "glovo", true, "other", true));
                settings.save(s);
            }
            if (settings.findById("payroll").isEmpty()) {
                SystemSetting p = new SystemSetting();
                p.setKey("payroll");
                p.setValue(Map.of("weeklyHours", com.saffron.cashflow.util.WeeklyOperatingHours.defaults().toApiMap()));
                settings.save(p);
            }

            LocalDate seedStart = LocalDate.of(2024, 1, 1);

            users.findByEmail("admin@saffron.local").orElseGet(() -> {
                User u = new User();
                u.setEmail("admin@saffron.local");
                u.setPasswordHash(encoder.encode("admin123"));
                u.setName("Admin");
                u.setRole(Role.ADMIN);
                u.setStartDate(seedStart);
                return users.save(u);
            });

            User cashier = users.findByEmail("cashier@saffron.local").orElseGet(() -> {
                User u = new User();
                u.setEmail("cashier@saffron.local");
                u.setPasswordHash(encoder.encode("cashier123"));
                u.setName("Maria Cashier");
                u.setRole(Role.CASHIER);
                u.setPayType(PayType.HOURLY);
                u.setPayAmount(new BigDecimal("28.00"));
                u.setStartDate(seedStart);
                return users.save(u);
            });
            if (cashier.getStartDate() == null) {
                cashier.setStartDate(seedStart);
                users.save(cashier);
            }
            if (cashier.getPayAmount() == null || cashier.getPayType() == null) {
                if (cashier.getPayType() == null) cashier.setPayType(PayType.HOURLY);
                if (cashier.getPayAmount() == null) cashier.setPayAmount(new BigDecimal("28.00"));
                users.save(cashier);
            }

            LocalDate today = LocalDate.now();
            if (!entries.existsByCashier_IdAndDate(cashier.getId(), today)) {
                DailyEntry e = new DailyEntry();
                e.setDate(today);
                e.setCashier(cashier);
                e.setStatus(EntryStatus.DRAFT);
                e.setOpeningBalance(new BigDecimal("500"));
                e.setCashSales(new BigDecimal("1200"));
                e.setCardSales(new BigDecimal("800"));
                e.setWoltSales(new BigDecimal("350"));
                e.setBoltSales(new BigDecimal("200"));
                e.setActualCashCounted(new BigDecimal("1850"));
                BigDecimal closing = EntryCalculator.round(
                        e.getOpeningBalance().add(EntryCalculator.totalSales(e))
                                .subtract(EntryCalculator.totalReturns(e))
                                .subtract(EntryCalculator.totalExpenses(e)));
                e.setClosingBalance(closing);
                e.setDifference(e.getActualCashCounted().subtract(closing));
                entries.save(e);
            }

            users.findByEmail("manager@saffron.local").orElseGet(() -> {
                User u = new User();
                u.setEmail("manager@saffron.local");
                u.setPasswordHash(encoder.encode("manager123"));
                u.setName("Alex Manager");
                u.setRole(Role.MANAGER);
                u.setStartDate(seedStart);
                return users.save(u);
            });

            System.out.println("Seed complete:");
            System.out.println("  Admin:   admin@saffron.local / admin123");
            System.out.println("  Manager: manager@saffron.local / manager123");
            System.out.println("  Cashier: cashier@saffron.local / cashier123");
        };
    }

    /** Move expense receipt from expense_item.receipt_file_id to receipt_file.expense_item_id. */
    private static void migrateExpenseInvoices(JdbcTemplate jdbc) {
        try {
            jdbc.execute("ALTER TABLE receipt_file ADD COLUMN IF NOT EXISTS expense_item_id VARCHAR(255)");
            jdbc.update(
                    """
                    UPDATE receipt_file rf
                    SET expense_item_id = ei.id
                    FROM expense_item ei
                    WHERE ei.receipt_file_id = rf.id
                      AND (rf.expense_item_id IS NULL OR rf.expense_item_id = '')
                    """);
            jdbc.execute("ALTER TABLE expense_item DROP COLUMN IF EXISTS receipt_file_id");
        } catch (Exception ex) {
            System.err.println("Warning: expense invoice migration: " + ex.getMessage());
        }
    }

  /**
   * Legacy DB check constraints omitted SYNC (expense sync) and UNLOCK.
   * Rebuild from {@link AuditAction} so new enum values always apply on startup.
   */
    private static void migrateAuditLogActionConstraint(JdbcTemplate jdbc) {
        String allowed = Arrays.stream(AuditAction.values())
                .map(a -> "'" + a.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_action_check");
            jdbc.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN ("
                            + allowed
                            + "))");
            System.out.println("Database: audit_log_action_check updated (" + AuditAction.values().length + " actions)");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to migrate audit_log action constraint: " + ex.getMessage(), ex);
        }
    }

    /** PostgreSQL check constraint from initial schema only allowed ADMIN/CASHIER. */
    private static void migrateRoleConstraint(JdbcTemplate jdbc) {
        try {
            jdbc.execute("ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_role_check");
            jdbc.execute(
                    "ALTER TABLE app_user ADD CONSTRAINT app_user_role_check "
                            + "CHECK (role IN ('ADMIN', 'MANAGER', 'CASHIER'))");
        } catch (Exception ex) {
            System.err.println("Warning: could not update app_user role constraint: " + ex.getMessage());
        }
    }
}
