package com.saffron.cashflow.domain;

/**
 * Kinds of records that can be tagged. Adding a new value here means:
 *  1. TagService.assertTargetExists handles it (so dangling assignments
 *     can't slip in).
 *  2. The matching list/detail mapper opts-in by calling
 *     TagService.tagsFor(...) and including them in its response.
 *  3. The frontend list page adds a TagFilterDropdown to its filter bar.
 */
public enum TaggedEntityType {
    ENTRY,
    EXPENSE,
    SALARY_PAYMENT,
    MANUAL_DELIVERY,
    /** Cash bank deposit records (treasury → bank). */
    BANK_DEPOSIT,
    /** Card acquirer settlements (cumulative bank credits from card sales). */
    CARD_SETTLEMENT
}
