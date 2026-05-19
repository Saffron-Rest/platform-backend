package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.LoginRequest;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    public Map<String, Object> login(LoginRequest req) {
        String email = req.email() != null ? req.email().trim().toLowerCase() : "";
        Optional<User> found = userRepository.findByEmail(email).filter(User::isActive);
        if (found.isEmpty()) {
            auditService.logFailedLogin(email, "unknown account");
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        }
        User user = found.get();
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            auditService.logFailedLogin(email, "invalid password");
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        }
        AuthUser authUser = new AuthUser(user.getId(), user.getEmail(), user.getRole(), user.getName());
        auditService.log(user.getId(), AuditAction.LOGIN, "User", user.getId(),
                Map.of("email", user.getEmail()), "Signed in");
        return Map.of(
                "token", jwtService.generateToken(authUser),
                "user", Map.of("id", user.getId(), "email", user.getEmail(), "role", user.getRole().name(), "name", user.getName()));
    }
}
