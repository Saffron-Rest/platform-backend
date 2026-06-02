package com.saffron.cashflow.util;

/** Constants for keys used in audit log detail maps. Centralised here to prevent typos. */
public final class AuditKeys {

    private AuditKeys() {}

    public static final String RESTORED        = "restored";
    public static final String DATE            = "date";
    public static final String CASHIER_ID      = "cashierId";
    public static final String REASON          = "reason";
    public static final String MOVED_FROM_DATE = "movedFromDate";
    public static final String MOVED_TO_DATE   = "movedToDate";
    public static final String REVERTED_FROM_AUDIT_ID = "revertedFromAuditId";
    public static final String REVERTED_ACTION         = "revertedAction";
}
