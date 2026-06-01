package com.saffron.cashflow.domain;

/**
 * Lifecycle of a {@link SupplierInvoice}.
 *
 * <p>The status is derived from {@code amountPaid} relative to
 * {@code total} — we still persist it as a column so list queries
 * filtering by "what's outstanding?" stay cheap (no need to evaluate
 * a CASE expression server-side).</p>
 */
public enum SupplierInvoiceStatus {
    /** {@code amountPaid == 0}. */
    UNPAID,
    /** {@code 0 < amountPaid < total}. */
    PARTIAL,
    /** {@code amountPaid >= total}. */
    PAID,
    /** Cancelled before any payment was recorded. Stock movements
     *  posted by the original invoice have been reverted. */
    VOID
}
