package com.saffron.cashflow.config;

import com.saffron.cashflow.domain.AuditAction;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Schema/data fixes for existing databases — not application seed data. */
@Configuration
public class DatabaseMigrationRunner {

    @Bean
    @Order(0)
    CommandLineRunner runMigrations(JdbcTemplate jdbc) {
        return args -> {
            try {
                jdbc.update("UPDATE app_user SET pay_type = 'HOURLY' WHERE pay_type IS NULL");
            } catch (Exception ignored) {
                // pay_type column added on first Hibernate schema update
            }
            migrateRoleConstraint(jdbc);
            migrateUserCredentials(jdbc);
            migrateAuditLogActionConstraint(jdbc);
            migrateExpenseInvoices(jdbc);
        };
    }

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

    private static void migrateUserCredentials(JdbcTemplate jdbc) {
        try {
            jdbc.execute("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS username VARCHAR(32)");
            jdbc.execute("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT true");
            jdbc.update(
                    """
                    UPDATE app_user
                    SET username = LOWER(SPLIT_PART(email, '@', 1))
                    WHERE (username IS NULL OR username = '')
                      AND email IS NOT NULL AND email <> ''
                    """);
            jdbc.update(
                    """
                    UPDATE app_user
                    SET username = 'user_' || SUBSTRING(id, 1, 8)
                    WHERE username IS NULL OR username = ''
                    """);
            jdbc.update("UPDATE app_user SET must_change_password = true WHERE must_change_password IS NULL");
            deduplicateUsernames(jdbc);
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_username ON app_user (username)");
        } catch (Exception ex) {
            System.err.println("Warning: user credentials migration: " + ex.getMessage());
        }
    }

    private static void deduplicateUsernames(JdbcTemplate jdbc) {
        int renamed = jdbc.update(
                """
                WITH ranked AS (
                  SELECT id,
                         ROW_NUMBER() OVER (
                           PARTITION BY username
                           ORDER BY CASE role WHEN 'ADMIN' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END,
                                    created_at NULLS LAST,
                                    id
                         ) AS rn
                  FROM app_user
                  WHERE username IS NOT NULL AND username <> ''
                )
                UPDATE app_user u
                SET username = LEFT(u.username, 24) || '_' || SUBSTRING(u.id, 1, 6)
                FROM ranked r
                WHERE u.id = r.id AND r.rn > 1
                """);
        if (renamed > 0) {
            System.out.println("Database: renamed " + renamed + " duplicate username(s)");
        }
    }

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
