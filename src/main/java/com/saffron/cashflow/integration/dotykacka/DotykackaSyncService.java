package com.saffron.cashflow.integration.dotykacka;

import com.fasterxml.jackson.databind.JsonNode;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.PosIntegration;
import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.repository.PosIntegrationRepository;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.service.MenuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pulls receipts from Dotykačka and writes them into our {@code pos_sale} table.
 *
 * Strategy: query orders with {@code documentType=RECEIPT}, ordered by
 * {@code versionDate}, paginated, with {@code include=orderItems}. We track
 * the highest {@code versionDate} we have seen per integration in
 * {@code PosIntegration.dotykackaSyncCursor} so each tick is incremental.
 *
 * Idempotency is enforced by the existing {@code (integrationId, externalId)}
 * unique constraint — we use Dotykačka's order-item id as the externalId.
 */
@Service
public class DotykackaSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(DotykackaSyncService.class);

    /** Pages of 100 — Dotykačka's max page size. */
    private static final int PAGE_SIZE = 100;
    /** Hard limit on pages per sync tick so a misconfigured integration can't
     *  monopolise the worker thread. */
    private static final int MAX_PAGES_PER_TICK = 20;

    private final PosIntegrationRepository integrationRepository;
    private final PosSaleRepository saleRepository;
    private final MenuService menuService;
    private final DotykackaClient client;
    private final ZoneId zoneId;

    public DotykackaSyncService(
            PosIntegrationRepository integrationRepository,
            PosSaleRepository saleRepository,
            MenuService menuService,
            DotykackaClient client,
            @Value("${app.timezone:Europe/Warsaw}") String timezone) {
        this.integrationRepository = integrationRepository;
        this.saleRepository = saleRepository;
        this.menuService = menuService;
        this.client = client;
        this.zoneId = ZoneId.of(timezone);
    }

    /**
     * Cron entrypoint — every 5 minutes. Each Dotykačka integration is synced
     * in its own transaction so a failure on one doesn't block the rest.
     */
    @Scheduled(fixedDelayString = "${app.dotykacka.sync-ms:300000}",
               initialDelayString = "${app.dotykacka.initial-delay-ms:60000}")
    public void scheduledSync() {
        List<PosIntegration> targets = integrationRepository.findAll().stream()
                .filter(p -> p.isActive())
                .filter(p -> "dotykacka".equalsIgnoreCase(p.getVendor()))
                .toList();
        for (PosIntegration integration : targets) {
            try {
                Map<String, Object> result = syncOne(integration.getId());
                LOG.info("Dotykačka sync {}: {}", integration.getName(), result);
            } catch (Exception e) {
                LOG.warn("Dotykačka sync failed for {}: {}", integration.getName(), e.getMessage());
            }
        }
    }

    /**
     * Run a sync for a specific integration on demand (e.g. admin clicks
     * "Sync now"). Returns a small summary the UI can display.
     */
    @Transactional
    public Map<String, Object> syncOne(String integrationId) {
        PosIntegration integration = integrationRepository.findById(integrationId)
                .orElseThrow(() -> new IllegalArgumentException("Integration not found"));
        if (!"dotykacka".equalsIgnoreCase(integration.getVendor())) {
            throw new IllegalArgumentException("Not a Dotykačka integration");
        }
        if (integration.getDotykackaRefreshToken() == null
                || integration.getDotykackaCloudId() == null) {
            throw new IllegalArgumentException("Dotykačka credentials not configured");
        }
        String accessToken = client.getAccessToken(
                integration.getDotykackaRefreshToken(),
                integration.getDotykackaCloudId());

        // Use versionDate as the cursor — Dotykačka updates it on every change.
        // Fall back to 7 days ago on first run so we don't pull all-time history.
        Instant cursor = integration.getDotykackaSyncCursor();
        if (cursor == null) cursor = Instant.now().minusSeconds(7 * 24 * 3600L);
        // Dotykačka's filter syntax uses literal "|" and ";" which Java's URI
        // parser rejects, so we percent-encode the whole filter value as one
        // unit rather than only the timestamp inside it.
        String filter = DotykackaClient.enc(
                "documentType|eq|RECEIPT;versionDate|gte|" + cursor.toString());

        int inserted = 0, skipped = 0, unmatched = 0, pagesFetched = 0;
        Instant maxSeen = cursor;
        int page = 1;
        while (pagesFetched < MAX_PAGES_PER_TICK) {
            String qs = "filter=" + filter
                    + "&include=orderItems"
                    + "&sort=versionDate"
                    + "&limit=" + PAGE_SIZE
                    + "&page=" + page;
            JsonNode body = client.get(accessToken,
                    "/v2/clouds/" + integration.getDotykackaCloudId() + "/orders", qs);
            pagesFetched++;
            JsonNode data = body.has("data") ? body.get("data") : body;
            if (data == null || !data.isArray() || data.isEmpty()) break;

            for (JsonNode order : data) {
                Instant orderVersion = parseInstant(order, "versionDate");
                if (orderVersion != null && orderVersion.isAfter(maxSeen)) maxSeen = orderVersion;
                IngestStats s = ingestOrder(integration, order);
                inserted += s.inserted;
                skipped += s.skipped;
                unmatched += s.unmatched;
            }

            if (data.size() < PAGE_SIZE) break;
            page++;
        }

        integration.setDotykackaSyncCursor(maxSeen);
        integration.setLastSyncedAt(Instant.now());
        integrationRepository.save(integration);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inserted", inserted);
        summary.put("skipped", skipped);
        summary.put("unmatched", unmatched);
        summary.put("pagesFetched", pagesFetched);
        summary.put("cursor", maxSeen.toString());
        return summary;
    }

    // ---------- Webhook entry point ----------

    /**
     * Ingest orders that arrived via Dotypos's native webhook ({@code
     * payloadEntity = "ORDERBEAN"}). The payload is a JSON array of orders.
     *
     * Reuses the same ingest path as polling, so idempotency and matching are
     * identical.
     */
    @Transactional
    public Map<String, Object> ingestWebhook(PosIntegration integration, JsonNode payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Empty webhook body");
        }
        IngestStats total = new IngestStats();
        if (payload.isArray()) {
            for (JsonNode order : payload) {
                IngestStats s = ingestOrder(integration, order);
                total.inserted += s.inserted;
                total.skipped += s.skipped;
                total.unmatched += s.unmatched;
            }
        } else if (payload.isObject()) {
            // Some Dotypos integrations wrap rows in {"data": [...]} — handle both.
            JsonNode arr = payload.has("data") ? payload.get("data") : payload;
            if (arr.isArray()) {
                for (JsonNode order : arr) {
                    IngestStats s = ingestOrder(integration, order);
                    total.inserted += s.inserted;
                    total.skipped += s.skipped;
                    total.unmatched += s.unmatched;
                }
            } else {
                // Single order object — ingest as-is.
                total = ingestOrder(integration, payload);
            }
        }
        integration.setLastSeenAt(Instant.now());
        integrationRepository.save(integration);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("inserted", total.inserted);
        out.put("skipped", total.skipped);
        out.put("unmatched", total.unmatched);
        return out;
    }

    // ---------- Per-order ingest ----------

    private IngestStats ingestOrder(PosIntegration integration, JsonNode order) {
        IngestStats stats = new IngestStats();
        // We only ingest paid receipts — open/unpaid orders are not real sales.
        boolean paid = order.path("paid").asBoolean(false);
        if (!paid) {
            // Still allow non-paid items through if Dotykačka returned them — Dotykačka
            // sometimes reports completed sales without setting paid=true. Treat
            // any order with a closeDate as a sale.
            if (order.path("closeDate").asText(null) == null) {
                return stats;
            }
        }

        Instant occurredAt = parseInstant(order, "closeDate");
        if (occurredAt == null) occurredAt = parseInstant(order, "issueDate");
        if (occurredAt == null) occurredAt = parseInstant(order, "created");
        if (occurredAt == null) occurredAt = Instant.now();
        LocalDate businessDay = occurredAt.atZone(zoneId).toLocalDate();
        String paymentMethod = mapPaymentMethod(order);
        long orderId = order.path("id").asLong();

        JsonNode items = order.path("orderItems");
        if (items == null || !items.isArray()) return stats;

        int index = 0;
        for (JsonNode item : items) {
            long orderItemId = item.path("id").asLong();
            String externalId = orderItemId > 0
                    ? String.valueOf(orderItemId)
                    : (orderId + "#" + index);
            index++;

            if (saleRepository.findByIntegrationIdAndExternalId(integration.getId(), externalId).isPresent()) {
                stats.skipped++;
                continue;
            }

            // Skip cancelled / refund-only lines (negative quantity is allowed
            // for refunds — capture them so totals still reconcile).
            BigDecimal quantity = bigDec(item, "quantity");
            BigDecimal unitPrice = bigDec(item, "unitPriceWithVat");
            if (unitPrice.signum() == 0) unitPrice = bigDec(item, "billedUnitPriceWithVat");
            if (quantity.signum() == 0 || unitPrice.signum() == 0) {
                stats.skipped++;
                continue;
            }
            BigDecimal discountPct = bigDec(item, "discountPercent");
            BigDecimal discountAmount = BigDecimal.ZERO;
            if (discountPct.signum() > 0) {
                discountAmount = unitPrice
                        .multiply(discountPct)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            }

            String productId = textOrNull(item, "_productId");
            String name = textOrNull(item, "name");
            if (name == null) name = textOrNull(item, "alternativeName");
            String externalProductRef = textOrNull(item, "externalId");

            Optional<MenuItem> match = Optional.empty();
            if (externalProductRef != null) match = menuService.findBySku(externalProductRef);
            if (match.isEmpty() && productId != null) match = menuService.findBySku(productId);
            if (match.isEmpty() && name != null) match = menuService.findByName(name);

            PosSale sale = new PosSale();
            sale.setIntegrationId(integration.getId());
            sale.setExternalId(externalId);
            sale.setSku(externalProductRef != null ? externalProductRef : productId);
            sale.setItemName(name);
            sale.setQuantity(quantity.setScale(3, RoundingMode.HALF_UP));
            sale.setUnitPrice(unitPrice.setScale(2, RoundingMode.HALF_UP));
            sale.setDiscountAmount(discountAmount.setScale(2, RoundingMode.HALF_UP));
            sale.setPaymentMethod(paymentMethod);
            sale.setOccurredAt(occurredAt);
            sale.setBusinessDay(businessDay);

            match.ifPresent(mi -> {
                sale.setMenuItemId(mi.getId());
                sale.setCategoryId(mi.getCategoryId());
                sale.setFoodCost(mi.getFoodCost());
                if (sale.getItemName() == null) sale.setItemName(mi.getName());
            });
            if (sale.getMenuItemId() == null) stats.unmatched++;
            saleRepository.save(sale);
            stats.inserted++;
        }
        return stats;
    }

    // ---------- Parsing helpers ----------

    /** Dotykačka's documentType=RECEIPT carries payment in a related money log;
     *  here we settle for a best-effort hint from the order itself.
     *  Owner can still see card vs cash from the daily entry totals — the menu
     *  analytics doesn't depend on this field. */
    private static String mapPaymentMethod(JsonNode order) {
        // Some integrations expose `paymentMethodId` (int) directly; fall back
        // to "UNKNOWN" so we can still ingest the line.
        if (order.hasNonNull("paymentMethodId")) {
            return "DOTYKACKA_" + order.get("paymentMethodId").asText();
        }
        return "UNKNOWN";
    }

    private static BigDecimal bigDec(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return BigDecimal.ZERO;
        try {
            return node.get(field).isNumber()
                    ? node.get(field).decimalValue()
                    : new BigDecimal(node.get(field).asText().replace(',', '.'));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        String s = node.get(field).asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Instant parseInstant(JsonNode node, String field) {
        String s = textOrNull(node, field);
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            // Some Dotykačka responses come as "2025-05-25 12:34:56" (no T).
            try {
                return Instant.parse(s.replace(' ', 'T') + (s.contains("Z") || s.endsWith("Z") ? "" : "Z"));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static final class IngestStats {
        int inserted;
        int skipped;
        int unmatched;
    }
}
