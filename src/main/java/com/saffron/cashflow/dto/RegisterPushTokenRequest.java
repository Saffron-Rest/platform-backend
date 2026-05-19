package com.saffron.cashflow.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterPushTokenRequest(
        @NotBlank String expoPushToken,
        String deviceName
) {}
