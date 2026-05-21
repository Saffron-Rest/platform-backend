package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single bank credit that settles one or more card-side ledger rows (e.g. a Monday
 * deposit covering Saturday + Sunday delivery / POS card sales). The {@code totalSettled}
 * is the bank-credited amount; the variance against the sum of linked {@code grossAmount}s
 * is the actual fee / holdback distributed pro-rata across the links.
 */
@Entity
@Table(name = "bank_deposit")
public class BankDeposit {

    @Id
    private String id;

    /** When the bank credited the amount. */
    @Column(name = "bank_date", nullable = false)
    private LocalDate bankDate;

    /** Actual amount the bank credited (sum of all linked rows after fees). */
    @Column(name = "total_settled", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalSettled = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(
            mappedBy = "bankDeposit",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<BankDepositLink> links = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getId() { return id; }

    public LocalDate getBankDate() { return bankDate; }
    public void setBankDate(LocalDate bankDate) { this.bankDate = bankDate; }

    public BigDecimal getTotalSettled() { return totalSettled; }
    public void setTotalSettled(BigDecimal totalSettled) {
        this.totalSettled = totalSettled != null ? totalSettled : BigDecimal.ZERO;
    }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    public List<BankDepositLink> getLinks() { return links; }

    public void addLink(BankDepositLink link) {
        link.setBankDeposit(this);
        this.links.add(link);
    }

    /** Sum of {@code grossAmount} across all links (snapshot of source row amounts). */
    public BigDecimal totalGross() {
        BigDecimal s = BigDecimal.ZERO;
        for (BankDepositLink l : links) {
            s = s.add(l.getGrossAmount() != null ? l.getGrossAmount() : BigDecimal.ZERO);
        }
        return s.setScale(2, RoundingMode.HALF_UP);
    }

    /** Net adjustment applied to the card / bank balance: {@code totalSettled − totalGross}. */
    public BigDecimal variance() {
        return totalSettled.subtract(totalGross()).setScale(2, RoundingMode.HALF_UP);
    }

    /** Each link's pro-rata share of {@code totalSettled} (sums back to {@code totalSettled}). */
    public BigDecimal shareFor(BankDepositLink link) {
        BigDecimal totalGross = totalGross();
        if (totalGross.signum() == 0) return BigDecimal.ZERO;
        return link.getGrossAmount()
                .multiply(totalSettled)
                .divide(totalGross, 2, RoundingMode.HALF_UP);
    }
}
