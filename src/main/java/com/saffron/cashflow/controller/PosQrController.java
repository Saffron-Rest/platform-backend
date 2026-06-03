package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.PosQrPaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pos")
public class PosQrController {

    private final PosQrPaymentService qrService;

    public PosQrController(PosQrPaymentService qrService) {
        this.qrService = qrService;
    }

    /** Initiate a QR / BLIK payment. Body: { "currency": "PLN" } */
    @PostMapping("/orders/{orderId}/qr-payment")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> initiate(@PathVariable String orderId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String currency = body != null ? (String) body.getOrDefault("currency", "PLN") : "PLN";
        return qrService.initiateQrPayment(orderId, currency);
    }

    /** Poll for payment status. */
    @GetMapping("/qr/{transactionId}/status")
    public Map<String, Object> status(@PathVariable String transactionId) {
        return qrService.getStatus(transactionId);
    }

    /**
     * Webhook / manual confirmation endpoint.
     * In production: called by the BLIK provider with a signed payload.
     * In development: call manually to test the confirmation flow.
     */
    @PostMapping("/qr/{transactionId}/confirm")
    public Map<String, Object> confirm(@PathVariable String transactionId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        String ref = body != null ? (String) body.get("providerReference") : null;
        return qrService.confirm(transactionId, ref);
    }

    /** Cancel a pending QR transaction. */
    @PostMapping("/qr/{transactionId}/cancel")
    public Map<String, Object> cancel(@PathVariable String transactionId) {
        return qrService.cancel(transactionId);
    }
}
