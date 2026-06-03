package com.saffron.cashflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches currency exchange rates from the National Bank of Poland (NBP)
 * public API (no API key required) and caches them for one hour.
 *
 * <p>Endpoint used: {@code https://api.nbp.pl/api/exchangerates/tables/A/?format=json}
 * Returns Table A rates (average rates) for ~170 currencies against PLN.</p>
 */
@Service
public class PosExchangeRateService {

    private static final Logger LOG = LoggerFactory.getLogger(PosExchangeRateService.class);
    private static final String NBP_URL = "https://api.nbp.pl/api/exchangerates/tables/A/?format=json";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, BigDecimal> rateCache = new ConcurrentHashMap<>();
    private volatile Instant cacheTime = Instant.EPOCH;

    /** Returns rates as code → PLN mid rate (e.g. EUR → 4.23). PLN itself = 1.00. */
    public Map<String, Object> getRates() {
        refreshIfStale();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base", "PLN");
        result.put("updatedAt", cacheTime.toString());
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("PLN", 1.0);
        rateCache.forEach((code, rate) -> rates.put(code, rate.doubleValue()));
        result.put("rates", rates);
        return result;
    }

    /** Returns PLN per 1 unit of the given currency (e.g. EUR → ~4.23). */
    public BigDecimal getRate(String currencyCode) {
        if ("PLN".equalsIgnoreCase(currencyCode)) return BigDecimal.ONE;
        refreshIfStale();
        return rateCache.getOrDefault(currencyCode.toUpperCase(), BigDecimal.ONE);
    }

    private synchronized void refreshIfStale() {
        if (Instant.now().isBefore(cacheTime.plus(CACHE_TTL))) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(NBP_URL))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                parseRates(resp.body());
                cacheTime = Instant.now();
                LOG.info("NBP exchange rates refreshed ({} currencies)", rateCache.size());
            } else {
                LOG.warn("NBP API returned status {}", resp.statusCode());
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch NBP rates: {}", e.getMessage());
            // Keep serving stale rates rather than failing completely.
        }
    }

    /** Very lightweight JSON parsing — avoids pulling in a JSON library dependency. */
    private void parseRates(String json) {
        // Pattern: "code":"EUR","mid":4.2345
        rateCache.clear();
        int i = 0;
        while (i < json.length()) {
            int codeIdx = json.indexOf("\"code\":\"", i);
            if (codeIdx < 0) break;
            int codeStart = codeIdx + 8;
            int codeEnd = json.indexOf("\"", codeStart);
            String code = json.substring(codeStart, codeEnd);

            int midIdx = json.indexOf("\"mid\":", codeEnd);
            int nextEntry = json.indexOf("\"code\":", codeEnd + 1);
            if (midIdx < 0 || (nextEntry > 0 && midIdx > nextEntry)) { i = codeEnd + 1; continue; }
            int midStart = midIdx + 6;
            int midEnd = midStart;
            while (midEnd < json.length() && (Character.isDigit(json.charAt(midEnd)) || json.charAt(midEnd) == '.')) midEnd++;
            try {
                BigDecimal rate = new BigDecimal(json.substring(midStart, midEnd));
                rateCache.put(code.toUpperCase(), rate);
            } catch (NumberFormatException ignored) { }
            i = midEnd;
        }
    }
}
