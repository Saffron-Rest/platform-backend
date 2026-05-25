package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.ExpenseItem;
import com.saffron.cashflow.domain.PaymentSource;
import com.saffron.cashflow.dto.EntryRequest;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

public final class EntryCalculator {

    private EntryCalculator() {}

    public static BigDecimal totalSales(EntryRequest r) {
        return r.getCashSales().add(r.getCardSales()).add(r.getWoltSales()).add(r.getBoltSales())
                .add(r.getUberEatsSales()).add(r.getGlovoSales()).add(r.getOtherPlatformSales());
    }

    public static BigDecimal totalSales(DailyEntry e) {
        return e.getCashSales().add(e.getCardSales()).add(e.getWoltSales()).add(e.getBoltSales())
                .add(e.getUberEatsSales()).add(e.getGlovoSales()).add(e.getOtherPlatformSales());
    }

    public static BigDecimal totalReturns(EntryRequest r) {
        return r.getCashRefunds().add(r.getCardRefunds()).add(r.getPlatformRefunds());
    }

    public static BigDecimal totalReturns(DailyEntry e) {
        return e.getCashRefunds().add(e.getCardRefunds()).add(e.getPlatformRefunds());
    }

    public static BigDecimal totalPayouts(EntryRequest r) {
        return r.getBankDeposit().add(r.getCashWithdrawal()).add(r.getOwnerWithdrawal());
    }

    public static BigDecimal totalPayouts(DailyEntry e) {
        return e.getBankDeposit().add(e.getCashWithdrawal()).add(e.getOwnerWithdrawal());
    }

    public static BigDecimal sumExpenseItems(Collection<ExpenseItem> items) {
        return items.stream()
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal sumExpenseItems(Collection<ExpenseItem> items, PaymentSource source) {
        return items.stream()
                .filter(i -> i.getPaymentSource() == source)
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal legacyExpenseFields(DailyEntry e) {
        return e.getSupplierPayments().add(e.getPettyCash()).add(e.getSupplies())
                .add(e.getStaffMeals()).add(e.getDeliveryCosts()).add(e.getOtherExpenses());
    }

    public static BigDecimal totalExpenses(EntryRequest r) {
        return totalPayouts(r);
    }

    public static BigDecimal totalExpenses(DailyEntry e) {
        BigDecimal lines = hasExpenseItems(e)
                ? sumExpenseItems(e.getExpenseItems())
                : legacyExpenseFields(e);
        return totalPayouts(e).add(lines);
    }

    private static boolean hasExpenseItems(DailyEntry e) {
        return Hibernate.isInitialized(e.getExpenseItems())
                && e.getExpenseItems() != null
                && !e.getExpenseItems().isEmpty();
    }

    /** Book expected drawer before count: opening + cash sales − refunds − cash expenses − payouts. */
    public static BigDecimal bookExpectedCash(DailyEntry e) {
        BigDecimal cashExpenses = expenseTotalForCashClosing(e);
        return round(e.getOpeningBalance()
                .add(e.getCashSales())
                .subtract(e.getCashRefunds())
                .subtract(cashExpenses)
                .subtract(totalPayouts(e)));
    }

    /** Expected cash in drawer (stored as closingBalance). */
    public static BigDecimal closingBalance(DailyEntry e) {
        return bookExpectedCash(e);
    }

    /** Card sales − card refunds − card expenses (no delivery settlement). */
    public static BigDecimal cardBalance(DailyEntry e) {
        BigDecimal cardExpenses = hasExpenseItems(e)
                ? sumExpenseItems(e.getExpenseItems(), PaymentSource.CARD)
                : BigDecimal.ZERO;
        return round(e.getCardSales().subtract(e.getCardRefunds()).subtract(cardExpenses));
    }

    /**
     * Net card/bank movement for treasury from a locked shift report.
     *
     * <p>Formula: (cardSales − cardRefunds) × settlementRate + bankDeposit − card expenses.
     *
     * <p>Delivery → card is <b>not</b> included here: delivery income sits as "pending bank
     * settlement" until the bank actually credits it. The credit is recognised once a
     * {@code BankDeposit} or {@code CardSettlement} reconciliation is attached to the delivery
     * row in /treasury/history. This keeps the card balance honest — only money already in
     * the bank is reflected.
     *
     * <p>Platform refunds are <b>not</b> subtracted here either: a refund on a Wolt/Glovo
     * order is recovered by the platform from their next settlement payout to us. That
     * shortfall is reflected automatically through the lower {@code BankDeposit} /
     * {@code CardSettlement} reconciliation — subtracting it here would double-count.
     *
     * <p>Settlement rate is applied to net in-store card volume (sales − refunds) so a
     * refund "costs" the same proportion that a sale earned. Otherwise a 100 PLN refund
     * would penalise the balance by the full 100 while a 100 PLN sale only added 97.
     */
    public static BigDecimal cardNetForTreasury(DailyEntry e, TreasurySettings settings) {
        BigDecimal netCardVolume = e.getCardSales().subtract(e.getCardRefunds());
        BigDecimal settledCardNet = netCardVolume.multiply(settings.getCardSalesSettlementRate());
        BigDecimal out = BigDecimal.ZERO;
        if (hasExpenseItems(e)) {
            out = out.add(sumExpenseItems(e.getExpenseItems(), PaymentSource.CARD));
        }
        return round(settledCardNet.subtract(out).add(e.getBankDeposit()));
    }

    private static BigDecimal expenseTotalForCashClosing(DailyEntry e) {
        if (hasExpenseItems(e)) {
            return sumExpenseItems(e.getExpenseItems(), PaymentSource.CASH);
        }
        return legacyExpenseFields(e);
    }

    /** Variance vs book drawer (opening + sales − refunds − cash expenses − payouts). */
    public static BigDecimal difference(DailyEntry e) {
        return round(e.getActualCashCounted().subtract(bookExpectedCash(e)));
    }

    /** Closing-only shift: same expected formula; difference = actual − expected. */
    public static void recalculateClosingShift(DailyEntry e) {
        BigDecimal expected = bookExpectedCash(e);
        e.setClosingBalance(expected);
        e.setDifference(round(e.getActualCashCounted().subtract(expected)));
    }

    public static BigDecimal difference(EntryRequest r, BigDecimal closing) {
        return round(r.getActualCashCounted().subtract(closing));
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    public static double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
