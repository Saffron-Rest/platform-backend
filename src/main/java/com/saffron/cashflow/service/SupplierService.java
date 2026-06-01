package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.Supplier;
import com.saffron.cashflow.repository.SupplierInvoiceRepository;
import com.saffron.cashflow.repository.SupplierRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.ConflictException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD for vendor master records used by the accounts-payable module.
 *
 * <p>Read access is gated by {@link Permission#PAYABLES_VIEW}; write
 * access by {@link Permission#PAYABLES_MANAGE}. Admins implicitly hold
 * both via {@link AuthHelper#requireAdminOr}.</p>
 *
 * <p>We don't physically delete a {@link Supplier} that has any invoice
 * history — deactivating ({@code active = false}) is enough to hide it
 * from the picker without orphaning the audit trail.</p>
 */
@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierInvoiceRepository invoiceRepository;
    private final AuditService auditService;

    public SupplierService(
            SupplierRepository supplierRepository,
            SupplierInvoiceRepository invoiceRepository,
            AuditService auditService) {
        this.supplierRepository = supplierRepository;
        this.invoiceRepository = invoiceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean includeInactive) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_VIEW, Permission.PAYABLES_MANAGE);
        List<Supplier> rows = includeInactive
                ? supplierRepository.findAllOrdered()
                : supplierRepository.findActiveOrdered();
        return rows.stream().map(SupplierService::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_VIEW, Permission.PAYABLES_MANAGE);
        return toMap(require(id));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        Supplier s = new Supplier();
        applyMutable(s, body);
        validate(s);
        ensureUniqueName(s.getName(), null);
        s.setCreatedBy(user.id());
        s = supplierRepository.save(s);
        auditService.logChange(user.id(), AuditAction.CREATE, "Supplier", s.getId(),
                null, toAuditMap(s), Map.of("name", s.getName()));
        return toMap(s);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        Supplier s = require(id);
        Map<String, Object> before = toAuditMap(s);
        applyMutable(s, body);
        validate(s);
        if (body.containsKey("name")) {
            ensureUniqueName(s.getName(), id);
        }
        s = supplierRepository.save(s);
        auditService.logChange(user.id(), AuditAction.UPDATE, "Supplier", s.getId(),
                before, toAuditMap(s), Map.of("name", s.getName()));
        return toMap(s);
    }

    @Transactional
    public void deactivate(String id) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        Supplier s = require(id);
        if (!s.isActive()) return;
        s.setActive(false);
        supplierRepository.save(s);
        auditService.log(user.id(), AuditAction.UPDATE, "Supplier", s.getId(),
                Map.of("name", s.getName(), "active", false));
    }

    @Transactional
    public void reactivate(String id) {
        AuthHelper.requireAdminOr(Permission.PAYABLES_MANAGE);
        AuthUser user = AuthHelper.currentUser();
        Supplier s = require(id);
        if (s.isActive()) return;
        s.setActive(true);
        supplierRepository.save(s);
        auditService.log(user.id(), AuditAction.UPDATE, "Supplier", s.getId(),
                Map.of("name", s.getName(), "active", true));
    }

    @Transactional
    public void deletePermanently(String id) {
        // Hard-delete is admin-only and refused if any history exists,
        // mirroring the safety net on StockService.deletePermanently.
        AuthHelper.requireAdmin();
        AuthUser user = AuthHelper.currentUser();
        Supplier s = require(id);
        if (!invoiceRepository.findBySupplierId(id).isEmpty()) {
            throw new ConflictException(
                    "Cannot delete a supplier with invoice history. Deactivate instead.");
        }
        supplierRepository.delete(s);
        auditService.log(user.id(), AuditAction.DELETE, "Supplier", id,
                Map.of("name", s.getName()));
    }

    /** Public — used by PayableService to resolve the supplier on
     *  invoice create. */
    public Supplier require(String id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier not found"));
    }

    public static Map<String, Object> toMap(Supplier s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        if (s.getContactName() != null) m.put("contactName", s.getContactName());
        if (s.getPhone() != null) m.put("phone", s.getPhone());
        if (s.getEmail() != null) m.put("email", s.getEmail());
        if (s.getVatId() != null) m.put("vatId", s.getVatId());
        if (s.getAddress() != null) m.put("address", s.getAddress());
        m.put("paymentTermsDays", s.getPaymentTermsDays());
        if (s.getNotes() != null) m.put("notes", s.getNotes());
        m.put("active", s.isActive());
        if (s.getCreatedAt() != null) m.put("createdAt", s.getCreatedAt().toString());
        if (s.getUpdatedAt() != null) m.put("updatedAt", s.getUpdatedAt().toString());
        return m;
    }

    private static Map<String, Object> toAuditMap(Supplier s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("contactName", s.getContactName());
        m.put("phone", s.getPhone());
        m.put("email", s.getEmail());
        m.put("vatId", s.getVatId());
        m.put("paymentTermsDays", s.getPaymentTermsDays());
        m.put("active", s.isActive());
        return m;
    }

    private void applyMutable(Supplier s, Map<String, Object> body) {
        if (body.containsKey("name")) s.setName(stringOrNull(body.get("name")));
        if (body.containsKey("contactName")) s.setContactName(stringOrNull(body.get("contactName")));
        if (body.containsKey("phone")) s.setPhone(stringOrNull(body.get("phone")));
        if (body.containsKey("email")) s.setEmail(stringOrNull(body.get("email")));
        if (body.containsKey("vatId")) s.setVatId(stringOrNull(body.get("vatId")));
        if (body.containsKey("address")) s.setAddress(stringOrNull(body.get("address")));
        if (body.containsKey("notes")) s.setNotes(stringOrNull(body.get("notes")));
        if (body.containsKey("paymentTermsDays")) {
            Object v = body.get("paymentTermsDays");
            if (v == null) {
                s.setPaymentTermsDays(0);
            } else if (v instanceof Number n) {
                s.setPaymentTermsDays(n.intValue());
            } else {
                try { s.setPaymentTermsDays(Integer.parseInt(v.toString().trim())); }
                catch (NumberFormatException e) {
                    throw new BadRequestException("paymentTermsDays must be a number");
                }
            }
        }
        if (body.containsKey("active")) {
            Object v = body.get("active");
            s.setActive(v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v)));
        }
    }

    private void validate(Supplier s) {
        if (s.getName() == null || s.getName().isBlank()) {
            throw new BadRequestException("Supplier name is required");
        }
        if (s.getName().length() > 160) {
            throw new BadRequestException("Supplier name too long");
        }
        if (s.getPaymentTermsDays() < 0 || s.getPaymentTermsDays() > 365) {
            throw new BadRequestException("paymentTermsDays must be between 0 and 365");
        }
    }

    private void ensureUniqueName(String name, String allowedId) {
        supplierRepository.findByNameIgnoreCase(name).ifPresent(other -> {
            if (allowedId == null || !other.getId().equals(allowedId)) {
                throw new ConflictException("A supplier with this name already exists");
            }
        });
    }

    private static String stringOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
