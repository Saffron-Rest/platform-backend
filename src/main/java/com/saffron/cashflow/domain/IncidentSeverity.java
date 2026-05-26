package com.saffron.cashflow.domain;

/**
 * Severity buckets for {@link Incident}. We use four levels so the UI can
 * sort and colour without overwhelming the user with a 1-5 numeric scale.
 */
public enum IncidentSeverity {
    /** Cosmetic / no operational impact. */
    LOW,
    /** Inconvenience or minor cost (broken plate, small spill). */
    MEDIUM,
    /** Customer impact or equipment outage. */
    HIGH,
    /** Safety incident, regulatory exposure, or significant cash loss. */
    CRITICAL
}
