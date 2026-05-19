package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.WorkShift;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

/** Computes paid hours from scheduled shift times. */
public final class ShiftHoursUtil {

    public static final LocalTime DEFAULT_SHIFT_START = LocalTime.of(9, 0);
    public static final LocalTime DEFAULT_STORE_CLOSE = LocalTime.of(22, 0);

    private ShiftHoursUtil() {}

    public static BigDecimal hoursWorked(WorkShift shift, LocalTime storeClose) {
        LocalDate date = shift != null ? shift.getDate() : null;
        WeeklyOperatingHours hours = date != null
                ? WeeklyOperatingHours.fromSingleClose(storeClose != null ? storeClose : DEFAULT_STORE_CLOSE)
                : WeeklyOperatingHours.defaults();
        return hoursWorked(shift, hours);
    }

    public static BigDecimal hoursWorked(WorkShift shift, WeeklyOperatingHours restaurantHours) {
        if (shift == null || !shift.isWorking()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        LocalDate date = shift.getDate();
        if (restaurantHours.isClosed(date)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        LocalTime start = shift.getStartTime() != null ? shift.getStartTime() : restaurantHours.openFor(date);
        LocalTime end = shift.getEndTime() != null ? shift.getEndTime() : restaurantHours.closeFor(date);
        if (!end.isAfter(start)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long minutes = Duration.between(start, end).toMinutes();
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    public static LocalTime parseTime(String value, LocalTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String s = value.trim();
        if (s.length() == 5) {
            return LocalTime.parse(s + ":00");
        }
        return LocalTime.parse(s);
    }
}
