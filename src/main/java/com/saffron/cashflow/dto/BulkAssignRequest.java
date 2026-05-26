package com.saffron.cashflow.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * "Schedule cashier X every Mon/Wed/Fri from June 1–30 at 10:00–18:00"
 * in a single request.
 *
 * <p>Resolution semantics (matched in {@code WorkShiftService.bulkAssign}):
 * <ul>
 *   <li>Every date in {@code [from, to]} whose weekday is in {@code weekdays}
 *       (or every day if the list is empty / null) is a candidate.</li>
 *   <li>For each (date × userId) we either create or update the shift row.</li>
 *   <li>If {@code skipExisting=true} the row is left alone when a shift
 *       already exists for that cashier that day. This is the safer default
 *       and prevents accidental overwrites.</li>
 * </ul></p>
 */
public record BulkAssignRequest(
        @NotBlank String from,
        @NotBlank String to,
        /** Empty or null = all weekdays. Otherwise ISO names like
         *  {@code MONDAY, TUESDAY, ...}. */
        List<String> weekdays,
        /** One or more cashiers to schedule with the same pattern. */
        List<String> userIds,
        @NotBlank String startTime,
        String endTime,
        boolean tillClose,
        /** When true, days where this cashier already has a shift are
         *  skipped instead of overwritten. */
        boolean skipExisting
) {}
