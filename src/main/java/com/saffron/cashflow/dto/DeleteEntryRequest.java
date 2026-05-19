package com.saffron.cashflow.dto;

import jakarta.validation.constraints.Size;

public record DeleteEntryRequest(@Size(min = 3) String reason) {}
