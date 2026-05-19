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

    /**
     * Displayed/stored expected cash: actual counted − cash expenses when a count exists,
     * otherwise the book expected drawer.
     */
    public static BigDecimal closingBalance(DailyEntry e) {
        BigDecimal actual = e.getActualCashCounted();
        if (actual != null && actual.compareTo(BigDecimal.ZERO) > 0) {
            return round(actual.subtract(expenseTotalForCashClosing(e)));
        }
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
     * Net card/bank movement for treasury: settled card sales + delivery to card − refunds − card expenses
     * + bank deposits.
     */
    public static BigDecimal cardNetForTreasury(DailyEntry e, TreasurySettings settings) {
        BigDecimal settledCardSales = e.getCardSales().multiply(settings.getCardSalesSettlementRate());
        BigDecimal deliveryToCard = PlatformSettlement.totalDeliverySettledToCard(e, settings);
        BigDecimal out = e.getCardRefunds().add(e.getPlatformRefunds());
        if (hasExpenseItems(e)) {
            out = out.add(sumExpenseItems(e.getExpenseItems(), PaymentSource.CARD));
        }
        return round(settledCardSales.add(deliveryToCard).subtract(out).add(e.getBankDeposit()));
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

    /** Closing-only shift: expected = actual − cash expenses when counted; difference vs opening. */
    public static void recalculateClosingShift(DailyEntry e) {
        BigDecimal actual = e.getActualCashCounted();
        if (actual != null && actual.compareTo(BigDecimal.ZERO) > 0) {
            e.setClosingBalance(round(actual.subtract(expenseTotalForCashClosing(e))));
        } else {
            e.setClosingBalance(round(e.getOpeningBalance()));
        }
        e.setDifference(round(actual.subtract(e.getOpeningBalance())));
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
