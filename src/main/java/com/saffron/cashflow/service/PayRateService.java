package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.PayRateChange;
import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.PayRateChangeRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayRateService {

    public record ResolvedPay(PayType payType, BigDecimal payAmount, LocalDate effectiveFrom) {}

    private final PayRateChangeRepository payRateChangeRepository;

    public PayRateService(PayRateChangeRepository payRateChangeRepository) {
        this.payRateChangeRepository = payRateChangeRepository;
    }

    @Transactional(readOnly = true)
    public ResolvedPay resolve(String userId, LocalDate date, User fallbackUser) {
        return payRateChangeRepository
                .findTopByUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescCreatedAtDesc(userId, date)
                .map(p -> new ResolvedPay(p.getPayType(), p.getPayAmount(), p.getEffectiveFrom()))
                .orElseGet(() -> fromUser(fallbackUser));
    }

    @Transactional(readOnly = true)
    public boolean hasMultipleRates(String userId) {
        return payRateChangeRepository.countByUserId(userId) > 1;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listHistory(String userId) {
        AuthHelper.requireAdmin();
        return payRateChangeRepository.findByUserIdOrderByEffectiveFromDescCreatedAtDesc(userId).stream()
                .map(this::toMap)
                .toList();
    }

    @Transactional
    public void recordInitial(User user, LocalDate effectiveFrom) {
        if (user.getPayAmount() == null || user.getPayAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (payRateChangeRepository.countByUserId(user.getId()) > 0) {
            return;
        }
        LocalDate from = effectiveFrom != null ? effectiveFrom : LocalDate.now();
        insert(user.getId(), user.getPayType(), user.getPayAmount(), from, "Initial pay");
    }

    @Transactional
    public void recordChange(
            User user, PayType payType, BigDecimal payAmount, LocalDate effectiveFrom, String notes) {
        if (payAmount == null || payAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Pay amount cannot be negative");
        }
        if (effectiveFrom == null) {
            throw new BadRequestException("payEffectiveFrom is required when changing pay");
        }
        PayType type = payType != null ? payType : PayType.HOURLY;
        ResolvedPay current = resolve(user.getId(), effectiveFrom, user);
        if (type == current.payType() && payAmount.compareTo(current.payAmount()) == 0) {
            return;
        }
        insert(user.getId(), type, payAmount, effectiveFrom, notes);
    }

    private void insert(String userId, PayType payType, BigDecimal payAmount, LocalDate effectiveFrom, String notes) {
        PayRateChange row = new PayRateChange();
        row.setUserId(userId);
        row.setPayType(payType != null ? payType : PayType.HOURLY);
        row.setPayAmount(payAmount);
        row.setEffectiveFrom(effectiveFrom);
        row.setNotes(notes);
        row.setCreatedBy(AuthHelper.currentUser().id());
        payRateChangeRepository.save(row);
    }

    private static ResolvedPay fromUser(User user) {
        PayType type = user.getPayType() != null ? user.getPayType() : PayType.HOURLY;
        BigDecimal amount = user.getPayAmount() != null ? user.getPayAmount() : BigDecimal.ZERO;
        LocalDate from = user.getStartDate() != null ? user.getStartDate() : LocalDate.now();
        return new ResolvedPay(type, amount, from);
    }

    private Map<String, Object> toMap(PayRateChange p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("payType", p.getPayType().name());
        m.put("payAmount", p.getPayAmount().doubleValue());
        m.put("effectiveFrom", p.getEffectiveFrom().toString());
        if (p.getNotes() != null) m.put("notes", p.getNotes());
        if (p.getCreatedAt() != null) m.put("createdAt", p.getCreatedAt().toString());
        return m;
    }
}
