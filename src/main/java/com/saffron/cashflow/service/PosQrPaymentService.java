package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.PosOrder;
import com.saffron.cashflow.domain.PosQrTransaction;
import com.saffron.cashflow.repository.PosOrderRepository;
import com.saffron.cashflow.repository.PosQrTransactionRepository;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages QR / BLIK payment requests.
 *
 * <p><b>Current implementation is a stub</b> — it generates a local transaction
 * reference and exposes a manual confirmation endpoint so the UI can be built
 * and tested end-to-end. Swap {@link #initiateQrPayment} for a real provider
 * call (e.g. Payten mPay, BlueMedia) when merchant credentials are available.</p>
 *
 * <p>QR payload format: {@code SAFFRON-QR:transactionId:amount:currency}
 * — encode this string in a QR code on the frontend using a library such as
 * qrcode.react. In production the payload would be a BLIK deep-link or a
 * provider-specific transaction URL.</p>
 */
@Service
public class PosQrPaymentService {

    private final PosQrTransactionRepository qrRepository;
    private final PosOrderRepository orderRepository;

    public PosQrPaymentService(PosQrTransactionRepository qrRepository,
                               PosOrderRepository orderRepository) {
        this.qrRepository = qrRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Map<String, Object> initiateQrPayment(String orderId, String currency) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() == PosOrder.Status.PAID) {
            throw new BadRequestException("Order already paid");
        }
        BigDecimal amount = order.getTotalGross()
                .add(order.getTipAmount() != null ? order.getTipAmount() : BigDecimal.ZERO);

        PosQrTransaction tx = new PosQrTransaction();
        tx.setOrderId(orderId);
        tx.setAmount(amount);
        // Stub QR payload — replace with provider-specific deep-link in production.
        String payload = String.format("SAFFRON-QR:%s:%.2f:%s", null, amount, currency != null ? currency : "PLN");
        tx = qrRepository.save(tx);
        // Update payload with real ID now that we have it.
        tx.setQrPayload(String.format("SAFFRON-QR:%s:%.2f:%s", tx.getId(), amount, currency != null ? currency : "PLN"));
        tx = qrRepository.save(tx);

        return toMap(tx);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(String transactionId) {
        PosQrTransaction tx = qrRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("QR transaction not found"));
        // Auto-expire if past expiry.
        if (tx.getStatus() == PosQrTransaction.Status.PENDING
                && Instant.now().isAfter(tx.getExpiresAt())) {
            tx.setStatus(PosQrTransaction.Status.EXPIRED);
            qrRepository.save(tx);
        }
        return toMap(tx);
    }

    /** Manual confirmation endpoint — used for testing and as a webhook receiver placeholder. */
    @Transactional
    public Map<String, Object> confirm(String transactionId, String providerReference) {
        PosQrTransaction tx = qrRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("QR transaction not found"));
        if (tx.getStatus() != PosQrTransaction.Status.PENDING) {
            throw new BadRequestException("Transaction is already " + tx.getStatus());
        }
        tx.setStatus(PosQrTransaction.Status.CONFIRMED);
        tx.setConfirmedAt(Instant.now());
        tx.setProviderReference(providerReference != null ? providerReference : "stub-" + System.currentTimeMillis());
        tx = qrRepository.save(tx);

        // Mark the order as PAID with method OTHER (QR).
        PosOrder order = orderRepository.findById(tx.getOrderId()).orElse(null);
        if (order != null && order.getStatus() != PosOrder.Status.PAID) {
            order.setStatus(PosOrder.Status.PAID);
            order.setPaidAt(Instant.now());
            order.setPaymentMethod(com.saffron.cashflow.domain.SupplierInvoicePayment.PaymentMethod.OTHER);
            orderRepository.save(order);
        }
        return toMap(tx);
    }

    @Transactional
    public Map<String, Object> cancel(String transactionId) {
        PosQrTransaction tx = qrRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("QR transaction not found"));
        if (tx.getStatus() == PosQrTransaction.Status.CONFIRMED) {
            throw new BadRequestException("Cannot cancel a confirmed transaction");
        }
        tx.setStatus(PosQrTransaction.Status.CANCELLED);
        tx = qrRepository.save(tx);
        return toMap(tx);
    }

    private Map<String, Object> toMap(PosQrTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("orderId", tx.getOrderId());
        m.put("amount", tx.getAmount().doubleValue());
        m.put("status", tx.getStatus().name());
        m.put("qrPayload", tx.getQrPayload());
        m.put("providerReference", tx.getProviderReference());
        m.put("createdAt", tx.getCreatedAt().toString());
        m.put("expiresAt", tx.getExpiresAt().toString());
        m.put("confirmedAt", tx.getConfirmedAt() != null ? tx.getConfirmedAt().toString() : null);
        return m;
    }
}
