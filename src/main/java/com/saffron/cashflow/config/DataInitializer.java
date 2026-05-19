package com.saffron.cashflow.config;

import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    @Order(1)
    CommandLineRunner seedAdmin(UserRepository users, PasswordEncoder encoder) {
        return args -> {
            if (users.findByUsername("admin").isPresent()) {
                return;
            }
            String password = System.getenv().getOrDefault("APP_SEED_ADMIN_PASSWORD", "admin123");
            User admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(encoder.encode(password));
            admin.setName("Admin");
            admin.setRole(Role.ADMIN);
            admin.setMustChangePassword(true);
            users.save(admin);
        };
    }
}
