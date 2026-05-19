package com.saffron.cashflow.domain;

public enum DeliveryPlatform {
    WOLT,
    BOLT,
    UBER_EATS,
    GLOVO,
    OTHER;

    /** Key used in treasury platform settlement rates. */
    public String settingsKey() {
        return switch (this) {
            case WOLT -> "wolt";
            case BOLT -> "bolt";
            case UBER_EATS -> "uberEats";
            case GLOVO -> "glovo";
            case OTHER -> "other";
        };
    }

    public static DeliveryPlatform parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("platform required");
        }
        String n = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        if ("UBER".equals(n) || "UBEREATS".equals(n)) {
            return UBER_EATS;
        }
        return DeliveryPlatform.valueOf(n);
    }
}
