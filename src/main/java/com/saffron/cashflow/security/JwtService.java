package com.saffron.cashflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

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
        return builder.signWith(key).compact();
    }

    public AuthUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Boolean mustChange = claims.get("mustChangePassword", Boolean.class);
        return new AuthUser(
                claims.getSubject(),
                claims.get("username", String.class),
                claims.get("email", String.class),
                com.saffron.cashflow.domain.Role.valueOf(claims.get("role", String.class)),
                claims.get("name", String.class),
                Boolean.TRUE.equals(mustChange));
    }
}
