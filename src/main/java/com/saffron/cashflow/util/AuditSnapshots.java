package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.ExpenseItem;
import com.saffron.cashflow.domain.User;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Audit-safe snapshots (no secrets, no large blobs). */
public final class AuditSnapshots {

    private AuditSnapshots() {}

    public static Map<String, Object> entry(DailyEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", e.getDate() != null ? e.getDate().toString() : null);
        m.put("cashierId", e.getCashierId());
        m.put("status", e.getStatus() != null ? e.getStatus().name() : null);
        m.put("openingBalance", EntryCalculator.toDouble(e.getOpeningBalance()));
        m.put("cashSales", EntryCalculator.toDouble(e.getCashSales()));
        m.put("cardSales", EntryCalculator.toDouble(e.getCardSales()));
        m.put("woltSales", EntryCalculator.toDouble(e.getWoltSales()));
        m.put("boltSales", EntryCalculator.toDouble(e.getBoltSales()));
        m.put("uberEatsSales", EntryCalculator.toDouble(e.getUberEatsSales()));
        m.put("glovoSales", EntryCalculator.toDouble(e.getGlovoSales()));
        m.put("otherPlatformSales", EntryCalculator.toDouble(e.getOtherPlatformSales()));
        // Per-platform card-settlement overrides — nullable. Null means
        // "use the global treasury %"; an explicit number overrides it for
        // this shift. We keep nulls as nulls (not 0.0) so the revert flow
        // can restore the "use default" state correctly.
        m.put("woltSettledToCard", nullableDouble(e.getWoltSettledToCard()));
        m.put("boltSettledToCard", nullableDouble(e.getBoltSettledToCard()));
        m.put("uberEatsSettledToCard", nullableDouble(e.getUberEatsSettledToCard()));
        m.put("glovoSettledToCard", nullableDouble(e.getGlovoSettledToCard()));
        m.put("otherSettledToCard", nullableDouble(e.getOtherSettledToCard()));
        m.put("cashRefunds", EntryCalculator.toDouble(e.getCashRefunds()));
        m.put("cardRefunds", EntryCalculator.toDouble(e.getCardRefunds()));
        m.put("platformRefunds", EntryCalculator.toDouble(e.getPlatformRefunds()));
        m.put("bankDeposit", EntryCalculator.toDouble(e.getBankDeposit()));
        m.put("cashWithdrawal", EntryCalculator.toDouble(e.getCashWithdrawal()));
        m.put("ownerWithdrawal", EntryCalculator.toDouble(e.getOwnerWithdrawal()));
        m.put("closingBalance", EntryCalculator.toDouble(e.getClosingBalance()));
        m.put("actualCashCounted", EntryCalculator.toDouble(e.getActualCashCounted()));
        m.put("difference", EntryCalculator.toDouble(e.getDifference()));
        if (e.getNotes() != null && !e.getNotes().isBlank()) {
            m.put("notes", e.getNotes());
        }
        return m;
    }

    public static Map<String, Object> expense(ExpenseItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entryId", item.getEntryId());
        m.put("category", item.getCategory() != null ? item.getCategory().name() : null);
        m.put("description", item.getDescription());
        m.put("amount", EntryCalculator.toDouble(item.getAmount()));
        m.put("paymentSource", item.getPaymentSource() != null ? item.getPaymentSource().name() : null);
        int invoiceCount = 0;
        if (Hibernate.isInitialized(item.getInvoices()) && item.getInvoices() != null) {
            invoiceCount = item.getInvoices().size();
        }
        m.put("invoiceCount", invoiceCount);
        return m;
    }

    public static Map<String, Object> user(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        if (u.getEmail() != null) m.put("email", u.getEmail());
        m.put("name", u.getName());
        m.put("role", u.getRole() != null ? u.getRole().name() : null);
        m.put("active", u.isActive());
        if (u.getPayType() != null) m.put("payType", u.getPayType().name());
        if (u.getPayAmount() != null) m.put("payAmount", u.getPayAmount().doubleValue());
        if (u.getStartDate() != null) m.put("startDate", u.getStartDate().toString());
        return m;
    }

    /** Null-preserving variant of {@link EntryCalculator#toDouble(BigDecimal)} —
     *  used for fields where {@code null} and {@code 0} mean different things
     *  (e.g. per-platform card-settlement overrides). */
    private static Double nullableDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    public static Map<String, Object> sanitize(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String key = e.getKey();
            if (key.toLowerCase(Locale.ROOT).contains("password")) continue;
            copy.put(key, e.getValue());
        }
        return copy;
    }
}
