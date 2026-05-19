package com.saffron.cashflow.domain;

/** Cashier push notification kinds (deduplicated per user per reference date). */
public enum CashierNotificationType {
    MISSING_REPORT,
    CLOSING_REMINDER,
    TOMORROW_SHIFT
}
