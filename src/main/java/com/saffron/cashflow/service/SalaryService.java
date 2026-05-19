package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.Role;
import com.saffron.cashflow.domain.User;
import com.saffron.cashflow.domain.WorkShift;
import com.saffron.cashflow.repository.UserRepository;
import com.saffron.cashflow.repository.WorkShiftRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SalaryService {

    private final UserRepository userRepository;
    private final WorkShiftRepository workShiftRepository;
    private final SettingsService settingsService;

    public SalaryService(
            UserRepository userRepository,
            WorkShiftRepository workShiftRepository,
            SettingsService settingsService) {
        this.userRepository = userRepository;
        this.workShiftRepository = workShiftRepository;
        this.settingsService = settingsService;
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

        List<Map<String, Object>> employees = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        BigDecimal grandHours = BigDecimal.ZERO;

        for (User cashier : cashiers) {
            PayType payType = cashier.getPayType() != null ? cashier.getPayType() : PayType.HOURLY;
            BigDecimal payAmount = rateOrZero(cashier.getPayAmount());
            List<WorkShift> userShifts = byUser.getOrDefault(cashier.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(WorkShift::getDate))
                    .toList();

            BigDecimal totalHours = BigDecimal.ZERO;
            List<Map<String, Object>> shiftRows = new ArrayList<>();
            BigDecimal shiftPaySum = BigDecimal.ZERO;

            for (WorkShift shift : userShifts) {
                BigDecimal hours = SalaryCalculator.hoursWorked(shift, restaurantHours);
                totalHours = totalHours.add(hours);
                BigDecimal dayPay = SalaryCalculator.payForShift(shift, payType, payAmount, restaurantHours);
                if (payType != PayType.MONTHLY) {
                    shiftPaySum = shiftPaySum.add(dayPay);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", shift.getDate().toString());
                row.put("hours", toDouble(hours));
                row.put("hoursLabel", WorkShiftService.hoursLabel(shift));
                row.put("pay", toDouble(dayPay));
                row.put("payNote", shiftPayNote(payType, hours, payAmount, shift, restaurantHours));
                shiftRows.add(row);
            }

            BigDecimal totalPay;
            if (payType == PayType.MONTHLY) {
                totalPay = SalaryCalculator.monthlyPayForPeriod(userShifts.size(), from, to, payAmount);
                if (!shiftRows.isEmpty()) {
                    BigDecimal perDay = totalPay.divide(
                            BigDecimal.valueOf(userShifts.size()), 2, RoundingMode.HALF_UP);
                    for (Map<String, Object> row : shiftRows) {
                        row.put("pay", toDouble(perDay));
                        row.put("payNote", "Monthly ÷ " + calendarDays + " days × 1 worked day");
                    }
                }
            } else {
                totalPay = shiftPaySum.setScale(2, RoundingMode.HALF_UP);
            }

            grandTotal = grandTotal.add(totalPay);
            grandHours = grandHours.add(totalHours);

            Map<String, Object> emp = new LinkedHashMap<>();
            emp.put("userId", cashier.getId());
            emp.put("name", cashier.getName());
            emp.put("email", cashier.getEmail());
            emp.put("active", cashier.isActive());
            emp.put("payType", payType.name());
            emp.put("payTypeLabel", SalaryCalculator.payTypeLabel(payType));
            emp.put("payAmount", toDouble(payAmount));
            emp.put("payAmountLabel", SalaryCalculator.amountLabel(payType));
            emp.put("calculationSummary", calculationSummary(payType, payAmount, userShifts.size(), calendarDays));
            emp.put("shiftCount", userShifts.size());
            emp.put("totalHours", toDouble(totalHours));
            emp.put("totalPay", toDouble(totalPay));
            emp.put("shifts", shiftRows);
            employees.add(emp);
        }

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
        result.put("rules", payrollRules());
        return result;
    }

    private static List<Map<String, String>> payrollRules() {
        List<Map<String, String>> rules = new ArrayList<>();
        rules.add(rule("HOURLY", "Each day: hours worked × hourly rate. Different hours = different pay that day."));
        rules.add(rule("DAILY", "Each day: daily rate × (shift hours ÷ restaurant open hours that day). 4h on a 12h open day = 33%."));
        rules.add(rule("MONTHLY", "Period total: monthly salary × (days worked ÷ days in period). Hours only affect attendance count, not the formula."));
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
