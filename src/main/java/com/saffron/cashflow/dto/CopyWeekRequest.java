package com.saffron.cashflow.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Copy a 7-day window of shifts onto another 7-day window — the most
 * common "schedule for next week looks like this week" operation.
 *
 * <p>{@code sourceWeekStart} and {@code targetWeekStart} are any
 * dates; the service simply pairs day N from the source with day N
 * from the target. We let the caller pick non-Monday starts in case
 * a restaurant's week begins differently.</p>
 */
public record CopyWeekRequest(
        @NotBlank String sourceWeekStart,
        @NotBlank String targetWeekStart,
        /** When true, existing shifts on the target days for the copied
         *  cashiers are replaced. When false the target day is skipped
         *  if that cashier already has any shift. */
        boolean overwrite
) {}
