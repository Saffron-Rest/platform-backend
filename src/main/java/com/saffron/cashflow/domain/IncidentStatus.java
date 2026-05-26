package com.saffron.cashflow.domain;

/**
 * Lifecycle state of an {@link Incident}. We deliberately keep the set
 * small — getting clever with statuses ("In review" / "Pending parts" /
 * etc.) usually means people stop maintaining them. Use comments + tags
 * for nuance.
 */
public enum IncidentStatus {
    /** Newly filed, nobody is working on it yet. */
    OPEN,
    /** Assigned to someone and being actively worked. */
    IN_PROGRESS,
    /** Done. Stored along with resolution notes for future search. */
    RESOLVED,
    /** Not actionable (false alarm, duplicate, withdrew complaint). */
    DISMISSED
}
