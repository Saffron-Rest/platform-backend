package com.saffron.cashflow.security;

import com.saffron.cashflow.domain.Permission;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(AuthUser user) {
        var builder = Jwts.builder()
                .subject(user.id())
                .claim("username", user.username())
                .claim("role", user.role().name())
                .claim("name", user.name())
                .claim("mustChangePassword", user.mustChangePassword())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs));
        if (user.email() != null) {
            builder.claim("email", user.email());
        }
        // Encode the deltas vs role default — extras granted above, and
        // role-default keys explicitly revoked. Defaults are derived
        // from the role at parse time, so the JWT stays compact and
        // survives changes to defaultsFor() without a forced logout.
        var defaults = Permission.defaultsFor(user.role());
        var extras = user.permissions().stream()
                .filter(p -> !defaults.contains(p))
                .map(Enum::name)
                .collect(Collectors.toList());
        if (!extras.isEmpty()) {
            builder.claim("permExtras", extras);
        }
        var revokes = defaults.stream()
                .filter(p -> !user.permissions().contains(p))
                .map(Enum::name)
                .collect(Collectors.toList());
        if (!revokes.isEmpty()) {
            builder.claim("permRevokes", revokes);
        }
        return builder.signWith(key).compact();
    }

    public AuthUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Boolean mustChange = claims.get("mustChangePassword", Boolean.class);
        com.saffron.cashflow.domain.Role role =
                com.saffron.cashflow.domain.Role.valueOf(claims.get("role", String.class));
        Set<Permission> permissions;
        if (role == com.saffron.cashflow.domain.Role.ADMIN) {
            // Admins always hold every permission — never trust the JWT
            // claims to whittle that down, both to honor the policy
            // documented on Permission and to avoid accidental
            // self-lockout if defaults change.
            permissions = EnumSet.allOf(Permission.class);
        } else {
            permissions = EnumSet.copyOf(Permission.defaultsFor(role));
            Object rawExtras = claims.get("permExtras");
            if (rawExtras instanceof java.util.List<?> list) {
                for (Object o : list) {
                    Permission p = Permission.tryParse(o == null ? null : o.toString());
                    if (p != null) permissions.add(p);
                }
            }
            Object rawRevokes = claims.get("permRevokes");
            if (rawRevokes instanceof java.util.List<?> list) {
                for (Object o : list) {
                    Permission p = Permission.tryParse(o == null ? null : o.toString());
                    if (p != null) permissions.remove(p);
                }
            }
        }
        return new AuthUser(
                claims.getSubject(),
                claims.get("username", String.class),
                claims.get("email", String.class),
                role,
                claims.get("name", String.class),
                Boolean.TRUE.equals(mustChange),
                permissions);
    }
}
