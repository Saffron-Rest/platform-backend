package com.saffron.cashflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saffron.cashflow.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MustChangePasswordFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser authUser) {
            var user = userRepository.findById(authUser.id()).orElse(null);
            if (user != null && user.isMustChangePassword() && !isAllowedWhilePasswordChangeRequired(request)) {
                writePasswordChangeRequired(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isAllowedWhilePasswordChangeRequired(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("/api/auth/me".equals(path) && "GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return "/api/auth/change-password".equals(path) && "POST".equalsIgnoreCase(request.getMethod());
    }

    private void writePasswordChangeRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", "Password change required",
                "code", "PASSWORD_CHANGE_REQUIRED"));
    }
}
