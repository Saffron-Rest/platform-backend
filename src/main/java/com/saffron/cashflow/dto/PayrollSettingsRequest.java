package com.saffron.cashflow.dto;

import java.util.Map;

public record PayrollSettingsRequest(
        String storeCloseTime,
        Map<String, Map<String, Object>> weeklyHours
) {}
