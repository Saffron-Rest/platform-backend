package com.saffron.cashflow.domain;

/**
 * Why a {@link StockMovement} happened. Keeps the audit log readable
 * without committing to a deep accounting model up front.
 */
public enum StockMovementType {
    /** Item sold through the POS (delta &lt; 0). */
    SALE,
    /** Inventory received from a supplier (delta &gt; 0). */
    PURCHASE,
    /** Manual count correction — admin set the on-hand to a specific number. */
    ADJUST,
    /** Recorded waste / breakage / spoilage (delta &lt; 0). */
    WASTE,
    /** Transferred between locations or used as ingredient (delta &lt; 0). */
    TRANSFER,
    /** Internal use / staff meals (delta &lt; 0). */
    INTERNAL_USE,
    /** Original count when the item was first created (delta = on-hand at create). */
    OPENING_COUNT,
    /** Cancels out a prior movement via {@code reverts_id}. */
    REVERT
}
