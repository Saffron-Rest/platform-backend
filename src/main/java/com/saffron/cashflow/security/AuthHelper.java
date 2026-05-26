package com.saffron.cashflow.security;

import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthHelper {

    private AuthHelper() {}

    public static AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw new IllegalStateException("Unauthorized");
        }
        return user;
    }

    public static boolean isCashier() {
        return currentUser().role() == Role.CASHIER;
    }

    public static boolean isAdmin() {
        return currentUser().role() == Role.ADMIN;
    }

    public static boolean isManager() {
        return currentUser().role() == Role.MANAGER;
    }

    /** Admin or manager — restaurant operations (reports, history, audit), not payroll/settings. */
    public static boolean isOperationsRole() {
        Role role = currentUser().role();
        return role == Role.ADMIN || role == Role.MANAGER;
    }

    public static void requireAdmin() {
        if (!isAdmin()) {
            throw new ForbiddenException("Admin only");
        }
    }

    public static void requireOperations() {
        if (!isOperationsRole()) {
            throw new ForbiddenException("Not allowed");
        }
    }

    // ------------------------------------------------------------------
    // Fine-grained permission overlay
    // ------------------------------------------------------------------

    /**
     * True when the currently-authenticated principal effectively holds
     * {@code permission} — either because their role grants it by
     * default or an admin has elevated them. Admins always return true.
     */
    public static boolean hasPermission(Permission permission) {
        if (permission == null) return false;
        AuthUser user = currentUser();
        if (user.role() == Role.ADMIN) return true;
        return user.permissions().contains(permission);
    }

    /**
     * Throws {@link ForbiddenException} when the current principal does
     * not hold {@code permission}. Prefer this to ad-hoc role checks at
     * the boundary of features that can be selectively delegated.
     */
    public static void requirePermission(Permission permission) {
        if (!hasPermission(permission)) {
            throw new ForbiddenException(
                    permission == null
                            ? "Not allowed"
                            : "Missing permission: " + permission.name());
        }
    }
}
