package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.CreateUserRequest;
import com.saffron.cashflow.dto.UpdateUserRequest;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.ConflictException;
import com.saffron.cashflow.util.AuditSnapshots;
import com.saffron.cashflow.util.PasswordGenerator;
import com.saffron.cashflow.util.UserCredentials;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * Generate a one-time temporary password for {@code id}, save its
     * hash, and force the user into the change-password flow on their
     * next login.
     *
     * <p>The plaintext is returned in the response <em>once</em> so the
     * admin can copy/share it. It is deliberately never logged or
     * audited — only the fact that a reset happened is recorded. If the
     * admin loses the temp password between the API response and
     * handing it off, they need to reset again.</p>
     *
     * <p>An admin cannot reset their own password through this endpoint;
     * the regular self-service change-password flow is the correct path
     * for that and avoids a foot-gun where an admin could lock themselves
     * out without knowing the freshly minted password (e.g. browser
     * crash mid-modal).</p>
     */
    @Transactional
    public Map<String, Object> resetPassword(String id) {
        AuthHelper.requireAdmin();
        String currentId = AuthHelper.currentUser().id();
        if (currentId.equals(id)) {
            throw new BadRequestException(
                    "Use Change password from your account menu to rotate your own password");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getRole() == Role.ADMIN) {
            throw new BadRequestException("Cannot reset another admin's password");
        }
        String temp = PasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(temp));
        user.setMustChangePassword(true);
        userRepository.save(user);
        // Audit records the fact only — never the plaintext. The "after"
        // map is intentionally minimal so a JSON dump of the audit log
        // can never leak the credential.
        auditService.logChange(
                currentId,
                AuditAction.UPDATE,
                "User",
                user.getId(),
                Map.of("passwordReset", false),
                Map.of("passwordReset", true, "mustChangePassword", true),
                Map.of("reason", "Admin-issued one-time password"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("username", user.getUsername());
        out.put("name", user.getName());
        out.put("tempPassword", temp);
        out.put("mustChangePassword", true);
        return out;
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
        // Permission overlay — defaults from role plus admin-granted
        // extras. The list endpoint includes these so the AdminTeam page
        // can show "X extra permissions" badges without a per-user GET.
        Set<Permission> defaults = Permission.defaultsFor(u.getRole());
        Set<Permission> extras = u.getRole() == Role.ADMIN
                ? EnumSet.noneOf(Permission.class)
                : Permission.parseCsv(u.getExtraPermissions());
        m.put("roleDefaultPermissions", toNameList(defaults));
        m.put("extraPermissions", toNameList(extras));
        Set<Permission> effective = EnumSet.copyOf(defaults);
        effective.addAll(extras);
        m.put("effectivePermissions", toNameList(effective));
        return m;
    }

    // ====================================================================
    // Permission overlay management
    // ====================================================================

    /**
     * Build the catalog of every permission the system knows about,
     * including a human label and description. The frontend uses this
     * to render the "Manage permissions" modal — it never needs to ship
     * the labels itself, so the catalog stays single-source-of-truth.
     */
    public List<Map<String, Object>> permissionCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Permission p : Permission.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", p.name());
            entry.put("label", p.label());
            entry.put("description", p.description());
            out.add(entry);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPermissions(String userId) {
        AuthHelper.requireAdmin();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        Set<Permission> defaults = Permission.defaultsFor(user.getRole());
        Set<Permission> extras = user.getRole() == Role.ADMIN
                ? EnumSet.noneOf(Permission.class)
                : Permission.parseCsv(user.getExtraPermissions());
        Set<Permission> effective = EnumSet.copyOf(defaults);
        effective.addAll(extras);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("username", user.getUsername());
        out.put("name", user.getName());
        out.put("role", user.getRole().name());
        out.put("isAdmin", user.getRole() == Role.ADMIN);
        out.put("roleDefaultPermissions", toNameList(defaults));
        out.put("extraPermissions", toNameList(extras));
        out.put("effectivePermissions", toNameList(effective));
        out.put("catalog", permissionCatalog());
        return out;
    }

    /**
     * Replace the user's extra (above-role) permissions with the
     * supplied set. Idempotent — if nothing actually changes the audit
     * row is skipped.
     *
     * <p>Safety rules:
     * <ul>
     *   <li>Admin-only. Managers cannot grant any permissions.</li>
     *   <li>Cannot target an admin: their effective set is "everything"
     *       by definition and tracking extras for them would only
     *       confuse the audit trail.</li>
     *   <li>Cannot target yourself — admins already have everything, and
     *       this avoids the "I just locked myself out" foot-gun in case
     *       the role model is ever extended.</li>
     *   <li>Unknown permission keys in the payload are ignored
     *       (forward/backward-compatible with future enum additions).</li>
     *   <li>Permissions already implied by the role default are dropped
     *       on save — storing them would create misleading "extras".</li>
     * </ul>
     * The audit log records the resulting extras set as before/after
     * arrays of enum names so a reviewer can answer "what did Joe gain
     * on 5 May?" by diffing two rows.</p>
     */
    @Transactional
    public Map<String, Object> setPermissions(String userId, Collection<String> requestedKeys, String reason) {
        AuthHelper.requireAdmin();
        String currentId = AuthHelper.currentUser().id();
        if (currentId.equals(userId)) {
            throw new BadRequestException("Cannot edit your own permissions");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getRole() == Role.ADMIN) {
            throw new BadRequestException(
                    "Admin users already hold every permission. Change their role first if you need to limit them.");
        }

        Set<Permission> requested = EnumSet.noneOf(Permission.class);
        if (requestedKeys != null) {
            for (String key : requestedKeys) {
                Permission p = Permission.tryParse(key);
                if (p != null) requested.add(p);
            }
        }
        // Drop anything the role already grants — keeps the column tidy
        // and the audit trail honest.
        Set<Permission> defaults = Permission.defaultsFor(user.getRole());
        requested.removeAll(defaults);

        Set<Permission> previous = Permission.parseCsv(user.getExtraPermissions());
        if (previous.equals(requested)) {
            // No-op — return current state, skip the audit write.
            return getPermissions(userId);
        }
        user.setExtraPermissions(requested.isEmpty() ? null : Permission.toCsv(requested));
        userRepository.save(user);

        Map<String, Object> extra = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) extra.put("reason", reason.trim());
        auditService.logChange(
                currentId,
                AuditAction.UPDATE,
                "User",
                user.getId(),
                Map.of("extraPermissions", toNameList(previous)),
                Map.of("extraPermissions", toNameList(requested)),
                extra);
        return getPermissions(userId);
    }

    /** Sorted list of enum names — used for stable audit diffs. */
    private static List<String> toNameList(Set<Permission> perms) {
        if (perms == null || perms.isEmpty()) return List.of();
        Set<String> sorted = new TreeSet<>();
        for (Permission p : perms) sorted.add(p.name());
        return new ArrayList<>(sorted);
    }
}
