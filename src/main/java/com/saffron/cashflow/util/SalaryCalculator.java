package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.PayType;
import com.saffron.cashflow.domain.WorkShift;
import com.saffron.cashflow.util.WeeklyOperatingHours;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

public final class SalaryCalculator {

    /** Fallback when restaurant hours are unavailable. */
    public static final BigDecimal STANDARD_DAY_HOURS = new BigDecimal("8");

    private SalaryCalculator() {}

    public static BigDecimal hoursWorked(WorkShift shift, WeeklyOperatingHours restaurantHours) {
        return ShiftHoursUtil.hoursWorked(shift, restaurantHours);
    }

    /** Pay for a single scheduled day (hourly or daily). Monthly returns zero per shift. */
    public static BigDecimal payForShift(
            WorkShift shift, PayType payType, BigDecimal payAmount, WeeklyOperatingHours restaurantHours) {
        if (payAmount == null || payAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal hours = hoursWorked(shift, restaurantHours);
        return switch (payType != null ? payType : PayType.HOURLY) {
            case HOURLY -> hours.multiply(payAmount).setScale(2, RoundingMode.HALF_UP);
            case DAILY -> dailyPayForShift(shift, hours, payAmount, restaurantHours);
            case MONTHLY -> BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        };
    }

    /**
     * Daily: day rate × (shift hours ÷ restaurant open hours that day), max 100%.
     * Example: restaurant open 12h, employee works 4h → 4/12 = 33% of daily rate.
     */
    public static BigDecimal dailyPayForShift(
            WorkShift shift, BigDecimal hoursWorked, BigDecimal dailyRate, WeeklyOperatingHours restaurantHours) {
        if (hoursWorked.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal fullDayHours = restaurantHours != null && shift.getDate() != null
                ? restaurantHours.openHoursFor(shift.getDate())
                : STANDARD_DAY_HOURS;
        if (fullDayHours.compareTo(BigDecimal.ZERO) <= 0) {
            fullDayHours = STANDARD_DAY_HOURS;
        }
        BigDecimal fraction = hoursWorked.divide(fullDayHours, 4, RoundingMode.HALF_UP);
        if (fraction.compareTo(BigDecimal.ONE) > 0) {
            fraction = BigDecimal.ONE;
        }
        return dailyRate.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal dailyFraction(BigDecimal hoursWorked, WeeklyOperatingHours restaurantHours, LocalDate date) {
        BigDecimal fullDay = restaurantHours.openHoursFor(date);
        if (fullDay.compareTo(BigDecimal.ZERO) <= 0) {
            fullDay = STANDARD_DAY_HOURS;
        }
        BigDecimal fraction = hoursWorked.divide(fullDay, 4, RoundingMode.HALF_UP);
        return fraction.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : fraction;
    }

    /**
     * Monthly: fixed monthly salary × (days worked in range ÷ calendar days in range).
     * Hours per day do not change monthly pay — only whether they were scheduled that day.
     */
    public static BigDecimal monthlyPayForPeriod(int daysWorked, LocalDate from, LocalDate to, BigDecimal monthlyRate) {
        if (monthlyRate == null || monthlyRate.compareTo(BigDecimal.ZERO) <= 0 || daysWorked <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long calendarDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (calendarDays <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return monthlyRate
                .multiply(BigDecimal.valueOf(daysWorked))
                .divide(BigDecimal.valueOf(calendarDays), 2, RoundingMode.HALF_UP);
    }

    public static String payTypeLabel(PayType type) {
        return switch (type != null ? type : PayType.HOURLY) {
            case HOURLY -> "Hourly";
            case DAILY -> "Daily";
            case MONTHLY -> "Monthly";
        };
    }

    public static String amountLabel(PayType type) {
        return switch (type != null ? type : PayType.HOURLY) {
            case HOURLY -> "PLN/hour";
            case DAILY -> "PLN/day (full open day)";
            case MONTHLY -> "PLN/month";
        };
    }
}
