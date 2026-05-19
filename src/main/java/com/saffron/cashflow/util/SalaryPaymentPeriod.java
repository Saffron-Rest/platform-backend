package com.saffron.cashflow.util;

import com.saffron.cashflow.domain.SalaryPayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Matches salary payments to a payroll period and aggregates by employee. */
public final class SalaryPaymentPeriod {

    private SalaryPaymentPeriod() {}

    /** Payment counts toward payroll for [periodFrom, periodTo] when periods overlap or paid in range. */
    public static boolean appliesToPayrollPeriod(SalaryPayment payment, LocalDate periodFrom, LocalDate periodTo) {
        if (payment.getPeriodFrom() != null && payment.getPeriodTo() != null) {
            return !payment.getPeriodFrom().isAfter(periodTo) && !payment.getPeriodTo().isBefore(periodFrom);
        }
        LocalDate paid = payment.getPaidDate();
        return paid != null && !paid.isBefore(periodFrom) && !paid.isAfter(periodTo);
    }

    public static Map<String, BigDecimal> sumPaidByUser(
            List<SalaryPayment> payments, LocalDate periodFrom, LocalDate periodTo) {
        Map<String, BigDecimal> byUser = new LinkedHashMap<>();
        for (SalaryPayment p : payments) {
            if (!appliesToPayrollPeriod(p, periodFrom, periodTo)) {
                continue;
            }
            byUser.merge(p.getUserId(), p.getAmount(), BigDecimal::add);
        }
        return byUser;
    }

    public static Map<String, List<SalaryPayment>> paymentsByUser(
            List<SalaryPayment> payments, LocalDate periodFrom, LocalDate periodTo) {
        Map<String, List<SalaryPayment>> byUser = new LinkedHashMap<>();
        for (SalaryPayment p : payments) {
            if (!appliesToPayrollPeriod(p, periodFrom, periodTo)) {
                continue;
            }
            byUser.computeIfAbsent(p.getUserId(), k -> new ArrayList<>()).add(p);
        }
        return byUser;
    }

    public static BigDecimal totalPaidInRange(List<SalaryPayment> payments, LocalDate from, LocalDate to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (SalaryPayment p : payments) {
            if (p.getPaidDate() != null
                    && !p.getPaidDate().isBefore(from)
                    && !p.getPaidDate().isAfter(to)) {
                sum = sum.add(p.getAmount());
            }
        }
        return sum;
    }
}
