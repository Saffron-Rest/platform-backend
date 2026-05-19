package com.saffron.cashflow.dto;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public class EntryRequest {

    private String date;
    private String cashierId;

    @DecimalMin("0") private BigDecimal openingBalance = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal cashSales = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal cardSales = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal woltSales = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal boltSales = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal uberEatsSales = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal glovoSales = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal otherPlatformSales = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal cashRefunds = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal cardRefunds = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal platformRefunds = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal bankDeposit = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal cashWithdrawal = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal ownerWithdrawal = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal supplierPayments = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal pettyCash = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal supplies = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal staffMeals = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal deliveryCosts = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal otherExpenses = BigDecimal.ZERO;
    @DecimalMin("0") private BigDecimal actualCashCounted = BigDecimal.ZERO;
    private String notes;

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getCashierId() { return cashierId; }
    public void setCashierId(String cashierId) { this.cashierId = cashierId; }
    public BigDecimal getOpeningBalance() { return nz(openingBalance); }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }
    public BigDecimal getCashSales() { return nz(cashSales); }
    public void setCashSales(BigDecimal cashSales) { this.cashSales = cashSales; }
    public BigDecimal getCardSales() { return nz(cardSales); }
    public void setCardSales(BigDecimal cardSales) { this.cardSales = cardSales; }
    public BigDecimal getWoltSales() { return nz(woltSales); }
    public void setWoltSales(BigDecimal woltSales) { this.woltSales = woltSales; }
    public BigDecimal getBoltSales() { return nz(boltSales); }
    public void setBoltSales(BigDecimal boltSales) { this.boltSales = boltSales; }
    public BigDecimal getUberEatsSales() { return nz(uberEatsSales); }
    public void setUberEatsSales(BigDecimal uberEatsSales) { this.uberEatsSales = uberEatsSales; }
    public BigDecimal getGlovoSales() { return nz(glovoSales); }
    public void setGlovoSales(BigDecimal glovoSales) { this.glovoSales = glovoSales; }
    public BigDecimal getOtherPlatformSales() { return nz(otherPlatformSales); }
    public void setOtherPlatformSales(BigDecimal otherPlatformSales) { this.otherPlatformSales = otherPlatformSales; }
    public BigDecimal getCashRefunds() { return nz(cashRefunds); }
    public void setCashRefunds(BigDecimal cashRefunds) { this.cashRefunds = cashRefunds; }
    public BigDecimal getCardRefunds() { return nz(cardRefunds); }
    public void setCardRefunds(BigDecimal cardRefunds) { this.cardRefunds = cardRefunds; }
    public BigDecimal getPlatformRefunds() { return nz(platformRefunds); }
    public void setPlatformRefunds(BigDecimal platformRefunds) { this.platformRefunds = platformRefunds; }
    public BigDecimal getBankDeposit() { return nz(bankDeposit); }
    public void setBankDeposit(BigDecimal bankDeposit) { this.bankDeposit = bankDeposit; }
    public BigDecimal getCashWithdrawal() { return nz(cashWithdrawal); }
    public void setCashWithdrawal(BigDecimal cashWithdrawal) { this.cashWithdrawal = cashWithdrawal; }
    public BigDecimal getOwnerWithdrawal() { return nz(ownerWithdrawal); }
    public void setOwnerWithdrawal(BigDecimal ownerWithdrawal) { this.ownerWithdrawal = ownerWithdrawal; }
    public BigDecimal getSupplierPayments() { return nz(supplierPayments); }
    public void setSupplierPayments(BigDecimal supplierPayments) { this.supplierPayments = supplierPayments; }
    public BigDecimal getPettyCash() { return nz(pettyCash); }
    public void setPettyCash(BigDecimal pettyCash) { this.pettyCash = pettyCash; }
    public BigDecimal getSupplies() { return nz(supplies); }
    public void setSupplies(BigDecimal supplies) { this.supplies = supplies; }
    public BigDecimal getStaffMeals() { return nz(staffMeals); }
    public void setStaffMeals(BigDecimal staffMeals) { this.staffMeals = staffMeals; }
    public BigDecimal getDeliveryCosts() { return nz(deliveryCosts); }
    public void setDeliveryCosts(BigDecimal deliveryCosts) { this.deliveryCosts = deliveryCosts; }
    public BigDecimal getOtherExpenses() { return nz(otherExpenses); }
    public void setOtherExpenses(BigDecimal otherExpenses) { this.otherExpenses = otherExpenses; }
    public BigDecimal getActualCashCounted() { return nz(actualCashCounted); }
    public void setActualCashCounted(BigDecimal actualCashCounted) { this.actualCashCounted = actualCashCounted; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
