package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.ShiftType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UpsertScheduleRequest(
        @NotBlank String date,
        @Valid @NotNull List<ShiftAssignment> shifts
) {
    public record ShiftAssignment(
            @NotBlank String userId,
            boolean working,
            String startTime,
            String endTime,
            ShiftType shiftType
    ) {}
}
