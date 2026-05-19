package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.LoginRequest;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var user = AuthHelper.currentUser();
        return Map.of("user", Map.of(
                "id", user.id(),
                "email", user.email(),
                "role", user.role().name(),
                "name", user.name()));
    }
}
