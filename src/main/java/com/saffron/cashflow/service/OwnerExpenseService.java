package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.ExpenseCategory;
import com.saffron.cashflow.domain.OwnerExpense;
import com.saffron.cashflow.domain.OwnerExpenseReimbursement;
import com.saffron.cashflow.domain.OwnerExpenseStatus;
import com.saffron.cashflow.domain.Permission;
import com.saffron.cashflow.domain.ReceiptFile;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.SupplierInvoicePayment;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.repository.OwnerExpenseReimbursementRepository;
import com.saffron.cashflow.repository.OwnerExpenseRepository;
import com.saffron.cashflow.repository.ReceiptFileRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.ConflictException;
import com.saffron.cashflow.web.NotFoundException;

import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-paid expense / reimbursement business logic.
 *
 * <p>Lifecycle parallels {@link PayableService} but with the creditor
 * being an internal {@link User} rather than an external {@code
 * Supplier}. Two events:</p>
 * <ol>
 *   <li><b>File</b> ({@link #file}): owner records the expense they
 *       paid out of pocket. P&amp;L picks it up on {@code expenseDate}
 *       under the chosen {@link ExpenseCategory}. The cash account is
 *       not affected — restaurant cash didn't move.</li>
 *   <li><b>Reimburse</b> ({@link #recordReimbursement}): restaurant
 *       pays the owner back. Cash moves on {@code paidDate}; P&amp;L
 *       is unaffected.</li>
 * </ol>
 *
 * <p>Voiding a reimbursement-free expense cancels it (P&amp;L drops
 * back). Voiding once any reimbursement exists is refused — reverse the
 * reimbursements first, then void.</p>
 *
 * <p>Permissions (per
 * {@link AuthHelper#requireAdminOr(Permission...)}):
 * <ul>
 *   <li>{@link Permission#OWNER_EXPENSES_VIEW} — see the list.</li>
 *   <li>{@link Permission#OWNER_EXPENSES_FILE} — file an expense
 *       (your own, by default — admins can file for any user).</li>
 *   <li>{@link Permission#OWNER_EXPENSES_MANAGE} — record
 *       reimbursements, edit fields after the fact, void.</li>
 * </ul></p>
 */
@Service
public class OwnerExpenseService {

    private static final int MAX_NOTES = 1000;
    private static final int MAX_DESCRIPTION = 300;

    private final OwnerExpenseRepository expenseRepository;
    private final OwnerExpenseReimbursementRepository reimbursementRepository;
    private final ReceiptFileRepository receiptFileRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final Path uploadDir;

    public OwnerExpenseService(
            OwnerExpenseRepository expenseRepository,
            OwnerExpenseReimbursementRepository reimbursementRepository,
            ReceiptFileRepository receiptFileRepository,
            UserRepository userRepository,
            AuditService auditService,
            @Value("${app.upload-dir}") String uploadDir) throws IOException {
        this.expenseRepository = expenseRepository;
        this.reimbursementRepository = reimbursementRepository;
        this.receiptFileRepository = receiptFileRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
    }

    // ========================================================================
    // Read paths
    // ========================================================================

    /**
     * @param statusFilter "PENDING" (default — PENDING + PARTIAL),
     *                     "REIMBURSED", "VOID", or "ALL".
     * @param ownerId      optional filter to a single owner.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String statusFilter, String ownerId) {
        AuthHelper.requireAdminOr(
                Permission.OWNER_EXPENSES_VIEW,
                Permission.OWNER_EXPENSES_MANAGE,
                Permission.OWNER_EXPENSES_FILE);

        List<OwnerExpenseStatus> statuses = parseStatusFilter(statusFilter);

        List<OwnerExpense> rows;
        if (ownerId != null && !ownerId.isBlank()) {
            rows = expenseRepository.findByOwnerUserId(ownerId);
            if (statuses != null) {
                rows = rows.stream().filter(e -> statuses.contains(e.getStatus())).toList();
            }
        } else if (statuses == null) {
            rows = expenseRepository.findAllOrdered();
        } else {
            rows = expenseRepository.findByStatuses(statuses);
        }

        Map<String, String> ownerNames = ownerNameMap(rows);

        BigDecimal totalOutstanding = BigDecimal.ZERO;
        List<Map<String, Object>> items = new ArrayList<>();
        for (OwnerExpense e : rows) {
            BigDecimal out = e.outstanding();
            if (e.getStatus() != OwnerExpenseStatus.VOID
                    && e.getStatus() != OwnerExpenseStatus.REIMBURSED) {
                totalOutstanding = totalOutstanding.add(out);
            }
            items.add(toListMap(e, ownerNames));
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("count", items.size());
        totals.put("outstanding", totalOutstanding);

        // By-owner summary so the page can render a "you owe Alice
        // 240, Bob 90" callout up top — a one-glance shortcut for the
        // most common question owners actually ask.
        Map<String, BigDecimal> outstandingByOwner = new LinkedHashMap<>();
        for (OwnerExpense e : rows) {
            if (e.getStatus() == OwnerExpenseStatus.VOID
                    || e.getStatus() == OwnerExpenseStatus.REIMBURSED) {
                continue;
            }
            outstandingByOwner.merge(e.getOwnerUserId(), e.outstanding(), BigDecimal::add);
        }
        List<Map<String, Object>> ownerRows = new ArrayList<>();
        outstandingByOwner.forEach((uid, amount) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ownerUserId", uid);
            row.put("ownerName", ownerNames.getOrDefault(uid, "Unknown"));
            row.put("outstanding", amount);
            ownerRows.add(row);
        });
        ownerRows.sort((a, b) -> ((BigDecimal) b.get("outstanding"))
                .compareTo((BigDecimal) a.get("outstanding")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("totals", totals);
        result.put("byOwner", ownerRows);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        AuthHelper.requireAdminOr(
                Permission.OWNER_EXPENSES_VIEW,
                Permission.OWNER_EXPENSES_MANAGE,
                Permission.OWNER_EXPENSES_FILE);
        OwnerExpense e = require(id);
        return toDetailMap(e, ownerNameMap(List.of(e)));
    }

    // ========================================================================
    // Write paths
    // ========================================================================

    @Transactional
    public Map<String, Object> file(Map<String, Object> body) {
        // Anyone with FILE/MANAGE/admin can record an out-of-pocket expense.
        AuthHelper.requireAdminOr(
                Permission.OWNER_EXPENSES_FILE,
                Permission.OWNER_EXPENSES_MANAGE);
        AuthUser actor = AuthHelper.currentUser();

        String ownerUserId = stringOrNull(body.get("ownerUserId"));
        if (ownerUserId == null) {
            // Default to the current user — the most common case is "I
            // paid for this myself".
            ownerUserId = actor.id();
        } else if (!ownerUserId.equals(actor.id())) {
            // Filing on someone else's behalf requires admin or MANAGE,
            // because it's an "I'll record this for you" privilege.
            if (actor.role() != Role.ADMIN
                    && !AuthHelper.hasPermission(Permission.OWNER_EXPENSES_MANAGE)) {
                throw new BadRequestException(
                        "You can only file expenses for yourself unless you can manage owner expenses");
            }
        }
        User owner = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new BadRequestException("Owner user not found"));

        OwnerExpense e = new OwnerExpense();
        e.setOwnerUserId(owner.getId());
        e.setExpenseDate(parseRequiredDate(body.get("expenseDate"), "expenseDate"));
        if (e.getExpenseDate().isAfter(LocalDate.now().plusDays(1))) {
            throw new BadRequestException("Expense date cannot be in the future");
        }
        e.setCategory(parseCategory(body.get("category"), ExpenseCategory.OTHER));

        String description = stringOrNull(body.get("description"));
        if (description == null) throw new BadRequestException("Description is required");
        if (description.length() > MAX_DESCRIPTION) {
            throw new BadRequestException("Description too long");
        }
        e.setDescription(description);

        BigDecimal total = parseOptionalAmount(body.get("total"));
        if (total == null || total.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        e.setTotal(total.setScale(2, RoundingMode.HALF_UP));
        e.setAmountReimbursed(BigDecimal.ZERO);
        e.setStatus(OwnerExpenseStatus.PENDING);

        e.setReference(stringOrNull(body.get("reference")));
        String notes = stringOrNull(body.get("notes"));
        if (notes != null && notes.length() > MAX_NOTES) {
            throw new BadRequestException("Notes too long");
        }
        e.setNotes(notes);
        e.setCreatedBy(actor.id());

        e = expenseRepository.save(e);
        auditService.log(actor.id(), AuditAction.CREATE, "OwnerExpense", e.getId(),
                Map.of(
                        "owner", owner.getName(),
                        "expenseDate", e.getExpenseDate().toString(),
                        "category", e.getCategory().name(),
                        "amount", e.getTotal(),
                        "description", e.getDescription()));
        return toDetailMap(e, Map.of(owner.getId(), owner.getName()));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.OWNER_EXPENSES_MANAGE);
        AuthUser actor = AuthHelper.currentUser();
        OwnerExpense e = require(id);
        if (e.getStatus() == OwnerExpenseStatus.VOID) {
            throw new ConflictException("Cannot edit a voided expense");
        }
        if (body.containsKey("description")) {
            String d = stringOrNull(body.get("description"));
            if (d == null) throw new BadRequestException("Description cannot be cleared");
            if (d.length() > MAX_DESCRIPTION) throw new BadRequestException("Description too long");
            e.setDescription(d);
        }
        if (body.containsKey("category")) {
            e.setCategory(parseCategory(body.get("category"), e.getCategory()));
        }
        if (body.containsKey("expenseDate")) {
            LocalDate d = parseRequiredDate(body.get("expenseDate"), "expenseDate");
            if (d.isAfter(LocalDate.now().plusDays(1))) {
                throw new BadRequestException("Expense date cannot be in the future");
            }
            e.setExpenseDate(d);
        }
        if (body.containsKey("reference")) {
            e.setReference(stringOrNull(body.get("reference")));
        }
        if (body.containsKey("notes")) {
            String n = stringOrNull(body.get("notes"));
            if (n != null && n.length() > MAX_NOTES) {
                throw new BadRequestException("Notes too long");
            }
            e.setNotes(n);
        }
        if (body.containsKey("total")) {
            if (e.getAmountReimbursed().signum() > 0) {
                throw new ConflictException(
                        "Cannot change total after a reimbursement has been recorded. "
                                + "Reverse reimbursements first.");
            }
            BigDecimal t = parseOptionalAmount(body.get("total"));
            if (t == null || t.signum() <= 0) {
                throw new BadRequestException("Amount must be greater than zero");
            }
            e.setTotal(t.setScale(2, RoundingMode.HALF_UP));
        }

        recomputeStatus(e);
        expenseRepository.save(e);
        auditService.log(actor.id(), AuditAction.UPDATE, "OwnerExpense", e.getId(),
                Map.of("amount", e.getTotal(), "category", e.getCategory().name()));
        return toDetailMap(e, ownerNameMap(List.of(e)));
    }

    @Transactional
    public Map<String, Object> voidExpense(String id, String reason) {
        AuthHelper.requireAdminOr(Permission.OWNER_EXPENSES_MANAGE);
        AuthUser actor = AuthHelper.currentUser();
        OwnerExpense e = require(id);
        if (e.getStatus() == OwnerExpenseStatus.VOID) {
            throw new ConflictException("Already voided");
        }
        if (e.getAmountReimbursed().signum() > 0) {
            throw new ConflictException(
                    "Cannot void an expense that has reimbursements. Reverse them first.");
        }
        e.setStatus(OwnerExpenseStatus.VOID);
        e.setVoidedAt(Instant.now());
        e.setVoidedBy(actor.id());
        expenseRepository.save(e);
        auditService.log(actor.id(), AuditAction.DELETE, "OwnerExpense", e.getId(),
                Map.of(
                        "amount", e.getTotal(),
                        "reason", reason == null ? "" : reason));
        return toDetailMap(e, ownerNameMap(List.of(e)));
    }

    @Transactional
    public Map<String, Object> recordReimbursement(String expenseId, Map<String, Object> body) {
        AuthHelper.requireAdminOr(Permission.OWNER_EXPENSES_MANAGE);
        AuthUser actor = AuthHelper.currentUser();
        OwnerExpense e = require(expenseId);
        if (e.getStatus() == OwnerExpenseStatus.VOID) {
            throw new ConflictException("Cannot reimburse a voided expense");
        }
        if (e.getStatus() == OwnerExpenseStatus.REIMBURSED) {
            throw new ConflictException("Already fully reimbursed");
        }

        BigDecimal amount = parseOptionalAmount(body.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Reimbursement amount must be greater than zero");
        }
        BigDecimal remaining = e.outstanding();
        if (amount.compareTo(remaining) > 0) {
            throw new BadRequestException(
                    "Amount exceeds outstanding (" + remaining.toPlainString() + ")");
        }

        OwnerExpenseReimbursement r = new OwnerExpenseReimbursement();
        r.setOwnerExpense(e);
        r.setPaidDate(parseRequiredDate(body.get("paidDate"), "paidDate"));
        if (r.getPaidDate().isAfter(LocalDate.now().plusDays(1))) {
            throw new BadRequestException("Payment date cannot be in the future");
        }
        if (r.getPaidDate().isBefore(e.getExpenseDate())) {
            throw new BadRequestException(
                    "Reimbursement date cannot precede the expense date ("
                            + e.getExpenseDate() + ")");
        }
        r.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        r.setMethod(parseMethod(body.get("method"), SupplierInvoicePayment.PaymentMethod.CASH));
        r.setReference(stringOrNull(body.get("reference")));
        String notes = stringOrNull(body.get("notes"));
        if (notes != null && notes.length() > MAX_NOTES) {
            throw new BadRequestException("Notes too long");
        }
        r.setNotes(notes);
        r.setCreatedBy(actor.id());
        r = reimbursementRepository.save(r);

        e.setAmountReimbursed(e.getAmountReimbursed().add(r.getAmount())
                .setScale(2, RoundingMode.HALF_UP));
        recomputeStatus(e);
        expenseRepository.save(e);

        auditService.log(actor.id(), AuditAction.CREATE, "OwnerExpenseReimbursement", r.getId(),
                Map.of(
                        "ownerExpenseId", e.getId(),
                        "amount", r.getAmount(),
                        "method", r.getMethod().name(),
                        "newOutstanding", e.outstanding(),
                        "newStatus", e.getStatus().name()));
        return toDetailMap(e, ownerNameMap(List.of(e)));
    }

    // ========================================================================
    // Receipt files
    // ========================================================================

    /**
     * Attach a receipt photo / PDF to an owner expense. Anyone who can
     * file an expense can also attach receipts to one — the owner of
     * the expense is most often the same person who has the proof on
     * their phone. Filing a receipt onto a voided expense is refused
     * (those entries are sealed).
     */
    @Transactional
    public Map<String, Object> uploadReceipt(String expenseId, MultipartFile file) throws IOException {
        AuthHelper.requireAdminOr(
                Permission.OWNER_EXPENSES_FILE,
                Permission.OWNER_EXPENSES_MANAGE);
        AuthUser actor = AuthHelper.currentUser();
        OwnerExpense e = require(expenseId);
        if (e.getStatus() == OwnerExpenseStatus.VOID) {
            throw new ConflictException("Cannot attach receipts to a cancelled expense");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file uploaded");
        }
        String original = file.getOriginalFilename();
        if (original == null || !original.matches("(?i).+\\.(jpg|jpeg|png|pdf|webp|heic)$")) {
            throw new BadRequestException("Only images and PDF allowed");
        }
        String stored = System.currentTimeMillis() + "-" + UUID.randomUUID() + extension(original);
        Files.copy(file.getInputStream(), uploadDir.resolve(stored));

        ReceiptFile rf = new ReceiptFile();
        rf.setFilename(original);
        rf.setPath(stored);
        rf.setCategory("owner-expense-receipt");
        e.addReceipt(rf);
        expenseRepository.save(e);

        auditService.log(actor.id(), AuditAction.UPDATE, "OwnerExpense", e.getId(),
                Map.of("receiptUploaded", original, "receiptCount", e.getReceipts().size()));
        return toDetailMap(e, ownerNameMap(List.of(e)));
    }

    @Transactional
    public Map<String, Object> deleteReceipt(String expenseId, String fileId) {
        // Anyone who could attach can detach — the typical "I uploaded
        // the wrong photo" recovery path. Voided expenses keep their
        // receipts (audit trail).
        AuthHelper.requireAdminOr(
                Permission.OWNER_EXPENSES_FILE,
                Permission.OWNER_EXPENSES_MANAGE);
        AuthUser actor = AuthHelper.currentUser();
        OwnerExpense e = require(expenseId);
        if (e.getStatus() == OwnerExpenseStatus.VOID) {
            throw new ConflictException("Cannot delete receipts from a cancelled expense");
        }
        ReceiptFile target = e.getReceipts().stream()
                .filter(rf -> rf.getId().equals(fileId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Receipt not found"));
        e.getReceipts().remove(target);
        try {
            Files.deleteIfExists(uploadDir.resolve(target.getPath()));
        } catch (IOException ignored) {
            // file may already be gone; finish DB cleanup either way
        }
        receiptFileRepository.delete(target);
        expenseRepository.save(e);
        auditService.log(actor.id(), AuditAction.UPDATE, "OwnerExpense", e.getId(),
                Map.of("receiptDeleted", target.getFilename(), "receiptCount", e.getReceipts().size()));
        return toDetailMap(e, ownerNameMap(List.of(e)));
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i) : "";
    }

    @Transactional
    public Map<String, Object> deleteReimbursement(String expenseId, String reimbursementId) {
        AuthHelper.requireAdminOr(Permission.OWNER_EXPENSES_MANAGE);
        AuthUser actor = AuthHelper.currentUser();
        OwnerExpense e = require(expenseId);
        OwnerExpenseReimbursement r = reimbursementRepository.findById(reimbursementId)
                .orElseThrow(() -> new NotFoundException("Reimbursement not found"));
        if (r.getOwnerExpense() == null
                || !r.getOwnerExpense().getId().equals(e.getId())) {
            throw new BadRequestException("Reimbursement does not belong to this expense");
        }

        BigDecimal restored = e.getAmountReimbursed().subtract(r.getAmount());
        if (restored.signum() < 0) restored = BigDecimal.ZERO;
        e.setAmountReimbursed(restored.setScale(2, RoundingMode.HALF_UP));
        e.getReimbursements().remove(r);
        reimbursementRepository.delete(r);
        recomputeStatus(e);
        expenseRepository.save(e);

        auditService.log(actor.id(), AuditAction.DELETE, "OwnerExpenseReimbursement", r.getId(),
                Map.of(
                        "ownerExpenseId", e.getId(),
                        "amount", r.getAmount(),
                        "newOutstanding", e.outstanding(),
                        "newStatus", e.getStatus().name()));
        return toDetailMap(e, ownerNameMap(List.of(e)));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private OwnerExpense require(String id) {
        return expenseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Owner expense not found"));
    }

    private void recomputeStatus(OwnerExpense e) {
        if (e.getStatus() == OwnerExpenseStatus.VOID) return;
        BigDecimal paid = e.getAmountReimbursed() == null ? BigDecimal.ZERO : e.getAmountReimbursed();
        BigDecimal total = e.getTotal() == null ? BigDecimal.ZERO : e.getTotal();
        if (paid.signum() == 0) e.setStatus(OwnerExpenseStatus.PENDING);
        else if (paid.compareTo(total) >= 0) e.setStatus(OwnerExpenseStatus.REIMBURSED);
        else e.setStatus(OwnerExpenseStatus.PARTIAL);
    }

    private static List<OwnerExpenseStatus> parseStatusFilter(String filter) {
        if (filter == null || filter.isBlank() || "PENDING".equalsIgnoreCase(filter)) {
            return List.of(OwnerExpenseStatus.PENDING, OwnerExpenseStatus.PARTIAL);
        }
        if ("ALL".equalsIgnoreCase(filter)) return null;
        try {
            return List.of(OwnerExpenseStatus.valueOf(filter.toUpperCase()));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown status filter: " + filter);
        }
    }

    private static ExpenseCategory parseCategory(Object raw, ExpenseCategory fallback) {
        String s = stringOrNull(raw);
        if (s == null) return fallback;
        try {
            return ExpenseCategory.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown expense category: " + s);
        }
    }

    private static SupplierInvoicePayment.PaymentMethod parseMethod(
            Object raw, SupplierInvoicePayment.PaymentMethod fallback) {
        String s = stringOrNull(raw);
        if (s == null) return fallback;
        try {
            return SupplierInvoicePayment.PaymentMethod.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown payment method: " + s);
        }
    }

    private static LocalDate parseRequiredDate(Object raw, String field) {
        String s = stringOrNull(raw);
        if (s == null) throw new BadRequestException(field + " is required");
        try { return LocalDate.parse(s); }
        catch (Exception ex) { throw new BadRequestException("Invalid date: " + s); }
    }

    private static BigDecimal parseOptionalAmount(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException ex) {
            throw new BadRequestException("Invalid amount: " + s);
        }
    }

    private static String stringOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Map<String, String> ownerNameMap(List<OwnerExpense> rows) {
        if (rows.isEmpty()) return Map.of();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (OwnerExpense e : rows) ids.add(e.getOwnerUserId());
        Map<String, String> out = new java.util.HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            out.put(u.getId(), u.getName());
        }
        return out;
    }

    private Map<String, Object> toListMap(OwnerExpense e, Map<String, String> ownerNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ownerUserId", e.getOwnerUserId());
        m.put("ownerName", ownerNames.getOrDefault(e.getOwnerUserId(), "Unknown"));
        m.put("expenseDate", e.getExpenseDate().toString());
        m.put("category", e.getCategory().name());
        m.put("description", e.getDescription());
        m.put("total", e.getTotal());
        m.put("amountReimbursed", e.getAmountReimbursed());
        m.put("outstanding", e.outstanding());
        m.put("status", e.getStatus().name());
        if (e.getReference() != null) m.put("reference", e.getReference());
        // Cheap "has proof?" hint for the list view; the full receipt
        // list is fetched on demand by the detail endpoint.
        m.put("receiptCount", e.getReceipts() == null ? 0 : e.getReceipts().size());
        return m;
    }

    private Map<String, Object> toDetailMap(OwnerExpense e, Map<String, String> ownerNames) {
        Map<String, Object> m = toListMap(e, ownerNames);
        if (e.getNotes() != null) m.put("notes", e.getNotes());
        m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        m.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);

        List<Map<String, Object>> reimbs = new ArrayList<>();
        Optional<? extends List<OwnerExpenseReimbursement>> reimbList =
                Optional.ofNullable(e.getReimbursements());
        if (reimbList.isPresent()) {
            for (OwnerExpenseReimbursement r : reimbList.get()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", r.getId());
                rm.put("paidDate", r.getPaidDate().toString());
                rm.put("amount", r.getAmount());
                rm.put("method", r.getMethod().name());
                if (r.getReference() != null) rm.put("reference", r.getReference());
                if (r.getNotes() != null) rm.put("notes", r.getNotes());
                rm.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
                reimbs.add(rm);
            }
        }
        m.put("reimbursements", reimbs);

        List<Map<String, Object>> files = new ArrayList<>();
        if (e.getReceipts() != null) {
            for (ReceiptFile rf : e.getReceipts()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", rf.getId());
                rm.put("filename", rf.getFilename());
                rm.put("createdAt", rf.getCreatedAt() != null ? rf.getCreatedAt().toString() : null);
                files.add(rm);
            }
        }
        m.put("receipts", files);
        return m;
    }
}
