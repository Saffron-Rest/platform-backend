package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.ShiftType;
import jakarta.validation.constraints.NotBlank;

public record AssignShiftRequest(
        @NotBlank String date,
        @NotBlank String userId,
        @NotBlank String startTime,
        String endTime,
        boolean tillClose,
        ShiftType shiftType
) {}
