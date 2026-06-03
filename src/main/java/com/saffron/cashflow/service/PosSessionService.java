package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.PosOrder;
import com.saffron.cashflow.domain.PosSession;
import com.saffron.cashflow.repository.PosOrderRepository;
import com.saffron.cashflow.repository.PosSessionRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PosSessionService {

    private static final Logger LOG = LoggerFactory.getLogger(PosSessionService.class);
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final PosSessionRepository sessionRepository;
    private final PosOrderRepository orderRepository;
    private final PosSalePostHandler posSalePostHandler;

    public PosSessionService(PosSessionRepository sessionRepository,
                             PosOrderRepository orderRepository,
                             PosSalePostHandler posSalePostHandler) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.posSalePostHandler = posSalePostHandler;
    }

    /** Returns the current open session for this cashier, or null. */
    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentSession() {
        String cashierId = AuthHelper.currentUser().id();
        return sessionRepository
                .findFirstByCashierIdAndStatusOrderByOpenedAtDesc(cashierId, PosSession.Status.OPEN)
                .map(this::toMap)
                .orElse(null);
    }

    @Transactional
    public Map<String, Object> openSession(BigDecimal openingFloat) {
        String cashierId = AuthHelper.currentUser().id();
        // Prevent double-open
        sessionRepository
                .findFirstByCashierIdAndStatusOrderByOpenedAtDesc(cashierId, PosSession.Status.OPEN)
                .ifPresent(s -> { throw new BadRequestException("A POS session is already open for this cashier."); });

        PosSession session = new PosSession();
        session.setCashierId(cashierId);
        session.setBusinessDay(LocalDate.now(WARSAW));
        session.setOpeningFloat(openingFloat != null ? openingFloat : BigDecimal.ZERO);
        session = sessionRepository.save(session);
        LOG.info("POS session opened: {} cashier={} float={}", session.getId(), cashierId, openingFloat);
        return toMap(session);
    }

    @Transactional
    public Map<String, Object> closeSession(String sessionId, BigDecimal closingFloat) {
        String cashierId = AuthHelper.currentUser().id();
        PosSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BadRequestException("Session not found"));
        if (!session.getCashierId().equals(cashierId)) {
            throw new BadRequestException("This session belongs to another cashier");
        }
        if (session.getStatus() == PosSession.Status.CLOSED) {
            throw new BadRequestException("Session already closed");
        }

        // Aggregate paid orders during this session
        Instant from = session.getOpenedAt();
        Instant to = Instant.now();
        List<PosOrder> paidOrders = orderRepository.findPaidBetween(from, to);

        BigDecimal cashSales = BigDecimal.ZERO;
        BigDecimal cardSales = BigDecimal.ZERO;
        for (PosOrder o : paidOrders) {
            if (o.getPaymentMethod() == null) continue;
            String method = o.getPaymentMethod().name();
            if (method.equals("CASH")) {
                cashSales = cashSales.add(o.getTotalGross());
            } else if (method.equals("CARD")) {
                cardSales = cardSales.add(o.getTotalGross());
            }
        }

        session.setStatus(PosSession.Status.CLOSED);
        session.setClosedAt(to);
        session.setClosingFloat(closingFloat != null ? closingFloat : BigDecimal.ZERO);
        session.setCashSalesTotal(cashSales);
        session.setCardSalesTotal(cardSales);
        session.setOrderCount(paidOrders.size());
        session = sessionRepository.save(session);

        // Auto-populate DailyEntry from POS sales — cashier just confirms
        try {
            posSalePostHandler.autoPopulateDailyEntry(cashierId, session.getBusinessDay());
        } catch (Exception ex) {
            LOG.warn("autoPopulateDailyEntry failed for session {}: {}", sessionId, ex.getMessage());
        }

        LOG.info("POS session closed: {} cashier={} cash={} card={} orders={}",
                sessionId, cashierId, cashSales, cardSales, paidOrders.size());
        return toMap(session);
    }

    private Map<String, Object> toMap(PosSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("cashierId", s.getCashierId());
        m.put("businessDay", s.getBusinessDay().toString());
        m.put("status", s.getStatus().name());
        m.put("openingFloat", s.getOpeningFloat().doubleValue());
        m.put("closingFloat", s.getClosingFloat() != null ? s.getClosingFloat().doubleValue() : null);
        m.put("cashSalesTotal", s.getCashSalesTotal() != null ? s.getCashSalesTotal().doubleValue() : null);
        m.put("cardSalesTotal", s.getCardSalesTotal() != null ? s.getCardSalesTotal().doubleValue() : null);
        m.put("orderCount", s.getOrderCount());
        m.put("openedAt", s.getOpenedAt().toString());
        m.put("closedAt", s.getClosedAt() != null ? s.getClosedAt().toString() : null);
        return m;
    }
}
