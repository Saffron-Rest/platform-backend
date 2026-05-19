package com.saffron.cashflow.domain;

/** Actions recorded in the immutable audit trail. */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    SUBMIT,
    UNLOCK,
    LOGIN,
    LOGIN_FAILED,
    EXPORT,
    SYNC
}
