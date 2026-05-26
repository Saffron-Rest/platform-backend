package com.saffron.cashflow.domain;

/**
 * Whether a checklist template runs at the start or end of a shift.
 * Used purely for grouping and the "what's due today" view.
 */
public enum ChecklistType {
    OPENING,
    CLOSING,
    /** Periodic — weekly deep clean, monthly inventory, etc. */
    PERIODIC
}
