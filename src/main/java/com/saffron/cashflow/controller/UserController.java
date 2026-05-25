package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.CreateUserRequest;
import com.saffron.cashflow.dto.PayRateEntryRequest;
import com.saffron.cashflow.dto.UpdatePayRateRequest;
import com.saffron.cashflow.dto.UpdateUserRequest;
import com.saffron.cashflow.service.PayRateService;
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
    private final PayRateService payRateService;

    public UserController(UserService userService, PayRateService payRateService) {
        this.userService = userService;
        this.payRateService = payRateService;
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

    @GetMapping("/pay-rates")
    public List<Map<String, Object>> allPayRates() {
        return payRateService.listAllHistory();
    }

    @GetMapping("/{id}/pay-rates")
    public List<Map<String, Object>> payRateHistory(@PathVariable String id) {
        return userService.payRateHistory(id);
    }

    @PostMapping("/{id}/pay-rates")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> addPayRate(
            @PathVariable String id, @Valid @RequestBody PayRateEntryRequest request) {
        return payRateService.addEntry(
                id, request.payType(), request.payAmount(), request.effectiveFrom(), request.notes());
    }

    @PatchMapping("/{id}/pay-rates/{entryId}")
    public List<Map<String, Object>> updatePayRate(
            @PathVariable String id,
            @PathVariable String entryId,
            @RequestBody UpdatePayRateRequest request) {
        return payRateService.updateEntry(
                id, entryId, request.payType(), request.payAmount(), request.effectiveFrom(), request.notes());
    }

    @DeleteMapping("/{id}/pay-rates/{entryId}")
    public List<Map<String, Object>> deletePayRate(
            @PathVariable String id, @PathVariable String entryId) {
        return payRateService.deleteEntry(id, entryId);
    }
}
