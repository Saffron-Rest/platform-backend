package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cashier's POS shift — opened with a cash float and closed at end of service.
 * Closing a session triggers auto-population of the corresponding DailyEntry
 * from aggregated POS sales so the cashier confirms totals rather than typing them.
 */
@Entity
@Table(name = "pos_session",
        indexes = {
                @Index(name = "ix_pos_session_cashier", columnList = "cashier_id"),
                @Index(name = "ix_pos_session_business_day", columnList = "business_day"),
                @Index(name = "ix_pos_session_status", columnList = "status")
        })
public class PosSession {

    public enum Status { OPEN, CLOSED }

    @Id
    private String id;

    @Column(name = "cashier_id", nullable = false, length = 36)
    private String cashierId;

    @Column(name = "business_day", nullable = false)
    private LocalDate businessDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status = Status.OPEN;

    /** Cash float put in the drawer at shift open. */
    @Column(name = "opening_float", nullable = false, precision = 12, scale = 2)
    private BigDecimal openingFloat = BigDecimal.ZERO;

    /** Physical cash counted in the drawer at shift close (before bank deposit). */
    @Column(name = "closing_float", precision = 12, scale = 2)
    private BigDecimal closingFloat;

    /** Total cash sales aggregated from POS orders during this session. */
    @Column(name = "cash_sales_total", precision = 12, scale = 2)
    private BigDecimal cashSalesTotal;

    /** Total card sales aggregated from POS orders during this session. */
    @Column(name = "card_sales_total", precision = 12, scale = 2)
    private BigDecimal cardSalesTotal;

    /** Number of PAID orders in this session. */
    @Column(name = "order_count")
    private Integer orderCount;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (openedAt == null) openedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCashierId() { return cashierId; }
    public void setCashierId(String cashierId) { this.cashierId = cashierId; }
    public LocalDate getBusinessDay() { return businessDay; }
    public void setBusinessDay(LocalDate businessDay) { this.businessDay = businessDay; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public BigDecimal getOpeningFloat() { return openingFloat; }
    public void setOpeningFloat(BigDecimal openingFloat) { this.openingFloat = openingFloat; }
    public BigDecimal getClosingFloat() { return closingFloat; }
    public void setClosingFloat(BigDecimal closingFloat) { this.closingFloat = closingFloat; }
    public BigDecimal getCashSalesTotal() { return cashSalesTotal; }
    public void setCashSalesTotal(BigDecimal cashSalesTotal) { this.cashSalesTotal = cashSalesTotal; }
    public BigDecimal getCardSalesTotal() { return cardSalesTotal; }
    public void setCardSalesTotal(BigDecimal cardSalesTotal) { this.cardSalesTotal = cardSalesTotal; }
    public Integer getOrderCount() { return orderCount; }
    public void setOrderCount(Integer orderCount) { this.orderCount = orderCount; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
