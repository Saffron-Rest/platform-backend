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

    /**
     * Admin-initiated password reset.
     *
     * <p>Generates a one-time temporary password, persists its hash, and
     * forces the target user into the change-password flow on next sign-
     * in. The plaintext temp password is returned in this response
     * exactly once — callers must surface it to the admin immediately
     * and never persist it client-side.</p>
     */
    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String id) {
        return userService.resetPassword(id);
    }

    // ====================================================================
    // POS PIN management
    // ====================================================================

    /**
     * Set or clear a cashier's POS PIN.
     * Body: {@code { "pin": "1234" }} to set, or {@code { "pin": null }} to clear.
     */
    @PutMapping("/{id}/pos-pin")
    public Map<String, Object> setPosPin(@PathVariable String id, @RequestBody java.util.Map<String, Object> body) {
        String pin = body.get("pin") != null ? body.get("pin").toString() : null;
        return userService.setPosPin(id, pin);
    }

    // ====================================================================
    // Permission overlay
    // ====================================================================

    /** Catalog of permissions known to the system. Used by the
     *  "Manage permissions" modal to render labels/descriptions. */
    @GetMapping("/permission-catalog")
    public List<Map<String, Object>> permissionCatalog() {
        return userService.permissionCatalog();
    }

    /**
     * Read the current permission view for a user — role defaults,
     * admin-granted extras, and the resulting effective set.
     */
    @GetMapping("/{id}/permissions")
    public Map<String, Object> getPermissions(@PathVariable String id) {
        return userService.getPermissions(id);
    }

    /**
     * Replace the user's extra permissions. Body shape:
     * <pre>{ "permissions": ["STOCK_VIEW", ...], "reason": "optional note" }</pre>
     * Unknown keys are silently dropped; permissions already implied by
     * the role are filtered out before storage.
     */
    @PutMapping("/{id}/permissions")
    public Map<String, Object> setPermissions(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("permissions");
        List<String> keys = new java.util.ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) keys.add(o.toString());
            }
        }
        Object reasonRaw = body == null ? null : body.get("reason");
        String reason = reasonRaw == null ? null : reasonRaw.toString();
        return userService.setPermissions(id, keys, reason);
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
