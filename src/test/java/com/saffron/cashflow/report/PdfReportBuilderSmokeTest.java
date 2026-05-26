package com.saffron.cashflow.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the analytics PDF builder. Hits the full happy path
 * (every optional section populated) plus the degraded path (every
 * optional section {@code null}) so a runtime issue inside any of the
 * many cell-event renderers gets caught at build time.
 *
 * <p>We don't try to assert specific layout; we just verify the bytes
 * come out as a valid PDF and that the rich path is larger than the
 * minimal path (proves the optional sections actually drew content).</p>
 */
class PdfReportBuilderSmokeTest {

    @Test
    void buildsFullAnalyticsPdfWithEverySection() {
        AnalyticsReportContext rich = sampleContext(true);
        byte[] bytes = PdfReportBuilder.build(rich);
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void buildsAnalyticsPdfWithoutOptionalSections() {
        AnalyticsReportContext bare = sampleContext(false);
        byte[] bytes = PdfReportBuilder.build(bare);
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void richReportIsLargerThanBareReport() {
        // A surprisingly common regression is "optional section accidentally
        // bailed out silently". The size delta is a cheap canary.
        byte[] rich = PdfReportBuilder.build(sampleContext(true));
        byte[] bare = PdfReportBuilder.build(sampleContext(false));
        assertThat(rich.length).isGreaterThan(bare.length);
    }

    @Test
    void buildsSingleShiftPdf() {
        // Single-shift mode (per-entry export) skips payroll / menu sections
        // but should still render cover, headline KPIs, and notes.
        LocalDate d = LocalDate.of(2026, 5, 1);
        Map<String, Object> summary = sampleSummary(d, d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("rows");
        AnalyticsReportContext ctx = new AnalyticsReportContext(
                "daily", d, d, summary, List.of(rows.get(0)),
                null, null, null,
                null, null, null, null, null);
        byte[] bytes = PdfReportBuilder.build(ctx);
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
    }

    private static AnalyticsReportContext sampleContext(boolean withOptionalSections) {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 5, 14);
        Map<String, Object> summary = sampleSummary(from, to);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("rows");

        if (!withOptionalSections) {
            return new AnalyticsReportContext(
                    "weekly", from, to, summary, rows,
                    null, null, null,
                    null, null, null, null, null);
        }

        LocalDate priorTo = from.minusDays(1);
        LocalDate priorFrom = priorTo.minusDays(13);
        Map<String, Object> priorSummary = sampleSummary(priorFrom, priorTo);

        return new AnalyticsReportContext(
                "weekly", from, to, summary, rows,
                priorSummary, priorFrom, priorTo,
                samplePnl(), sampleTreasury(),
                samplePayroll(), sampleMenuAnalytics(), sampleMenuEngineering());
    }

    private static Map<String, Object> sampleSummary(LocalDate from, LocalDate to) {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("sales", 12_345.67);
        totals.put("cashSales", 4_200.00);
        totals.put("cardSales", 5_800.00);
        totals.put("returns", 120.50);
        totals.put("expenses", 1_350.00);
        totals.put("payouts", 800.00);
        totals.put("expectedCash", 4_300.00);
        totals.put("actualCash", 4_180.00);
        totals.put("difference", -120.00);
        totals.put("cardBalance", 5_400.00);
        totals.put("draftCount", 1);
        totals.put("lockedCount", 12);

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        LocalDate cursor = from;
        int day = 0;
        while (!cursor.isAfter(to)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", cursor.toString());
            row.put("status", day == 0 ? "DRAFT" : "LOCKED");
            row.put("cashSales", 300.0 + day * 15);
            row.put("cardSales", 400.0 + day * 12);
            row.put("woltSales", 80.0 + day);
            row.put("boltSales", 40.0);
            row.put("uberEatsSales", 25.0);
            row.put("glovoSales", 10.0);
            row.put("otherPlatformSales", 0.0);
            row.put("bankDeposit", day == 7 ? 500.0 : 0.0);
            row.put("cashWithdrawal", 0.0);
            row.put("ownerWithdrawal", 0.0);
            row.put("difference", day % 3 == 0 ? -8.5 : 0.0);
            row.put("notes", day == 5 ? "POS glitch around lunch" : null);

            Map<String, Object> cashier = Map.of(
                    "id", day % 2 == 0 ? "u1" : "u2",
                    "name", day % 2 == 0 ? "Aysel Əliyeva" : "Şahin Hüseynov");
            row.put("cashier", cashier);

            // Expense lines on a few of the rows
            if (day % 3 == 0) {
                row.put("expenses", List.of(
                        Map.of("category", "SUPPLIER_PAYMENTS", "paymentSource", "CASH", "amount", 45.50),
                        Map.of("category", "STAFF_MEALS", "paymentSource", "CARD", "amount", 22.00)));
            }
            rows.add(row);
            cursor = cursor.plusDays(1);
            day++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("period", "weekly");
        summary.put("from", from.toString());
        summary.put("to", to.toString());
        summary.put("rows", rows);
        summary.put("totals", totals);
        summary.put("count", rows.size());
        return summary;
    }

    private static Map<String, Object> samplePnl() {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        lines.add(Map.of("key", "section", "label", "Revenue", "section", true, "indent", 0));
        lines.add(Map.of("key", "gross_revenue", "label", "Gross revenue", "amount", 12_345.67,
                "indent", 0, "bold", false, "subtotal", false, "section", false));
        lines.add(Map.of("key", "platform_sales", "label", "Platform sales", "amount", 1_200.0,
                "indent", 1, "bold", false, "subtotal", false, "section", false));
        lines.add(Map.of("key", "wolt", "label", "Wolt", "amount", 600.0,
                "indent", 2, "bold", false, "subtotal", false, "section", false));
        lines.add(Map.of("key", "bolt", "label", "Bolt", "amount", 400.0,
                "indent", 2, "bold", false, "subtotal", false, "section", false));
        lines.add(Map.of("key", "net_revenue", "label", "Net revenue", "amount", 12_225.17,
                "indent", 0, "bold", true, "subtotal", true, "section", false));
        lines.add(Map.of("key", "section", "label", "Costs", "section", true, "indent", 0));
        lines.add(Map.of("key", "cogs", "label", "Cost of goods sold", "amount", 3_500.0,
                "indent", 0, "bold", false, "subtotal", false, "section", false));
        lines.add(Map.of("key", "labor", "label", "Labour", "amount", 2_800.0,
                "indent", 0, "bold", false, "subtotal", false, "section", false));
        lines.add(Map.of("key", "operating_profit", "label", "Operating profit", "amount", 4_575.17,
                "indent", 0, "bold", true, "subtotal", true, "section", false));
        lines.add(Map.of("key", "net_profit", "label", "Net profit", "amount", 4_575.17,
                "indent", 0, "bold", true, "subtotal", true, "section", false));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", "2026-05-01");
        result.put("to", "2026-05-14");
        result.put("template", "PL");
        result.put("templateLabel", "Polish P&L");
        result.put("lines", lines);
        result.put("totals", Map.of(
                "grossRevenue", 12_345.67,
                "returns", 120.5,
                "netRevenue", 12_225.17,
                "cogs", 3_500.0,
                "grossProfit", 8_725.17,
                "operatingProfit", 4_575.17,
                "netProfit", 4_575.17));
        result.put("margins", Map.of(
                "grossMarginPct", 71.4,
                "operatingMarginPct", 37.4,
                "netMarginPct", 37.4));
        result.put("expensesByCategory", List.of(
                Map.of("label", "Supplier Payments", "amount", 2_100.0),
                Map.of("label", "Staff Meals", "amount", 180.0),
                Map.of("label", "Petty Cash", "amount", 320.0)));
        return result;
    }

    private static Map<String, Object> sampleTreasury() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cashOnHand", 4_180.00);
        m.put("cashPool", 4_180.00);
        m.put("cardPool", 5_400.00);
        m.put("latestCountDate", "2026-05-14");
        m.put("latestCountCashier", "Aysel Əliyeva");
        return m;
    }

    private static Map<String, Object> samplePayroll() {
        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("name", "Aysel Əliyeva");
        e1.put("shiftCount", 8);
        e1.put("totalPay", 1_600.0);
        e1.put("paidAmount", 800.0);
        e1.put("remainingPay", 800.0);

        Map<String, Object> e2 = new LinkedHashMap<>();
        e2.put("name", "Şahin Hüseynov");
        e2.put("shiftCount", 6);
        e2.put("totalPay", 1_200.0);
        e2.put("paidAmount", 1_200.0);
        e2.put("remainingPay", 0.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employees", List.of(e1, e2));
        result.put("grandTotalPay", 2_800.0);
        result.put("grandTotalPaid", 2_000.0);
        result.put("grandTotalRemaining", 800.0);
        return result;
    }

    private static Map<String, Object> sampleMenuAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totals", Map.of("revenue", 8_000.0, "marginPct", 62.5));
        result.put("items", List.of(
                menuItem("Lamb Plov", 2_400.0, 80, 65.0, 0.30),
                menuItem("Dolma", 1_600.0, 64, 58.0, 0.20),
                menuItem("Dovga", 1_200.0, 96, 70.0, 0.15)));
        return result;
    }

    private static Map<String, Object> menuItem(String name, double revenue, double qty, double marginPct, double share) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("revenue", revenue);
        m.put("quantity", qty);
        m.put("marginPct", marginPct);
        m.put("share", share);
        return m;
    }

    private static Map<String, Object> sampleMenuEngineering() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestions", List.of(
                Map.of("severity", "high",
                        "title", "Review portion / supplier on Dovga",
                        "detail", "Food cost ratio is 30% — well above the 25% target. "
                                + "Try a smaller portion or a cheaper herb supplier."),
                Map.of("severity", "medium",
                        "title", "Raise price of Lamb Plov by ~5%",
                        "detail", "Star dish (high margin, high popularity) — bears a modest price test."),
                Map.of("severity", "low",
                        "title", "Promote Dolma to lunch combos",
                        "detail", "High margin but only mid-popularity. Pairing with tea could lift volume.")));
        return result;
    }
}
