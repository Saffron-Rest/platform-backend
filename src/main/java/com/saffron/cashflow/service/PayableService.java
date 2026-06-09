package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.ExpenseCategory;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.StockItem;
import com.saffron.cashflow.domain.StockMovement;
import com.saffron.cashflow.domain.StockMovementType;
import com.saffron.cashflow.domain.Supplier;
import com.saffron.cashflow.domain.SupplierInvoice;
import com.saffron.cashflow.domain.SupplierInvoiceLine;
import com.saffron.cashflow.domain.SupplierInvoicePayment;
import com.saffron.cashflow.domain.SupplierInvoiceStatus;
import com.saffron.cashflow.repository.StockItemRepository;
import com.saffron.cashflow.repository.StockMovementRepository;
import com.saffron.cashflow.repository.SupplierInvoicePaymentRepository;
import com.saffron.cashflow.repository.SupplierInvoiceRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.ConflictException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accounts-payable / supplier-credit business logic.
 *
 * <p>Two booking events are kept strictly separate so the P&amp;L and
 * cash position read like an accountant expects:</p>
 * <ol>
 *   <li><b>Invoice</b> — created on the delivery date. Hits stock
 *       (PURCHASE movements for any line that points at a stock item)
 *       and is rolled into COGS by {@link ProfitLossService} via the
 *       invoice's {@code invoiceDate} and {@code category}. Cash is
 *       <em>not</em> moved.</li>
 *   <li><b>Payment</b> — recorded against the invoice with its own
 *       {@code paymentDate}. Decrements the outstanding balance and is
 *       what the Treasury / Finance ledger picks up. P&amp;L is not
 *       affected by payments.</li>
 * </ol>
 *
 * <p>Voiding an unpaid invoice cancels the stock PURCHASE movements
 * via paired REVERT rows so inventory is restored to its pre-delivery
 * state. Voiding is refused once any payment is on file — the user has
 * to first reverse the payments, then void.</p>
 */
@Service
public class PayableService {

    private static final int MAX_NOTES = 1000;

    private final SupplierInvoiceRepository invoiceRepository;
    private final SupplierInvoicePaymentRepository paymentRepository;
    private final SupplierService supplierService;
    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditService auditService;

