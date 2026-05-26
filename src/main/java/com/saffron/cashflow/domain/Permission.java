package com.saffron.cashflow.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Granular per-feature capabilities that admins can grant to teammates
 * on top of their {@link Role} defaults.
 *
 * <p>The model is intentionally additive:
 * <ul>
 *   <li>Each role has a baked-in default set returned by
 *       {@link #defaultsFor(Role)} — never persisted, always recomputed
 *       from the role.</li>
 *   <li>An admin can grant extra capabilities to an individual user.
 *       These are stored on {@code app_user.extra_permissions} as a
 *       comma-separated CSV of enum names.</li>
 *   <li>The user's effective set at authentication time is
 *       {@code defaultsFor(role) ∪ extras}.</li>
 * </ul>
 * Admins always have <i>every</i> permission — extras for an
 * {@link Role#ADMIN} are ignored to avoid confusing audit trails.</p>
 *
 * <p>When adding a new capability:
 * <ol>
 *   <li>Add a new enum constant with a human-readable {@code label} and
 *       {@code description}.</li>
 *   <li>Update {@link #defaultsFor} if any role should get it by default.
 *       The conservative choice is "no defaults", letting admins grant
 *       it explicitly.</li>
 *   <li>Use {@code AuthHelper.requirePermission(Permission.XXX)} in the
 *       service or controller you want to gate.</li>
 * </ol></p>
 */
public enum Permission {

    STOCK_VIEW("Stock — view", "Can open the Stock page and see balances."),
    STOCK_MANAGE("Stock — manage", "Create, edit, adjust, archive, or delete stock items."),

    SCHEDULE_MANAGE("Schedule — manage", "Add, edit, or remove shifts on the calendar."),

    INCIDENTS_MANAGE("Incidents — manage", "File, assign, and resolve operational incidents."),
    CHECKLISTS_MANAGE("Checklists — manage", "Configure templates and review daily runs."),
    HACCP_MANAGE("HACCP — manage", "Configure HACCP checks and review the log."),
    CERTIFICATIONS_MANAGE("Certifications — manage", "Track employee certifications and expiries."),

    SALARIES_VIEW("Salaries — view", "See payroll figures and totals (no edits)."),
    SALARIES_MANAGE("Salaries — manage", "Edit pay rates, mark periods paid, and run payroll."),

    EXPENSES_EDIT("Expenses — edit", "Edit expense lines on shift reports outside the cashier flow."),
    TREASURY_VIEW("Treasury — view", "Open the treasury panel and view cash positions."),
    REPORTS_EXPORT("Reports — export", "Download analytics PDFs and CSV exports."),

    AUDIT_VIEW("Audit — view", "Open the audit log with raw before/after diffs."),
    POS_INTEGRATION_MANAGE("POS integration — manage", "Configure POS sync (Dotykačka, manual ingest, etc.)."),
    ;

    private final String label;
    private final String description;

    Permission(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() { return label; }
    public String description() { return description; }

    /**
     * Default capabilities baked into each role. ADMIN is exempt — it
     * implicitly has everything and {@link #isGrantedByRole(Role)} short
     * circuits there.
     *
     * <p>MANAGER gets the operational defaults (open the floor, run
     * service, file incidents/HACCP, see treasury & reports). They do
     * not get payroll, audit raw view, or POS integration — those are
     * elevated capabilities an admin must grant explicitly.</p>
     */
    public static Set<Permission> defaultsFor(Role role) {
        if (role == null) return EnumSet.noneOf(Permission.class);
        return switch (role) {
            case ADMIN -> EnumSet.allOf(Permission.class);
            case MANAGER -> EnumSet.of(
                    STOCK_VIEW,
                    SCHEDULE_MANAGE,
                    INCIDENTS_MANAGE,
                    CHECKLISTS_MANAGE,
                    HACCP_MANAGE,
                    CERTIFICATIONS_MANAGE,
                    EXPENSES_EDIT,
                    TREASURY_VIEW,
                    REPORTS_EXPORT);
            case CASHIER -> EnumSet.noneOf(Permission.class);
        };
    }

    public static boolean isGrantedByRole(Role role, Permission p) {
        if (role == Role.ADMIN) return true;
        return defaultsFor(role).contains(p);
    }

    /** Tolerant parse — unknown values are dropped silently so an old
     *  column value that names a deleted permission doesn't crash auth. */
    public static Permission tryParse(String name) {
        if (name == null) return null;
        try { return Permission.valueOf(name.trim()); } catch (Exception e) { return null; }
    }

    /** Parse a CSV (the storage format on {@code app_user.extra_permissions})
     *  into an EnumSet, ignoring blanks and unknown names. */
    public static EnumSet<Permission> parseCsv(String csv) {
        EnumSet<Permission> out = EnumSet.noneOf(Permission.class);
        if (csv == null || csv.isBlank()) return out;
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Permission::tryParse)
                .filter(p -> p != null)
                .forEach(out::add);
        return out;
    }

    /** Stable CSV encoding — sorted by {@link #ordinal()} so audit
     *  diffs aren't noisy from reordering. */
    public static String toCsv(Set<Permission> perms) {
        if (perms == null || perms.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Permission p : EnumSet.copyOf(perms)) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.name());
        }
        return sb.toString();
    }
}
