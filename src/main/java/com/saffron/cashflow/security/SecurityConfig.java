package com.saffron.cashflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final MustChangePasswordFilter mustChangePasswordFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, MustChangePasswordFilter mustChangePasswordFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.mustChangePasswordFilter = mustChangePasswordFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/login").permitAll()
                        // POS PIN authentication — public so the tablet can work without platform login
                        .requestMatchers(HttpMethod.POST, "/api/pos/pin-auth").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/pos/cashiers-today").permitAll()
                        // Public menu image endpoint — used by the printable PDF preview and
                        // any guest-facing menu page. Images are non-sensitive.
                        .requestMatchers(HttpMethod.GET, "/api/files/menu/**").permitAll()
                        // POS webhooks authenticate via HMAC signature in the
                        // handler, not JWT — open the path here.
                        .requestMatchers(HttpMethod.POST, "/api/pos/webhook/**").permitAll()
                        // POS push endpoint authenticates via per-integration
                        // token embedded in the URL (?token=…) — works for any
                        // vendor that posts JSON but can't sign requests. We
                        // also permit GET so admins can sanity-check the URL
                        // from a browser (handler still validates the token).
                        .requestMatchers(HttpMethod.POST, "/api/pos/push/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/pos/push/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/pos/dotypos-webhook/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/pos/dotypos-webhook/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(mustChangePasswordFilter, JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
