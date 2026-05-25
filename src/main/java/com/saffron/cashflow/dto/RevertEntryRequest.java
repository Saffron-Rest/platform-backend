package com.saffron.cashflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/entries/{id}/revert}.
 *
 * <p>The {@code auditId} identifies the row in {@code audit_log} that we want
 * to undo — the report will be restored to the {@code details.before} snapshot
 * captured at the time of that change. {@code reason} is required for the
 * audit trail of the revert itself (so ops can later see why someone rolled
 * a report back).</p>
 */
public record RevertEntryRequest(
        @NotBlank String auditId,
        @NotBlank @Size(min = 3, max = 500) String reason) {}
