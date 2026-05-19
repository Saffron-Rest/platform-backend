package com.saffron.cashflow.security;

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
}
