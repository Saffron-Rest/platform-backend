package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.PosOrder;
import com.saffron.cashflow.domain.PosOrderLine;
import com.saffron.cashflow.domain.PosOrderPayment;
import com.saffron.cashflow.domain.PosTable;
import com.saffron.cashflow.domain.PosTimeBasedPrice;
import com.saffron.cashflow.domain.SupplierInvoicePayment;
import com.saffron.cashflow.repository.MenuCategoryRepository;
import com.saffron.cashflow.repository.MenuItemRepository;
import com.saffron.cashflow.repository.PosOrderPaymentRepository;
import com.saffron.cashflow.repository.PosOrderRepository;
import com.saffron.cashflow.repository.PosTableRepository;
import com.saffron.cashflow.repository.PosTimeBasedPriceRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PosOrderService {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final PosOrderRepository orderRepository;
    private final PosTableRepository tableRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuCategoryRepository categoryRepository;
    private final PosOrderPaymentRepository paymentRepository;
    private final PosTimeBasedPriceRepository timeBasedPriceRepository;

    public PosOrderService(PosOrderRepository orderRepository,
                           PosTableRepository tableRepository,
                           MenuItemRepository menuItemRepository,
                           MenuCategoryRepository categoryRepository,
                           PosOrderPaymentRepository paymentRepository,
                           PosTimeBasedPriceRepository timeBasedPriceRepository) {
        this.orderRepository = orderRepository;
        this.tableRepository = tableRepository;
        this.menuItemRepository = menuItemRepository;
        this.categoryRepository = categoryRepository;
        this.paymentRepository = paymentRepository;
        this.timeBasedPriceRepository = timeBasedPriceRepository;
    }

    // ── Menu for POS ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> posMenu() {
        List<MenuItem> items = menuItemRepository.findAllByActiveTrueOrderByNameAsc()
                .stream()
                .filter(MenuItem::isPosAvailable)
                .sorted((a, b) -> {
                    int cmp = a.getCategoryId().compareTo(b.getCategoryId());
                    return cmp != 0 ? cmp : Integer.compare(a.getPosDisplayOrder(), b.getPosDisplayOrder());
                })
                .toList();
        // Build id→name lookup so category tabs show real names, not UUIDs.
        java.util.Map<String, String> catNames = new java.util.HashMap<>();
        java.util.Map<String, Integer> catOrder = new java.util.HashMap<>();
        for (com.saffron.cashflow.domain.MenuCategory c :
                categoryRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc()) {
            catNames.put(c.getId(), c.getName());
            catOrder.put(c.getId(), c.getSortOrder());
        }
        // Build happy-hour index: itemId → active rule (if any) for current time.
        ZonedDateTime now = ZonedDateTime.now(WARSAW);
        LocalTime currentTime = now.toLocalTime();
        String todayAbbr = now.getDayOfWeek().name().substring(0, 3); // MON, TUE…
        java.util.Map<String, PosTimeBasedPrice> happyHours = new java.util.HashMap<>();
        for (PosTimeBasedPrice rule : timeBasedPriceRepository.findAllByActiveTrue()) {
            if (!currentTime.isBefore(rule.getStartTime()) && currentTime.isBefore(rule.getEndTime())) {
                String days = rule.getDaysOfWeek();
                if (days == null || days.isBlank() || days.contains(todayAbbr)) {
                    happyHours.put(rule.getMenuItemId(), rule);
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (MenuItem i : items) {
            PosTimeBasedPrice hh = happyHours.get(i.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("categoryId", i.getCategoryId());
            m.put("categoryName", catNames.getOrDefault(i.getCategoryId(), i.getCategoryId()));
            m.put("categorySortOrder", catOrder.getOrDefault(i.getCategoryId(), 999));
            m.put("name", i.getName());
            m.put("sku", i.getSku());
            m.put("barcode", i.getBarcode());
            m.put("sellPrice", hh != null ? hh.getEffectivePrice().doubleValue() : i.getSellPrice().doubleValue());
            m.put("originalPrice", i.getSellPrice().doubleValue());
            m.put("isHappyHour", hh != null);
            m.put("happyHourName", hh != null ? hh.getName() : null);
            m.put("happyHourEnds", hh != null ? hh.getEndTime().toString() : null);
            m.put("vatRatePct", i.getVatRatePct().doubleValue());
            m.put("dietaryTags", i.getDietaryTags());
            m.put("allergens", i.getAllergens());
            m.put("imagePath", i.getImagePath());
            m.put("posDisplayOrder", i.getPosDisplayOrder());
            result.add(m);
        }
        return result;
    }

    // ── Happy Hour management ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTimeBasedPrices(String menuItemId) {
        List<PosTimeBasedPrice> rules = menuItemId != null
                ? timeBasedPriceRepository.findByMenuItemIdAndActiveTrue(menuItemId)
                : timeBasedPriceRepository.findAllByActiveTrue();
        return rules.stream().map(this::timePriceToMap).toList();
    }

    @Transactional
    public Map<String, Object> saveTimeBasedPrice(Map<String, Object> req) {
        AuthHelper.requireOperations();
        String id = (String) req.get("id");
        PosTimeBasedPrice rule = id != null
                ? timeBasedPriceRepository.findById(id).orElseThrow(() -> new NotFoundException("Rule not found"))
                : new PosTimeBasedPrice();
        rule.setMenuItemId((String) req.get("menuItemId"));
        rule.setName((String) req.getOrDefault("name", "Happy Hour"));
        rule.setEffectivePrice(new BigDecimal(req.get("effectivePrice").toString()));
        rule.setStartTime(LocalTime.parse((String) req.get("startTime")));
        rule.setEndTime(LocalTime.parse((String) req.get("endTime")));
        if (req.get("daysOfWeek") != null) rule.setDaysOfWeek((String) req.get("daysOfWeek"));
        if (req.get("active") != null) rule.setActive((Boolean) req.get("active"));
        rule = timeBasedPriceRepository.save(rule);
        return timePriceToMap(rule);
    }

    @Transactional
    public void deleteTimeBasedPrice(String id) {
        AuthHelper.requireOperations();
        timeBasedPriceRepository.deleteById(id);
    }

    private Map<String, Object> timePriceToMap(PosTimeBasedPrice r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("menuItemId", r.getMenuItemId());
        m.put("name", r.getName());
        m.put("effectivePrice", r.getEffectivePrice().doubleValue());
        m.put("startTime", r.getStartTime().toString());
        m.put("endTime", r.getEndTime().toString());
        m.put("daysOfWeek", r.getDaysOfWeek());
        m.put("active", r.isActive());
        return m;
    }

    // ── Tables ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTables() {
        List<PosOrder> openOrders = orderRepository.findAllOpen();
        Map<String, String> occupiedByTable = new LinkedHashMap<>();
        for (PosOrder o : openOrders) {
            if (o.getTableId() != null) occupiedByTable.put(o.getTableId(), o.getId());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (PosTable t : tableRepository.findByActiveTrueOrderByAreaAscGridYAscGridXAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("area", t.getArea());
            m.put("gridX", t.getGridX());
            m.put("gridY", t.getGridY());
            m.put("seats", t.getSeats());
            m.put("occupied", occupiedByTable.containsKey(t.getId()));
            m.put("openOrderId", occupiedByTable.get(t.getId()));
            result.add(m);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> saveTable(Map<String, Object> req) {
        AuthHelper.requireOperations();
        String id = (String) req.get("id");
        PosTable table = id != null
                ? tableRepository.findById(id).orElseThrow(() -> new NotFoundException("Table not found"))
                : new PosTable();
        if (req.containsKey("name")) table.setName((String) req.get("name"));
        if (req.containsKey("area")) table.setArea((String) req.get("area"));
        if (req.containsKey("gridX")) table.setGridX(((Number) req.get("gridX")).intValue());
        if (req.containsKey("gridY")) table.setGridY(((Number) req.get("gridY")).intValue());
        if (req.containsKey("seats")) table.setSeats(((Number) req.get("seats")).intValue());
        if (req.containsKey("active")) table.setActive((Boolean) req.get("active"));
        table = tableRepository.save(table);
        return tableToMap(table);
    }

    // ── Orders ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOpenOrders() {
        return orderRepository.findAllOpen().stream().map(this::orderToMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOrder(String id) {
        PosOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        return orderToMap(order);
    }

    @Transactional
    public Map<String, Object> createOrder(Map<String, Object> req) {
        String cashierId = AuthHelper.currentUser().id();
        String integrationId = (String) req.getOrDefault("integrationId", "saffron-pos");

        PosOrder order = new PosOrder();
        order.setCashierId(cashierId);
        order.setIntegrationId(integrationId);
        order.setTableId((String) req.get("tableId"));
        if (req.get("orderType") != null) order.setOrderType((String) req.get("orderType"));
        if (req.get("customerName") != null) order.setCustomerName((String) req.get("customerName"));
        if (req.get("customerPhone") != null) order.setCustomerPhone((String) req.get("customerPhone"));
        if (req.get("deliveryAddress") != null) order.setDeliveryAddress((String) req.get("deliveryAddress"));
        if (req.get("specialRequests") != null) order.setSpecialRequests((String) req.get("specialRequests"));
        if (req.get("covers") != null) order.setCovers(((Number) req.get("covers")).intValue());
        if (req.get("orderNote") != null) order.setOrderNote((String) req.get("orderNote"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lineReqs = (List<Map<String, Object>>) req.get("lines");
        if (lineReqs != null) {
            for (Map<String, Object> l : lineReqs) {
                addLineToOrder(order, l);
            }
        }
        recomputeTotals(order);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    @Transactional
    public Map<String, Object> addLine(String orderId, Map<String, Object> req) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() != PosOrder.Status.OPEN) {
            throw new BadRequestException("Cannot modify a " + order.getStatus() + " order");
        }
        addLineToOrder(order, req);
        recomputeTotals(order);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    @Transactional
    public Map<String, Object> removeLine(String orderId, String lineId) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() != PosOrder.Status.OPEN) {
            throw new BadRequestException("Cannot modify a " + order.getStatus() + " order");
        }
        order.getLines().removeIf(l -> l.getId().equals(lineId));
        recomputeTotals(order);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    @Transactional
    public Map<String, Object> payOrder(String orderId, Map<String, Object> req) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() == PosOrder.Status.PAID) {
            throw new BadRequestException("Order already paid");
        }
        if (order.getStatus() == PosOrder.Status.VOIDED) {
            throw new BadRequestException("Order was voided");
        }
        String methodStr = (String) req.getOrDefault("paymentMethod", "CASH");
        order.setPaymentMethod(parseMethod(methodStr));
        if (req.get("amountTendered") != null) {
            order.setAmountTendered(new BigDecimal(req.get("amountTendered").toString()));
        }
        if (req.get("buyerNip") != null) {
            order.setBuyerNip((String) req.get("buyerNip"));
        }
        if (req.get("fiscalReceiptNumber") != null) {
            order.setFiscalReceiptNumber((String) req.get("fiscalReceiptNumber"));
        }
        if (req.get("tipAmount") != null) {
            order.setTipAmount(new BigDecimal(req.get("tipAmount").toString()));
        }
        order.setStatus(PosOrder.Status.PAID);
        order.setPaidAt(Instant.now());
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    @Transactional
    public Map<String, Object> parkOrder(String orderId, String note) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() != PosOrder.Status.OPEN) {
            throw new BadRequestException("Only OPEN orders can be parked");
        }
        order.setStatus(PosOrder.Status.PARKED);
        order.setParkedAt(Instant.now());
        order.setParkedNote(note);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    @Transactional
    public Map<String, Object> resumeOrder(String orderId) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() != PosOrder.Status.PARKED) {
            throw new BadRequestException("Only PARKED orders can be resumed");
        }
        order.setStatus(PosOrder.Status.OPEN);
        order.setParkedAt(null);
        order.setParkedNote(null);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lookupByBarcode(String code) {
        // Search barcode first, then fall back to SKU.
        MenuItem item = menuItemRepository.findFirstByBarcodeAndActiveTrue(code)
                .or(() -> menuItemRepository.findFirstBySkuIgnoreCase(code))
                .orElse(null);
        if (item == null || !item.isPosAvailable()) return null;
        java.util.Map<String, String> catNames = new java.util.HashMap<>();
        categoryRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc()
                .forEach(c -> catNames.put(c.getId(), c.getName()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("categoryId", item.getCategoryId());
        m.put("categoryName", catNames.getOrDefault(item.getCategoryId(), item.getCategoryId()));
        m.put("name", item.getName());
        m.put("sku", item.getSku());
        m.put("barcode", item.getBarcode());
        m.put("sellPrice", item.getSellPrice().doubleValue());
        m.put("vatRatePct", item.getVatRatePct().doubleValue());
        m.put("posDisplayOrder", item.getPosDisplayOrder());
        return m;
    }

    @Transactional
    public Map<String, Object> voidOrder(String orderId) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() == PosOrder.Status.PAID) {
            throw new BadRequestException("Cannot void a paid order. Use a refund instead.");
        }
        order.setStatus(PosOrder.Status.VOIDED);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    // ── Discounts ────────────────────────────────────────────────────────────

    /**
     * Apply a discount to a specific line (ITEM) or to the whole order (ORDER).
     * req: { type: "ITEM"|"ORDER", lineId?, value: number, isPercentage: boolean }
     */
    @Transactional
    public Map<String, Object> applyDiscount(String orderId, Map<String, Object> req) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() != PosOrder.Status.OPEN) {
            throw new BadRequestException("Cannot modify a " + order.getStatus() + " order");
        }
        String type = (String) req.getOrDefault("type", "ORDER");
        BigDecimal value = new BigDecimal(req.get("value").toString());
        boolean isPct = Boolean.TRUE.equals(req.get("isPercentage"));

        if ("ITEM".equals(type)) {
            String lineId = (String) req.get("lineId");
            order.getLines().stream()
                    .filter(l -> l.getId().equals(lineId))
                    .findFirst()
                    .ifPresent(l -> {
                        BigDecimal disc = isPct
                                ? l.getUnitPrice().multiply(value).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                                : value;
                        l.setDiscountAmount(disc);
                        l.recomputeVat();
                    });
        } else {
            // ORDER-level discount: spread proportionally across all lines.
            BigDecimal gross = order.getLines().stream()
                    .map(PosOrderLine::getLineGross)
                    .filter(g -> g != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (gross.signum() == 0) return orderToMap(order);
            BigDecimal totalDisc = isPct
                    ? gross.multiply(value).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                    : value;
            for (PosOrderLine l : order.getLines()) {
                if (l.getLineGross() == null || l.getLineGross().signum() == 0) continue;
                BigDecimal share = l.getLineGross().divide(gross, 4, RoundingMode.HALF_UP)
                        .multiply(totalDisc).setScale(2, RoundingMode.HALF_UP);
                l.setDiscountAmount(l.getDiscountAmount().add(share));
                l.recomputeVat();
            }
        }
        recomputeTotals(order);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    @Transactional
    public Map<String, Object> clearDiscount(String orderId) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        for (PosOrderLine l : order.getLines()) {
            l.setDiscountAmount(BigDecimal.ZERO);
            l.recomputeVat();
        }
        recomputeTotals(order);
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    // ── Combined payment ─────────────────────────────────────────────────────

    /**
     * Pay with multiple methods. Body: { payments: [{method, amount}], tipAmount?, buyerNip? }
     * The sum of payment amounts must equal totalGross + tipAmount.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> payOrderMulti(String orderId, Map<String, Object> req) {
        PosOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() == PosOrder.Status.PAID) throw new BadRequestException("Already paid");
        if (order.getStatus() == PosOrder.Status.VOIDED) throw new BadRequestException("Order voided");

        if (req.get("tipAmount") != null) order.setTipAmount(new BigDecimal(req.get("tipAmount").toString()));
        if (req.get("buyerNip") != null) order.setBuyerNip((String) req.get("buyerNip"));
        if (req.get("fiscalReceiptNumber") != null) order.setFiscalReceiptNumber((String) req.get("fiscalReceiptNumber"));

        List<Map<String, Object>> payments = (List<Map<String, Object>>) req.get("payments");
        if (payments == null || payments.isEmpty()) throw new BadRequestException("At least one payment method required");

        BigDecimal required = order.getTotalGross().add(order.getTipAmount() != null ? order.getTipAmount() : BigDecimal.ZERO);
        BigDecimal paid = BigDecimal.ZERO;
        for (Map<String, Object> p : payments) {
            BigDecimal amt = new BigDecimal(p.get("amount").toString());
            paid = paid.add(amt);
        }
        if (paid.compareTo(required.setScale(2, RoundingMode.HALF_UP)) < 0) {
            throw new BadRequestException(String.format("Payment total %.2f is less than order total %.2f", paid, required));
        }

        // Save each payment leg.
        boolean first = true;
        for (Map<String, Object> p : payments) {
            PosOrderPayment leg = new PosOrderPayment();
            leg.setOrderId(orderId);
            leg.setMethod(parseMethod((String) p.getOrDefault("method", "CASH")));
            leg.setAmount(new BigDecimal(p.get("amount").toString()));
            if (p.get("reference") != null) leg.setReference((String) p.get("reference"));
            paymentRepository.save(leg);
            if (first) { order.setPaymentMethod(leg.getMethod()); first = false; }
        }
        if (req.get("amountTendered") != null) order.setAmountTendered(new BigDecimal(req.get("amountTendered").toString()));
        order.setStatus(PosOrder.Status.PAID);
        order.setPaidAt(Instant.now());
        order = orderRepository.save(order);
        return orderToMap(order);
    }

    // ── Customer display ─────────────────────────────────────────────────────

    /** Returns the most recently modified OPEN order for display on the customer screen. */
    @Transactional(readOnly = true)
    public Map<String, Object> currentDisplayOrder() {
        List<PosOrder> open = orderRepository.findAllOpen();
        if (open.isEmpty()) return null;
        // Show the most recently updated open order.
        return orderToMap(open.get(open.size() - 1));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void addLineToOrder(PosOrder order, Map<String, Object> req) {
        String menuItemId = (String) req.get("menuItemId");
        MenuItem item = menuItemId != null
                ? menuItemRepository.findById(menuItemId).orElse(null)
                : null;

        PosOrderLine line = new PosOrderLine();
        line.setOrder(order);
        line.setMenuItemId(menuItemId);
        line.setItemName(item != null ? item.getName() : (String) req.getOrDefault("itemName", "Item"));
        line.setQuantity(new BigDecimal(req.getOrDefault("quantity", "1").toString()));
        BigDecimal price = item != null
                ? item.getSellPrice()
                : new BigDecimal(req.getOrDefault("unitPrice", "0").toString());
        line.setUnitPrice(price);
        BigDecimal vatRate = item != null
                ? item.getVatRatePct()
                : new BigDecimal(req.getOrDefault("vatRatePct", "8").toString());
        line.setVatRatePct(vatRate);
        if (req.get("discountAmount") != null) {
            line.setDiscountAmount(new BigDecimal(req.get("discountAmount").toString()));
        }
        if (req.get("note") != null) line.setNote((String) req.get("note"));
        line.recomputeVat();
        order.getLines().add(line);
    }

    private void recomputeTotals(PosOrder order) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        for (PosOrderLine l : order.getLines()) {
            if (l.getLineGross() != null) gross = gross.add(l.getLineGross());
            if (l.getVatAmount() != null) vat = vat.add(l.getVatAmount());
        }
        order.setTotalGross(gross.setScale(2, RoundingMode.HALF_UP));
        order.setTotalVat(vat.setScale(2, RoundingMode.HALF_UP));
    }

    private SupplierInvoicePayment.PaymentMethod parseMethod(String s) {
        try {
            return SupplierInvoicePayment.PaymentMethod.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return SupplierInvoicePayment.PaymentMethod.CASH;
        }
    }

    private Map<String, Object> tableToMap(PosTable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("area", t.getArea());
        m.put("gridX", t.getGridX());
        m.put("gridY", t.getGridY());
        m.put("seats", t.getSeats());
        m.put("active", t.isActive());
        return m;
    }

    private Map<String, Object> orderToMap(PosOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("tableId", o.getTableId());
        m.put("cashierId", o.getCashierId());
        m.put("status", o.getStatus().name());
        m.put("orderType", o.getOrderType());
        m.put("customerName", o.getCustomerName());
        m.put("customerPhone", o.getCustomerPhone());
        m.put("deliveryAddress", o.getDeliveryAddress());
        m.put("specialRequests", o.getSpecialRequests());
        m.put("covers", o.getCovers());
        m.put("orderNote", o.getOrderNote());
        m.put("totalGross", o.getTotalGross().doubleValue());
        m.put("totalVat", o.getTotalVat().doubleValue());
        BigDecimal tip = o.getTipAmount() != null ? o.getTipAmount() : BigDecimal.ZERO;
        m.put("tipAmount", tip.doubleValue());
        m.put("paymentTotal", o.getTotalGross().add(tip).doubleValue());
        m.put("paymentMethod", o.getPaymentMethod() != null ? o.getPaymentMethod().name() : null);
        m.put("amountTendered", o.getAmountTendered() != null ? o.getAmountTendered().doubleValue() : null);
        m.put("fiscalReceiptNumber", o.getFiscalReceiptNumber());
        m.put("buyerNip", o.getBuyerNip());
        m.put("parkedAt", o.getParkedAt() != null ? o.getParkedAt().toString() : null);
        m.put("parkedNote", o.getParkedNote());
        m.put("openedAt", o.getOpenedAt() != null ? o.getOpenedAt().toString() : null);
        m.put("paidAt", o.getPaidAt() != null ? o.getPaidAt().toString() : null);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PosOrderLine l : o.getLines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.getId());
            lm.put("menuItemId", l.getMenuItemId());
            lm.put("itemName", l.getItemName());
            lm.put("quantity", l.getQuantity().doubleValue());
            lm.put("unitPrice", l.getUnitPrice().doubleValue());
            lm.put("vatRatePct", l.getVatRatePct().doubleValue());
            lm.put("discountAmount", l.getDiscountAmount().doubleValue());
            lm.put("lineGross", l.getLineGross() != null ? l.getLineGross().doubleValue() : null);
            lm.put("vatNetAmount", l.getVatNetAmount() != null ? l.getVatNetAmount().doubleValue() : null);
            lm.put("vatAmount", l.getVatAmount() != null ? l.getVatAmount().doubleValue() : null);
            lm.put("note", l.getNote());
            lines.add(lm);
        }
        m.put("lines", lines);
        // Include payment legs (for combined payment orders).
        List<Map<String, Object>> pmts = paymentRepository.findByOrderIdOrderByProcessedAtAsc(o.getId())
                .stream().map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("method", p.getMethod().name());
                    pm.put("amount", p.getAmount().doubleValue());
                    pm.put("reference", p.getReference());
                    pm.put("processedAt", p.getProcessedAt().toString());
                    return pm;
                }).toList();
        m.put("payments", pmts);
        return m;
    }
}
