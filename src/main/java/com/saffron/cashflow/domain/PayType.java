package com.saffron.cashflow.domain;

/** How a cashier's pay is calculated from attendance. */
public enum PayType {
    /** Pay = sum of (shift hours × hourly rate). */
    HOURLY,
    /** Pay = daily rate × min(1, shift hours ÷ 8) per scheduled day. */
    DAILY,
    /** Pay = monthly salary × (days worked in period ÷ calendar days in period). */
    MONTHLY
}
