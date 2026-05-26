package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.ChangePasswordRequest;
import com.saffron.cashflow.dto.LoginRequest;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.security.JwtService;
import com.saffron.cashflow.util.UserCredentials;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final TotpService totpService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService, TotpService totpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.totpService = totpService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> login(LoginRequest req) {
        String username = UserCredentials.normalizeUsername(req.username());
        Optional<User> found = userRepository.findByUsername(username).filter(User::isActive);
        if (found.isEmpty()) {
            auditService.logFailedLogin(username, "unknown account");
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        }
        User user = found.get();
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            auditService.logFailedLogin(username, "invalid password");
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        }
        // 2FA gate: if the user has TOTP enabled the FE must include the
        // 6-digit code. We deliberately keep this AFTER password verification
        // so a 2FA prompt only appears once we know the password was right —
        // otherwise the prompt would itself leak whether the password was
        // correct, defeating the purpose.
        if (totpService.isEnabledFor(user.getId())) {
            String code = req.totpCode();
            if (code == null || code.isBlank()) {
                auditService.logFailedLogin(username, "missing 2FA code");
                throw new TwoFactorRequiredException();
            }
            if (!totpService.verifyAtLogin(user.getId(), code)) {
                auditService.logFailedLogin(username, "invalid 2FA code");
                throw new org.springframework.security.authentication.BadCredentialsException("Invalid 2FA code");
            }
        }
        AuthUser authUser = toAuthUser(user);
        auditService.log(user.getId(), AuditAction.LOGIN, "User", user.getId(),
                Map.of("username", user.getUsername()), "Signed in");
        return authResponse(user, authUser);
    }

    /**
     * Signals that the password was correct but the account requires a
     * TOTP code that wasn't provided. Mapped to HTTP 401 with body
     * {@code { requires2fa: true }} by {@link com.saffron.cashflow.web.GlobalExceptionHandler}.
     */
    public static final class TwoFactorRequiredException extends RuntimeException {
        public TwoFactorRequiredException() { super("Two-factor authentication required"); }
    }

    @Transactional
    public Map<String, Object> changePassword(ChangePasswordRequest req) {
        User user = userRepository.findById(AuthHelper.currentUser().id())
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Unauthorized"));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new org.springframework.security.authentication.BadCredentialsException("Current password is incorrect");
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("New password must be different from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setMustChangePassword(false);
        user = userRepository.save(user);
        AuthUser authUser = toAuthUser(user);
        auditService.log(user.getId(), AuditAction.UPDATE, "User", user.getId(),
                Map.of("passwordChanged", true, "mustChangePassword", false), "Password updated");
        return authResponse(user, authUser);
    }

    public static AuthUser toAuthUser(User user) {
        return new AuthUser(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getName(),
                user.isMustChangePassword(),
                effectivePermissions(user));
    }

    /**
     * Compute the effective permission set for a user:
     * role defaults union any admin-granted extras stored in the
     * {@code extra_permissions} column. Admins always get everything —
     * extras are ignored for them so the audit trail isn't polluted by
     * pointless toggles on omnipotent accounts.
     */
    public static java.util.Set<com.saffron.cashflow.domain.Permission> effectivePermissions(User user) {
        if (user == null || user.getRole() == null) {
            return java.util.EnumSet.noneOf(com.saffron.cashflow.domain.Permission.class);
        }
        java.util.EnumSet<com.saffron.cashflow.domain.Permission> out =
                java.util.EnumSet.copyOf(
                        com.saffron.cashflow.domain.Permission.defaultsFor(user.getRole()));
        if (user.getRole() != com.saffron.cashflow.domain.Role.ADMIN) {
            out.addAll(com.saffron.cashflow.domain.Permission.parseCsv(user.getExtraPermissions()));
        }
        return out;
    }

    public static Map<String, Object> userMap(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        if (user.getEmail() != null) m.put("email", user.getEmail());
        m.put("role", user.getRole().name());
        m.put("name", user.getName());
        m.put("mustChangePassword", user.isMustChangePassword());
        return m;
    }

    private Map<String, Object> authResponse(User user, AuthUser authUser) {
        return Map.of(
                "token", jwtService.generateToken(authUser),
                "user", userMap(user));
    }
}
