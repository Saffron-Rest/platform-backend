package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.ShiftType;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.domain.WorkShift;
import com.saffron.cashflow.dto.AssignShiftRequest;
import com.saffron.cashflow.dto.UpsertScheduleRequest;
import com.saffron.cashflow.web.NotFoundException;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.repository.WorkShiftRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.security.AuthUser;
import com.saffron.cashflow.security.ForbiddenException;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Service
@Transactional(readOnly = true)
public class WorkShiftService {

    private static final LocalTime DEFAULT_SHIFT_END = LocalTime.of(17, 0);

    private final WorkShiftRepository workShiftRepository;
    private final UserRepository userRepository;

    private record ReconcileResult(boolean adjusted, String closerName) {}

    public WorkShiftService(WorkShiftRepository workShiftRepository, UserRepository userRepository) {
        this.workShiftRepository = workShiftRepository;
        this.userRepository = userRepository;
    }

    public Optional<WorkShift> findShift(String userId, LocalDate date) {
        return workShiftRepository.findByUser_IdAndDateWithUser(userId, date);
    }

    public ShiftType resolveShiftType(String userId, LocalDate date) {
        return findShift(userId, date)
                .filter(WorkShift::isWorking)
                .map(WorkShift::getShiftType)
                .orElse(ShiftType.FULL);
    }

    /**
     * True when this cashier is on the dedicated closing shift AND there is at least one
     * other working cashier that day (otherwise they're the sole reporter and need the
     * full form, not the closing-only short form).
     */
    public boolean isClosingShift(String userId, LocalDate date) {
        if (resolveShiftType(userId, date) != ShiftType.CLOSING) return false;
        return countWorkingOnDate(date) > 1;
    }

    public boolean isScheduledToWork(String userId, LocalDate date) {
        return findShift(userId, date).map(WorkShift::isWorking).orElse(false);
    }

    /** All working shifts on a date (with user loaded). */
    public List<WorkShift> findWorkingShiftsOnDate(LocalDate date) {
        return workShiftRepository.findByDateWithUser(date).stream()
                .filter(WorkShift::isWorking)
                .toList();
    }

    /**
     * Earlier colleague on the same day whose shift ends at a fixed time (handoff before this cashier starts).
     * Returns empty when two people are both "till close" with no end time — no clear handoff point.
     */
    public Optional<WorkShift> findHandoverSource(String cashierId, LocalDate date) {
        Optional<WorkShift> mine = findShift(cashierId, date);
        if (mine.isEmpty() || !mine.get().isWorking()) {
            return Optional.empty();
        }
        LocalTime myStart = mine.get().getStartTime();
        WorkShift best = null;
        for (WorkShift other : workShiftRepository.findByDateWithUser(date)) {
            if (!other.isWorking() || cashierId.equals(other.getUserId())) {
                continue;
            }
            LocalTime otherEnd = other.getEndTime();
            if (otherEnd == null) {
                continue;
            }
            if (myStart != null && otherEnd.isAfter(myStart)) {
                continue;
            }
            if (best == null || otherEnd.isAfter(best.getEndTime())) {
                best = other;
            }
        }
        return Optional.ofNullable(best);
    }

    public long countTillCloseOnDate(LocalDate date) {
        return workShiftRepository.findByDateWithUser(date).stream()
                .filter(w -> w.isWorking() && w.getEndTime() == null)
                .count();
    }

    public Map<String, Object> scheduleFor(String userId, LocalDate date) {
        long workingCount = countWorkingOnDate(date);
        return findShift(userId, date)
                .map(w -> toMap(w, date, workingCount))
                .orElse(offDayMap(userId, date));
    }

