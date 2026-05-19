package com.saffron.cashflow.report;

public enum ProfitLossTemplate {
    GENERIC,
    US,
    EU,
    PL;

    public static ProfitLossTemplate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERIC;
        }
        return ProfitLossTemplate.valueOf(raw.trim().toUpperCase());
    }
}
