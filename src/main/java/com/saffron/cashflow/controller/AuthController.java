package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.ChangePasswordRequest;
import com.saffron.cashflow.dto.LoginRequest;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.service.AuthService;
import com.saffron.cashflow.web.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(request);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var user = userRepository.findById(AuthHelper.currentUser().id())
                .orElseThrow(() -> new NotFoundException("User not found"));
        return Map.of("user", AuthService.userMap(user));
    }
}
