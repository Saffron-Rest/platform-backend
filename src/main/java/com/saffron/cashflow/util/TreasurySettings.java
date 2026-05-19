package com.saffron.cashflow.util;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Restaurant cash drawer vs card/bank pool — stored in system_setting key {@value #SETTINGS_KEY}. */
public final class TreasurySettings {

    public static final String SETTINGS_KEY = "treasury";

    private BigDecimal initialCashBalance = BigDecimal.ZERO;
    private BigDecimal initialCardBalance = BigDecimal.ZERO;
    /** Share of in-store card sales that reaches the card/bank balance (usually 1.0). */
    private BigDecimal cardSalesSettlementRate = BigDecimal.ONE;
    /** Share of each delivery platform sale that reaches the card/bank balance (e.g. 0.5 = half). */
    private Map<String, BigDecimal> platformSettlementRates = defaultPlatformRates();

    public static Map<String, BigDecimal> defaultPlatformRates() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("wolt", new BigDecimal("0.50"));
        m.put("bolt", new BigDecimal("0.50"));
        m.put("uberEats", new BigDecimal("0.50"));
        m.put("glovo", new BigDecimal("0.50"));
        m.put("other", new BigDecimal("0.50"));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TreasurySettings fromMap(Map<String, Object> raw) {
        TreasurySettings t = new TreasurySettings();
        if (raw == null || raw.isEmpty()) {
            return t;
        }
        if (raw.get("initialCashBalance") != null) {
            t.initialCashBalance = toBigDecimal(raw.get("initialCashBalance"));
        }
        if (raw.get("initialCardBalance") != null) {
            t.initialCardBalance = toBigDecimal(raw.get("initialCardBalance"));
        }
        if (raw.get("cardSalesSettlementRate") != null) {
            t.cardSalesSettlementRate = toBigDecimal(raw.get("cardSalesSettlementRate"));
        }
        if (raw.get("platformSettlementRates") instanceof Map<?, ?> rates) {
            Map<String, BigDecimal> parsed = new LinkedHashMap<>(defaultPlatformRates());
            rates.forEach((k, v) -> parsed.put(String.valueOf(k), toBigDecimal(v)));
            t.platformSettlementRates = parsed;
        }
        return t;
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("initialCashBalance", initialCashBalance.doubleValue());
        m.put("initialCardBalance", initialCardBalance.doubleValue());
        m.put("cardSalesSettlementRate", cardSalesSettlementRate.doubleValue());
        Map<String, Double> rates = new LinkedHashMap<>();
        platformSettlementRates.forEach((k, v) -> rates.put(k, v.doubleValue()));
        m.put("platformSettlementRates", rates);
        return m;
    }

    public BigDecimal getInitialCashBalance() { return initialCashBalance; }
    public BigDecimal getInitialCardBalance() { return initialCardBalance; }
    public BigDecimal getCardSalesSettlementRate() { return cardSalesSettlementRate; }
    public Map<String, BigDecimal> getPlatformSettlementRates() { return platformSettlementRates; }

    public void setInitialCashBalance(BigDecimal initialCashBalance) {
        this.initialCashBalance = initialCashBalance != null ? initialCashBalance : BigDecimal.ZERO;
    }

    public void setInitialCardBalance(BigDecimal initialCardBalance) {
        this.initialCardBalance = initialCardBalance != null ? initialCardBalance : BigDecimal.ZERO;
    }

    public void setCardSalesSettlementRate(BigDecimal cardSalesSettlementRate) {
        this.cardSalesSettlementRate = cardSalesSettlementRate != null ? cardSalesSettlementRate : BigDecimal.ONE;
    }

    public void setPlatformSettlementRates(Map<String, BigDecimal> platformSettlementRates) {
        this.platformSettlementRates = platformSettlementRates != null
                ? platformSettlementRates
                : defaultPlatformRates();
    }

    public BigDecimal platformRate(String key) {
        return platformSettlementRates.getOrDefault(key, new BigDecimal("0.50"));
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(v.toString());
    }
}