    public PayableService(
            SupplierInvoiceRepository invoiceRepository,
            SupplierInvoicePaymentRepository paymentRepository,
            SupplierService supplierService,
            StockItemRepository stockItemRepository,
            StockMovementRepository stockMovementRepository,
            AuditService auditService) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.supplierService = supplierService;
        this.stockItemRepository = stockItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.auditService = auditService;
    }

    // ========================================================================
    // Read paths
    // ========================================================================

    /**
     * Build the payables list view.
     *
     * @param statusFilter one of "OUTSTANDING" (UNPAID + PARTIAL — the
     *                     default), "PAID", "VOID", or "ALL".
     * @param supplierId   optional supplier filter.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String statusFilter, String supplierId) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_VIEW, Permission.PAYABLES_MANAGE);
        List<SupplierInvoiceStatus> statuses = parseStatusFilter(statusFilter);

        List<SupplierInvoice> rows;
        if (supplierId != null && !supplierId.isBlank()) {
            rows = invoiceRepository.findBySupplierId(supplierId);
            if (statuses != null) {
                rows = rows.stream().filter(i -> statuses.contains(i.getStatus())).toList();
            }
        } else if (statuses == null) {
            rows = invoiceRepository.findAllOrdered();
        } else {
            rows = invoiceRepository.findByStatuses(statuses);
        }

        LocalDate today = LocalDate.now();
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal overdueOutstanding = BigDecimal.ZERO;
        int overdueCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (SupplierInvoice inv : rows) {
            BigDecimal out = inv.outstanding();
            boolean overdue = inv.getStatus() != SupplierInvoiceStatus.PAID
                    && inv.getStatus() != SupplierInvoiceStatus.VOID
                    && inv.getDueDate() != null
                    && inv.getDueDate().isBefore(today)
                    && out.signum() > 0;
            if (inv.getStatus() != SupplierInvoiceStatus.VOID
                    && inv.getStatus() != SupplierInvoiceStatus.PAID) {
                totalOutstanding = totalOutstanding.add(out);
                if (overdue) {
                    overdueOutstanding = overdueOutstanding.add(out);
                    overdueCount++;
                }
            }
            items.add(toListMap(inv, today, overdue));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("count", items.size());
        totals.put("outstanding", totalOutstanding);
        totals.put("overdueAmount", overdueOutstanding);
        totals.put("overdueCount", overdueCount);
        result.put("totals", totals);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_VIEW, Permission.PAYABLES_MANAGE);
        SupplierInvoice inv = require(id);
        return toDetailMap(inv);
    }

    /** Aging buckets for the dashboard summary. */
    @Transactional(readOnly = true)
    public Map<String, Object> aging() {
        AuthHelper.requireAdminOr(Permission.PAYABLES_VIEW, Permission.PAYABLES_MANAGE);
        List<SupplierInvoice> outstanding = invoiceRepository.findByStatuses(
                List.of(SupplierInvoiceStatus.UNPAID, SupplierInvoiceStatus.PARTIAL));

        LocalDate today = LocalDate.now();
        BigDecimal current = BigDecimal.ZERO;
        BigDecimal d1to7 = BigDecimal.ZERO;
        BigDecimal d8to30 = BigDecimal.ZERO;
        BigDecimal d31to60 = BigDecimal.ZERO;
        BigDecimal d60plus = BigDecimal.ZERO;

        for (SupplierInvoice inv : outstanding) {
            BigDecimal out = inv.outstanding();
            if (out.signum() <= 0) continue;
            long daysPastDue = inv.getDueDate() == null
                    ? 0
                    : java.time.temporal.ChronoUnit.DAYS.between(inv.getDueDate(), today);
            if (daysPastDue <= 0) current = current.add(out);
            else if (daysPastDue <= 7) d1to7 = d1to7.add(out);
            else if (daysPastDue <= 30) d8to30 = d8to30.add(out);
            else if (daysPastDue <= 60) d31to60 = d31to60.add(out);
            else d60plus = d60plus.add(out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", current);
        result.put("d1to7", d1to7);
        result.put("d8to30", d8to30);
        result.put("d31to60", d31to60);
        result.put("d60plus", d60plus);
        result.put("total", current.add(d1to7).add(d8to30).add(d31to60).add(d60plus));
        return result;
    }

    // ========================================================================
    // Invoice lifecycle
    // ========================================================================

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();

        String supplierId = stringOrNull(body.get("supplierId"));
        if (supplierId == null) throw new BadRequestException("supplierId is required");
        Supplier supplier = supplierService.require(supplierId);
        if (!supplier.isActive()) {
            throw new BadRequestException("Supplier is deactivated. Reactivate it first.");
        }

        SupplierInvoice inv = new SupplierInvoice();
        inv.setSupplier(supplier);
        inv.setInvoiceNumber(stringOrNull(body.get("invoiceNumber")));
        inv.setInvoiceDate(parseRequiredDate(body.get("invoiceDate"), "invoiceDate"));
        // Default due date to invoice + supplier terms when omitted.
        LocalDate due = parseOptionalDate(body.get("dueDate"));
        if (due == null) {
            due = inv.getInvoiceDate().plusDays(Math.max(0, supplier.getPaymentTermsDays()));
        }
        inv.setDueDate(due);
        inv.setCategory(parseCategory(body.get("category"), ExpenseCategory.SUPPLIER));
        inv.setNotes(stringOrNull(body.get("notes")));
        inv.setCreatedBy(user.id());

        List<Map<String, Object>> rawLines = asLineList(body.get("lines"));
        if (rawLines.isEmpty()) {
            throw new BadRequestException("At least one line is required");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        int order = 0;
        List<SupplierInvoiceLine> attachedLines = new ArrayList<>();
        for (Map<String, Object> raw : rawLines) {
            SupplierInvoiceLine line = buildLine(raw, order++);
            line.setInvoice(inv);
            attachedLines.add(line);
            subtotal = subtotal.add(line.getLineTotal());
        }
        inv.getLines().addAll(attachedLines);
        inv.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));

        // If any line carries a vatPct, compute invoice VAT as Σ line vatAmounts
        // (this is the standard per-line VAT model). Otherwise fall back to the
        // explicit flat PLN amount the legacy form supplies.
        boolean hasLineVat = attachedLines.stream().anyMatch(l -> l.getVatPct() != null);
        BigDecimal vat;
        if (hasLineVat) {
            vat = attachedLines.stream()
                    .map(l -> l.getVatAmount() != null ? l.getVatAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            BigDecimal explicit = parseOptionalAmount(body.get("vat"));
            vat = explicit != null ? explicit : BigDecimal.ZERO;
        }
        inv.setVat(vat.setScale(2, RoundingMode.HALF_UP));

        // Total can be supplied explicitly (handles supplier rounding /
        // discounts that don't match Σ lines + VAT exactly). Otherwise
        // we compute it from subtotal + VAT.
        BigDecimal explicitTotal = parseOptionalAmount(body.get("total"));
        BigDecimal total = explicitTotal != null
                ? explicitTotal
                : inv.getSubtotal().add(inv.getVat());
        if (total.signum() <= 0) {
            throw new BadRequestException("Invoice total must be greater than zero");
        }
        inv.setTotal(total.setScale(2, RoundingMode.HALF_UP));
        inv.setAmountPaid(BigDecimal.ZERO);
        inv.setStatus(SupplierInvoiceStatus.UNPAID);

        if (inv.getInvoiceDate().isAfter(LocalDate.now().plusDays(1))) {
            throw new BadRequestException("Invoice date cannot be in the future");
        }
        if (inv.getDueDate().isBefore(inv.getInvoiceDate())) {
            throw new BadRequestException("Due date cannot precede the invoice date");
        }

        inv = invoiceRepository.save(inv);

        // Post stock PURCHASE movements for every stock-linked line.
        // Recording them after the save so the line ids exist on the
        // movement reference (REVERT lookup uses the line id).
        for (SupplierInvoiceLine line : inv.getLines()) {
            if (line.getStockItemId() == null) continue;
            postStockPurchase(line, inv, user.id());
        }

        auditService.log(user.id(), AuditAction.CREATE, "SupplierInvoice", inv.getId(),
                Map.of(
                        "supplier", supplier.getName(),
                        "invoiceNumber", String.valueOf(inv.getInvoiceNumber()),
                        "invoiceDate", inv.getInvoiceDate().toString(),
                        "total", inv.getTotal(),
                        "lines", inv.getLines().size()));

        return toDetailMap(inv);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        SupplierInvoice inv = require(id);
        if (inv.getStatus() == SupplierInvoiceStatus.VOID) {
            throw new ConflictException("Cannot edit a voided invoice");
        }

        // Fields that are always safe to touch (no money/stock impact).
        if (body.containsKey("invoiceNumber")) {
            inv.setInvoiceNumber(stringOrNull(body.get("invoiceNumber")));
        }
        if (body.containsKey("notes")) {
            inv.setNotes(stringOrNull(body.get("notes")));
        }
        if (body.containsKey("dueDate")) {
            LocalDate due = parseOptionalDate(body.get("dueDate"));
            if (due == null) throw new BadRequestException("dueDate cannot be cleared");
            if (due.isBefore(inv.getInvoiceDate())) {
                throw new BadRequestException("Due date cannot precede the invoice date");
            }
            inv.setDueDate(due);
        }
        if (body.containsKey("category")) {
            inv.setCategory(parseCategory(body.get("category"), inv.getCategory()));
        }

        // Total edits — allowed only when no payment has been recorded
        // and the new total is > 0. Editing a partially-paid invoice is
        // a recipe for reconciliation pain, so we refuse it.
        if (body.containsKey("total")) {
            if (inv.getAmountPaid().signum() > 0) {
                throw new ConflictException(
                        "Cannot change total after a payment has been recorded. Reverse payments first.");
            }
            BigDecimal newTotal = parseOptionalAmount(body.get("total"));
            if (newTotal == null || newTotal.signum() <= 0) {
                throw new BadRequestException("Invoice total must be greater than zero");
            }
            inv.setTotal(newTotal.setScale(2, RoundingMode.HALF_UP));
        }
        if (body.containsKey("vat")) {
            BigDecimal vat = parseOptionalAmount(body.get("vat"));
            inv.setVat(vat == null ? BigDecimal.ZERO : vat);
        }

        recomputeStatus(inv);
        invoiceRepository.save(inv);
        auditService.log(user.id(), AuditAction.UPDATE, "SupplierInvoice", inv.getId(),
                Map.of("invoiceNumber", String.valueOf(inv.getInvoiceNumber())));
        return toDetailMap(inv);
    }

    /**
     * Replace the full lines collection of a non-void, unpaid invoice.
     *
     * <p>Rules:
     * <ul>
     *   <li>Refused once any payment is on file — reverse payments first.</li>
     *   <li>Old stock-PURCHASE movements are reverted before new ones are posted,
     *       so inventory stays consistent.</li>
     *   <li>Invoice subtotal / VAT / total are recomputed from the new lines
     *       (same logic as {@link #create}).</li>
     * </ul></p>
     */
    @Transactional
    public Map<String, Object> updateLines(String id, Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        SupplierInvoice inv = require(id);

        if (inv.getStatus() == SupplierInvoiceStatus.VOID) {
            throw new ConflictException("Cannot edit a voided invoice");
        }
        if (inv.getAmountPaid().signum() > 0) {
            throw new ConflictException(
                    "Cannot change lines after a payment has been recorded. Reverse payments first.");
        }

        List<Map<String, Object>> rawLines = asLineList(body.get("lines"));
        if (rawLines.isEmpty()) {
            throw new BadRequestException("At least one line is required");
        }

        // Revert stock purchases for every line that posted a movement.
        for (SupplierInvoiceLine line : inv.getLines()) {
            if (line.getStockMovementId() != null) {
                revertStockPurchase(line, inv, user.id(), "Line edit");
            }
        }

        // Orphan-remove the old lines.
        inv.getLines().clear();

        // Build and attach the replacement lines.
        BigDecimal subtotal = BigDecimal.ZERO;
        int order = 0;
        List<SupplierInvoiceLine> newLines = new ArrayList<>();
        for (Map<String, Object> raw : rawLines) {
            SupplierInvoiceLine line = buildLine(raw, order++);
            line.setInvoice(inv);
            newLines.add(line);
            subtotal = subtotal.add(line.getLineTotal());
        }
        inv.getLines().addAll(newLines);
        inv.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));

        // VAT: per-line if any line carries a vatPct, flat PLN override otherwise.
        boolean hasLineVat = newLines.stream().anyMatch(l -> l.getVatPct() != null);
        BigDecimal vat;
        if (hasLineVat) {
            vat = newLines.stream()
                    .map(l -> l.getVatAmount() != null ? l.getVatAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            BigDecimal explicit = parseOptionalAmount(body.get("vat"));
            vat = explicit != null ? explicit : BigDecimal.ZERO;
        }
        inv.setVat(vat.setScale(2, RoundingMode.HALF_UP));

        // Total: explicit override or subtotal + VAT.
        BigDecimal explicitTotal = parseOptionalAmount(body.get("total"));
        BigDecimal total = explicitTotal != null ? explicitTotal : inv.getSubtotal().add(inv.getVat());
        if (total.signum() <= 0) {
            throw new BadRequestException("Invoice total must be greater than zero");
        }
        inv.setTotal(total.setScale(2, RoundingMode.HALF_UP));

        inv = invoiceRepository.save(inv);

        // Post new stock PURCHASE movements (after save so line IDs are assigned).
        for (SupplierInvoiceLine line : inv.getLines()) {
            if (line.getStockItemId() == null) continue;
            postStockPurchase(line, inv, user.id());
        }

        auditService.log(user.id(), AuditAction.UPDATE, "SupplierInvoice", inv.getId(),
                Map.of("action", "lines_updated",
                        "invoiceNumber", String.valueOf(inv.getInvoiceNumber()),
                        "lineCount", inv.getLines().size(),
                        "newTotal", inv.getTotal()));
        return toDetailMap(inv);
    }

    @Transactional
    public Map<String, Object> voidInvoice(String id, String reason) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        SupplierInvoice inv = require(id);
        if (inv.getStatus() == SupplierInvoiceStatus.VOID) {
            throw new ConflictException("Already voided");
        }
        if (inv.getAmountPaid().signum() > 0) {
            throw new ConflictException(
                    "Cannot void an invoice that has payments. Reverse the payments first.");
        }

        // Revert any stock PURCHASE movements posted by this invoice's
        // lines so inventory is restored to the pre-delivery state.
        for (SupplierInvoiceLine line : inv.getLines()) {
            if (line.getStockMovementId() == null) continue;
            revertStockPurchase(line, inv, user.id(), reason);
        }

        inv.setStatus(SupplierInvoiceStatus.VOID);
        inv.setVoidedAt(Instant.now());
        inv.setVoidedBy(user.id());
        invoiceRepository.save(inv);

        auditService.log(user.id(), AuditAction.DELETE, "SupplierInvoice", inv.getId(),
                Map.of(
                        "supplier", inv.getSupplier().getName(),
                        "invoiceNumber", String.valueOf(inv.getInvoiceNumber()),
                        "reason", reason == null ? "" : reason));
        return toDetailMap(inv);
    }

    // ========================================================================
    // Payments
    // ========================================================================

    @Transactional
    public Map<String, Object> recordPayment(String invoiceId, Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        SupplierInvoice inv = require(invoiceId);
        if (inv.getStatus() == SupplierInvoiceStatus.VOID) {
            throw new ConflictException("Cannot pay a voided invoice");
        }
        if (inv.getStatus() == SupplierInvoiceStatus.PAID) {
            throw new ConflictException("Invoice is already fully paid");
        }

        BigDecimal amount = parseOptionalAmount(body.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Payment amount must be greater than zero");
        }
        BigDecimal remaining = inv.outstanding();
        if (amount.compareTo(remaining) > 0) {
            throw new BadRequestException(
                    "Payment exceeds outstanding balance (" + remaining.toPlainString() + ")");
        }

        SupplierInvoicePayment p = new SupplierInvoicePayment();
        p.setInvoice(inv);
        p.setPaymentDate(parseRequiredDate(body.get("paymentDate"), "paymentDate"));
        if (p.getPaymentDate().isAfter(LocalDate.now().plusDays(1))) {
            throw new BadRequestException("Payment date cannot be in the future");
        }
        if (p.getPaymentDate().isBefore(inv.getInvoiceDate())) {
            throw new BadRequestException(
                    "Payment date cannot precede the invoice date ("
                            + inv.getInvoiceDate() + ")");
        }
        p.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        p.setMethod(parseMethod(body.get("method"), SupplierInvoicePayment.PaymentMethod.BANK_TRANSFER));
        p.setReference(stringOrNull(body.get("reference")));
        String notes = stringOrNull(body.get("notes"));
        if (notes != null && notes.length() > MAX_NOTES) {
            throw new BadRequestException("Notes too long");
        }
        p.setNotes(notes);
        p.setCreatedBy(user.id());
        p = paymentRepository.save(p);

        inv.setAmountPaid(inv.getAmountPaid().add(p.getAmount()).setScale(2, RoundingMode.HALF_UP));
        recomputeStatus(inv);
        invoiceRepository.save(inv);

        auditService.log(user.id(), AuditAction.CREATE, "SupplierInvoicePayment", p.getId(),
                Map.of(
                        "invoiceId", inv.getId(),
                        "supplier", inv.getSupplier().getName(),
                        "amount", p.getAmount(),
                        "method", p.getMethod().name(),
                        "balanceAfter", inv.outstanding(),
                        "newStatus", inv.getStatus().name()));
        return toDetailMap(inv);
    }

    @Transactional
    public Map<String, Object> deletePayment(String invoiceId, String paymentId) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        SupplierInvoice inv = require(invoiceId);
        SupplierInvoicePayment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        if (p.getInvoice() == null || !p.getInvoice().getId().equals(inv.getId())) {
            throw new BadRequestException("Payment does not belong to this invoice");
        }

        BigDecimal restored = inv.getAmountPaid().subtract(p.getAmount());
        if (restored.signum() < 0) restored = BigDecimal.ZERO;
        inv.setAmountPaid(restored.setScale(2, RoundingMode.HALF_UP));
        inv.getPayments().remove(p);
        paymentRepository.delete(p);
        recomputeStatus(inv);
        invoiceRepository.save(inv);

        auditService.log(user.id(), AuditAction.DELETE, "SupplierInvoicePayment", p.getId(),
                Map.of(
                        "invoiceId", inv.getId(),
                        "amount", p.getAmount(),
                        "newStatus", inv.getStatus().name(),
                        "newOutstanding", inv.outstanding()));
        return toDetailMap(inv);
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private SupplierInvoice require(String id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
    }

    private SupplierInvoiceLine buildLine(Map<String, Object> raw, int sortOrder) {
        SupplierInvoiceLine line = new SupplierInvoiceLine();
        line.setSortOrder(sortOrder);
        String stockItemId = stringOrNull(raw.get("stockItemId"));
        line.setStockItemId(stockItemId);
        String description = stringOrNull(raw.get("description"));
        if (description == null && stockItemId != null) {
            description = stockItemRepository.findById(stockItemId)
                    .map(StockItem::getName)
                    .orElse(null);
        }
        if (description == null || description.isBlank()) {
            throw new BadRequestException("Each line needs a description");
        }
        line.setDescription(description);

        BigDecimal qty = parseOptionalAmount(raw.get("quantity"));
        if (qty == null) qty = BigDecimal.ONE;
        if (qty.signum() <= 0) {
            throw new BadRequestException("Line quantity must be greater than zero");
        }
        line.setQuantity(qty.setScale(3, RoundingMode.HALF_UP));

        String unit = stringOrNull(raw.get("unit"));
        if (unit == null && stockItemId != null) {
            unit = stockItemRepository.findById(stockItemId)
                    .map(StockItem::getUnit)
                    .orElse("pcs");
        }
        line.setUnit(unit == null || unit.isBlank() ? "pcs" : unit);

        BigDecimal unitCost = parseOptionalAmount(raw.get("unitCost"));
        if (unitCost == null) unitCost = BigDecimal.ZERO;
        if (unitCost.signum() < 0) {
            throw new BadRequestException("Unit cost cannot be negative");
        }
        line.setUnitCost(unitCost.setScale(4, RoundingMode.HALF_UP));

        // Gross = quantity × unitCost (before discount)
        BigDecimal gross = line.getQuantity().multiply(line.getUnitCost());

        // Discount
        String discountType = stringOrNull(raw.get("discountType"));
        BigDecimal discountValue = parseOptionalAmount(raw.get("discountValue"));
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (discountType != null && discountValue != null && discountValue.signum() > 0) {
            if ("PERCENTAGE".equals(discountType)) {
                if (discountValue.compareTo(BigDecimal.valueOf(100)) > 0)
                    throw new BadRequestException("Discount percentage cannot exceed 100%");
                discountAmount = gross.multiply(discountValue)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else if ("AMOUNT".equals(discountType)) {
                discountAmount = discountValue.min(gross).setScale(2, RoundingMode.HALF_UP);
            } else {
                throw new BadRequestException("discountType must be PERCENTAGE or AMOUNT");
            }
            line.setDiscountType(discountType);
            line.setDiscountValue(discountValue.setScale(4, RoundingMode.HALF_UP));
            line.setDiscountAmount(discountAmount);
        }

        // Net line total = gross − discount (explicit override accepted for rounding)
        BigDecimal lineTotal = parseOptionalAmount(raw.get("lineTotal"));
        if (lineTotal == null) {
            lineTotal = gross.subtract(discountAmount);
        }
        if (lineTotal.signum() < 0) {
            throw new BadRequestException("Line total cannot be negative");
        }
        line.setLineTotal(lineTotal.setScale(2, RoundingMode.HALF_UP));

        // VAT on post-discount net
        BigDecimal vatPct = parseOptionalAmount(raw.get("vatPct"));
        if (vatPct != null) {
            if (vatPct.signum() < 0) throw new BadRequestException("VAT rate cannot be negative");
            line.setVatPct(vatPct.setScale(2, RoundingMode.HALF_UP));
            line.setVatAmount(lineTotal.multiply(vatPct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        return line;
    }

    private void postStockPurchase(SupplierInvoiceLine line, SupplierInvoice inv, String userId) {
        StockItem item = stockItemRepository.findById(line.getStockItemId()).orElse(null);
        if (item == null) {
            // Stock item was deleted between picker render and submit —
            // record a clear error rather than silently dropping the
            // movement. We've already committed the invoice header so
            // throw to roll the whole thing back.
            throw new BadRequestException(
                    "Stock item " + line.getStockItemId() + " no longer exists");
        }

        BigDecimal current = item.getOnHand() == null ? BigDecimal.ZERO : item.getOnHand();
        BigDecimal next = current.add(line.getQuantity()).setScale(3, RoundingMode.HALF_UP);
        item.setOnHand(next);
        item.setLastMovementAt(Instant.now());
        // Keep the unit_cost on the stock item synced with the most
        // recent supplier price so recipe costing stays accurate.
        if (line.getUnitCost() != null && line.getUnitCost().signum() > 0) {
            item.setUnitCost(line.getUnitCost().setScale(2, RoundingMode.HALF_UP));
        }
        stockItemRepository.save(item);

        StockMovement m = new StockMovement();
        m.setStockItemId(item.getId());
        m.setType(StockMovementType.PURCHASE);
        m.setDelta(line.getQuantity().setScale(3, RoundingMode.HALF_UP));
        m.setBalanceAfter(next);
        m.setReferenceType("SUPPLIER_INVOICE");
        m.setReferenceId(inv.getId() + ":" + line.getId());
        m.setReason("Credit purchase · " + inv.getSupplier().getName()
                + (inv.getInvoiceNumber() == null || inv.getInvoiceNumber().isBlank()
                        ? "" : " · " + inv.getInvoiceNumber()));
        m.setUserId(userId);
        m = stockMovementRepository.save(m);
        line.setStockMovementId(m.getId());
    }

    private void revertStockPurchase(SupplierInvoiceLine line, SupplierInvoice inv,
                                     String userId, String reason) {
        Optional<StockMovement> orig = stockMovementRepository.findById(line.getStockMovementId());
        if (orig.isEmpty()) return;
        StockMovement m = orig.get();
        if (m.isReverted()) return;
        StockItem item = stockItemRepository.findById(m.getStockItemId()).orElse(null);
        if (item == null) return;

        BigDecimal compensating = m.getDelta().negate();
        BigDecimal current = item.getOnHand() == null ? BigDecimal.ZERO : item.getOnHand();
        BigDecimal next = current.add(compensating).setScale(3, RoundingMode.HALF_UP);
        item.setOnHand(next);
        item.setLastMovementAt(Instant.now());
        stockItemRepository.save(item);

        StockMovement revert = new StockMovement();
        revert.setStockItemId(item.getId());
        revert.setType(StockMovementType.REVERT);
        revert.setDelta(compensating);
        revert.setBalanceAfter(next);
        revert.setReferenceType("REVERT");
        revert.setReferenceId(m.getId());
        revert.setReason("Voided supplier invoice"
                + (reason == null || reason.isBlank() ? "" : " · " + reason));
        revert.setUserId(userId);
        stockMovementRepository.save(revert);

        m.setReverted(true);
        m.setRevertedById(userId);
        m.setRevertedAt(Instant.now());
        stockMovementRepository.save(m);
    }

    private void recomputeStatus(SupplierInvoice inv) {
        if (inv.getStatus() == SupplierInvoiceStatus.VOID) return;
        BigDecimal paid = inv.getAmountPaid() == null ? BigDecimal.ZERO : inv.getAmountPaid();
        BigDecimal total = inv.getTotal() == null ? BigDecimal.ZERO : inv.getTotal();
        if (paid.signum() == 0) inv.setStatus(SupplierInvoiceStatus.UNPAID);
        else if (paid.compareTo(total) >= 0) inv.setStatus(SupplierInvoiceStatus.PAID);
        else inv.setStatus(SupplierInvoiceStatus.PARTIAL);
    }

    private static List<SupplierInvoiceStatus> parseStatusFilter(String filter) {
        if (filter == null || filter.isBlank() || "OUTSTANDING".equalsIgnoreCase(filter)) {
            return List.of(SupplierInvoiceStatus.UNPAID, SupplierInvoiceStatus.PARTIAL);
        }
        if ("ALL".equalsIgnoreCase(filter)) return null;
        try {
            return List.of(SupplierInvoiceStatus.valueOf(filter.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status filter: " + filter);
        }
    }

    private static ExpenseCategory parseCategory(Object raw, ExpenseCategory fallback) {
        String s = stringOrNull(raw);
        if (s == null) return fallback;
        try {
            return ExpenseCategory.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown expense category: " + s);
        }
    }

    private static SupplierInvoicePayment.PaymentMethod parseMethod(
            Object raw, SupplierInvoicePayment.PaymentMethod fallback) {
        String s = stringOrNull(raw);
        if (s == null) return fallback;
        try {
            return SupplierInvoicePayment.PaymentMethod.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown payment method: " + s);
        }
    }

    private static LocalDate parseRequiredDate(Object raw, String field) {
        LocalDate d = parseOptionalDate(raw);
        if (d == null) throw new BadRequestException(field + " is required");
        return d;
    }

    private static LocalDate parseOptionalDate(Object raw) {
        String s = stringOrNull(raw);
        if (s == null) return null;
        try { return LocalDate.parse(s); }
        catch (Exception e) { throw new BadRequestException("Invalid date: " + s); }
    }

    private static BigDecimal parseOptionalAmount(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException e) {
            throw new BadRequestException("Invalid amount: " + s);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asLineList(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (!(raw instanceof List<?> list)) {
            throw new BadRequestException("lines must be an array");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            } else {
                throw new BadRequestException("Each line must be an object");
            }
        }
        return out;
    }

    private static String stringOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ─── DTO mappers ────────────────────────────────────────────────────────

    private Map<String, Object> toListMap(SupplierInvoice inv, LocalDate today, boolean overdue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inv.getId());
        m.put("supplier", Map.of(
                "id", inv.getSupplier().getId(),
                "name", inv.getSupplier().getName()));
        if (inv.getInvoiceNumber() != null) m.put("invoiceNumber", inv.getInvoiceNumber());
        m.put("invoiceDate", inv.getInvoiceDate().toString());
        m.put("dueDate", inv.getDueDate().toString());
        m.put("category", inv.getCategory().name());
        m.put("subtotal", inv.getSubtotal());
        m.put("vat", inv.getVat());
        m.put("total", inv.getTotal());
        m.put("amountPaid", inv.getAmountPaid());
        m.put("outstanding", inv.outstanding());
        m.put("status", inv.getStatus().name());
        m.put("overdue", overdue);
        if (overdue) {
            m.put("daysPastDue", java.time.temporal.ChronoUnit.DAYS.between(inv.getDueDate(), today));
        }
        return m;
    }

    private Map<String, Object> toDetailMap(SupplierInvoice inv) {
        Map<String, Object> m = toListMap(inv, LocalDate.now(),
                inv.getStatus() != SupplierInvoiceStatus.PAID
                        && inv.getStatus() != SupplierInvoiceStatus.VOID
                        && inv.getDueDate().isBefore(LocalDate.now())
                        && inv.outstanding().signum() > 0);
        if (inv.getNotes() != null) m.put("notes", inv.getNotes());
        m.put("createdAt", inv.getCreatedAt() != null ? inv.getCreatedAt().toString() : null);
        m.put("updatedAt", inv.getUpdatedAt() != null ? inv.getUpdatedAt().toString() : null);

        List<Map<String, Object>> lines = new ArrayList<>();
        for (SupplierInvoiceLine line : inv.getLines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", line.getId());
            if (line.getStockItemId() != null) lm.put("stockItemId", line.getStockItemId());
            lm.put("description", line.getDescription());
            lm.put("quantity", line.getQuantity());
            lm.put("unit", line.getUnit());
            lm.put("unitCost", line.getUnitCost());
            if (line.getDiscountType() != null) {
                lm.put("discountType", line.getDiscountType());
                lm.put("discountValue", line.getDiscountValue());
                lm.put("discountAmount", line.getDiscountAmount());
            }
            lm.put("lineTotal", line.getLineTotal());
            if (line.getVatPct() != null) lm.put("vatPct", line.getVatPct());
            if (line.getVatAmount() != null) lm.put("vatAmount", line.getVatAmount());
            if (line.getStockMovementId() != null) lm.put("stockMovementId", line.getStockMovementId());
            lines.add(lm);
        }
        m.put("lines", lines);

        List<Map<String, Object>> payments = new ArrayList<>();
        for (SupplierInvoicePayment p : inv.getPayments()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("paymentDate", p.getPaymentDate().toString());
            pm.put("amount", p.getAmount());
            pm.put("method", p.getMethod().name());
            if (p.getReference() != null) pm.put("reference", p.getReference());
            if (p.getNotes() != null) pm.put("notes", p.getNotes());
            pm.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
            payments.add(pm);
        }
        m.put("payments", payments);

        // Attachment metadata
        if (inv.getInvoiceFilePath() != null) {
            m.put("attachment", Map.of(
                    "filename", inv.getInvoiceFilename() != null ? inv.getInvoiceFilename() : "invoice",
                    "filePath", inv.getInvoiceFilePath()));
        }

        // Supplier bank details — shown on the payment screen
        Supplier sup = inv.getSupplier();
        if (sup.getBankAccountNumber() != null || sup.getBankName() != null) {
            Map<String, Object> bank = new LinkedHashMap<>();
            if (sup.getBankAccountNumber() != null) bank.put("accountNumber", sup.getBankAccountNumber());
            if (sup.getBankName() != null) bank.put("bankName", sup.getBankName());
            if (sup.getBankBicSwift() != null) bank.put("bicSwift", sup.getBankBicSwift());
            m.put("supplierBank", bank);
        }

        return m;
    }

    // ─── Invoice file attachment ──────────────────────────────────────────────

    @Transactional
    public Map<String, Object> attachFile(String id, MultipartFile file,
                                          FileStorageService fileStorageService) throws IOException {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        SupplierInvoice inv = require(id);
        if (inv.getStatus() == SupplierInvoiceStatus.VOID) {
            throw new BadRequestException("Cannot attach a file to a voided invoice");
        }
        // Remove old file if one exists
        if (inv.getInvoiceFilePath() != null) {
            Path old = fileStorageService.resolveOperationsFile(inv.getInvoiceFilePath());
            if (old != null) Files.deleteIfExists(old);
        }
        String path = fileStorageService.storeUnderPrefix(file, "payable-invoices");
        inv.setInvoiceFilePath(path);
        inv.setInvoiceFilename(file.getOriginalFilename());
        invoiceRepository.save(inv);
        return Map.of("filename", file.getOriginalFilename(), "filePath", path);
    }

    @Transactional
    public void removeAttachment(String id, FileStorageService fileStorageService) throws IOException {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        SupplierInvoice inv = require(id);
        if (inv.getInvoiceFilePath() == null) return;
        Path old = fileStorageService.resolveOperationsFile(inv.getInvoiceFilePath());
        if (old != null) Files.deleteIfExists(old);
        inv.setInvoiceFilePath(null);
        inv.setInvoiceFilename(null);
        invoiceRepository.save(inv);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveAttachment(String id) {
        AuthHelper.requireOperations();
        SupplierInvoice inv = require(id);
        if (inv.getInvoiceFilePath() == null) {
            throw new NotFoundException("No attachment on this invoice");
        }
        return Map.of(
                "filename", inv.getInvoiceFilename() != null ? inv.getInvoiceFilename() : "invoice",
                "filePath", inv.getInvoiceFilePath());
    }
}
