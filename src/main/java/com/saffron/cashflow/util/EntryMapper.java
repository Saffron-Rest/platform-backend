package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.ExpenseItem;
import com.saffron.cashflow.domain.PaymentSource;
import com.saffron.cashflow.domain.ReceiptFile;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.dto.EntryRequest;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class EntryMapper {

    private EntryMapper() {}

    /** Closing shift: only opening, actual count, and notes; sales/expenses stay zero. */
    public static void applyClosingOnly(DailyEntry entry, EntryRequest req) {
        zeroTransactionalFields(entry);
        entry.setOpeningBalance(req.getOpeningBalance());
        entry.setActualCashCounted(req.getActualCashCounted());
        entry.setNotes(req.getNotes());
    }

    private static void zeroTransactionalFields(DailyEntry entry) {
        entry.setCashSales(BigDecimal.ZERO);
        entry.setCardSales(BigDecimal.ZERO);
        entry.setWoltSales(BigDecimal.ZERO);
        entry.setBoltSales(BigDecimal.ZERO);
        entry.setUberEatsSales(BigDecimal.ZERO);
        entry.setGlovoSales(BigDecimal.ZERO);
        entry.setOtherPlatformSales(BigDecimal.ZERO);
        entry.setWoltSettledToCard(null);
        entry.setBoltSettledToCard(null);
        entry.setUberEatsSettledToCard(null);
        entry.setGlovoSettledToCard(null);
        entry.setOtherSettledToCard(null);
        entry.setCashRefunds(BigDecimal.ZERO);
        entry.setCardRefunds(BigDecimal.ZERO);
        entry.setPlatformRefunds(BigDecimal.ZERO);
        entry.setBankDeposit(BigDecimal.ZERO);
        entry.setCashWithdrawal(BigDecimal.ZERO);
        entry.setOwnerWithdrawal(BigDecimal.ZERO);
        entry.setSupplierPayments(BigDecimal.ZERO);
        entry.setPettyCash(BigDecimal.ZERO);
        entry.setSupplies(BigDecimal.ZERO);
        entry.setStaffMeals(BigDecimal.ZERO);
        entry.setDeliveryCosts(BigDecimal.ZERO);
        entry.setOtherExpenses(BigDecimal.ZERO);
    }

    public static void applyRequest(DailyEntry entry, EntryRequest req, BigDecimal closing, BigDecimal diff) {
        entry.setOpeningBalance(req.getOpeningBalance());
        entry.setCashSales(req.getCashSales());
        entry.setCardSales(req.getCardSales());
        entry.setWoltSales(req.getWoltSales());
        entry.setBoltSales(req.getBoltSales());
        entry.setUberEatsSales(req.getUberEatsSales());
        entry.setGlovoSales(req.getGlovoSales());
        entry.setOtherPlatformSales(req.getOtherPlatformSales());
        entry.setWoltSettledToCard(req.getWoltSettledToCard());
        entry.setBoltSettledToCard(req.getBoltSettledToCard());
        entry.setUberEatsSettledToCard(req.getUberEatsSettledToCard());
        entry.setGlovoSettledToCard(req.getGlovoSettledToCard());
        entry.setOtherSettledToCard(req.getOtherSettledToCard());
        entry.setCashRefunds(req.getCashRefunds());
        entry.setCardRefunds(req.getCardRefunds());
        entry.setPlatformRefunds(req.getPlatformRefunds());
        entry.setBankDeposit(req.getBankDeposit());
        entry.setCashWithdrawal(req.getCashWithdrawal());
        entry.setOwnerWithdrawal(req.getOwnerWithdrawal());
        entry.setSupplierPayments(BigDecimal.ZERO);
        entry.setPettyCash(BigDecimal.ZERO);
        entry.setSupplies(BigDecimal.ZERO);
        entry.setStaffMeals(BigDecimal.ZERO);
        entry.setDeliveryCosts(BigDecimal.ZERO);
        entry.setOtherExpenses(BigDecimal.ZERO);
        entry.setActualCashCounted(req.getActualCashCounted());
        entry.setClosingBalance(closing);
        entry.setDifference(diff);
        entry.setNotes(req.getNotes());
    }

    public static Map<String, Object> toMap(DailyEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("date", e.getDate().toString());
        m.put("cashierId", e.getCashierId());
        m.put("status", e.getStatus().name());
        m.put("openingBalance", EntryCalculator.toDouble(e.getOpeningBalance()));
        m.put("cashSales", EntryCalculator.toDouble(e.getCashSales()));
        m.put("cardSales", EntryCalculator.toDouble(e.getCardSales()));
        m.put("woltSales", EntryCalculator.toDouble(e.getWoltSales()));
        m.put("boltSales", EntryCalculator.toDouble(e.getBoltSales()));
        m.put("uberEatsSales", EntryCalculator.toDouble(e.getUberEatsSales()));
        m.put("glovoSales", EntryCalculator.toDouble(e.getGlovoSales()));
        m.put("otherPlatformSales", EntryCalculator.toDouble(e.getOtherPlatformSales()));
        putOptionalAmount(m, "woltSettledToCard", e.getWoltSettledToCard());
        putOptionalAmount(m, "boltSettledToCard", e.getBoltSettledToCard());
        putOptionalAmount(m, "uberEatsSettledToCard", e.getUberEatsSettledToCard());
        putOptionalAmount(m, "glovoSettledToCard", e.getGlovoSettledToCard());
        putOptionalAmount(m, "otherSettledToCard", e.getOtherSettledToCard());
        m.put("cashRefunds", EntryCalculator.toDouble(e.getCashRefunds()));
        m.put("cardRefunds", EntryCalculator.toDouble(e.getCardRefunds()));
        m.put("platformRefunds", EntryCalculator.toDouble(e.getPlatformRefunds()));
        m.put("bankDeposit", EntryCalculator.toDouble(e.getBankDeposit()));
        m.put("cashWithdrawal", EntryCalculator.toDouble(e.getCashWithdrawal()));
        m.put("ownerWithdrawal", EntryCalculator.toDouble(e.getOwnerWithdrawal()));
        m.put("supplierPayments", EntryCalculator.toDouble(e.getSupplierPayments()));
        m.put("pettyCash", EntryCalculator.toDouble(e.getPettyCash()));
        m.put("supplies", EntryCalculator.toDouble(e.getSupplies()));
        m.put("staffMeals", EntryCalculator.toDouble(e.getStaffMeals()));
        m.put("deliveryCosts", EntryCalculator.toDouble(e.getDeliveryCosts()));
        m.put("otherExpenses", EntryCalculator.toDouble(e.getOtherExpenses()));
        m.put("closingBalance", EntryCalculator.toDouble(e.getClosingBalance()));
        m.put("actualCashCounted", EntryCalculator.toDouble(e.getActualCashCounted()));
        m.put("difference", EntryCalculator.toDouble(e.getDifference()));
        if (e.getNotes() != null) m.put("notes", e.getNotes());
        if (e.getSubmittedAt() != null) m.put("submittedAt", e.getSubmittedAt().toString());
        if (e.getCashier() != null) m.put("cashier", cashierMap(e.getCashier()));
        if (Hibernate.isInitialized(e.getFiles()) && e.getFiles() != null && !e.getFiles().isEmpty()) {
            m.put("files", e.getFiles().stream().map(EntryMapper::fileMap).collect(Collectors.toList()));
        }
        List<ExpenseItem> items = expenseItems(e);
        if (!items.isEmpty()) {
            m.put("expenses", items.stream().map(EntryMapper::expenseToMap).collect(Collectors.toList()));
        } else {
            m.put("expenses", List.of());
        }
        m.put("expenseLinesTotal", EntryCalculator.toDouble(EntryCalculator.sumExpenseItems(items)));
        m.put("expenseCashTotal", EntryCalculator.toDouble(EntryCalculator.sumExpenseItems(items, PaymentSource.CASH)));
        m.put("expenseCardTotal", EntryCalculator.toDouble(EntryCalculator.sumExpenseItems(items, PaymentSource.CARD)));
        m.put("payoutsTotal", EntryCalculator.toDouble(EntryCalculator.totalPayouts(e)));
        m.put("cardBalance", EntryCalculator.toDouble(EntryCalculator.cardBalance(e)));
        return m;
    }

    private static List<ExpenseItem> expenseItems(DailyEntry e) {
        if (!Hibernate.isInitialized(e.getExpenseItems()) || e.getExpenseItems() == null) {
            return List.of();
        }
        return e.getExpenseItems();
    }

    public static Map<String, Object> expenseToMap(ExpenseItem item) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", item.getId());
        m.put("category", item.getCategory().name());
        m.put("description", item.getDescription());
        m.put("amount", EntryCalculator.toDouble(item.getAmount()));
        m.put("paymentSource", item.getPaymentSource().name());
        if (!item.getInvoices().isEmpty()) {
            List<Map<String, Object>> files = item.getInvoices().stream().map(EntryMapper::fileMap).toList();
            m.put("invoices", files);
            m.put("invoice", files.get(0));
        }
        return m;
    }

    private static void putOptionalAmount(Map<String, Object> m, String key, BigDecimal value) {
        if (value != null) {
            m.put(key, EntryCalculator.toDouble(value));
        }
    }

    private static Map<String, Object> cashierMap(User u) {
        return Map.of("id", u.getId(), "name", u.getName(), "email", u.getEmail());
    }

    private static Map<String, Object> fileMap(ReceiptFile f) {
        return Map.of(
                "id", f.getId(),
                "entryId", f.getEntryId(),
                "filename", f.getFilename(),
                "path", f.getPath(),
                "createdAt", f.getCreatedAt().toString());
    }
}
