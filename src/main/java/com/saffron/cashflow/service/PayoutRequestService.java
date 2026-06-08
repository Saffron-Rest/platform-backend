package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.PaymentSource;
import com.saffron.cashflow.domain.PayoutRequest;
import com.saffron.cashflow.domain.PayoutRequestStatus;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.RecordSalaryPaymentRequest;
import com.saffron.cashflow.repository.PayoutRequestRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.security.ForbiddenException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PayoutRequestService {

    private final PayoutRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final SalaryService salaryService;
    private final TreasuryService treasuryService;

    public PayoutRequestService(
            PayoutRequestRepository requestRepository,
            UserRepository userRepository,
            SalaryService salaryService,
            TreasuryService treasuryService) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.salaryService = salaryService;
        this.treasuryService = treasuryService;
    }

    // ── Cashier self-service ──────────────────────────────────────────────────

    /**
     * Returns the earnings summary for the currently logged-in cashier
     * (earned, paid, remaining) plus their own payout-request history.
     * Requires {@code canViewEarnings = true} on their user record.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getMyEarnings(String fromParam, String toParam) {
        String userId = AuthHelper.currentUser().id();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!user.isCanViewEarnings()) {
            throw new ForbiddenException("Earnings view is not enabled for your account");
        }

        Map<String, Object> payroll = salaryService.calculateForUser(fromParam, toParam, userId);
        List<PayoutRequest> requests = requestRepository.findByUserId(userId);

        Map<String, Object> result = new LinkedHashMap<>(payroll);
        result.put("requests", requests.stream().map(this::toMap).toList());
        return result;
    }

    /** Cashier submits a payout request for a given amount. */
    @Transactional
    public Map<String, Object> createRequest(BigDecimal amount, String notes) {
        String userId = AuthHelper.currentUser().id();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!user.isCanViewEarnings()) {
            throw new ForbiddenException("Earnings view is not enabled for your account");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        if (requestRepository.existsByUserIdAndStatus(userId, PayoutRequestStatus.PENDING)) {
            throw new BadRequestException("You already have a pending payout request. Wait for it to be reviewed before submitting another.");
        }

        PayoutRequest req = new PayoutRequest();
        req.setUserId(userId);
        req.setRequestedAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        req.setRequestedDate(LocalDate.now());
        req.setNotes(notes != null && !notes.isBlank() ? notes.trim() : null);
        req.setStatus(PayoutRequestStatus.PENDING);
        req = requestRepository.save(req);
        return toMap(req);
    }

    // ── Admin actions ─────────────────────────────────────────────────────────

    /** List all requests (admin). Optionally filter by status. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRequests(String statusParam) {
        AuthHelper.requireAdminOr(Permission.SALARIES_MANAGE);
        List<PayoutRequest> rows = statusParam == null || statusParam.isBlank()
                ? requestRepository.findAllOrdered()
                : requestRepository.findByStatus(PayoutRequestStatus.valueOf(statusParam.toUpperCase()));

        return rows.stream().map(r -> {
            Map<String, Object> m = toMap(r);
            userRepository.findById(r.getUserId()).ifPresent(u -> m.put("userName", u.getName()));
            return m;
        }).toList();
    }

    /**
     * Approve a payout request: creates a {@link com.saffron.cashflow.domain.SalaryPayment}
     * via the treasury service and marks the request APPROVED.
     *
     * <p>The payment is recorded against the cashier's account and reduces
     * treasury balances exactly as a manually-recorded salary payment would.
     * All existing P&L / payroll report numbers are affected normally.</p>
     */
    @Transactional
    public Map<String, Object> approve(String id, PaymentSource source, String adminNotes) {
        AuthHelper.requireAdminOr(Permission.SALARIES_MANAGE);
        PayoutRequest req = require(id);
        if (req.getStatus() != PayoutRequestStatus.PENDING) {
            throw new BadRequestException("Only PENDING requests can be approved");
        }
        if (source == null) source = PaymentSource.CASH;

        RecordSalaryPaymentRequest payReq = new RecordSalaryPaymentRequest(
                req.getUserId(),
                req.getRequestedAmount(),
                LocalDate.now(),
                source,
                null, null,
                "Payout request approved" + (req.getNotes() != null ? " · " + req.getNotes() : ""),
                false);

        Map<String, Object> payResult = treasuryService.recordSalaryPayment(payReq);
        String paymentId = (String) ((Map<?, ?>) payResult.get("payment")).get("id");

        req.setStatus(PayoutRequestStatus.APPROVED);
        req.setAdminNotes(adminNotes != null && !adminNotes.isBlank() ? adminNotes.trim() : null);
        req.setReviewedBy(AuthHelper.currentUser().id());
        req.setReviewedAt(Instant.now());
        req.setSalaryPaymentId(paymentId);
        requestRepository.save(req);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", toMap(req));
        result.put("payment", payResult.get("payment"));
        return result;
    }

    /**
     * Decline a request — sets status to DECLINED with an optional reason.
     * No salary payment is created; report numbers are unaffected.
     */
    @Transactional
    public Map<String, Object> decline(String id, String adminNotes) {
        AuthHelper.requireAdminOr(Permission.SALARIES_MANAGE);
        PayoutRequest req = require(id);
        if (req.getStatus() != PayoutRequestStatus.PENDING) {
            throw new BadRequestException("Only PENDING requests can be declined");
        }
        req.setStatus(PayoutRequestStatus.DECLINED);
        req.setAdminNotes(adminNotes != null && !adminNotes.isBlank() ? adminNotes.trim() : null);
        req.setReviewedBy(AuthHelper.currentUser().id());
        req.setReviewedAt(Instant.now());
        requestRepository.save(req);
        return toMap(req);
    }

    // ── Access toggle ─────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> setEarningsAccess(String userId, boolean enabled) {
        AuthHelper.requireAdminOr(Permission.SALARIES_MANAGE);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        user.setCanViewEarnings(enabled);
        userRepository.save(user);
        return Map.of("userId", userId, "canViewEarnings", enabled);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PayoutRequest require(String id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payout request not found"));
    }

    private Map<String, Object> toMap(PayoutRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("userId", r.getUserId());
        m.put("requestedAmount", r.getRequestedAmount());
        m.put("requestedDate", r.getRequestedDate().toString());
        m.put("status", r.getStatus().name());
        if (r.getNotes() != null) m.put("notes", r.getNotes());
        if (r.getAdminNotes() != null) m.put("adminNotes", r.getAdminNotes());
        if (r.getReviewedAt() != null) m.put("reviewedAt", r.getReviewedAt().toString());
        if (r.getSalaryPaymentId() != null) m.put("salaryPaymentId", r.getSalaryPaymentId());
        m.put("createdAt", r.getCreatedAt().toString());
        return m;
    }
}
