package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.MenuItem;
import com.saffron.cashflow.domain.PosOrder;
import com.saffron.cashflow.domain.PosOrderLine;
import com.saffron.cashflow.domain.PosTable;
import com.saffron.cashflow.domain.SupplierInvoicePayment;
import com.saffron.cashflow.repository.MenuCategoryRepository;
import com.saffron.cashflow.repository.MenuItemRepository;
import com.saffron.cashflow.repository.PosOrderRepository;
import com.saffron.cashflow.repository.PosTableRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PosOrderService {

    private final PosOrderRepository orderRepository;
    private final PosTableRepository tableRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuCategoryRepository categoryRepository;

    public PosOrderService(PosOrderRepository orderRepository,
                           PosTableRepository tableRepository,
                           MenuItemRepository menuItemRepository,
                           MenuCategoryRepository categoryRepository) {
        this.orderRepository = orderRepository;
        this.tableRepository = tableRepository;
        this.menuItemRepository = menuItemRepository;
        this.categoryRepository = categoryRepository;
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
        List<Map<String, Object>> result = new ArrayList<>();
        for (MenuItem i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("categoryId", i.getCategoryId());
            m.put("categoryName", catNames.getOrDefault(i.getCategoryId(), i.getCategoryId()));
            m.put("categorySortOrder", catOrder.getOrDefault(i.getCategoryId(), 999));
            m.put("name", i.getName());
            m.put("sku", i.getSku());
            m.put("sellPrice", i.getSellPrice().doubleValue());
            m.put("vatRatePct", i.getVatRatePct().doubleValue());
            m.put("dietaryTags", i.getDietaryTags());
            m.put("allergens", i.getAllergens());
            m.put("imagePath", i.getImagePath());
            m.put("posDisplayOrder", i.getPosDisplayOrder());
            result.add(m);
        }
        return result;
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
        order.setStatus(PosOrder.Status.PAID);
        order.setPaidAt(Instant.now());
        order = orderRepository.save(order);
        return orderToMap(order);
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
        m.put("covers", o.getCovers());
        m.put("orderNote", o.getOrderNote());
        m.put("totalGross", o.getTotalGross().doubleValue());
        m.put("totalVat", o.getTotalVat().doubleValue());
        m.put("paymentMethod", o.getPaymentMethod() != null ? o.getPaymentMethod().name() : null);
        m.put("amountTendered", o.getAmountTendered() != null ? o.getAmountTendered().doubleValue() : null);
        m.put("fiscalReceiptNumber", o.getFiscalReceiptNumber());
        m.put("buyerNip", o.getBuyerNip());
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
        return m;
    }
}
