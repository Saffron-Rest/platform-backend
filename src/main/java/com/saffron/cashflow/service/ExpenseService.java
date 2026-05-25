package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.*;
import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.dto.ExpenseItemRequest;
import com.saffron.cashflow.dto.StandaloneExpenseRequest;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.ExpenseItemRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.security.ForbiddenException;
import com.saffron.cashflow.util.AuditSnapshots;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.EntryMapper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExpenseService {

    private final Path uploadDir;
    private final ExpenseItemRepository expenseRepository;
    private final DailyEntryRepository entryRepository;
    private final EntryService entryService;
    private final WorkShiftService workShiftService;
    private final AuditService auditService;
    private final TagService tagService;
    private final CommentService commentService;

    public ExpenseService(
            @Value("${app.upload-dir}") String uploadDir,
            ExpenseItemRepository expenseRepository,
            DailyEntryRepository entryRepository,
            @Lazy EntryService entryService,
            WorkShiftService workShiftService,
            AuditService auditService,
            @Lazy TagService tagService,
            @Lazy CommentService commentService) throws IOException {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
        this.expenseRepository = expenseRepository;
        this.entryRepository = entryRepository;
        this.entryService = entryService;
        this.workShiftService = workShiftService;
        this.auditService = auditService;
        this.tagService = tagService;
        this.commentService = commentService;
    }

    public List<Map<String, Object>> listForEntry(String entryId) {
        verifyEntryAccess(entryId);
        migrateLegacyIfNeeded(entryId);
        return expenseRepository.findByEntryIdWithInvoice(entryId).stream()
                .map(EntryMapper::expenseToMap)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAll(String fromParam, String toParam, List<String> tagIds) {
        AuthHelper.requireOperations();
        LocalDate from = LocalDate.parse(fromParam);
        LocalDate to = LocalDate.parse(toParam);
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must be on or before 'to'");
        }
        List<ExpenseItem> rows = expenseRepository.findByEffectiveDateBetweenWithInvoices(from, to);
        if (tagIds != null && !tagIds.isEmpty()) {
            Set<String> allowed = new HashSet<>(
                    tagService.entityIdsTaggedWithAll(
                            com.saffron.cashflow.domain.TaggedEntityType.EXPENSE, tagIds));
            rows = rows.stream().filter(e -> allowed.contains(e.getId())).toList();
        }
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(ExpenseItem::getId).toList();
        Map<String, List<Map<String, Object>>> tagsByExpense = tagService.tagsForBulk(
                com.saffron.cashflow.domain.TaggedEntityType.EXPENSE, ids);
        Map<String, Long> commentsByExpense = commentService.countByEntities(
                com.saffron.cashflow.domain.TaggedEntityType.EXPENSE, ids);
        return rows.stream()
                .map(e -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>(EntryMapper.expenseToMap(e));
                    m.put("tags", tagsByExpense.getOrDefault(e.getId(), List.of()));
                    m.put("commentCount", commentsByExpense.getOrDefault(e.getId(), 0L));
                    return m;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public BigDecimal sumStandaloneBetween(LocalDate from, LocalDate to, PaymentSource source) {
        return expenseRepository.findStandaloneBetweenWithInvoices(from, to).stream()
                .filter(i -> i.getPaymentSource() == source)
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumStandaloneBetween(LocalDate from, LocalDate to) {
        return expenseRepository.findStandaloneBetweenWithInvoices(from, to).stream()
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<ExpenseItem> findStandaloneBetween(LocalDate from, LocalDate to) {
        return expenseRepository.findStandaloneBetweenWithInvoices(from, to);
    }

    @Transactional
    public Map<String, Object> createStandalone(StandaloneExpenseRequest req, MultipartFile invoice)
            throws IOException {
        AuthHelper.requireOperations();
        if (req.getAmount() == null || req.getAmount().signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        ExpenseItem item = new ExpenseItem();
        item.setEntry(null);
        item.setEffectiveDate(LocalDate.parse(req.getEffectiveDate()));
        applyStandaloneRequest(item, req);
        if (invoice != null && !invoice.isEmpty()) {
            item.addInvoice(storeFile(null, item, invoice, "expense-invoice"));
        }
        item = expenseRepository.save(item);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "ExpenseItem", item.getId(),
                Map.of(), AuditSnapshots.expense(item), Map.of("standalone", true));
        return mapExpense(item.getId());
    }

    @Transactional
    public Map<String, Object> updateStandalone(String expenseId, StandaloneExpenseRequest req, MultipartFile invoice)
            throws IOException {
        AuthHelper.requireOperations();
        ExpenseItem item = loadStandalone(expenseId);
        Map<String, Object> before = AuditSnapshots.expense(item);
        if (req.getAmount() == null || req.getAmount().signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        item.setEffectiveDate(LocalDate.parse(req.getEffectiveDate()));
        applyStandaloneRequest(item, req);
        if (invoice != null && !invoice.isEmpty()) {
            item.addInvoice(storeFile(null, item, invoice, "expense-invoice"));
        }
        item = expenseRepository.save(item);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "ExpenseItem", item.getId(),
                before, AuditSnapshots.expense(item), Map.of("standalone", true));
        return mapExpense(item.getId());
    }

    @Transactional
    public void deleteStandalone(String expenseId) {
        AuthHelper.requireOperations();
        ExpenseItem item = loadStandalone(expenseId);
        Map<String, Object> before = AuditSnapshots.expense(item);
        for (ReceiptFile inv : new ArrayList<>(item.getInvoices())) {
            deleteFile(inv.getPath());
        }
        tagService.clearForEntity(com.saffron.cashflow.domain.TaggedEntityType.EXPENSE, expenseId);
        expenseRepository.delete(item);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "ExpenseItem", expenseId, before,
                Map.of(), Map.of("standalone", true));
    }

    @Transactional
    public Map<String, Object> create(String entryId, ExpenseItemRequest req, MultipartFile invoice) throws IOException {
        DailyEntry entry = verifyEntryAccess(entryId);
        assertEditable(entry);

        ExpenseItem item = new ExpenseItem();
        item.setEntry(entry);
        item.setEffectiveDate(entry.getDate());
        applyRequest(item, req);
        if (invoice != null && !invoice.isEmpty()) {
            item.addInvoice(storeFile(entry, item, invoice, "expense-invoice"));
        }
        item = expenseRepository.save(item);
        entryService.recalculateEntry(entryId);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.CREATE, "ExpenseItem", item.getId(),
                Map.of(), AuditSnapshots.expense(item), Map.of("entryId", entryId));
        return mapExpense(item.getId());
    }

    @Transactional
    public Map<String, Object> update(String expenseId, ExpenseItemRequest req, MultipartFile invoice) throws IOException {
        ExpenseItem item = expenseRepository.findByIdWithInvoices(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        DailyEntry entry = verifyEntryAccess(item.getEntryId());
        assertEditable(entry);
        Map<String, Object> before = AuditSnapshots.expense(item);

        applyRequest(item, req);
        if (invoice != null && !invoice.isEmpty()) {
            item.addInvoice(storeFile(entry, item, invoice, "expense-invoice"));
        }
        item = expenseRepository.save(item);
        entryService.recalculateEntry(entry.getId());
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "ExpenseItem", item.getId(),
                before, AuditSnapshots.expense(item), Map.of("entryId", entry.getId()));
        return mapExpense(item.getId());
    }

    @Transactional
    public Map<String, Object> uploadInvoice(String expenseId, MultipartFile invoice) throws IOException {
        ExpenseItem item = expenseRepository.findByIdWithInvoices(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        DailyEntry entry = requireEditableExpense(item);
        if (invoice == null || invoice.isEmpty()) {
            throw new BadRequestException("No file uploaded");
        }
        int before = item.getInvoices().size();
        item.addInvoice(storeFile(entry, item, invoice, "expense-invoice"));
        item = expenseRepository.save(item);
        if (entry != null) {
            entryService.recalculateEntry(entry.getId());
        }
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "ExpenseItem", item.getId(),
                Map.of("invoiceCount", before), Map.of("invoiceCount", item.getInvoices().size()),
                entry != null
                        ? Map.of("entryId", entry.getId(), "invoice", true)
                        : Map.of("standalone", true, "invoice", true));
        return mapExpense(item.getId());
    }

    @Transactional
    public Map<String, Object> deleteInvoice(String expenseId, String fileId) {
        ExpenseItem item = expenseRepository.findByIdWithInvoices(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        DailyEntry entry = requireEditableExpense(item);
        ReceiptFile target = item.getInvoices().stream()
                .filter(f -> f.getId().equals(fileId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        item.getInvoices().remove(target);
        deleteFile(target.getPath());
        expenseRepository.save(item);
        if (entry != null) {
            entryService.recalculateEntry(entry.getId());
        }
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "ExpenseItem", item.getId(),
                Map.of("deletedFileId", fileId), Map.of("invoiceCount", item.getInvoices().size()),
                entry != null ? Map.of("entryId", entry.getId()) : Map.of("standalone", true));
        return mapExpense(item.getId());
    }

    @Transactional
    public void delete(String expenseId) {
        ExpenseItem item = expenseRepository.findByIdWithInvoices(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        DailyEntry entry = verifyEntryAccess(item.getEntryId());
        assertEditable(entry);
        Map<String, Object> before = AuditSnapshots.expense(item);
        for (ReceiptFile inv : new ArrayList<>(item.getInvoices())) {
            deleteFile(inv.getPath());
        }
        tagService.clearForEntity(com.saffron.cashflow.domain.TaggedEntityType.EXPENSE, expenseId);
        expenseRepository.delete(item);
        entryService.recalculateEntry(entry.getId());
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "ExpenseItem", expenseId, before, Map.of(),
                Map.of("entryId", entry.getId()));
    }

    @Transactional
    public List<Map<String, Object>> sync(String entryId, List<ExpenseItemRequest> items) {
        DailyEntry entry = verifyEntryAccess(entryId);
        assertEditable(entry);
        migrateLegacyIfNeeded(entryId);

        List<ExpenseItem> existing = expenseRepository.findByEntryIdWithInvoice(entryId);
        Set<String> keepIds = items.stream()
                .map(ExpenseItemRequest::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (ExpenseItem ex : existing) {
            if (!keepIds.contains(ex.getId())) {
                for (ReceiptFile inv : new ArrayList<>(ex.getInvoices())) {
                    deleteFile(inv.getPath());
                }
                expenseRepository.delete(ex);
            }
        }

        for (ExpenseItemRequest req : items) {
            if (req.getId() != null && !req.getId().isBlank()) {
                var matched = expenseRepository.findByIdAndEntryIdWithInvoices(req.getId(), entryId);
                if (matched.isPresent()) {
                    applyRequest(matched.get(), req);
                    expenseRepository.save(matched.get());
                } else {
                    ExpenseItem item = new ExpenseItem();
                    item.setEntry(entry);
                    item.setEffectiveDate(entry.getDate());
                    applyRequest(item, req);
                    expenseRepository.save(item);
                }
            } else {
                ExpenseItem item = new ExpenseItem();
                item.setEntry(entry);
                item.setEffectiveDate(entry.getDate());
                applyRequest(item, req);
                expenseRepository.save(item);
            }
        }

        entryService.recalculateEntry(entryId);
        auditService.log(AuthHelper.currentUser().id(), AuditAction.SYNC, "DailyEntry", entryId,
                Map.of("expenseCount", items.size()), "Synced " + items.size() + " expenses");
        return listForEntry(entryId);
    }

    @Transactional
    public void migrateLegacyIfNeeded(String entryId) {
        if (!expenseRepository.findByEntryIdWithInvoice(entryId).isEmpty()) return;
        DailyEntry entry = entryRepository.findActiveById(entryId)
                .orElseThrow(() -> new NotFoundException("Not found"));

        List<ExpenseItem> migrated = new ArrayList<>();
        migrated.addAll(legacy(entry, ExpenseCategory.SUPPLIER, "Supplier payments", entry.getSupplierPayments()));
        migrated.addAll(legacy(entry, ExpenseCategory.PETTY_CASH, "Petty cash", entry.getPettyCash()));
        migrated.addAll(legacy(entry, ExpenseCategory.SUPPLIES, "Supplies", entry.getSupplies()));
        migrated.addAll(legacy(entry, ExpenseCategory.STAFF_MEALS, "Staff meals", entry.getStaffMeals()));
        migrated.addAll(legacy(entry, ExpenseCategory.DELIVERY, "Delivery costs", entry.getDeliveryCosts()));
        migrated.addAll(legacy(entry, ExpenseCategory.OTHER, "Other expenses", entry.getOtherExpenses()));

        if (migrated.isEmpty()) return;

        expenseRepository.saveAll(migrated);
        entry.setSupplierPayments(BigDecimal.ZERO);
        entry.setPettyCash(BigDecimal.ZERO);
        entry.setSupplies(BigDecimal.ZERO);
        entry.setStaffMeals(BigDecimal.ZERO);
        entry.setDeliveryCosts(BigDecimal.ZERO);
        entry.setOtherExpenses(BigDecimal.ZERO);
        entryRepository.save(entry);
    }

    private List<ExpenseItem> legacy(DailyEntry entry, ExpenseCategory cat, String desc, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return List.of();
        ExpenseItem item = new ExpenseItem();
        item.setEntry(entry);
        item.setEffectiveDate(entry.getDate());
        item.setCategory(cat);
        item.setDescription(desc);
        item.setAmount(amount);
        return List.of(item);
    }

    private void applyStandaloneRequest(ExpenseItem item, StandaloneExpenseRequest req) {
        try {
            item.setCategory(ExpenseCategory.valueOf(req.getCategory()));
        } catch (IllegalArgumentException e) {
            item.setCategory(ExpenseCategory.OTHER);
        }
        item.setDescription(req.getDescription() != null ? req.getDescription().trim() : "");
        item.setAmount(req.getAmount());
        try {
            item.setPaymentSource(PaymentSource.valueOf(req.getPaymentSource()));
        } catch (IllegalArgumentException e) {
            item.setPaymentSource(PaymentSource.CASH);
        }
    }

    private Map<String, Object> mapExpense(String expenseId) {
        ExpenseItem item = expenseRepository.findByIdWithInvoices(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        Map<String, Object> m = new java.util.LinkedHashMap<>(EntryMapper.expenseToMap(item));
        m.put("tags", tagService.tagsFor(
                com.saffron.cashflow.domain.TaggedEntityType.EXPENSE, expenseId));
        return m;
    }

    private ExpenseItem loadStandalone(String expenseId) {
        ExpenseItem item = expenseRepository.findByIdWithInvoices(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        if (!item.isStandalone()) {
            throw new BadRequestException("This expense belongs to a shift report — edit it on that report");
        }
        return item;
    }

    private void applyRequest(ExpenseItem item, ExpenseItemRequest req) {
        try {
            item.setCategory(ExpenseCategory.valueOf(req.getCategory()));
        } catch (IllegalArgumentException e) {
            item.setCategory(ExpenseCategory.OTHER);
        }
        item.setDescription(req.getDescription().trim());
        item.setAmount(req.getAmount() != null ? req.getAmount() : BigDecimal.ZERO);
        try {
            item.setPaymentSource(PaymentSource.valueOf(req.getPaymentSource()));
        } catch (IllegalArgumentException e) {
            item.setPaymentSource(PaymentSource.CASH);
        }
    }

    private ReceiptFile storeFile(DailyEntry entry, ExpenseItem item, MultipartFile file, String category)
            throws IOException {
        String original = file.getOriginalFilename();
        if (original == null || !original.matches("(?i).+\\.(jpg|jpeg|png|pdf|webp|heic)$")) {
            throw new BadRequestException("Only images and PDF allowed");
        }
        String stored = System.currentTimeMillis() + "-" + UUID.randomUUID() + extension(original);
        Files.copy(file.getInputStream(), uploadDir.resolve(stored));

        ReceiptFile rf = new ReceiptFile();
        rf.setEntry(entry);
        rf.setFilename(original);
        rf.setPath(stored);
        rf.setCategory(category);
        return rf;
    }

    private void deleteFile(String path) {
        try {
            Files.deleteIfExists(uploadDir.resolve(path));
        } catch (IOException ignored) {}
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i) : "";
    }

    private DailyEntry verifyEntryAccess(String entryId) {
        DailyEntry entry = entryRepository.findActiveById(entryId)
                .orElseThrow(() -> new NotFoundException("Entry not found"));
        AuthUser user = AuthHelper.currentUser();
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        return entry;
    }

    /** Standalone post-close expenses: operations only. Shift expenses: entry access + editable check. */
    private DailyEntry requireEditableExpense(ExpenseItem item) {
        if (item.isStandalone()) {
            AuthHelper.requireOperations();
            return null;
        }
        DailyEntry entry = verifyEntryAccess(item.getEntryId());
        assertEditable(entry);
        return entry;
    }

    private void assertEditable(DailyEntry entry) {
        if (entry.getStatus() == EntryStatus.LOCKED && !AuthHelper.isOperationsRole()) {
            throw new ForbiddenException("Entry is locked");
        }
        if (workShiftService.isClosingShift(entry.getCashierId(), entry.getDate())
                && EntryCalculator.totalSales(entry).compareTo(java.math.BigDecimal.ZERO) == 0) {
            throw new ForbiddenException("Closing shift reports only support opening and cash count");
        }
    }
}
