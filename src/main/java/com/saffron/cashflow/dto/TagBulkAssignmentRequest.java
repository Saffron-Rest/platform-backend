package com.saffron.cashflow.dto;

import com.saffron.cashflow.domain.TaggedEntityType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TagBulkAssignmentRequest(
        @NotNull TaggedEntityType entityType,
        @NotEmpty List<String> entityIds) {}
