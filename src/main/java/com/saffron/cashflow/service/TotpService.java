package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.domain.UserTotp;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.repository.UserTotpRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Two-factor authentication management.
 *
 * <p>Enrollment is a two-step ritual so accidental scans don't lock anyone
 * out:</p>
 * <ol>
 *   <li>{@link #beginEnrollment()} generates a fresh secret and stores it
 *       with {@code enabled = false}. The caller renders the QR code.</li>
 *   <li>{@link #confirmEnrollment(String)} verifies the first user-typed
 *       code; only then do we flip {@code enabled = true} so subsequent
 *       logins require a code.</li>
 * </ol>
 *
 * <p>An admin (and only an admin) can disable 2FA on someone else's
 * account via {@link #adminDisable(String)} — useful when a phone is lost.
 * A user can always disable their own 2FA with a current code.</p>
 */
@Service
public class TotpService {

    private final UserRepository userRepository;
    private final UserTotpRepository totpRepository;
    private final AuditService auditService;
    private final String issuer;

    public TotpService(
            UserRepository userRepository,
            UserTotpRepository totpRepository,
            AuditService auditService,
            @Value("${app.totp.issuer:Saffron}") String issuer) {
        this.userRepository = userRepository;
        this.totpRepository = totpRepository;
        this.auditService = auditService;
        this.issuer = issuer;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        AuthUser current = AuthHelper.currentUser();
        UserTotp totp = totpRepository.findByUserId(current.id()).orElse(null);
        return statusFor(totp);
    }

    /** Admin view — shows whether 2FA is enabled for an arbitrary user. */
    @Transactional(readOnly = true)
    public Map<String, Object> statusFor(String userId) {
        AuthHelper.requireAdmin();
        UserTotp totp = totpRepository.findByUserId(userId).orElse(null);
        return statusFor(totp);
    }

    /** Step 1 — generate (or rotate) a fresh secret and return the
     *  otpauth URI so the FE can render a QR code. */
    @Transactional
    public Map<String, Object> beginEnrollment() {
        AuthUser current = AuthHelper.currentUser();
        User u = userRepository.findById(current.id())
                .orElseThrow(() -> new NotFoundException("User not found"));
        String secret = TotpCodec.generateSecretBase32();
        UserTotp totp = totpRepository.findByUserId(current.id()).orElseGet(() -> {
            UserTotp fresh = new UserTotp();
            fresh.setUserId(current.id());
            return fresh;
        });
        totp.setSecretB32(secret);
        totp.setEnabled(false);          // not active until confirmed
        totp.setEnabledAt(null);
        totp = totpRepository.save(totp);

        String account = u.getEmail() != null && !u.getEmail().isBlank()
                ? u.getEmail()
                : u.getUsername();
        String uri = TotpCodec.buildOtpAuthUri(issuer, account, secret);

        auditService.log(current.id(), AuditAction.UPDATE, "UserTotp", current.id(),
                Map.of("step", "begin_enrollment"), "Started 2FA enrollment");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secret", secret);
        out.put("otpauthUri", uri);
        out.put("issuer", issuer);
        out.put("account", account);
        return out;
    }

    /** Step 2 — verify the user can produce a current code, then flip enabled. */
    @Transactional
    public Map<String, Object> confirmEnrollment(String code) {
        AuthUser current = AuthHelper.currentUser();
        UserTotp totp = totpRepository.findByUserId(current.id())
                .orElseThrow(() -> new BadRequestException("Start enrollment first"));
        if (totp.isEnabled()) {
            throw new BadRequestException("2FA is already enabled");
        }
        if (!TotpCodec.verify(totp.getSecretB32(), code)) {
            throw new BadRequestException("Code is wrong — check your authenticator and try again");
        }
        totp.setEnabled(true);
        totp.setEnabledAt(Instant.now());
        totp.setLastUsedAt(Instant.now());
        totpRepository.save(totp);
        auditService.log(current.id(), AuditAction.UPDATE, "UserTotp", current.id(),
                Map.of("enabled", true), "Two-factor authentication enabled");
        return statusFor(totp);
    }

    /** Self-service disable: requires the user to prove they still have
     *  the device by submitting a current code. */
    @Transactional
    public Map<String, Object> selfDisable(String code) {
        AuthUser current = AuthHelper.currentUser();
        UserTotp totp = totpRepository.findByUserId(current.id())
                .orElseThrow(() -> new BadRequestException("2FA is not enabled"));
        if (!totp.isEnabled()) throw new BadRequestException("2FA is not enabled");
        if (!TotpCodec.verify(totp.getSecretB32(), code)) {
            throw new BadRequestException("Code is wrong");
        }
        totpRepository.delete(totp);
        auditService.log(current.id(), AuditAction.UPDATE, "UserTotp", current.id(),
                Map.of("enabled", false), "Two-factor authentication disabled");
        return Map.of("enabled", false);
    }

    /** Admin override — used when an employee loses their phone. */
    @Transactional
    public Map<String, Object> adminDisable(String userId) {
        AuthHelper.requireAdmin();
        AuthUser current = AuthHelper.currentUser();
        UserTotp totp = totpRepository.findByUserId(userId).orElse(null);
        if (totp != null) totpRepository.delete(totp);
        auditService.log(current.id(), AuditAction.UPDATE, "UserTotp", userId,
                Map.of("enabled", false, "by", "admin"),
                "Two-factor authentication revoked by admin");
        return Map.of("enabled", false, "userId", userId);
    }

    /** Used by the login flow. Returns true if the user has 2FA active. */
    @Transactional(readOnly = true)
    public boolean isEnabledFor(String userId) {
        return totpRepository.findByUserId(userId).map(UserTotp::isEnabled).orElse(false);
    }

    /** Used by the login flow to verify a submitted code, and watermark
     *  {@code lastUsedAt} for the activity log. */
    @Transactional
    public boolean verifyAtLogin(String userId, String code) {
        UserTotp totp = totpRepository.findByUserId(userId).orElse(null);
        if (totp == null || !totp.isEnabled()) return false;
        if (!TotpCodec.verify(totp.getSecretB32(), code)) return false;
        totp.setLastUsedAt(Instant.now());
        totpRepository.save(totp);
        return true;
    }

    // ------------------------------------------------------------------------

    private static Map<String, Object> statusFor(UserTotp totp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", totp != null && totp.isEnabled());
        m.put("hasPendingEnrollment", totp != null && !totp.isEnabled() && totp.getSecretB32() != null);
        m.put("enabledAt", totp != null ? totp.getEnabledAt() : null);
        m.put("lastUsedAt", totp != null ? totp.getLastUsedAt() : null);
        return m;
    }
}
