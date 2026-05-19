package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.CreateUserRequest;
import com.saffron.cashflow.dto.UpdateUserRequest;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.ConflictException;
import com.saffron.cashflow.util.AuditSnapshots;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AuthHelper.requireOperations();
        return userRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> create(CreateUserRequest req) {
        AuthHelper.requireAdmin();
        if (userRepository.findByEmail(req.email()).isPresent()) {
            throw new ConflictException("Email already exists");
        }
        User user = new User();
        user.setEmail(req.email());
        user.setName(req.name());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole(req.role() != null ? req.role() : Role.CASHIER);
        if (req.payType() != null) user.setPayType(req.payType());
        if (req.payAmount() != null) user.setPayAmount(req.payAmount());
        user.setStartDate(req.startDate());
        user = userRepository.save(user);
        auditService.log(AuthHelper.currentUser().id(), AuditAction.CREATE, "User", user.getId(),
                Map.of("email", user.getEmail()));
        return toMap(user);
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateUserRequest req) {
        AuthHelper.requireAdmin();
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        Map<String, Object> before = AuditSnapshots.user(user);
        if (req.name() != null) user.setName(req.name());
        if (req.email() != null && !req.email().isBlank()) {
            if (!req.email().equalsIgnoreCase(user.getEmail())
                    && userRepository.findByEmail(req.email()).isPresent()) {
                throw new ConflictException("Email already exists");
            }
            user.setEmail(req.email().trim().toLowerCase());
        }
        if (req.active() != null) user.setActive(req.active());
        if (req.password() != null && !req.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        if (req.payType() != null) user.setPayType(req.payType());
        BigDecimal amount = req.payAmount() != null ? req.payAmount() : req.hourlyRate();
        if (amount != null) {
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new BadRequestException("Pay amount cannot be negative");
            }
            user.setPayAmount(amount);
        }
        if (req.startDate() != null) user.setStartDate(req.startDate());
        user = userRepository.save(user);
        Map<String, Object> after = AuditSnapshots.user(user);
        if (req.password() != null && !req.password().isBlank()) {
            after = new LinkedHashMap<>(after);
            after.put("passwordChanged", true);
        }
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "User", user.getId(), before, after, null);
        return toMap(user);
    }

    @Transactional
    public Map<String, Object> deactivate(String id) {
        AuthHelper.requireAdmin();
        if (id.equals(AuthHelper.currentUser().id())) {
            throw new BadRequestException("Cannot delete yourself");
        }
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        Map<String, Object> before = AuditSnapshots.user(user);
        user.setActive(false);
        userRepository.save(user);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "User", id, before,
                Map.of("active", false), null);
        return Map.of("ok", true);
    }

    private Map<String, Object> toMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("name", u.getName());
        m.put("role", u.getRole().name());
        m.put("active", u.isActive());
        m.put("payType", u.getPayType() != null ? u.getPayType().name() : PayType.HOURLY.name());
        m.put("payAmount", u.getPayAmount() != null ? u.getPayAmount().doubleValue() : null);
        m.put("hourlyRate", u.getPayAmount() != null ? u.getPayAmount().doubleValue() : null);
        if (u.getStartDate() != null) m.put("startDate", u.getStartDate().toString());
        if (u.getCreatedAt() != null) m.put("createdAt", u.getCreatedAt().toString());
        return m;
    }
}
