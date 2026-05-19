package com.saffron.cashflow.security;

import com.saffron.cashflow.domain.Role;

public record AuthUser(
        String id,
        String username,
        String email,
        Role role,
        String name,
        boolean mustChangePassword
) {}
