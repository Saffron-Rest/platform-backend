package com.saffron.cashflow.security;

import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/**
 * Validates the bearer JWT and binds the resulting {@link AuthUser} to
 * the security context for the duration of the request.
 *
 * <p><b>Why we re-hydrate from the DB on every request</b> — the JWT is
 * the trust anchor (signed, can't be forged), but the user's role and
 * fine-grained permissions can change at any moment via the admin
 * panel. If we relied on the JWT claims alone, an admin grant or revoke
 * would only take effect after the user logged out and back in, which
 * historically led to "I granted them STOCK_DELETE but they still get
 * 403" support tickets.</p>
 *
 * <p>The cost is one indexed primary-key lookup per authenticated
 * request, which is negligible on the single-restaurant scale Saffron
 * runs at. If the user has been deleted between issuance and now we
 * fall back to the JWT-attested principal so the request can still be
 * audited / 401'd by downstream service gates rather than silently
 * succeeding.</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                header = "Bearer " + queryToken.trim();
            }
        }
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthUser jwtPrincipal = jwtService.parseToken(header.substring(7));
                AuthUser principal = refreshFromDb(jwtPrincipal);
                var auth = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Rebuild the principal from the persisted user row so role and
     * permission overlay edits made through the admin panel propagate
     * to every authenticated request without forcing a re-login. Falls
     * back to the JWT-attested principal when the user row no longer
     * exists — downstream service gates will still 401/403 if needed.
     */
    private AuthUser refreshFromDb(AuthUser jwtPrincipal) {
        return userRepository.findById(jwtPrincipal.id())
                .map(AuthService::toAuthUser)
                .orElse(jwtPrincipal);
    }
}
