package com.saffron.cashflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.PosIntegration;
import com.saffron.cashflow.domain.PosSale;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.repository.PosIntegrationRepository;
import com.saffron.cashflow.repository.PosSaleRepository;
import com.saffron.cashflow.repository.StockItemRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ingests POS sales pushed to {@code POST /api/pos/webhook/{id}}.
 *
 * Security: every request must include an {@code X-Pos-Signature} header
 * carrying the HMAC-SHA256 of the raw body using the integration's secret.
 * This lets POS vendors authenticate without us managing user accounts.
 *
 * Payload shape (lenient — we accept either flat or nested item arrays):
 * <pre>
 * {
 *   "externalId": "order-12345-line-1",   // required, used for idempotency
 *   "occurredAt": "2026-05-25T14:32:00Z", // optional, defaults to now
 *   "paymentMethod": "CARD",              // CARD | CASH | PLATFORM | …
 *   "items": [
 *     {
 *       "sku": "PLOV-LAMB",                // matched against MenuItem.sku
 *       "name": "Lamb Plov",               // fallback match
 *       "quantity": 2,                     // required
 *       "unitPrice": 32.00,                // gross, PLN
 *       "discount": 0
 *     }
 *   ]
 * }
 * </pre>
 *
 * For each item we either resolve to a {@link MenuItem} or persist the sale
 * with a null itemId (visible as "Unmatched" in analytics). Existing rows
 * keyed by {@code (integrationId, externalId#index)} are skipped.
 */
@Service
public class PosIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(PosIngestService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PosIntegrationRepository integrationRepository;
    private final PosSaleRepository saleRepository;
    private final PosIntegrationService integrationService;
    private final MenuService menuService;
    private final PosSalePostHandler postHandler;
    private final StockItemRepository stockItemRepository;
    private final ZoneId zoneId;

    public PosIngestService(
            PosIntegrationRepository integrationRepository,
            PosSaleRepository saleRepository,
            PosIntegrationService integrationService,
            MenuService menuService,
            PosSalePostHandler postHandler,
            StockItemRepository stockItemRepository,
            @Value("${app.timezone:Europe/Warsaw}") String timezone) {
        this.integrationRepository = integrationRepository;
        this.saleRepository = saleRepository;
        this.integrationService = integrationService;
        this.menuService = menuService;
        this.postHandler = postHandler;
        this.stockItemRepository = stockItemRepository;
        this.zoneId = ZoneId.of(timezone);
    }

    /**
     * Verify the signature and persist the payload. Returns a summary that the
     * webhook caller can use for debugging.
     */
    @Transactional
    public Map<String, Object> ingest(String integrationId, String signature, String rawBody) {
        PosIntegration integration = integrationRepository.findById(integrationId)
                .orElseThrow(() -> new BadRequestException("Unknown integration"));
        if (!integration.isActive()) {
            throw new BadRequestException("Integration is inactive");
        }
        verifySignature(integration.getWebhookSecret(), signature, rawBody);

        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BadRequestException("Invalid JSON: " + e.getMessage());
        }

        return ingestPayload(integration, payload);
    }

    /**
     * Persist the parsed payload — same logic the webhook uses, separated so
     * the admin simulator can reuse the exact ingest path without faking a
     * signature.
     *
     * <p>Caller is responsible for authorization and for verifying the
     * integration is active.</p>
     */
    @Transactional
    public Map<String, Object> ingestPayload(PosIntegration integration, JsonNode payload) {
        String externalId = textOrNull(payload, "externalId");
        if (externalId == null) externalId = textOrNull(payload, "external_id");
        if (externalId == null) throw new BadRequestException("externalId is required");

        Instant occurredAt = parseInstant(textOrNull(payload, "occurredAt"));
        if (occurredAt == null) occurredAt = parseInstant(textOrNull(payload, "occurred_at"));
        if (occurredAt == null) occurredAt = Instant.now();
        LocalDate businessDay = occurredAt.atZone(zoneId).toLocalDate();
        String paymentMethod = textOrNull(payload, "paymentMethod");
        if (paymentMethod == null) paymentMethod = textOrNull(payload, "payment_method");

        JsonNode items = payload.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new BadRequestException("items[] is required and must be non-empty");
        }

        int inserted = 0, skipped = 0, unmatched = 0;
        for (int i = 0; i < items.size(); i++) {
            JsonNode it = items.get(i);
            String lineExternalId = externalId + "#" + i;

            if (saleRepository.findByIntegrationIdAndExternalId(integration.getId(), lineExternalId).isPresent()) {
                skipped++;
                continue;
            }

            BigDecimal quantity = decimal(it.get("quantity"));
            BigDecimal unitPrice = decimal(it.get("unitPrice"));
            if (unitPrice == null) unitPrice = decimal(it.get("unit_price"));
            BigDecimal discount = decimal(it.get("discount"));
            if (quantity == null || quantity.signum() <= 0 || unitPrice == null) {
                LOG.warn("Skipping malformed line {} for integration {}: missing qty/price", i, integration.getId());
                continue;
            }

            String sku = textOrNull(it, "sku");
            String name = textOrNull(it, "name");
            String menuItemIdHint = textOrNull(it, "menuItemId");

            Optional<MenuItem> match = Optional.empty();
            if (menuItemIdHint != null) match = menuService.findById(menuItemIdHint);
            if (match.isEmpty() && sku != null) match = menuService.findBySku(sku);
            if (match.isEmpty() && name != null) match = menuService.findByName(name);

            PosSale sale = new PosSale();
            sale.setIntegrationId(integration.getId());
            sale.setExternalId(lineExternalId);
            sale.setSku(sku);
            sale.setItemName(name);
            sale.setQuantity(quantity.setScale(3, RoundingMode.HALF_UP));
            sale.setUnitPrice(unitPrice.setScale(2, RoundingMode.HALF_UP));
            sale.setDiscountAmount(discount != null ? discount.setScale(2, RoundingMode.HALF_UP) : null);
            sale.setPaymentMethod(paymentMethod);
            sale.setOccurredAt(occurredAt);
            sale.setBusinessDay(businessDay);

            match.ifPresentOrElse(item -> {
                sale.setMenuItemId(item.getId());
                sale.setCategoryId(item.getCategoryId());
                sale.setFoodCost(item.getFoodCost());
                if (sale.getItemName() == null) sale.setItemName(item.getName());
            }, () -> { /* leave null — surface as Unmatched */ });

            PosSale persisted = saleRepository.save(sale);
            postHandler.afterSaleSaved(persisted);
            inserted++;
            if (sale.getMenuItemId() == null) unmatched++;
        }

        integrationService.markReceived(integration, externalId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        result.put("unmatched", unmatched);
        result.put("externalId", externalId);
        return result;
    }

    // ========================================================================
    // Admin simulator
    // ========================================================================

    /**
     * Synthesize a fake POS receipt and run it through the live ingest path so
     * an admin can verify menu→stock mappings without ringing up a real sale.
     *
     * <p>When {@code dryRun} is true we still run the full mutation path
     * (so menu matching, stock lookup, decrement, audit row are all
     * exercised) but mark the surrounding transaction rollback-only just
     * before returning. The before/after stock snapshot is captured against
     * the live state so the caller sees exactly what would have changed.</p>
     *
     * <p>Authorization is checked here — the simulator is an admin-only
     * tool because it can mutate stock balances.</p>
     */
    @Transactional
    public Map<String, Object> simulate(String integrationId, SimulationRequest req) {
        AuthHelper.requireAdminOr(Permission.POS_INTEGRATION_MANAGE);
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BadRequestException("At least one line item is required");
        }
        PosIntegration integration = integrationRepository.findById(integrationId)
                .orElseThrow(() -> new NotFoundException("Integration not found"));
        if (!integration.isActive()) {
            throw new BadRequestException("Integration is inactive — activate it before simulating sales");
        }

        // Capture before-state for any stock items that *might* be affected
        // by these lines, keyed by stock item id. Resolution mirrors
        // PosSalePostHandler exactly so the diff matches reality.
        Map<String, StockSnapshot> before = new LinkedHashMap<>();
        for (SimulationLine line : req.items()) {
            Optional<StockItem> hit = resolveStockFor(line);
            hit.ifPresent(s -> before.computeIfAbsent(s.getId(),
                    id -> new StockSnapshot(s.getId(), s.getName(), s.getUnit(),
                            s.getOnHand() == null ? BigDecimal.ZERO : s.getOnHand())));
        }

        ObjectNode payload = buildSimulatedPayload(req);
        Map<String, Object> ingestResult = ingestPayload(integration, payload);

        // Re-read affected stock to see the actual after-balance.
        List<Map<String, Object>> stockImpact = new ArrayList<>();
        for (StockSnapshot snap : before.values()) {
            StockItem fresh = stockItemRepository.findById(snap.id()).orElse(null);
            BigDecimal after = (fresh == null || fresh.getOnHand() == null)
                    ? snap.onHand() : fresh.getOnHand();
            BigDecimal delta = after.subtract(snap.onHand());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockItemId", snap.id());
            row.put("name", snap.name());
            row.put("unit", snap.unit());
            row.put("before", snap.onHand());
            row.put("after", after);
            row.put("delta", delta);
            stockImpact.add(row);
        }

        // Lines that had no linked stock — surface them so the admin knows
        // those POS sales would record but not move inventory.
        List<Map<String, Object>> unlinked = new ArrayList<>();
        for (SimulationLine line : req.items()) {
            if (resolveStockFor(line).isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("menuItemId", line.menuItemId());
                row.put("sku", line.sku());
                row.put("name", line.name());
                row.put("quantity", line.quantity());
                unlinked.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>(ingestResult);
        out.put("dryRun", req.dryRun());
        out.put("stockImpact", stockImpact);
        out.put("unlinkedLines", unlinked);

        if (req.dryRun()) {
            // Roll back EVERY mutation done above (POS sale rows, stock
            // movements, balance updates, integration.lastSeenAt). The
            // returned map is built from the in-memory state captured before
            // the rollback so the admin still sees what would have happened.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            out.put("rolledBack", true);
        }

        return out;
    }

    private Optional<StockItem> resolveStockFor(SimulationLine line) {
        if (line.menuItemId() != null && !line.menuItemId().isBlank()) {
            Optional<StockItem> hit = stockItemRepository.findFirstByMenuItemIdAndActiveTrue(line.menuItemId());
            if (hit.isPresent()) return hit;
        }
        if (line.sku() != null && !line.sku().isBlank()) {
            return stockItemRepository.findFirstBySkuIgnoreCase(line.sku());
        }
        return Optional.empty();
    }

    private ObjectNode buildSimulatedPayload(SimulationRequest req) {
        ObjectNode root = MAPPER.createObjectNode();
        // Tag the externalId so it's obvious in audit logs / activity panel
        // that this line came from the admin simulator, not a real receipt.
        root.put("externalId", "saffron-simulator-" + UUID.randomUUID());
        root.put("occurredAt", (req.occurredAt() != null ? req.occurredAt() : Instant.now()).toString());
        root.put("paymentMethod", req.paymentMethod() == null || req.paymentMethod().isBlank()
                ? "SIMULATOR" : req.paymentMethod());
        ArrayNode items = root.putArray("items");
        for (SimulationLine line : req.items()) {
            ObjectNode it = items.addObject();
            // Resolve a sensible unit price + name from the menu item if the
            // caller didn't supply them, so the simulator works with just an
            // id + quantity in the simplest case.
            Optional<MenuItem> mi = (line.menuItemId() != null && !line.menuItemId().isBlank())
                    ? menuService.findById(line.menuItemId()) : Optional.empty();

            String sku = line.sku() != null && !line.sku().isBlank()
                    ? line.sku()
                    : mi.map(MenuItem::getSku).orElse(null);
            String name = line.name() != null && !line.name().isBlank()
                    ? line.name()
                    : mi.map(MenuItem::getName).orElse(null);
            BigDecimal price = line.unitPrice() != null
                    ? line.unitPrice()
                    : mi.map(MenuItem::getSellPrice).orElse(BigDecimal.ONE);

            if (line.menuItemId() != null && !line.menuItemId().isBlank()) {
                it.put("menuItemId", line.menuItemId());
            }
            if (sku != null) it.put("sku", sku);
            if (name != null) it.put("name", name);
            it.put("quantity", line.quantity());
            it.put("unitPrice", price);
        }
        return root;
    }

    /** Inputs to {@link #simulate(String, SimulationRequest)} — bag of
     *  lines plus meta so the controller can stay thin. */
    public record SimulationRequest(
            List<SimulationLine> items,
            String paymentMethod,
            Instant occurredAt,
            boolean dryRun) {}

    public record SimulationLine(
            String menuItemId,
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice) {}

    private record StockSnapshot(
            String id,
            String name,
            String unit,
            BigDecimal onHand) {}

    // ---------- Helpers ----------

    private static void verifySignature(String secret, String signature, String body) {
        if (signature == null || signature.isBlank()) {
            throw new BadRequestException("Missing X-Pos-Signature header");
        }
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            expected = hex.toString();
        } catch (Exception e) {
            throw new BadRequestException("Signature verification failed");
        }
        // Trim common header prefix like "sha256=..."
        String actual = signature.trim();
        if (actual.startsWith("sha256=")) actual = actual.substring("sha256=".length());
        if (!constantTimeEquals(expected, actual.toLowerCase())) {
            throw new BadRequestException("Bad signature");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String s = node.get(field).asText();
        return s.isBlank() ? null : s.trim();
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            if (node.isNumber()) return node.decimalValue();
            String s = node.asText();
            if (s == null || s.isBlank()) return null;
            return new BigDecimal(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
