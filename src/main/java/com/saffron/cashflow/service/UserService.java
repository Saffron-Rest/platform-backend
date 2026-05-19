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
import com.saffron.cashflow.util.UserCredentials;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final PayRateService payRateService;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            PayRateService payRateService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.payRateService = payRateService;
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
        String username = UserCredentials.normalizeUsername(req.username());
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("Username already exists");
        }
        String email = UserCredentials.normalizeEmail(req.email());
        if (email != null && userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("Email already exists");
        }
        Role role = req.role() != null ? req.role() : Role.CASHIER;
        if (role == Role.ADMIN) {
            throw new BadRequestException("Cannot create admin users via API");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setName(req.name());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setMustChangePassword(true);
        user.setRole(role);
        if (req.payType() != null) user.setPayType(req.payType());
        if (req.payAmount() != null) user.setPayAmount(req.payAmount());
        user.setStartDate(req.startDate());
        user = userRepository.save(user);
        payRateService.recordInitial(user, req.startDate());
        auditService.log(AuthHelper.currentUser().id(), AuditAction.CREATE, "User", user.getId(),
                Map.of("username", user.getUsername()));
        return toMap(user);
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateUserRequest req) {
        AuthHelper.requireAdmin();
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        Map<String, Object> before = AuditSnapshots.user(user);
        if (req.name() != null) user.setName(req.name());
        if (req.username() != null && !req.username().isBlank()) {
            String username = UserCredentials.normalizeUsername(req.username());
            if (!username.equals(user.getUsername()) && userRepository.findByUsername(username).isPresent()) {
                throw new ConflictException("Username already exists");
            }
            user.setUsername(username);
        }
        if (req.email() != null) {
            String email = UserCredentials.normalizeEmail(req.email());
            if (email != null && !email.equalsIgnoreCase(user.getEmail() != null ? user.getEmail() : "")
                    && userRepository.findByEmail(email).isPresent()) {
                throw new ConflictException("Email already exists");
            }
            user.setEmail(email);
        }
        if (req.active() != null) user.setActive(req.active());
        if (req.role() != null) {
            if (req.role() == Role.ADMIN) {
                throw new BadRequestException("Cannot assign admin role via API");
            }
            if (user.getRole() == Role.ADMIN) {
                throw new BadRequestException("Cannot change admin role");
            }
            user.setRole(req.role());
        }
        if (req.password() != null && !req.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
            user.setMustChangePassword(true);
        }
        PayType previousPayType = user.getPayType();
        BigDecimal previousPayAmount = user.getPayAmount();
        PayType newPayType = req.payType() != null ? req.payType() : previousPayType;
        BigDecimal amount = req.payAmount() != null ? req.payAmount() : req.hourlyRate();
        boolean payChanged = req.payType() != null && req.payType() != previousPayType
                || amount != null
                        && (previousPayAmount == null || amount.compareTo(previousPayAmount) != 0);
        if (amount != null) {
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new BadRequestException("Pay amount cannot be negative");
            }
        }
        if (payChanged) {
            BigDecimal newAmount = amount != null ? amount : previousPayAmount;
            if (newAmount == null) {
                throw new BadRequestException("Pay amount is required when changing pay");
            }
            LocalDate effectiveFrom = req.payEffectiveFrom() != null ? req.payEffectiveFrom() : LocalDate.now();
            payRateService.recordChange(user, newPayType, newAmount, effectiveFrom, req.payChangeNote());
            user.setPayType(newPayType);
            user.setPayAmount(newAmount);
        } else {
            if (req.payType() != null) user.setPayType(req.payType());
            if (amount != null) user.setPayAmount(amount);
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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> payRateHistory(String userId) {
        return payRateService.listHistory(userId);
    }

    private Map<String, Object> toMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        if (u.getEmail() != null) m.put("email", u.getEmail());
        m.put("name", u.getName());
        m.put("mustChangePassword", u.isMustChangePassword());
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
