package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.CreateUserRequest;
import com.saffron.cashflow.dto.UpdateUserRequest;
import com.saffron.cashflow.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return userService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return userService.deactivate(id);
    }

    @GetMapping("/{id}/pay-rates")
    public List<Map<String, Object>> payRateHistory(@PathVariable String id) {
        return userService.payRateHistory(id);
    }
}
