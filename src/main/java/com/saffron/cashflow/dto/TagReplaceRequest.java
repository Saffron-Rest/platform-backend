package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.TaggedEntityType;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TagReplaceRequest(
        @NotNull TaggedEntityType entityType,
        @NotNull String entityId,
        List<String> tagIds) {}
