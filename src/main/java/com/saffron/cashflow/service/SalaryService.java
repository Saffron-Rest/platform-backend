package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.SalaryPayment;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.domain.WorkShift;
import com.saffron.cashflow.repository.SalaryPaymentRepository;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.repository.WorkShiftRepository;
import com.saffron.cashflow.util.SalaryPaymentPeriod;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.SalaryCalculator;
import com.saffron.cashflow.util.WeeklyOperatingHours;
import com.saffron.cashflow.web.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SalaryService {

    private final UserRepository userRepository;
    private final WorkShiftRepository workShiftRepository;
    private final SettingsService settingsService;
    private final PayRateService payRateService;
    private final SalaryPaymentRepository salaryPaymentRepository;

    public SalaryService(
            UserRepository userRepository,
            WorkShiftRepository workShiftRepository,
            SettingsService settingsService,
            PayRateService payRateService,
            SalaryPaymentRepository salaryPaymentRepository) {
        this.userRepository = userRepository;
        this.workShiftRepository = workShiftRepository;
        this.settingsService = settingsService;
        this.payRateService = payRateService;
        this.salaryPaymentRepository = salaryPaymentRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> calculate(String fromParam, String toParam) {
        AuthHelper.requireAdmin();
        LocalDate from = LocalDate.parse(fromParam);
        LocalDate to = LocalDate.parse(toParam);
        if (to.isBefore(from)) {
            throw new BadRequestException("'to' must be on or after 'from'");
        }

        WeeklyOperatingHours restaurantHours = settingsService.loadWeeklyHours();
        long calendarDays = ChronoUnit.DAYS.between(from, to) + 1;
        List<WorkShift> shifts = workShiftRepository.findWorkingBetween(from, to);
        Map<String, List<WorkShift>> byUser = shifts.stream()
                .collect(Collectors.groupingBy(WorkShift::getUserId));

        List<User> cashiers = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.CASHIER)
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<SalaryPayment> periodPayments = salaryPaymentRepository.findApplicableToPayrollPeriod(from, to);
        Map<String, BigDecimal> paidByUser = SalaryPaymentPeriod.sumPaidByUser(periodPayments, from, to);
        Map<String, List<SalaryPayment>> paymentsByUser =
                SalaryPaymentPeriod.paymentsByUser(periodPayments, from, to);

        List<Map<String, Object>> employees = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        BigDecimal grandPaid = BigDecimal.ZERO;
        BigDecimal grandHours = BigDecimal.ZERO;

        for (User cashier : cashiers) {
            PayType currentPayType = cashier.getPayType() != null ? cashier.getPayType() : PayType.HOURLY;
            BigDecimal currentPayAmount = rateOrZero(cashier.getPayAmount());
            List<WorkShift> userShifts = byUser.getOrDefault(cashier.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(WorkShift::getDate))
                    .toList();

            BigDecimal totalHours = BigDecimal.ZERO;
            List<Map<String, Object>> shiftRows = new ArrayList<>();
            BigDecimal shiftPaySum = BigDecimal.ZERO;
            Map<String, Integer> monthlyDaysByRate = new LinkedHashMap<>();
            Map<String, BigDecimal> monthlyBandTotals = new HashMap<>();

            for (WorkShift shift : userShifts) {
                PayRateService.ResolvedPay rate =
                        payRateService.resolve(cashier.getId(), shift.getDate(), cashier);
                PayType payType = rate.payType() != null ? rate.payType() : PayType.HOURLY;
                BigDecimal payAmount = rateOrZero(rate.payAmount());

                BigDecimal hours = SalaryCalculator.hoursWorked(shift, restaurantHours);
                totalHours = totalHours.add(hours);
                BigDecimal dayPay = SalaryCalculator.payForShift(shift, payType, payAmount, restaurantHours);

                if (payType == PayType.MONTHLY) {
                    String key = rateKey(payType, payAmount);
                    monthlyDaysByRate.merge(key, 1, Integer::sum);
                } else {
                    shiftPaySum = shiftPaySum.add(dayPay);
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", shift.getDate().toString());
                row.put("hours", toDouble(hours));
                row.put("hoursLabel", WorkShiftService.hoursLabel(shift));
                row.put("pay", toDouble(payType == PayType.MONTHLY ? BigDecimal.ZERO : dayPay));
                row.put("payNote", shiftPayNote(payType, hours, payAmount, shift, restaurantHours));
                row.put("payType", payType.name());
                row.put("payAmount", toDouble(payAmount));
                // Surface "this hours figure is estimated, not measured" to
                // the frontend. True whenever the shift was scheduled as
                // till-close (endTime is null) so hoursWorked() filled in
                // restaurantHours.closeFor(date) instead of a real end time.
                row.put("tillCloseAssumed", shift.getEndTime() == null);
                if (rate.effectiveFrom() != null) {
                    row.put("rateEffectiveFrom", rate.effectiveFrom().toString());
                }
                shiftRows.add(row);
            }

            for (Map.Entry<String, Integer> band : monthlyDaysByRate.entrySet()) {
                BigDecimal amount = parseRateKeyAmount(band.getKey());
                BigDecimal bandTotal =
                        SalaryCalculator.monthlyPayForPeriod(band.getValue(), from, to, amount);
                monthlyBandTotals.put(band.getKey(), bandTotal);
            }

            BigDecimal totalPay = shiftPaySum;
            for (BigDecimal bandTotal : monthlyBandTotals.values()) {
                totalPay = totalPay.add(bandTotal);
            }
            totalPay = totalPay.setScale(2, RoundingMode.HALF_UP);

            for (int i = 0; i < userShifts.size(); i++) {
                WorkShift shift = userShifts.get(i);
                PayRateService.ResolvedPay rate =
                        payRateService.resolve(cashier.getId(), shift.getDate(), cashier);
                if (rate.payType() != PayType.MONTHLY) {
                    continue;
                }
                String key = rateKey(rate.payType(), rate.payAmount());
                int daysInBand = monthlyDaysByRate.getOrDefault(key, 1);
                BigDecimal bandTotal = monthlyBandTotals.getOrDefault(key, BigDecimal.ZERO);
                BigDecimal perDay = bandTotal.divide(
                        BigDecimal.valueOf(daysInBand), 2, RoundingMode.HALF_UP);
                Map<String, Object> row = shiftRows.get(i);
                row.put("pay", toDouble(perDay));
                row.put(
                        "payNote",
                        "Monthly "
                                + rate.payAmount()
                                + " PLN × ("
                                + daysInBand
                                + " ÷ "
                                + calendarDays
                                + " days) ÷ "
                                + daysInBand
                                + " worked day(s) at this rate");
            }

            grandTotal = grandTotal.add(totalPay);
            grandHours = grandHours.add(totalHours);

            // "Earned till today" — only shifts whose date is on or before today
            // count. Lets the UI show progress within the current pay period
            // (e.g. "1,500 of 2,000 PLN earned so far"). For past periods this
            // equals totalPay; for future periods it's zero.
            LocalDate today = LocalDate.now();
            BigDecimal earnedToDate;
            int daysWorkedToDate = 0;
            BigDecimal hoursToDate = BigDecimal.ZERO;
            if (today.isBefore(from)) {
                earnedToDate = BigDecimal.ZERO;
            } else if (today.isAfter(to)) {
                earnedToDate = totalPay;
                daysWorkedToDate = userShifts.size();
                hoursToDate = totalHours;
            } else {
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < userShifts.size(); i++) {
                    WorkShift shift = userShifts.get(i);
                    if (shift.getDate().isAfter(today)) continue;
                    daysWorkedToDate++;
                    Map<String, Object> row = shiftRows.get(i);
                    Object pay = row.get("pay");
                    if (pay instanceof Number n) {
                        sum = sum.add(BigDecimal.valueOf(n.doubleValue()));
                    }
                    Object hrs = row.get("hours");
                    if (hrs instanceof Number n) {
                        hoursToDate = hoursToDate.add(BigDecimal.valueOf(n.doubleValue()));
                    }
                }
                earnedToDate = sum.setScale(2, RoundingMode.HALF_UP);
                hoursToDate = hoursToDate.setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal paidAmount = paidByUser.getOrDefault(cashier.getId(), BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal remainingPay = totalPay.subtract(paidAmount).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            // "Owed right now" — what's been earned through today minus what's
            // been paid so far. Useful when the period is mid-flight: pays out
            // exactly what's due even though more days are scheduled later.
            BigDecimal owedNow = earnedToDate.subtract(paidAmount).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            grandPaid = grandPaid.add(paidAmount);

            boolean multipleRates = payRateService.hasMultipleRates(cashier.getId())
                    || monthlyDaysByRate.size() > 1;

            Map<String, Object> emp = new LinkedHashMap<>();
            emp.put("userId", cashier.getId());
            emp.put("name", cashier.getName());
            emp.put("email", cashier.getEmail());
            emp.put("active", cashier.isActive());
            emp.put("payType", currentPayType.name());
            emp.put("payTypeLabel", SalaryCalculator.payTypeLabel(currentPayType));
            emp.put("payAmount", toDouble(currentPayAmount));
            emp.put("payAmountLabel", SalaryCalculator.amountLabel(currentPayType));
            emp.put(
                    "calculationSummary",
                    multipleRates
                            ? "Uses pay rate in effect on each shift date (see history in Team)"
                            : calculationSummary(
                                    currentPayType, currentPayAmount, userShifts.size(), calendarDays));
            emp.put("usesPayHistory", multipleRates);
            emp.put("shiftCount", userShifts.size());
            emp.put("daysWorkedToDate", daysWorkedToDate);
            emp.put("totalHours", toDouble(totalHours));
            emp.put("hoursToDate", toDouble(hoursToDate));
            emp.put("totalPay", toDouble(totalPay));
            emp.put("earnedToDate", toDouble(earnedToDate));
            emp.put("paidAmount", toDouble(paidAmount));
            emp.put("remainingPay", toDouble(remainingPay));
            emp.put("owedNow", toDouble(owedNow));
            emp.put("fullyPaid", remainingPay.compareTo(BigDecimal.ZERO) <= 0 && totalPay.compareTo(BigDecimal.ZERO) > 0);
            emp.put("payments", paymentMaps(paymentsByUser.getOrDefault(cashier.getId(), List.of())));
            emp.put("shifts", shiftRows);
            employees.add(emp);
        }

        BigDecimal grandRemaining = grandTotal.subtract(grandPaid).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("calendarDays", calendarDays);
        result.put("weeklyHours", restaurantHours.toApiMap());
        result.put("storeCloseTime", formatTime(restaurantHours.closeFor(to)));
        result.put("standardDayHours", SalaryCalculator.STANDARD_DAY_HOURS.doubleValue());
        result.put("currency", "PLN");
        result.put("employees", employees);
        result.put("grandTotalHours", toDouble(grandHours));
        result.put("grandTotalPay", toDouble(grandTotal));
        result.put("grandTotalPaid", toDouble(grandPaid));
        result.put("grandTotalRemaining", toDouble(grandRemaining));
        result.put("periodPayments", paymentMaps(periodPayments));
        result.put("rules", payrollRules());
        return result;
    }

    private List<Map<String, Object>> paymentMaps(List<SalaryPayment> payments) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SalaryPayment p : payments) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("userId", p.getUserId());
            m.put("amount", toDouble(p.getAmount()));
            m.put("paidDate", p.getPaidDate().toString());
            m.put("source", p.getPaymentSource().name());
            if (p.getPeriodFrom() != null) m.put("periodFrom", p.getPeriodFrom().toString());
            if (p.getPeriodTo() != null) m.put("periodTo", p.getPeriodTo().toString());
            if (p.getNotes() != null && !p.getNotes().isBlank()) m.put("notes", p.getNotes());
            m.put("excludeFromTreasury", p.isExcludeFromTreasury());
            rows.add(m);
        }
        return rows;
    }

    private static String rateKey(PayType payType, BigDecimal payAmount) {
        return payType.name()
                + "|"
                + payAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal parseRateKeyAmount(String key) {
        int sep = key.indexOf('|');
        return new BigDecimal(key.substring(sep + 1));
    }

    private static List<Map<String, String>> payrollRules() {
        List<Map<String, String>> rules = new ArrayList<>();
        rules.add(rule(
                "HOURLY",
                "Each day: hours worked × hourly rate in effect on that date. Past periods keep the old rate."));
        rules.add(rule(
                "DAILY",
                "Each day: daily rate × (shift hours ÷ open hours). Rate in effect on that date applies."));
        rules.add(rule(
                "MONTHLY",
                "Period total: monthly salary × (days worked ÷ days in period), per rate band if pay changed mid-period."));
        rules.add(rule(
                "CHANGES",
                "Raise or lower pay in Team with an effective date. Shifts before that date use the previous rate."));
        return rules;
    }

    private static Map<String, String> rule(String type, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("payType", type);
        m.put("text", text);
        return m;
    }

    private static String shiftPayNote(
            PayType payType,
            BigDecimal hours,
            BigDecimal payAmount,
            WorkShift shift,
            WeeklyOperatingHours restaurantHours) {
        String closeHint = "";
        if (shift.getEndTime() == null && shift.getDate() != null) {
            closeHint = " (close " + formatTime(restaurantHours.closeFor(shift.getDate())) + ")";
        }
        if (restaurantHours.isClosed(shift.getDate())) {
            return "Restaurant closed this weekday";
        }
        return switch (payType) {
            case HOURLY -> hours.setScale(1, RoundingMode.HALF_UP) + " h × " + payAmount + " PLN/h" + closeHint;
            case DAILY -> {
                BigDecimal openH = restaurantHours.openHoursFor(shift.getDate());
                BigDecimal fraction = SalaryCalculator.dailyFraction(hours, restaurantHours, shift.getDate());
                String tillCloseWarn = shift.getEndTime() == null
                        ? " — till close uses full open hours; set an end time for partial days"
                        : "";
                yield hours.setScale(1, RoundingMode.HALF_UP) + "h ÷ "
                        + openH.setScale(1, RoundingMode.HALF_UP) + "h open = "
                        + fraction.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
                        + "% of day rate" + tillCloseWarn;
            }
            case MONTHLY -> "Counts as 1 worked day";
        };
    }

    private static String calculationSummary(PayType payType, BigDecimal payAmount, int daysWorked, long calendarDays) {
        return switch (payType) {
            case HOURLY -> "Sum of (hours × " + payAmount + " PLN/h) per shift";
            case DAILY -> "Sum of (day rate × shift hours÷open hours) per day, max one full day";
            case MONTHLY -> payAmount + " PLN × (" + daysWorked + " ÷ " + calendarDays + " days in period)";
        };
    }

    private static BigDecimal rateOrZero(BigDecimal rate) {
        return rate != null ? rate : BigDecimal.ZERO;
    }

    private static double toDouble(BigDecimal v) {
        return v != null ? v.doubleValue() : 0;
    }

    private static String formatTime(LocalTime t) {
        return t == null ? null : t.toString().substring(0, 5);
    }
}
