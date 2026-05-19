package com.saffron.cashflow.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Restaurant open/close times per day of week (for shift & payroll hour calculation). */
public final class WeeklyOperatingHours {

    public record DaySchedule(boolean closed, LocalTime open, LocalTime close) {}

    private final EnumMap<DayOfWeek, DaySchedule> byDay;

    public WeeklyOperatingHours(Map<DayOfWeek, DaySchedule> schedule) {
        this.byDay = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            this.byDay.put(d, schedule.getOrDefault(d, defaultFor(d)));
        }
    }

    public boolean isClosed(LocalDate date) {
        return byDay.get(date.getDayOfWeek()).closed();
    }

    public LocalTime openFor(LocalDate date) {
        DaySchedule d = byDay.get(date.getDayOfWeek());
        return d.closed() ? ShiftHoursUtil.DEFAULT_SHIFT_START : d.open();
    }

    public LocalTime closeFor(LocalDate date) {
        DaySchedule d = byDay.get(date.getDayOfWeek());
        return d.closed() ? d.open() : d.close();
    }

    /** How long the restaurant is open on this weekday (for daily pay pro-rating). */
    public BigDecimal openHoursFor(LocalDate date) {
        if (isClosed(date)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        LocalTime open = openFor(date);
        LocalTime close = closeFor(date);
        if (!close.isAfter(open)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long minutes = Duration.between(open, close).toMinutes();
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            DaySchedule s = byDay.get(d);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("closed", s.closed());
            day.put("open", format(s.open()));
            day.put("close", format(s.close()));
            m.put(d.name(), day);
        }
        return m;
    }

    public static WeeklyOperatingHours fromApiMap(Map<String, ?> raw) {
        Map<DayOfWeek, DaySchedule> schedule = new EnumMap<>(DayOfWeek.class);
        if (raw == null || raw.isEmpty()) {
            return defaults();
        }
        for (DayOfWeek d : DayOfWeek.values()) {
            Object val = raw.get(d.name());
            if (val instanceof Map<?, ?> day) {
                boolean closed = Boolean.TRUE.equals(day.get("closed"));
                LocalTime open = ShiftHoursUtil.parseTime(str(day.get("open")), defaultFor(d).open());
                LocalTime close = ShiftHoursUtil.parseTime(str(day.get("close")), defaultFor(d).close());
                schedule.put(d, new DaySchedule(closed, open, close));
            } else {
                schedule.put(d, defaultFor(d));
            }
        }
        return new WeeklyOperatingHours(schedule);
    }

    /** Legacy single close time applied to all open days. */
    public static WeeklyOperatingHours fromSingleClose(LocalTime close) {
        Map<DayOfWeek, DaySchedule> schedule = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            DaySchedule def = defaultFor(d);
            if (!def.closed()) {
                schedule.put(d, new DaySchedule(false, def.open(), close));
            } else {
                schedule.put(d, def);
            }
        }
        return new WeeklyOperatingHours(schedule);
    }

    public static WeeklyOperatingHours defaults() {
        Map<DayOfWeek, DaySchedule> schedule = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            schedule.put(d, defaultFor(d));
        }
        return new WeeklyOperatingHours(schedule);
    }

    private static DaySchedule defaultFor(DayOfWeek d) {
        return switch (d) {
            case MONDAY, TUESDAY, WEDNESDAY, THURSDAY -> new DaySchedule(false, time(10, 0), time(22, 0));
            case FRIDAY, SATURDAY -> new DaySchedule(false, time(11, 0), time(23, 0));
            case SUNDAY -> new DaySchedule(false, time(12, 0), time(21, 0));
        };
    }

    private static LocalTime time(int h, int m) {
        return LocalTime.of(h, m);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String format(LocalTime t) {
        return t == null ? null : t.toString().substring(0, 5);
    }
}
