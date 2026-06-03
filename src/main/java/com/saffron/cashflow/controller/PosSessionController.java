package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.PosSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/pos/session")
public class PosSessionController {

    private final PosSessionService posSessionService;

    public PosSessionController(PosSessionService posSessionService) {
        this.posSessionService = posSessionService;
    }

    /** Returns the current open session, or 204 if none. */
    @GetMapping("/current")
    public Map<String, Object> current() {
        return posSessionService.getCurrentSession();
    }

    /** Opens a POS shift. Body: { "openingFloat": 200.00 } */
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> open(@RequestBody(required = false) Map<String, Object> body) {
        BigDecimal float_ = body != null && body.get("openingFloat") != null
                ? new BigDecimal(body.get("openingFloat").toString())
                : BigDecimal.ZERO;
        return posSessionService.openSession(float_);
    }

    /** Closes the session. Body: { "sessionId": "...", "closingFloat": 150.00 } */
    @PostMapping("/close")
    public Map<String, Object> close(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        BigDecimal closingFloat = body.get("closingFloat") != null
                ? new BigDecimal(body.get("closingFloat").toString())
                : BigDecimal.ZERO;
        return posSessionService.closeSession(sessionId, closingFloat);
    }
}
