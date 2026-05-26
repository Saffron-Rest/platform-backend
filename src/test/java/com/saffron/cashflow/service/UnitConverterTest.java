package com.saffron.cashflow.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conversion table for the small set of units we actually encounter in
 * a restaurant kitchen. We don't try to cover the world — only the
 * everyday measurements (g/kg, ml/l, common imperial) — but inside that
 * set we expect bit-for-bit correctness.
 */
class UnitConverterTest {

    @Test
    void converts_kg_to_g() {
        Optional<BigDecimal> v = UnitConverter.convert(new BigDecimal("1.5"), "kg", "g");
        assertTrue(v.isPresent());
        assertEquals(0, v.get().compareTo(new BigDecimal("1500")));
    }

    @Test
    void converts_g_to_kg() {
        Optional<BigDecimal> v = UnitConverter.convert(new BigDecimal("750"), "g", "kg");
        assertTrue(v.isPresent());
        assertEquals(0, v.get().compareTo(new BigDecimal("0.75")));
    }

    @Test
    void converts_l_to_ml_and_back() {
        assertEquals(0, UnitConverter.convert(new BigDecimal("0.5"), "l", "ml").orElseThrow()
                .compareTo(new BigDecimal("500")));
        assertEquals(0, UnitConverter.convert(new BigDecimal("250"), "ml", "l").orElseThrow()
                .compareTo(new BigDecimal("0.25")));
    }

    @Test
    void respects_aliases_case_and_pluralization() {
        // "Kilogram" / "KG" / "kg" / "kilo" all resolve to the same unit.
        assertTrue(UnitConverter.isConvertible("Kilogram", "g"));
        assertTrue(UnitConverter.isConvertible("KG", "g"));
        assertTrue(UnitConverter.isConvertible("kilo", "g"));
        // "litre" vs "liter" vs "l" vs "lt".
        assertTrue(UnitConverter.isConvertible("litre", "ml"));
        assertTrue(UnitConverter.isConvertible("liter", "ml"));
        assertTrue(UnitConverter.isConvertible("lt", "ml"));
    }

    @Test
    void refuses_mixed_families() {
        // You can't convert mass to volume — water density is not the
        // converter's business.
        assertFalse(UnitConverter.isConvertible("kg", "ml"));
        assertFalse(UnitConverter.isConvertible("g", "l"));
        assertFalse(UnitConverter.isConvertible("oz", "tbsp"));
    }

    @Test
    void unknown_units_only_convert_to_themselves() {
        // Custom kitchen units like "tray" or "scoop" only resolve to
        // their own canonical alias. We refuse to invent a conversion.
        assertFalse(UnitConverter.isConvertible("tray", "g"));
        assertFalse(UnitConverter.isConvertible("scoop", "ml"));
        // Same string passes through 1:1.
        assertEquals(0, UnitConverter.convert(new BigDecimal("3"), "scoop", "scoop")
                .orElseThrow().compareTo(new BigDecimal("3")));
    }

    @Test
    void count_units_only_equal_themselves() {
        // "pcs" → "piece" both canonicalize to pcs, so they convert.
        assertTrue(UnitConverter.isConvertible("pcs", "piece"));
        // But pcs → portion does NOT — we don't know how many pieces a portion is.
        assertFalse(UnitConverter.isConvertible("pcs", "portion"));
        // And portion is in the COUNT family so it can't convert to mass either.
        assertFalse(UnitConverter.isConvertible("portion", "g"));
    }

    @Test
    void family_lookup_returns_expected_buckets() {
        assertEquals(UnitConverter.Family.MASS, UnitConverter.familyOf("kg"));
        assertEquals(UnitConverter.Family.VOLUME, UnitConverter.familyOf("ml"));
        assertEquals(UnitConverter.Family.COUNT, UnitConverter.familyOf("piece"));
        assertEquals(UnitConverter.Family.UNKNOWN, UnitConverter.familyOf("tray"));
    }

    @Test
    void tbsp_to_ml_uses_15_factor() {
        // 2 tbsp = 30 ml.
        assertEquals(0, UnitConverter.convert(new BigDecimal("2"), "tbsp", "ml").orElseThrow()
                .compareTo(new BigDecimal("30")));
    }

    @Test
    void ounce_to_gram_uses_28_3495() {
        // 1 oz = 28.3495 g.
        BigDecimal v = UnitConverter.convert(BigDecimal.ONE, "oz", "g").orElseThrow();
        // We don't pin all the way to the last decimal — converter
        // strips trailing zeros — so check rounded value.
        assertEquals(0, v.setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("28.35")));
    }

    @Test
    void canonical_returns_lowercase_input_when_unknown() {
        assertEquals("kg", UnitConverter.canonical("Kg"));
        assertEquals("ml", UnitConverter.canonical("Millilitres"));
        // Unknown units pass through lowercased.
        assertEquals("tray", UnitConverter.canonical("Tray"));
    }
}
