package com.saffron.cashflow.config;

import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * On startup: optionally wipe all app data (production reset), then ensure a single admin user.
 * Never creates reports, entries, cashiers, managers, or settings.
 */
@Configuration
public class AdminBootstrap {

    @Bean
    @Order(1)
    CommandLineRunner ensureAdminOnly(UserRepository users, PasswordEncoder encoder, JdbcTemplate jdbc) {
        return args -> {
            if (emptyDatabaseRequested()) {
                wipeAllApplicationData(jdbc);
                createAdmin(users, encoder);
                System.out.println("Bootstrap: database emptied — only admin user (change password on first login)");
                return;
            }

            ensureAdmin(users, encoder);
        };
    }

    private static boolean emptyDatabaseRequested() {
        return "true".equalsIgnoreCase(System.getenv("APP_BOOTSTRAP_EMPTY_DATABASE"));
    }

    private static void wipeAllApplicationData(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                TRUNCATE TABLE
                  notification_dispatch,
                  push_token,
                  audit_log,
                  alert,
                  receipt_file,
                  expense_item,
                  work_shift,
                  daily_entry,
                  system_setting,
                  app_user
                RESTART IDENTITY CASCADE
                """);
    }

    private static void ensureAdmin(UserRepository users, PasswordEncoder encoder) {
        if (users.existsByUsername("admin")) {
            return;
        }
        var existingAdmin = users.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN)
                .findFirst();
        if (existingAdmin.isPresent()) {
            User admin = existingAdmin.get();
            admin.setUsername("admin");
            admin.setActive(true);
            users.save(admin);
            return;
        }
        createAdmin(users, encoder);
    }

    private static void createAdmin(UserRepository users, PasswordEncoder encoder) {
        String password = System.getenv("APP_SEED_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Set APP_SEED_ADMIN_PASSWORD (required when creating the admin user or wiping the database).");
        }
        User admin = new User();
        admin.setUsername("admin");
        admin.setPasswordHash(encoder.encode(password));
        admin.setName("Admin");
        admin.setRole(Role.ADMIN);
        admin.setMustChangePassword(true);
        users.save(admin);
    }
}
