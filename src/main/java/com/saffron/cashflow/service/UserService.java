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
        AuthHelper.requireAdminOr(Permission.TEAM_MANAGE);
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
        // Admins always pass; managers with TEAM_MANAGE can edit non-pay
        // team fields. Pay-rate edits and password resets get an
        // additional gate inside this method so a TEAM_MANAGE-only
        // delegate can't sneak past their narrower mandate.
        AuthHelper.requireAdminOr(Permission.TEAM_MANAGE, Permission.PAY_RATES_MANAGE, Permission.TEAM_RESET_PASSWORD);
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        Map<String, Object> before = AuditSnapshots.user(user);

        // Decide which scopes the request actually touches and gate
        // each on its own permission. This keeps a TEAM_MANAGE delegate
        // from sneaking in pay-rate or password-reset edits.
        boolean touchesTeamFields =
                req.name() != null
                        || (req.username() != null && !req.username().isBlank())
                        || req.email() != null
                        || req.active() != null
                        || req.role() != null
                        || req.startDate() != null;
        boolean touchesPassword = req.password() != null && !req.password().isBlank();
        if (touchesTeamFields) {
            AuthHelper.requireAdminOr(Permission.TEAM_MANAGE);
        }
        if (touchesPassword) {
            AuthHelper.requireAdminOr(Permission.TEAM_RESET_PASSWORD);
        }

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
        if (touchesPassword) {
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
            AuthHelper.requireAdminOr(Permission.PAY_RATES_MANAGE);
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
        AuthHelper.requireAdminOr(Permission.TEAM_RESET_PASSWORD);
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
    public Map<String, Object> setPosPin(String id, String pin) {
        AuthHelper.requireAdminOr(Permission.TEAM_MANAGE);
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (pin == null || pin.isBlank()) {
            user.setPosPin(null);
        } else {
            if (!pin.matches("\\d{4}")) throw new BadRequestException("PIN must be exactly 4 digits");
            user.setPosPin(passwordEncoder.encode(pin));
        }
        userRepository.save(user);
        return Map.of("success", true, "hasPin", user.getPosPin() != null);
    }

    @Transactional
    public Map<String, Object> deactivate(String id) {
        AuthHelper.requireAdminOr(Permission.TEAM_MANAGE);
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
        m.put("canViewEarnings", u.isCanViewEarnings());
        m.put("payType", u.getPayType() != null ? u.getPayType().name() : PayType.HOURLY.name());
        m.put("payAmount", u.getPayAmount() != null ? u.getPayAmount().doubleValue() : null);
        m.put("hourlyRate", u.getPayAmount() != null ? u.getPayAmount().doubleValue() : null);
        if (u.getStartDate() != null) m.put("startDate", u.getStartDate().toString());
        if (u.getCreatedAt() != null) m.put("createdAt", u.getCreatedAt().toString());
        m.put("hasPin", u.getPosPin() != null);
        // Permission overlay — role defaults, admin-granted extras, and
        // admin-revoked defaults. The list endpoint includes these so
        // the AdminTeam page can show summary badges without a per-user
        // GET.
        Set<Permission> defaults = Permission.defaultsFor(u.getRole());
        boolean isAdmin = u.getRole() == Role.ADMIN;
        Set<Permission> extras = isAdmin
                ? EnumSet.noneOf(Permission.class)
                : Permission.parseCsv(u.getExtraPermissions());
        Set<Permission> revokes = isAdmin
                ? EnumSet.noneOf(Permission.class)
                : Permission.parseCsv(u.getRevokedPermissions());
        m.put("roleDefaultPermissions", toNameList(defaults));
        m.put("extraPermissions", toNameList(extras));
        m.put("revokedPermissions", toNameList(revokes));
        Set<Permission> effective = isAdmin
                ? EnumSet.allOf(Permission.class)
                : Permission.effective(u.getRole(), extras, revokes);
        m.put("effectivePermissions", toNameList(effective));
        return m;
    }

    // ====================================================================
    // Permission overlay management
    // ====================================================================

    /**
     * Build the catalog of every permission the system knows about,
     * including a human label, description, and category. The frontend
     * uses this to render the "Manage permissions" modal — it never
     * needs to ship the labels itself, so the catalog stays
     * single-source-of-truth.
     */
    public List<Map<String, Object>> permissionCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Permission p : Permission.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", p.name());
            entry.put("label", p.label());
            entry.put("description", p.description());
            entry.put("category", p.category().name());
            entry.put("categoryLabel", p.category().label());
            out.add(entry);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getPermissions(String userId) {
        AuthHelper.requireAdmin();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        boolean isAdmin = user.getRole() == Role.ADMIN;
        Set<Permission> defaults = Permission.defaultsFor(user.getRole());
        Set<Permission> extras = isAdmin
                ? EnumSet.noneOf(Permission.class)
                : Permission.parseCsv(user.getExtraPermissions());
        Set<Permission> revokes = isAdmin
                ? EnumSet.noneOf(Permission.class)
                : Permission.parseCsv(user.getRevokedPermissions());
        Set<Permission> effective = isAdmin
                ? EnumSet.allOf(Permission.class)
                : Permission.effective(user.getRole(), extras, revokes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("username", user.getUsername());
        out.put("name", user.getName());
        out.put("role", user.getRole().name());
        out.put("isAdmin", isAdmin);
        out.put("roleDefaultPermissions", toNameList(defaults));
        out.put("extraPermissions", toNameList(extras));
        out.put("revokedPermissions", toNameList(revokes));
        out.put("effectivePermissions", toNameList(effective));
        out.put("catalog", permissionCatalog());
        return out;
    }

    /**
     * Replace the user's full effective permission set. The client
     * sends the desired final state (the union of every permission the
     * user should have) and the server splits it into:
     *
     * <ul>
     *   <li><b>extras</b> = desired − roleDefaults — keys granted on top
     *       of the role baseline.</li>
     *   <li><b>revokes</b> = roleDefaults − desired — role-default keys
     *       explicitly denied.</li>
     * </ul>
     *
     * <p>This lets the modal use a single "what the user can do" mental
     * model instead of asking admins to think in deltas. Idempotent —
     * if nothing actually changes the audit row is skipped.</p>
     *
     * <p>Safety rules:
     * <ul>
     *   <li>Admin-only. Managers cannot grant or revoke any
     *       permissions.</li>
     *   <li>Cannot target an admin: their effective set is "everything"
     *       by definition and tracking overrides for them would only
     *       confuse the audit trail. Change their role first if you
     *       need to limit them.</li>
     *   <li>Cannot target yourself.</li>
     *   <li>Unknown permission keys in the payload are ignored
     *       (forward/backward-compatible with future enum additions).</li>
     * </ul>
     *
     * <p>The audit log records both columns before/after as sorted
     * arrays of enum names so a reviewer can answer "what did Joe gain
     * — or lose — on 5 May?" by diffing two rows.</p>
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

        // Parse the desired set tolerantly: ignore unknown enum names so
        // forward/backward-compat with future enum changes is automatic.
        Set<Permission> desired = EnumSet.noneOf(Permission.class);
        if (requestedKeys != null) {
            for (String key : requestedKeys) {
                Permission p = Permission.tryParse(key);
                if (p != null) desired.add(p);
            }
        }

        Set<Permission> defaults = Permission.defaultsFor(user.getRole());
        EnumSet<Permission> newExtras = EnumSet.copyOf(desired);
        newExtras.removeAll(defaults);
        EnumSet<Permission> newRevokes = EnumSet.copyOf(defaults);
        newRevokes.removeAll(desired);

        Set<Permission> prevExtras = Permission.parseCsv(user.getExtraPermissions());
        Set<Permission> prevRevokes = Permission.parseCsv(user.getRevokedPermissions());
        if (prevExtras.equals(newExtras) && prevRevokes.equals(newRevokes)) {
            // No-op — return current state, skip the audit write.
            return getPermissions(userId);
        }
        user.setExtraPermissions(newExtras.isEmpty() ? null : Permission.toCsv(newExtras));
        user.setRevokedPermissions(newRevokes.isEmpty() ? null : Permission.toCsv(newRevokes));
        userRepository.save(user);

        Map<String, Object> extra = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) extra.put("reason", reason.trim());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("extraPermissions", toNameList(prevExtras));
        before.put("revokedPermissions", toNameList(prevRevokes));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("extraPermissions", toNameList(newExtras));
        after.put("revokedPermissions", toNameList(newRevokes));
        auditService.logChange(
                currentId,
                AuditAction.UPDATE,
                "User",
                user.getId(),
                before,
                after,
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
