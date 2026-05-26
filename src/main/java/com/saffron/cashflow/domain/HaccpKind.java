package com.saffron.cashflow.domain;

/**
 * What the {@link HaccpLog} entry is about.
 *
 * <p>Keep tight. Sanepid inspectors want a familiar set of categories — if
 * you find yourself reaching for a new kind, prefer a free-form note inside
 * an existing one first.</p>
 */
public enum HaccpKind {
    /** Walk-in / display / blast-chiller temperature reading. */
    FRIDGE_TEMP,
    /** Freezer temperature reading. */
    FREEZER_TEMP,
    /** Cooking / reheat core temperature probe. */
    COOK_TEMP,
    /** Surface / equipment cleaning record. */
    CLEANING,
    /** Goods received from a supplier — temperatures, dates, packaging. */
    DELIVERY,
    /** Pest control sighting / treatment record. */
    PEST_CONTROL,
    /** Anything else — free-form notes. */
    OTHER
}
