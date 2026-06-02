package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "daily_entry", uniqueConstraints = @UniqueConstraint(columnNames = {"cashier_id", "entry_date"}))
public class DailyEntry {

    @Id
    private String id;

    @Column(name = "entry_date", nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id", nullable = false)
    private User cashier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryStatus status = EntryStatus.DRAFT;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal cashSales = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal cardSales = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal woltSales = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal boltSales = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal uberEatsSales = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal glovoSales = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal otherPlatformSales = BigDecimal.ZERO;

    /** When set, overrides treasury % for how much of that platform's sales count toward card/bank. */
    @Column(name = "wolt_settled_to_card", precision = 12, scale = 2)
    private BigDecimal woltSettledToCard;
    @Column(name = "bolt_settled_to_card", precision = 12, scale = 2)
    private BigDecimal boltSettledToCard;
    @Column(name = "uber_eats_settled_to_card", precision = 12, scale = 2)
    private BigDecimal uberEatsSettledToCard;
    @Column(name = "glovo_settled_to_card", precision = 12, scale = 2)
    private BigDecimal glovoSettledToCard;
    @Column(name = "other_settled_to_card", precision = 12, scale = 2)
    private BigDecimal otherSettledToCard;

    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal cashRefunds = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal cardRefunds = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal platformRefunds = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal bankDeposit = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal cashWithdrawal = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal ownerWithdrawal = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal supplierPayments = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal pettyCash = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal supplies = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal staffMeals = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal deliveryCosts = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal otherExpenses = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal closingBalance = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal actualCashCounted = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal difference = BigDecimal.ZERO;

    private String notes;
    private Instant submittedAt;
    private Instant deletedAt;
    private String deleteReason;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReceiptFile> files = new ArrayList<>();

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseItem> expenseItems = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getCashierId() { return cashier != null ? cashier.getId() : null; }
    public void setCashier(User cashier) { this.cashier = cashier; }
    public User getCashier() { return cashier; }
    public EntryStatus getStatus() { return status; }
    public void setStatus(EntryStatus status) { this.status = status; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }
    public BigDecimal getCashSales() { return cashSales; }
    public void setCashSales(BigDecimal cashSales) { this.cashSales = cashSales; }
    public BigDecimal getCardSales() { return cardSales; }
    public void setCardSales(BigDecimal cardSales) { this.cardSales = cardSales; }
    public BigDecimal getWoltSales() { return woltSales; }
    public void setWoltSales(BigDecimal woltSales) { this.woltSales = woltSales; }
    public BigDecimal getBoltSales() { return boltSales; }
    public void setBoltSales(BigDecimal boltSales) { this.boltSales = boltSales; }
    public BigDecimal getUberEatsSales() { return uberEatsSales; }
    public void setUberEatsSales(BigDecimal uberEatsSales) { this.uberEatsSales = uberEatsSales; }
    public BigDecimal getGlovoSales() { return glovoSales; }
    public void setGlovoSales(BigDecimal glovoSales) { this.glovoSales = glovoSales; }
    public BigDecimal getOtherPlatformSales() { return otherPlatformSales; }
    public void setOtherPlatformSales(BigDecimal otherPlatformSales) { this.otherPlatformSales = otherPlatformSales; }
    public BigDecimal getWoltSettledToCard() { return woltSettledToCard; }
    public void setWoltSettledToCard(BigDecimal woltSettledToCard) { this.woltSettledToCard = woltSettledToCard; }
    public BigDecimal getBoltSettledToCard() { return boltSettledToCard; }
    public void setBoltSettledToCard(BigDecimal boltSettledToCard) { this.boltSettledToCard = boltSettledToCard; }
    public BigDecimal getUberEatsSettledToCard() { return uberEatsSettledToCard; }
    public void setUberEatsSettledToCard(BigDecimal uberEatsSettledToCard) { this.uberEatsSettledToCard = uberEatsSettledToCard; }
    public BigDecimal getGlovoSettledToCard() { return glovoSettledToCard; }
    public void setGlovoSettledToCard(BigDecimal glovoSettledToCard) { this.glovoSettledToCard = glovoSettledToCard; }
    public BigDecimal getOtherSettledToCard() { return otherSettledToCard; }
    public void setOtherSettledToCard(BigDecimal otherSettledToCard) { this.otherSettledToCard = otherSettledToCard; }
    public BigDecimal getCashRefunds() { return cashRefunds; }
    public void setCashRefunds(BigDecimal cashRefunds) { this.cashRefunds = cashRefunds; }
    public BigDecimal getCardRefunds() { return cardRefunds; }
    public void setCardRefunds(BigDecimal cardRefunds) { this.cardRefunds = cardRefunds; }
    public BigDecimal getPlatformRefunds() { return platformRefunds; }
    public void setPlatformRefunds(BigDecimal platformRefunds) { this.platformRefunds = platformRefunds; }
    public BigDecimal getBankDeposit() { return bankDeposit; }
    public void setBankDeposit(BigDecimal bankDeposit) { this.bankDeposit = bankDeposit; }
    public BigDecimal getCashWithdrawal() { return cashWithdrawal; }
    public void setCashWithdrawal(BigDecimal cashWithdrawal) { this.cashWithdrawal = cashWithdrawal; }
    public BigDecimal getOwnerWithdrawal() { return ownerWithdrawal; }
    public void setOwnerWithdrawal(BigDecimal ownerWithdrawal) { this.ownerWithdrawal = ownerWithdrawal; }
    public BigDecimal getSupplierPayments() { return supplierPayments; }
    public void setSupplierPayments(BigDecimal supplierPayments) { this.supplierPayments = supplierPayments; }
    public BigDecimal getPettyCash() { return pettyCash; }
    public void setPettyCash(BigDecimal pettyCash) { this.pettyCash = pettyCash; }
    public BigDecimal getSupplies() { return supplies; }
    public void setSupplies(BigDecimal supplies) { this.supplies = supplies; }
    public BigDecimal getStaffMeals() { return staffMeals; }
    public void setStaffMeals(BigDecimal staffMeals) { this.staffMeals = staffMeals; }
    public BigDecimal getDeliveryCosts() { return deliveryCosts; }
    public void setDeliveryCosts(BigDecimal deliveryCosts) { this.deliveryCosts = deliveryCosts; }
    public BigDecimal getOtherExpenses() { return otherExpenses; }
    public void setOtherExpenses(BigDecimal otherExpenses) { this.otherExpenses = otherExpenses; }
    public BigDecimal getClosingBalance() { return closingBalance; }
    public void setClosingBalance(BigDecimal closingBalance) { this.closingBalance = closingBalance; }
    public BigDecimal getActualCashCounted() { return actualCashCounted; }
    public void setActualCashCounted(BigDecimal actualCashCounted) { this.actualCashCounted = actualCashCounted; }
    public BigDecimal getDifference() { return difference; }
    public void setDifference(BigDecimal difference) { this.difference = difference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public String getDeleteReason() { return deleteReason; }
    public void setDeleteReason(String deleteReason) { this.deleteReason = deleteReason; }
    public List<ReceiptFile> getFiles() { return files; }
    public List<ExpenseItem> getExpenseItems() { return expenseItems; }
}
