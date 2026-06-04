package com.saffron.cashflow.controller;

import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.domain.WorkShift;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.repository.WorkShiftRepository;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.security.JwtService;
import com.saffron.cashflow.service.AuthService;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public POS PIN authentication endpoints.
 *
 * <p>Both routes are permitted without a JWT so the POS tablet
 * can operate without a prior platform login.</p>
 */
@RestController
@RequestMapping("/api/pos")
public class PosPinController {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final WorkShiftRepository workShiftRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public PosPinController(WorkShiftRepository workShiftRepository,
                            UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            JwtService jwtService) {
        this.workShiftRepository = workShiftRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Returns the cashiers scheduled to work today.
     * Public — no JWT required. Returns only id, name, hasPin.
     */
    @GetMapping("/cashiers-today")
    public List<Map<String, Object>> cashiersToday() {
        LocalDate today = LocalDate.now(WARSAW);
        List<WorkShift> shifts = workShiftRepository.findByDateWithUser(today);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkShift shift : shifts) {
            if (!shift.isWorking()) continue;
            User u = shift.getUser();
            if (u == null || !u.isActive()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", u.getName());
            m.put("hasPin", u.getPosPin() != null);
            result.add(m);
        }
        return result;
    }

    /**
     * Authenticates a cashier via their 4-digit PIN.
     *
     * <p>Validates that the PIN matches a scheduled-today active cashier
     * and returns a standard JWT. Body: {@code { "pin": "1234" }}.</p>
     */
    @PostMapping("/pin-auth")
    public Map<String, Object> pinAuth(@RequestBody Map<String, Object> body) {
        String pin = body.get("pin") != null ? body.get("pin").toString().trim() : "";
        if (pin.length() != 4 || !pin.matches("\\d{4}")) {
            throw new BadRequestException("PIN must be exactly 4 digits");
        }

        // Collect active users with a PIN set who are scheduled today
        LocalDate today = LocalDate.now(WARSAW);
        List<WorkShift> shifts = workShiftRepository.findByDateWithUser(today);

        User matched = null;
        for (WorkShift shift : shifts) {
            if (!shift.isWorking()) continue;
            User u = shift.getUser();
            if (u == null || !u.isActive() || u.getPosPin() == null) continue;
            if (passwordEncoder.matches(pin, u.getPosPin())) {
                matched = u;
                break;
            }
        }

        if (matched == null) {
            throw new BadCredentialsException("Invalid PIN or not scheduled today");
        }

        AuthUser authUser = AuthService.toAuthUser(matched);
        Map<String, Object> cashierMap = new LinkedHashMap<>();
        cashierMap.put("id", matched.getId());
        cashierMap.put("name", matched.getName());

        return Map.of("token", jwtService.generateToken(authUser), "cashier", cashierMap);
    }
}