    public Map<String, Object> getToday(String dateParam, String userIdParam) {
        AuthUser user = AuthHelper.currentUser();
        LocalDate date = parseDate(dateParam);
        String userId = user.role() == Role.CASHIER ? user.id() : userIdParam;
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("userId is required");
        }
        if (user.role() == Role.CASHIER && !userId.equals(user.id())) {
            throw new ForbiddenException("Forbidden");
        }
        long workingCount = countWorkingOnDate(date);
        return findShift(userId, date)
                .map(w -> toMap(w, date, workingCount))
                .orElse(offDayMap(userId, date));
    }

    /** All working assignments on a single day (multiple employees allowed). */
    public List<Map<String, Object>> listForDate(String dateParam) {
        AuthHelper.currentUser();
        LocalDate date = parseDate(dateParam);
        List<WorkShift> working = workShiftRepository.findByDateWithUser(date).stream()
                .filter(WorkShift::isWorking)
                .toList();
        long workingCount = working.size();
        return working.stream().map(w -> toMap(w, date, workingCount)).toList();
    }

    /** Calendar month view: date → list of assignments (read-only for cashiers). */
    public Map<String, List<Map<String, Object>>> listForRange(String fromParam, String toParam) {
        AuthHelper.currentUser();
        LocalDate from = LocalDate.parse(fromParam);
        LocalDate to = LocalDate.parse(toParam);
        if (to.isBefore(from)) {
            throw new BadRequestException("'to' must be on or after 'from'");
        }
        // Pre-compute working counts per date so the per-shift `closingOnly` flag stays
        // consistent with the rule: a sole working cashier always gets the full report.
        Map<LocalDate, Long> workingByDate = new java.util.HashMap<>();
        for (WorkShift w : workShiftRepository.findWorkingBetween(from, to)) {
            workingByDate.merge(w.getDate(), 1L, Long::sum);
        }
        Map<String, List<Map<String, Object>>> byDate = new TreeMap<>();
        for (WorkShift w : workShiftRepository.findWorkingBetween(from, to)) {
            String key = w.getDate().toString();
            long count = workingByDate.getOrDefault(w.getDate(), 1L);
            byDate.computeIfAbsent(key, k -> new ArrayList<>()).add(toMap(w, w.getDate(), count));
        }
        return byDate;
    }

    /** Number of cashiers actually working a given date. */
    private long countWorkingOnDate(LocalDate date) {
        return workShiftRepository.findByDateWithUser(date).stream()
                .filter(WorkShift::isWorking)
                .count();
    }

    @Transactional
    public Map<String, Object> assign(AssignShiftRequest req) {
        AuthHelper.requireOperations();
        LocalDate date = LocalDate.parse(req.date());
        User cashier = userRepository.findById(req.userId())
                .orElseThrow(() -> new BadRequestException("Unknown user"));
        if (cashier.getRole() != Role.CASHIER) {
            throw new BadRequestException("Only cashiers can be scheduled");
        }
        WorkShift shift = workShiftRepository.findByUser_IdAndDateWithUser(req.userId(), date)
                .orElseGet(() -> {
                    WorkShift w = new WorkShift();
                    w.setUser(cashier);
                    w.setDate(date);
                    return w;
                });
        UpsertScheduleRequest.ShiftAssignment a = new UpsertScheduleRequest.ShiftAssignment(
                req.userId(),
                true,
                req.startTime(),
                req.tillClose() ? null : req.endTime(),
                req.shiftType()
        );
        applyAssignment(shift, a);
        workShiftRepository.save(shift);
        ReconcileResult reconciled = reconcileClosingShifts(date);
        shift = workShiftRepository.findByUser_IdAndDateWithUser(req.userId(), date).orElse(shift);
        Map<String, Object> map = toMap(shift, date, countWorkingOnDate(date));
        if (reconciled.adjusted()) {
            map.put("autoAdjustedClosing", true);
            map.put("designatedCloserName", reconciled.closerName());
        }
        return map;
    }

    @Transactional
    public void deleteShift(String id) {
        AuthHelper.requireOperations();
        WorkShift shift = workShiftRepository.findByIdWithUser(id)
                .orElseThrow(() -> new NotFoundException("Shift not found"));
        LocalDate date = shift.getDate();
        workShiftRepository.delete(shift);
        reconcileClosingShifts(date);
    }

    @Transactional
    public List<Map<String, Object>> upsertSchedule(UpsertScheduleRequest req) {
        AuthHelper.requireOperations();
        LocalDate date = LocalDate.parse(req.date());
        for (UpsertScheduleRequest.ShiftAssignment a : req.shifts()) {
            User cashier = userRepository.findById(a.userId())
                    .orElseThrow(() -> new BadRequestException("Unknown user: " + a.userId()));
            if (cashier.getRole() != Role.CASHIER) {
                throw new BadRequestException("Only cashiers can be scheduled: " + cashier.getEmail());
            }
            WorkShift shift = workShiftRepository.findByUser_IdAndDateWithUser(a.userId(), date)
                    .orElseGet(() -> {
                        WorkShift w = new WorkShift();
                        w.setUser(cashier);
                        w.setDate(date);
                        return w;
                    });
            applyAssignment(shift, a);
            workShiftRepository.save(shift);
        }
        reconcileClosingShifts(date);
        return listForDate(date.toString());
    }

    /**
     * Exactly one employee per day may work until close (CLOSING report).
     * If several are marked till-close, the latest-starting shift keeps closing; others get a fixed end time.
     */
    private ReconcileResult reconcileClosingShifts(LocalDate date) {
        List<WorkShift> working = workShiftRepository.findByDateWithUser(date).stream()
                .filter(WorkShift::isWorking)
                .toList();
        List<WorkShift> tillClose = working.stream().filter(w -> w.getEndTime() == null).toList();
        boolean adjusted = false;

        if (tillClose.size() <= 1) {
            for (WorkShift w : working) {
                applyShiftTypeFromTimes(w);
            }
            workShiftRepository.saveAll(working);
            String name = tillClose.isEmpty() ? null : userName(tillClose.getFirst());
            return new ReconcileResult(false, name);
        }

        WorkShift closer = tillClose.stream()
                .max(Comparator
                        .comparing((WorkShift w) -> w.getStartTime() != null ? w.getStartTime() : LocalTime.MIN)
                        .thenComparing(w -> userName(w) != null ? userName(w) : ""))
                .orElse(tillClose.getLast());

        for (WorkShift w : working) {
            if (w.getId().equals(closer.getId())) {
                w.setEndTime(null);
                w.setShiftType(ShiftType.CLOSING);
            } else if (w.getEndTime() == null) {
                w.setEndTime(demoteEndTime(w, closer));
                w.setShiftType(ShiftType.FULL);
                adjusted = true;
            } else {
                applyShiftTypeFromTimes(w);
            }
        }
        workShiftRepository.saveAll(working);
        return new ReconcileResult(adjusted, userName(closer));
    }

    private static void applyShiftTypeFromTimes(WorkShift w) {
        w.setShiftType(w.getEndTime() == null ? ShiftType.CLOSING : ShiftType.FULL);
    }

    private static LocalTime demoteEndTime(WorkShift demoted, WorkShift closer) {
        LocalTime closerStart = closer.getStartTime();
        LocalTime demotedStart = demoted.getStartTime();
        if (closerStart != null && demotedStart != null && closerStart.isAfter(demotedStart)) {
            return closerStart;
        }
        if (closerStart != null && demotedStart == null) {
            return closerStart;
        }
        return DEFAULT_SHIFT_END;
    }

    private static String userName(WorkShift w) {
        User u = w.getUser();
        return u != null ? u.getName() : null;
    }

    private void applyAssignment(WorkShift shift, UpsertScheduleRequest.ShiftAssignment a) {
        shift.setWorking(a.working());
        if (!a.working()) {
            shift.setStartTime(null);
            shift.setEndTime(null);
            shift.setShiftType(ShiftType.FULL);
            return;
        }
        LocalTime start = parseTime(a.startTime(), "startTime");
        LocalTime end = a.endTime() == null || a.endTime().isBlank() ? null : parseTime(a.endTime(), "endTime");
        if (start != null && end != null && !end.isAfter(start)) {
            throw new BadRequestException("End time must be after start time for " + shift.getUser().getName());
        }
        shift.setStartTime(start);
        shift.setEndTime(end);
        if (a.shiftType() != null) {
            shift.setShiftType(a.shiftType());
        } else {
            shift.setShiftType(end == null ? ShiftType.CLOSING : ShiftType.FULL);
        }
    }

    private static Map<String, Object> toMap(WorkShift w, LocalDate date, long workingCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        User u = w.getUser();
        m.put("id", w.getId());
        m.put("userId", w.getUserId());
        if (u != null) {
            m.put("name", u.getName());
            m.put("email", u.getEmail());
        }
        // A sole working cashier handles the whole day's reporting (nobody else can
        // record sales for them), so they always get the full report — even if their
        // schedule is marked "till close".
        boolean isCloser = w.isWorking() && w.getShiftType() == ShiftType.CLOSING;
        boolean closingOnly = isCloser && workingCount > 1;

        m.put("date", date.toString());
        m.put("working", w.isWorking());
        m.put("startTime", formatTime(w.getStartTime()));
        m.put("endTime", formatTime(w.getEndTime()));
        m.put("tillClose", w.isWorking() && w.getEndTime() == null);
        m.put("shiftType", closingOnly ? ShiftType.CLOSING.name() : ShiftType.FULL.name());
        m.put("closingOnly", closingOnly);
        m.put("designatedCloser", isCloser && w.getEndTime() == null);
        m.put("hoursLabel", hoursLabel(w));
        return m;
    }

    private static Map<String, Object> defaultRow(User u, LocalDate date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.getId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("date", date.toString());
        m.put("working", false);
        m.put("startTime", null);
        m.put("endTime", null);
        m.put("tillClose", false);
        m.put("shiftType", ShiftType.FULL.name());
        m.put("closingOnly", false);
        m.put("designatedCloser", false);
        m.put("hoursLabel", "Off");
        return m;
    }

    private static Map<String, Object> offDayMap(String userId, LocalDate date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("date", date.toString());
        m.put("working", false);
        m.put("startTime", null);
        m.put("endTime", null);
        m.put("tillClose", false);
        m.put("shiftType", ShiftType.FULL.name());
        m.put("closingOnly", false);
        m.put("designatedCloser", false);
        m.put("hoursLabel", "Not scheduled");
        return m;
    }

    static String hoursLabel(WorkShift w) {
        if (!w.isWorking()) return "Off";
        if (w.getStartTime() == null && w.getEndTime() == null) return "All day";
        String start = formatTime(w.getStartTime());
        if (w.getEndTime() == null) {
            return w.getShiftType() == ShiftType.CLOSING ? start + " – close (final count)" : start + " – close";
        }
        return start + " – " + formatTime(w.getEndTime());
    }

    private static String formatTime(LocalTime t) {
        return t == null ? null : t.toString().substring(0, 5);
    }

    private static LocalTime parseTime(String s, String field) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() == 5) return LocalTime.parse(s + ":00");
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid " + field + ": " + s);
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return LocalDate.now();
        return LocalDate.parse(s);
    }
}
