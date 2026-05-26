package com.saffron.cashflow.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Immutable bundle of every aggregate that the analytics PDF can render.
 *
 * <p>{@link PdfReportBuilder} is intentionally permissive: any of the
 * optional fields can be {@code null}, in which case the corresponding
 * section is skipped. This lets callers degrade gracefully when a service
 * is not available (e.g. {@link com.saffron.cashflow.service.SalaryService}
 * requires admin; a manager exporting a PDF gets every section except
 * the payroll one).</p>
 *
 * <p>All maps follow the same shape the corresponding REST endpoints
 * already return, so no transformation is needed in the builder.</p>
 */
public record AnalyticsReportContext(
        /** "daily" / "weekly" / "monthly" — used for the cover subtitle. */
        String period,
        LocalDate from,
        LocalDate to,
        /** Mandatory: the summary map produced by {@code ReportService#buildSummary}. */
        Map<String, Object> summary,
        /** Mandatory: per-shift rows, same shape as {@code summary.get("rows")}. */
        List<Map<String, Object>> rows,

        /** Optional: same-shape summary for the previous window of equal
         *  length, so the cover KPIs can show deltas. */
        Map<String, Object> priorSummary,
        LocalDate priorFrom,
        LocalDate priorTo,

        /** Optional: {@code ProfitLossService#profitAndLoss(...)} output. */
        Map<String, Object> profitLoss,
        /** Optional: {@code TreasuryService#overview()} output. */
        Map<String, Object> treasury,
        /** Optional: {@code SalaryService#calculate(from, to)} output. */
        Map<String, Object> payroll,
        /** Optional: {@code MenuAnalyticsService#compute(from, to)} output. */
        Map<String, Object> menuAnalytics,
        /** Optional: {@code MenuEngineService#compute(from, to)} output. */
        Map<String, Object> menuEngineering,

        /** Optional: every standalone expense (no parent shift) in the
         *  period. Each map carries at minimum {@code date}, {@code category},
         *  {@code description}, {@code amount}, {@code paymentSource}. When
         *  null we fall back to the per-shift expense lines for the ledger. */
        List<Map<String, Object>> standaloneExpenses) {

    /** True when this is the per-shift export (single date, single row). */
    public boolean isSingleShift() {
        return rows != null && rows.size() == 1 && from.equals(to);
    }
}
