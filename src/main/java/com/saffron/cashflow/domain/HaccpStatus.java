package com.saffron.cashflow.domain;

/**
 * Outcome of an {@link HaccpLog} entry.
 * Distinguishes "all good" from "saw a problem, here's what I did".
 */
public enum HaccpStatus {
    OK,
    ATTENTION,
    CORRECTIVE_ACTION
}
