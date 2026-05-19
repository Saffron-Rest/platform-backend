package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.DailyEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Resolves delivery platform amounts that reach the card/bank pool for treasury. */
public final class PlatformSettlement {

    private PlatformSettlement() {}

    public static BigDecimal settledToCard(
            DailyEntry entry, String platformKey, BigDecimal sales, TreasurySettings settings) {
        BigDecimal manual = manualAmount(entry, platformKey);
        if (manual != null) {
            return manual.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        return sales.multiply(settings.platformRate(platformKey)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal totalDeliverySettledToCard(DailyEntry entry, TreasurySettings settings) {
        return settledToCard(entry, "wolt", entry.getWoltSales(), settings)
                .add(settledToCard(entry, "bolt", entry.getBoltSales(), settings))
                .add(settledToCard(entry, "uberEats", entry.getUberEatsSales(), settings))
                .add(settledToCard(entry, "glovo", entry.getGlovoSales(), settings))
                .add(settledToCard(entry, "other", entry.getOtherPlatformSales(), settings));
    }

    private static BigDecimal manualAmount(DailyEntry entry, String platformKey) {
        return switch (platformKey) {
            case "wolt" -> entry.getWoltSettledToCard();
            case "bolt" -> entry.getBoltSettledToCard();
            case "uberEats" -> entry.getUberEatsSettledToCard();
            case "glovo" -> entry.getGlovoSettledToCard();
            case "other" -> entry.getOtherSettledToCard();
            default -> null;
        };
    }
}
