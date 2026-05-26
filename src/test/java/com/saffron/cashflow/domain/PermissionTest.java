package com.saffron.cashflow.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the contracts of {@link Permission}'s role-default,
 * parse, serialize, and effective-set helpers. These are the bones
 * the permission overlay rides on — a regression here would silently
 * change every user's effective access.
 */
class PermissionTest {

    @Test
    void admin_gets_every_permission_by_default() {
        Set<Permission> all = EnumSet.allOf(Permission.class);
        assertEquals(all, Permission.defaultsFor(Role.ADMIN));
    }

    @Test
    void manager_defaults_cover_operations_but_not_payroll_or_audit() {
        Set<Permission> managerDefaults = Permission.defaultsFor(Role.MANAGER);
        assertTrue(managerDefaults.contains(Permission.STOCK_VIEW));
        assertTrue(managerDefaults.contains(Permission.STOCK_ADJUST));
        assertTrue(managerDefaults.contains(Permission.INCIDENTS_RESOLVE));
        assertTrue(managerDefaults.contains(Permission.SCHEDULE_MANAGE));
        assertTrue(managerDefaults.contains(Permission.REPORTS_EXPORT));
        // Elevated — admins must grant explicitly
        assertFalse(managerDefaults.contains(Permission.SALARIES_MANAGE));
        assertFalse(managerDefaults.contains(Permission.SALARIES_VIEW));
        assertFalse(managerDefaults.contains(Permission.AUDIT_VIEW));
        assertFalse(managerDefaults.contains(Permission.POS_INTEGRATION_MANAGE));
        assertFalse(managerDefaults.contains(Permission.TEAM_MANAGE));
        assertFalse(managerDefaults.contains(Permission.STOCK_DELETE));
        assertFalse(managerDefaults.contains(Permission.STOCK_MANAGE));
    }

    @Test
    void cashier_defaults_cover_self_service_only() {
        Set<Permission> cashier = Permission.defaultsFor(Role.CASHIER);
        // Cashiers can do their daily work — file incidents, run
        // checklists, log HACCP — but can't change anyone or anything.
        assertTrue(cashier.contains(Permission.INCIDENTS_FILE));
        assertTrue(cashier.contains(Permission.CHECKLISTS_RUN));
        assertTrue(cashier.contains(Permission.HACCP_LOG));
        assertFalse(cashier.contains(Permission.INCIDENTS_RESOLVE));
        assertFalse(cashier.contains(Permission.SCHEDULE_MANAGE));
        assertFalse(cashier.contains(Permission.STOCK_VIEW));
        assertFalse(cashier.contains(Permission.TEAM_MANAGE));
    }

    @Test
    void null_role_yields_empty_defaults() {
        assertEquals(EnumSet.noneOf(Permission.class), Permission.defaultsFor(null));
    }

    @Test
    void isGrantedByRole_short_circuits_for_admin() {
        for (Permission p : Permission.values()) {
            assertTrue(Permission.isGrantedByRole(Role.ADMIN, p),
                    "ADMIN must hold every permission, missing: " + p);
        }
    }

    @Test
    void parseCsv_round_trips_and_ignores_garbage() {
        Set<Permission> input = EnumSet.of(
                Permission.STOCK_VIEW,
                Permission.AUDIT_VIEW,
                Permission.SALARIES_VIEW);
        String csv = Permission.toCsv(input);
        // Toss in blanks, whitespace, and an unknown name — all must be
        // silently dropped so a hand-edited DB row can't crash auth.
        Set<Permission> parsed = Permission.parseCsv(csv + ", ,UNKNOWN_FLAG ,");
        assertEquals(input, parsed);
    }

    @Test
    void parseCsv_handles_null_and_blank() {
        assertEquals(EnumSet.noneOf(Permission.class), Permission.parseCsv(null));
        assertEquals(EnumSet.noneOf(Permission.class), Permission.parseCsv(""));
        assertEquals(EnumSet.noneOf(Permission.class), Permission.parseCsv("   "));
    }

    @Test
    void tryParse_returns_null_for_unknown_and_null_inputs() {
        assertNull(Permission.tryParse(null));
        assertNull(Permission.tryParse(""));
        assertNull(Permission.tryParse("definitely-not-a-permission"));
        assertEquals(Permission.STOCK_VIEW, Permission.tryParse("STOCK_VIEW"));
        assertEquals(Permission.STOCK_VIEW, Permission.tryParse("  STOCK_VIEW  "));
    }

    @Test
    void toCsv_of_empty_set_is_empty_string() {
        assertEquals("", Permission.toCsv(EnumSet.noneOf(Permission.class)));
        assertEquals("", Permission.toCsv(null));
    }

    @Test
    void label_and_description_are_present_for_every_permission() {
        for (Permission p : Permission.values()) {
            assertTrue(p.label() != null && !p.label().isBlank(),
                    "Permission " + p + " is missing a label");
            assertTrue(p.description() != null && !p.description().isBlank(),
                    "Permission " + p + " is missing a description");
            assertNotNull(p.category(), "Permission " + p + " is missing a category");
        }
    }

    // ─── effective() — the heart of the overlay ─────────────────────

    @Test
    void effective_with_no_overrides_equals_role_defaults() {
        Set<Permission> eff = Permission.effective(
                Role.MANAGER,
                EnumSet.noneOf(Permission.class),
                EnumSet.noneOf(Permission.class));
        assertEquals(Permission.defaultsFor(Role.MANAGER), eff);
    }

    @Test
    void effective_adds_extras_above_role() {
        Set<Permission> eff = Permission.effective(
                Role.CASHIER,
                EnumSet.of(Permission.STOCK_VIEW, Permission.AUDIT_VIEW),
                EnumSet.noneOf(Permission.class));
        assertTrue(eff.contains(Permission.STOCK_VIEW));
        assertTrue(eff.contains(Permission.AUDIT_VIEW));
        // Cashier defaults preserved
        assertTrue(eff.contains(Permission.INCIDENTS_FILE));
    }

    @Test
    void effective_revokes_role_defaults() {
        // A manager with salaries view bolted on but treasury revoked
        // — the realistic "manager minus money" use case.
        Set<Permission> eff = Permission.effective(
                Role.MANAGER,
                EnumSet.of(Permission.SALARIES_VIEW),
                EnumSet.of(Permission.TREASURY_VIEW, Permission.EXPENSES_EDIT));
        assertTrue(eff.contains(Permission.SALARIES_VIEW));
        assertFalse(eff.contains(Permission.TREASURY_VIEW));
        assertFalse(eff.contains(Permission.EXPENSES_EDIT));
        // Unrelated default kept
        assertTrue(eff.contains(Permission.SCHEDULE_MANAGE));
    }

    @Test
    void effective_revoke_wins_over_extra_for_same_key() {
        // If a permission is both granted (extras) and denied
        // (revokes), the revoke wins — explicit denial is the safer
        // default.
        Set<Permission> eff = Permission.effective(
                Role.CASHIER,
                EnumSet.of(Permission.STOCK_VIEW),
                EnumSet.of(Permission.STOCK_VIEW));
        assertFalse(eff.contains(Permission.STOCK_VIEW));
    }

    @Test
    void effective_handles_null_collections() {
        // Don't NPE if the caller passes nulls — common when fields are
        // optional in a DTO or freshly migrated row has NULL columns.
        Set<Permission> eff = Permission.effective(Role.MANAGER, null, null);
        assertEquals(Permission.defaultsFor(Role.MANAGER), eff);
    }
}
