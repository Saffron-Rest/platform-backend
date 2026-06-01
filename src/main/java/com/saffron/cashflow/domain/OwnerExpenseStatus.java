package com.saffron.cashflow.domain;

/**
 * Lifecycle of an {@link OwnerExpense} (money the restaurant owes its
 * owner for an out-of-pocket payment).
 *
 * <p>Mirrors {@link SupplierInvoiceStatus} — the value is derived from
 * {@code amountReimbursed} relative to {@code total} but persisted as a
 * column so "what's still pending?" stays an indexed lookup.</p>
 */
public enum OwnerExpenseStatus {
    /** {@code amountReimbursed == 0}. */
    PENDING,
    /** {@code 0 < amountReimbursed < total}. */
    PARTIAL,
    /** {@code amountReimbursed >= total}. */
    REIMBURSED,
    /** Filed by mistake or rejected during review; never paid back. */
    VOID
}
