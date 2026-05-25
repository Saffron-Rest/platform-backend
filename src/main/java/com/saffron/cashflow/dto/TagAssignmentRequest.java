package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.TaggedEntityType;
import jakarta.validation.constraints.NotNull;

public record TagAssignmentRequest(
        @NotNull TaggedEntityType entityType,
        @NotNull String entityId) {}
