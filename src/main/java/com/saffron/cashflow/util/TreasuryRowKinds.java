package com.saffron.cashflow.util;

import java.util.Set;

/**
 * Classification of treasury ledger row kinds for balance accounting.
 *
 * <p>A "pending" kind represents income that is owed by a third party (typically a delivery
 * platform) but has not been credited to the bank yet. These rows are <b>excluded</b> from
 * the auto-computed card balance (so the dashboard reflects only money already in the bank).
 * They get included only once they're explicitly reconciled by a {@code BankDeposit} or
 * {@code CardSettlement}.
 *
 * <p>Counted kinds always contribute (positive or negative) — they represent money already
 * moved at the bank/POS level. When a counted row is reconciled, only the variance between
 * the stored amount and the actual settled amount affects the balance.
 */
public final class TreasuryRowKinds {

    private TreasuryRowKinds() {}

    /** Row kinds that do not contribute to the card balance until reconciled. */
    public static final Set<String> PENDING_KINDS = Set.of(
            "SHIFT_DELIVERY_SETTLED");

    /** True if a row of this kind should be treated as "pending bank settlement". */
    public static boolean isPending(String kind) {
        return kind != null && PENDING_KINDS.contains(kind);
    }
}
