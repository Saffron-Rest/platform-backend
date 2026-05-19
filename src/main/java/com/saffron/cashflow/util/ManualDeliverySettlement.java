package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.ManualDeliveryIncome;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ManualDeliverySettlement {

    private ManualDeliverySettlement() {}

    public static BigDecimal settledToCard(ManualDeliveryIncome income, TreasurySettings settings) {
        if (income.getSettledToCard() != null) {
            return income.getSettledToCard().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rate = settings.platformRate(income.getPlatform().settingsKey());
        return income.getGrossAmount().multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
