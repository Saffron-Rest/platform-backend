package com.saffron.cashflow.security;

import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.Role;

import java.util.EnumSet;
import java.util.Set;

/**
 * Authenticated principal carried in the security context for the
 * duration of a request.
 *
 * <p>{@link #permissions} is the <i>effective</i> set —
 * {@code Permission.defaultsFor(role) ∪ extras}. Always check this set
 * (via {@link AuthHelper#hasPermission}) for fine-grained gating;
 * {@link #role} is only useful for the broad-stroke ADMIN /
 * MANAGER / CASHIER distinction.</p>
 */
public record AuthUser(
        String id,
        String username,
        String email,
        Role role,
        String name,
        boolean mustChangePassword,
        Set<Permission> permissions
) {
    /** Defensive copy on construction — Stop callers mutating the
     *  permission set after the principal is bound. */
    public AuthUser {
        permissions = permissions == null
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(permissions);
    }
}
