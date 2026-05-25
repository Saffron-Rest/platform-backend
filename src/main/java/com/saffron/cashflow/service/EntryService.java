package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.DailyEntry;
import com.saffron.cashflow.domain.EntryStatus;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.ShiftType;
import com.saffron.cashflow.dto.EntryRequest;
import com.saffron.cashflow.domain.SystemSetting;
import com.saffron.cashflow.domain.ExpenseItem;
import com.saffron.cashflow.domain.ReceiptFile;
import com.saffron.cashflow.domain.PaymentSource;
import com.saffron.cashflow.domain.SalaryPayment;
import com.saffron.cashflow.repository.DailyEntryRepository;
import com.saffron.cashflow.repository.ExpenseItemRepository;
import com.saffron.cashflow.repository.ReceiptFileRepository;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.util.TreasurySettings;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.security.ForbiddenException;
import com.saffron.cashflow.util.AuditSnapshots;
import com.saffron.cashflow.util.EntryCalculator;
import com.saffron.cashflow.util.EntryMapper;
import com.saffron.cashflow.web.BadRequestException;
import com.saffron.cashflow.web.ConflictException;
import com.saffron.cashflow.web.NotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EntryService {

    private final DailyEntryRepository entryRepository;
    private final ExpenseItemRepository expenseRepository;
    private final ReceiptFileRepository receiptFileRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AlertService alertService;
    private final WorkShiftService workShiftService;
    private final SystemSettingRepository settingRepository;
    private final ManualDeliveryService manualDeliveryService;
    private final SalaryPaymentRepository salaryPaymentRepository;

    /** File category for POS card sales report uploads attached to a shift entry. */
    public static final String POS_REPORT_CATEGORY = "pos-report";

    public EntryService(
            DailyEntryRepository entryRepository,
            ExpenseItemRepository expenseRepository,
            ReceiptFileRepository receiptFileRepository,
            UserRepository userRepository,
            AuditService auditService,
            AlertService alertService,
            WorkShiftService workShiftService,
            SystemSettingRepository settingRepository,
            ManualDeliveryService manualDeliveryService,
            SalaryPaymentRepository salaryPaymentRepository) {
        this.entryRepository = entryRepository;
        this.expenseRepository = expenseRepository;
        this.receiptFileRepository = receiptFileRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.alertService = alertService;
        this.workShiftService = workShiftService;
        this.settingRepository = settingRepository;
        this.manualDeliveryService = manualDeliveryService;
        this.salaryPaymentRepository = salaryPaymentRepository;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String from, String to, String cashierId, String status) {
        AuthUser user = AuthHelper.currentUser();
        String filterCashier = AuthHelper.isCashier() ? user.id() : (cashierId != null && !cashierId.isBlank() ? cashierId : null);
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        if (AuthHelper.isCashier()) {
            LocalDate day = fromDate != null ? fromDate : (toDate != null ? toDate : LocalDate.now());
            fromDate = day;
            toDate = day;
        }
        EntryStatus st = status != null && !status.isBlank() ? EntryStatus.valueOf(status) : null;
        Specification<DailyEntry> spec = EntrySpecification.filter(filterCashier, fromDate, toDate, st);
        return entryRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "date")).stream()
                .map(e -> mapEntry(load(e.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getToday(String cashierIdParam, String dateParam) {
        AuthUser user = AuthHelper.currentUser();
        LocalDate date = dateParam != null && !dateParam.isBlank() ? LocalDate.parse(dateParam) : LocalDate.now();
        String cashierId = AuthHelper.isCashier() ? user.id() : cashierIdParam;
        DailyEntry entry;
        if (cashierId != null && !cashierId.isBlank()) {
            entry = entryRepository.findByCashierIdAndDateAndDeletedAtIsNull(cashierId, date).orElse(null);
        } else if (AuthHelper.isOperationsRole()) {
            var list = entryRepository.findByDateAndDeletedAtIsNull(date);
            entry = list.isEmpty() ? null : list.getFirst();
        } else {
            entry = entryRepository.findByCashierIdAndDateAndDeletedAtIsNull(user.id(), date).orElse(null);
        }
        if (entry == null) return null;
        DailyEntry loaded = load(entry.getId());
        return mapEntry(loaded);
    }

    /**
     * Suggested opening for the restaurant drawer: latest same-day actual count from any colleague,
     * otherwise the latest locked actual count from any cashier on the previous close day.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSuggestedOpening(String dateParam, String cashierIdParam) {
        AuthUser user = AuthHelper.currentUser();
        LocalDate date = dateParam != null && !dateParam.isBlank() ? LocalDate.parse(dateParam) : LocalDate.now();
        String cashierId = AuthHelper.isCashier() ? user.id() : cashierIdParam;
        if (cashierId == null || cashierId.isBlank()) {
            throw new BadRequestException("cashierId is required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "NONE");
        result.put("handoverCashierName", null);
        result.put("handoverEndTime", null);
        result.put("handoverPending", false);

        Optional<DailyEntry> sameDay = findSameDayRestaurantHandover(cashierId, date);
        if (sameDay.isPresent()) {
            putSameDayHandoverResult(result, sameDay.get(), date);
            return result;
        }

        Optional<HandoverPendingInfo> pending = findHandoverPendingInfo(cashierId, date);
        if (pending.isPresent()) {
            HandoverPendingInfo p = pending.get();
            result.put("handoverPending", true);
            result.put("handoverCashierName", p.colleagueName());
            result.put("handoverEndTime", null);
        }

        Optional<DailyEntry> previous = findLatestRestaurantCloseBefore(date);
        if (previous.isEmpty()) {
            result.put("openingBalance", 0);
            result.put("rawCountedBalance", 0);
            result.put("postCountCashOut", 0);
            result.put("previousDate", null);
            result.put("previousEntryId", null);
            return result;
        }
        putPreviousRestaurantCloseResult(result, previous.get(), date);
        return result;
    }

    private static boolean hasActualCount(DailyEntry entry) {
        return entry.getActualCashCounted() != null
                && entry.getActualCashCounted().compareTo(BigDecimal.ZERO) > 0;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getById(String id) {
        DailyEntry entry = load(id);
        AuthUser user = AuthHelper.currentUser();
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        return mapEntry(entry);
    }

    @Transactional
    public Map<String, Object> create(EntryRequest req) {
        AuthUser user = AuthHelper.currentUser();
        LocalDate date = req.getDate() != null ? LocalDate.parse(req.getDate()) : LocalDate.now();
        rejectFutureReportDate(date);
        String cashierId = AuthHelper.isCashier() ? user.id() : req.getCashierId();
        if (!AuthHelper.isCashier() && (cashierId == null || cashierId.isBlank())) {
            throw new BadRequestException("cashierId is required");
        }
        Optional<DailyEntry> active = entryRepository.findByCashierIdAndDateAndDeletedAtIsNull(cashierId, date);
        if (active.isPresent()) {
            throw new ConflictException(Map.of(
                    "error", "Entry already exists for this date",
                    "id", active.get().getId()));
        }

        Optional<DailyEntry> deleted = entryRepository.findByCashier_IdAndDate(cashierId, date)
                .filter(e -> e.getDeletedAt() != null);
        if (deleted.isPresent()) {
            DailyEntry entry = deleted.get();
            entry.setDeletedAt(null);
            entry.setDeleteReason(null);
            entry.setStatus(EntryStatus.DRAFT);
            entry.setSubmittedAt(null);
            Map<String, Object> beforeRestore = AuditSnapshots.entry(entry);
            applyEntryRequest(entry, req, cashierId, date);
            applyOpeningBalance(entry, req, user.role(), cashierId, date);
            entryRepository.save(entry);
            recalculateEntry(entry.getId());
            DailyEntry saved = load(entry.getId());
            auditService.logChange(user.id(), AuditAction.UPDATE, "DailyEntry", entry.getId(), beforeRestore, AuditSnapshots.entry(saved),
                    Map.of("restored", true));
            return mapEntry(saved);
        }

        DailyEntry entry = new DailyEntry();
        entry.setDate(date);
        entry.setCashier(userRepository.getReferenceById(cashierId));
        entry.setStatus(EntryStatus.DRAFT);
        applyEntryRequest(entry, req, cashierId, date);
        applyOpeningBalance(entry, req, user.role(), cashierId, date);
        entry = entryRepository.save(entry);
        recalculateEntry(entry.getId());
        DailyEntry saved = load(entry.getId());
        auditService.logChange(user.id(), AuditAction.CREATE, "DailyEntry", entry.getId(), Map.of(), AuditSnapshots.entry(saved),
                Map.of("date", date.toString(), "cashierId", cashierId));
        return mapEntry(saved);
    }

    @Transactional
    public Map<String, Object> update(String id, EntryRequest req) {
        DailyEntry entry = load(id);
        Map<String, Object> before = AuditSnapshots.entry(entry);
        AuthUser user = AuthHelper.currentUser();
        if (entry.getStatus() == EntryStatus.LOCKED && !AuthHelper.isOperationsRole()) {
            throw new ForbiddenException("Entry is locked");
        }
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        applyEntryRequest(entry, req, entry.getCashierId(), entry.getDate());
        applyOpeningBalance(entry, req, user.role(), entry.getCashierId(), entry.getDate());
        entryRepository.save(entry);
        recalculateEntry(id);
        DailyEntry saved = load(id);
        auditService.logChange(user.id(), AuditAction.UPDATE, "DailyEntry", entry.getId(), before, AuditSnapshots.entry(saved), null);
        return mapEntry(saved);
    }

    @Transactional
    public Map<String, Object> submit(String id) {
        DailyEntry entry = load(id);
        Map<String, Object> before = Map.of("status", entry.getStatus().name());
        AuthUser user = AuthHelper.currentUser();
        if (AuthHelper.isCashier() && !entry.getCashierId().equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        if (entry.getStatus() == EntryStatus.LOCKED && !AuthHelper.isOperationsRole()) {
            throw new BadRequestException("Already submitted");
        }
        // POS card sales report required whenever any card sales were entered.
        if (entry.getCardSales() != null && entry.getCardSales().compareTo(BigDecimal.ZERO) > 0) {
            boolean hasPosReport = receiptFileRepository
                    .findByEntry_IdOrderByCreatedAtAsc(entry.getId())
                    .stream()
                    .anyMatch(f -> POS_REPORT_CATEGORY.equalsIgnoreCase(f.getCategory()));
            if (!hasPosReport) {
                throw new BadRequestException(
                        "Upload the POS card sales report before submitting (a card sales total was recorded but no POS receipt is attached).");
            }
        }
        entry.setStatus(EntryStatus.LOCKED);
        entry.setSubmittedAt(Instant.now());
        entryRepository.save(entry);
        recalculateEntry(entry.getId());
        alertService.checkEntryAlerts(entry);
        DailyEntry saved = load(entry.getId());
        auditService.logChange(user.id(), AuditAction.SUBMIT, "DailyEntry", entry.getId(), before,
                Map.of("status", saved.getStatus().name(), "submittedAt", saved.getSubmittedAt().toString()), null);
        return mapEntry(saved);
    }

    @Transactional
    public Map<String, Object> unlock(String id) {
        AuthHelper.requireOperations();
        DailyEntry entry = load(id);
        if (entry.getStatus() != EntryStatus.LOCKED) {
            throw new BadRequestException("Report is not locked");
        }
        Map<String, Object> before = Map.of("status", entry.getStatus().name());
        entry.setStatus(EntryStatus.DRAFT);
        entry.setSubmittedAt(null);
        entryRepository.save(entry);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UNLOCK, "DailyEntry", entry.getId(), before,
                Map.of("status", EntryStatus.DRAFT.name()), null);
        DailyEntry saved = load(entry.getId());
        return mapEntry(saved);
    }

    @Transactional
    public void delete(String id, String reason) {
        AuthHelper.requireOperations();
        if (reason == null || reason.length() < 3) {
            throw new BadRequestException("Delete reason required (min 3 chars)");
        }
        DailyEntry entry = load(id);
        Map<String, Object> before = AuditSnapshots.entry(entry);
        entry.setDeletedAt(Instant.now());
        entry.setDeleteReason(reason);
        entryRepository.save(entry);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.DELETE, "DailyEntry", entry.getId(), before, Map.of(),
                Map.of("reason", reason));
    }

    @Transactional
    public void recalculateEntry(String entryId) {
        DailyEntry entry = entryRepository.findActiveByIdWithExpenses(entryId)
                .orElseThrow(() -> new NotFoundException("Not found"));
        if (workShiftService.isClosingShift(entry.getCashierId(), entry.getDate())) {
            EntryCalculator.recalculateClosingShift(entry);
        } else {
            BigDecimal closing = EntryCalculator.closingBalance(entry);
            BigDecimal diff = EntryCalculator.difference(entry);
            entry.setClosingBalance(closing);
            entry.setDifference(diff);
        }
        entryRepository.save(entry);
    }

    private void applyEntryRequest(DailyEntry entry, EntryRequest req, String cashierId, LocalDate date) {
        boolean closing = workShiftService.isClosingShift(cashierId, date);
        boolean hasSavedSales = EntryCalculator.totalSales(entry).compareTo(BigDecimal.ZERO) > 0;
        if (closing && !hasSavedSales && !AuthHelper.isOperationsRole()) {
            EntryMapper.applyClosingOnly(entry, req);
        } else {
            EntryMapper.applyRequest(entry, req, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private Map<String, Object> mapEntry(DailyEntry entry) {
        TreasurySettings treasury = loadTreasurySettings();
        List<ExpenseItem> expenseLines = expenseRepository.findByEntryIdWithInvoice(entry.getId());
        // Files loaded with a dedicated query (avoids MultipleBagFetchException when joining
        // both expenses and files in the same fetch); passed explicitly to the mapper.
        List<ReceiptFile> files = receiptFileRepository.findByEntry_IdOrderByCreatedAtAsc(entry.getId());
        return enrichWithShift(
                EntryMapper.toMap(entry, treasury, expenseLines, files),
                entry.getCashierId(),
                entry.getDate(),
                treasury);
    }

    private TreasurySettings loadTreasurySettings() {
        return settingRepository.findById(TreasurySettings.SETTINGS_KEY)
                .map(SystemSetting::getValue)
                .map(TreasurySettings::fromMap)
                .orElseGet(TreasurySettings::new);
    }

    private Map<String, Object> enrichWithShift(
            Map<String, Object> map, String cashierId, LocalDate date, TreasurySettings treasury) {
        Map<String, Object> schedule = workShiftService.scheduleFor(cashierId, date);
        map.put("schedule", schedule);
        map.put("shiftType", schedule.get("shiftType"));
        map.put("closingOnly", schedule.get("closingOnly"));
        if (AuthHelper.isOperationsRole()) {
            var manualToCard = manualDeliveryService.totalCardCreditForDate(date, treasury);
            if (manualToCard.signum() > 0) {
                map.put("manualDeliveryToCard", EntryCalculator.toDouble(manualToCard));
            }
        }
        return map;
    }

    private DailyEntry load(String id) {
        return entryRepository.findActiveByIdWithExpenses(id)
                .or(() -> entryRepository.findActiveByIdWithFiles(id))
                .or(() -> entryRepository.findActiveById(id))
                .orElseThrow(() -> new NotFoundException("Not found"));
    }

    /**
     * Opening = latest restaurant actual cash counted (same day from any colleague, else last close day).
     */
    private Optional<BigDecimal> resolveAutomaticOpening(String cashierId, LocalDate date) {
        Optional<DailyEntry> sameDay = findSameDayRestaurantHandover(cashierId, date);
        if (sameDay.isPresent()) {
            return Optional.of(sameDay.get().getActualCashCounted());
        }
        return findLatestRestaurantCloseBefore(date).map(DailyEntry::getActualCashCounted);
    }

    private record HandoverPendingInfo(String colleagueName) {}

    private Optional<DailyEntry> findSameDayRestaurantHandover(String excludeCashierId, LocalDate date) {
        var list = entryRepository.findLatestSameDayRestaurantCount(
                excludeCashierId, date, EntryStatus.LOCKED, PageRequest.of(0, 1));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private Optional<DailyEntry> findLatestRestaurantCloseBefore(LocalDate date) {
        return entryRepository.findLatestRestaurantCloseDateBefore(date, EntryStatus.LOCKED)
                .flatMap(closeDate -> {
                    var list = entryRepository.findLatestRestaurantCloseOnDate(
                            closeDate, EntryStatus.LOCKED, PageRequest.of(0, 1));
                    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
                });
    }

    private Optional<HandoverPendingInfo> findHandoverPendingInfo(String cashierId, LocalDate date) {
        if (findSameDayRestaurantHandover(cashierId, date).isPresent()) {
            return Optional.empty();
        }
        for (DailyEntry e : entryRepository.findByDateAndDeletedAtIsNull(date)) {
            if (cashierId.equals(e.getCashierId())) {
                continue;
            }
            if (!hasActualCount(e) && e.getCashier() != null) {
                return Optional.of(new HandoverPendingInfo(e.getCashier().getName()));
            }
        }
        return Optional.empty();
    }

    private void putSameDayHandoverResult(Map<String, Object> result, DailyEntry sourceEntry, LocalDate date) {
        BigDecimal raw = nullToZero(sourceEntry.getActualCashCounted());
        BigDecimal postCountOut = postCountCashMovements(sourceEntry, date);
        BigDecimal opening = raw.subtract(postCountOut).max(BigDecimal.ZERO);
        result.put("openingBalance", EntryCalculator.toDouble(opening));
        result.put("rawCountedBalance", EntryCalculator.toDouble(raw));
        result.put("postCountCashOut", EntryCalculator.toDouble(postCountOut));
        result.put("previousDate", date.toString());
        result.put("previousEntryId", sourceEntry.getId());
        result.put("source", "SAME_DAY_HANDOVER");
        result.put("handoverPending", false);
        if (sourceEntry.getCashier() != null) {
            result.put("handoverCashierName", sourceEntry.getCashier().getName());
        }
    }

    private void putPreviousRestaurantCloseResult(
            Map<String, Object> result, DailyEntry sourceEntry, LocalDate newShiftDate) {
        BigDecimal raw = nullToZero(sourceEntry.getActualCashCounted());
        BigDecimal postCountOut = postCountCashMovements(sourceEntry, newShiftDate);
        BigDecimal opening = raw.subtract(postCountOut).max(BigDecimal.ZERO);
        result.put("openingBalance", EntryCalculator.toDouble(opening));
        result.put("rawCountedBalance", EntryCalculator.toDouble(raw));
        result.put("postCountCashOut", EntryCalculator.toDouble(postCountOut));
        result.put("previousDate", sourceEntry.getDate().toString());
        result.put("previousEntryId", sourceEntry.getId());
        result.put("source", "PREVIOUS_DAY");
        result.put("handoverPending", false);
        if (sourceEntry.getCashier() != null) {
            result.put("handoverCashierName", sourceEntry.getCashier().getName());
        }
    }

    /**
     * Sum of cash that left the drawer AFTER the source count was locked but
     * on/before the new shift's date — standalone cash expenses and cash
     * salary payouts that affect treasury. Mirrors the post-count adjustment
     * in {@code TreasuryService.overview()} so the opening drawer always
     * agrees with the "Cash on hand" tile.
     */
    private BigDecimal postCountCashMovements(DailyEntry source, LocalDate newShiftDate) {
        LocalDate cutoffDate = source.getDate();
        Instant cutoffStamp = source.getSubmittedAt();
        if (cutoffDate == null) return BigDecimal.ZERO;
        LocalDate upper = newShiftDate != null && !newShiftDate.isBefore(cutoffDate)
                ? newShiftDate
                : cutoffDate;

        BigDecimal total = BigDecimal.ZERO;
        for (ExpenseItem ex : expenseRepository.findStandaloneBetweenWithInvoices(cutoffDate, upper)) {
            if (ex.getPaymentSource() != PaymentSource.CASH) continue;
            BigDecimal amt = ex.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            if (isAfterCutoff(ex.getEffectiveDate(), ex.getCreatedAt(), cutoffDate, cutoffStamp)) {
                total = total.add(amt);
            }
        }
        for (SalaryPayment p : salaryPaymentRepository.findByPaidDateBetween(cutoffDate, upper)) {
            if (p.getPaymentSource() != PaymentSource.CASH) continue;
            if (p.isExcludeFromTreasury()) continue;
            BigDecimal amt = p.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            if (isAfterCutoff(p.getPaidDate(), p.getCreatedAt(), cutoffDate, cutoffStamp)) {
                total = total.add(amt);
            }
        }
        return total;
    }

    /** Same timestamp-aware cutoff rule used by {@code TreasuryService}. */
    private static boolean isAfterCutoff(
            LocalDate recordDate, Instant recordCreatedAt,
            LocalDate cutoffDate, Instant cutoffStamp) {
        if (cutoffDate == null) return true;
        if (recordDate == null) return false;
        if (recordDate.isAfter(cutoffDate)) return true;
        if (recordDate.isBefore(cutoffDate)) return false;
        if (recordCreatedAt == null || cutoffStamp == null) return true;
        return recordCreatedAt.isAfter(cutoffStamp);
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private void applyOpeningBalance(
            DailyEntry entry, EntryRequest req, Role role, String cashierId, LocalDate date) {
        if (role == Role.ADMIN || role == Role.MANAGER) {
            entry.setOpeningBalance(req.getOpeningBalance());
        } else {
            entry.setOpeningBalance(resolveAutomaticOpening(cashierId, date).orElse(BigDecimal.ZERO));
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s);
    }

    private static void rejectFutureReportDate(LocalDate date) {
        if (date.isAfter(LocalDate.now())) {
            throw new BadRequestException("Cannot create reports for future dates");
        }
    }
}
