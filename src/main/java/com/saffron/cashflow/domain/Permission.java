package com.saffron.cashflow.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Granular per-feature capabilities. Forms the leaf of a two-layer
 * authorization model:
 *
 * <ol>
 *   <li>The user's {@link Role} grants a baked-in baseline returned by
 *       {@link #defaultsFor(Role)}. Always recomputed; never stored.</li>
 *   <li>Per-user overrides are layered on top:
 *       <ul>
 *         <li><b>Extras</b> ({@code app_user.extra_permissions}) — keys
 *             added beyond the role default.</li>
 *         <li><b>Revokes</b> ({@code app_user.revoked_permissions}) —
 *             keys removed from the role default. Lets admins create
 *             "manager minus payroll" or "cashier plus stock" without
 *             inventing new roles.</li>
 *       </ul></li>
 * </ol>
 *
 * <p>The effective set computed at authentication time is
 * {@code (defaults(role) − revokes) ∪ extras}. Admins are an exception:
 * they implicitly hold every permission and overrides on them are
 * refused at the service layer to avoid the obvious "I just locked
 * myself out" foot-gun.</p>
 *
 * <p>When adding a new capability:
 * <ol>
 *   <li>Add a new constant with a {@link Category}, label, and
 *       description. Keep categories small and stable — they drive the
 *       UI grouping in the "Manage permissions" modal.</li>
 *   <li>Decide whether any role should hold it by default. The
 *       conservative answer is "no" — let admins grant it on demand.</li>
 *   <li>Gate the relevant service or controller with
 *       {@code AuthHelper.requirePermission(Permission.XXX)}.</li>
 * </ol></p>
 */
public enum Permission {

    // ───── Reports & analytics ─────
    REPORTS_VIEW(Category.REPORTS, "View reports",
            "Open shift reports and analytics summaries."),
    REPORTS_EXPORT(Category.REPORTS, "Export reports",
            "Download analytics PDFs and CSV exports."),
    REPORTS_EDIT_OTHERS(Category.REPORTS, "Edit others' reports",
            "Edit or correct shift reports submitted by other cashiers."),
    PROFIT_LOSS_VIEW(Category.REPORTS, "View P&L",
            "Open the Profit & Loss dashboard with revenue, costs and margins."),

    // ───── Stock & inventory ─────
    STOCK_VIEW(Category.STOCK, "View stock",
            "Open the Stock page and see balances, low/out flags, and history."),
    STOCK_ADJUST(Category.STOCK, "Adjust stock",
            "Record purchases, waste, transfers, internal use, and set-on-hand."),
    STOCK_MANAGE(Category.STOCK, "Manage stock items",
            "Create, edit, and archive stock items."),
    STOCK_DELETE(Category.STOCK, "Delete stock permanently",
            "Permanently remove archived items and their movement history."),

    // ───── Schedule & attendance ─────
    ATTENDANCE_VIEW(Category.SCHEDULE, "View attendance",
            "Open the calendar and see who is scheduled."),
    SCHEDULE_MANAGE(Category.SCHEDULE, "Manage schedule",
            "Add, edit, or remove individual shifts on the calendar."),
    SCHEDULE_BULK(Category.SCHEDULE, "Bulk schedule",
            "Use bulk-assign, copy-week, and clear-range tools."),

    // ───── People & payroll ─────
    TEAM_VIEW(Category.TEAM, "View team",
            "List teammates and see their profile details."),
    TEAM_MANAGE(Category.TEAM, "Manage team",
            "Create, edit, deactivate teammates and change non-admin roles."),
    TEAM_RESET_PASSWORD(Category.TEAM, "Reset passwords",
            "Issue one-time temporary passwords for teammates."),
    TEAM_PERMISSIONS_MANAGE(Category.TEAM, "Manage permissions",
            "Grant or revoke fine-grained permissions for teammates."),
    SALARIES_VIEW(Category.PAYROLL, "View salaries",
            "See payroll figures, accruals, and totals (read-only)."),
    SALARIES_MANAGE(Category.PAYROLL, "Manage salaries",
            "Edit pay rates, run payroll, and mark periods paid."),
    PAY_RATES_MANAGE(Category.PAYROLL, "Manage pay rates",
            "Edit hourly/daily/monthly rates and history for individuals."),

    // ───── Cash, expenses & treasury ─────
    EXPENSES_VIEW(Category.FINANCE, "View expenses",
            "See expense lines on shift reports and standalone expenses."),
    EXPENSES_EDIT(Category.FINANCE, "Edit expenses",
            "Add, edit, and re-categorize expense lines outside the cashier flow."),
    EXPENSES_DELETE(Category.FINANCE, "Delete expenses",
            "Remove expense lines or standalone entries permanently."),
    TREASURY_VIEW(Category.FINANCE, "View treasury",
            "Open the treasury panel and view cash positions."),
    TREASURY_MANAGE(Category.FINANCE, "Manage treasury",
            "Record deposits, withdrawals, and treasury settings."),

    // ───── Operations & compliance ─────
    INCIDENTS_VIEW(Category.OPERATIONS, "View incidents",
            "Open the incident log to see open and resolved issues."),
    INCIDENTS_FILE(Category.OPERATIONS, "File incidents",
            "Create new incidents and attach photos/cost estimates."),
    INCIDENTS_RESOLVE(Category.OPERATIONS, "Resolve incidents",
            "Assign, update, and close incidents."),
    CHECKLISTS_RUN(Category.OPERATIONS, "Run checklists",
            "Tick items off the daily checklist as they're completed."),
    CHECKLISTS_CONFIGURE(Category.OPERATIONS, "Configure checklists",
            "Edit checklist templates and steps."),
    HACCP_LOG(Category.OPERATIONS, "Log HACCP",
            "Record HACCP measurements (temperatures, deliveries, hygiene)."),
    HACCP_CONFIGURE(Category.OPERATIONS, "Configure HACCP",
            "Edit HACCP check definitions, thresholds, and export rules."),
    HACCP_EXPORT(Category.OPERATIONS, "Export HACCP",
            "Download HACCP logs as PDF for inspections."),
    CERTIFICATIONS_VIEW(Category.OPERATIONS, "View certifications",
            "See employee certification status and expiry dates."),
    CERTIFICATIONS_MANAGE(Category.OPERATIONS, "Manage certifications",
            "Add, edit, and renew employee certifications."),

    // ───── Audit & integrations ─────
    AUDIT_VIEW(Category.ADMIN, "View audit log",
            "Open the audit log with raw before/after diffs for every change."),
    POS_INTEGRATION_VIEW(Category.ADMIN, "View POS integration",
            "See POS sync status, last pull, and import history."),
    POS_INTEGRATION_MANAGE(Category.ADMIN, "Manage POS integration",
            "Configure Dotykačka credentials and trigger manual ingest."),
    SETTINGS_VIEW(Category.ADMIN, "View settings",
            "Open restaurant settings (hours, departments, treasury config)."),
    SETTINGS_MANAGE(Category.ADMIN, "Manage settings",
            "Edit restaurant hours, departments, and other configuration."),
    TAGS_MANAGE(Category.ADMIN, "Manage tags",
            "Create or edit tags used for expenses and reports."),
    ;

    /** UI grouping bucket — used by the "Manage permissions" modal to
     *  render permissions under collapsible sections so the catalog
     *  stays scannable as it grows. */
    public enum Category {
        REPORTS("Reports & analytics"),
        STOCK("Stock & inventory"),
        SCHEDULE("Schedule & attendance"),
        TEAM("People"),
        PAYROLL("Payroll"),
        FINANCE("Cash, expenses & treasury"),
        OPERATIONS("Operations & compliance"),
        ADMIN("Audit & integrations"),
        ;

        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final Category category;
    private final String label;
    private final String description;

    Permission(Category category, String label, String description) {
        this.category = category;
        this.label = label;
        this.description = description;
    }

    public Category category() { return category; }
    public String label() { return label; }
    public String description() { return description; }

    /**
     * Default capabilities baked into each role. ADMIN is exempt — it
     * implicitly has everything and overrides on admins are refused at
     * the service layer.
     *
     * <p>The defaults aim for a sensible "out of the box" set without
     * trying to be exhaustive. Admins can revoke any of these and grant
     * any others on a per-user basis through the permissions modal.</p>
     */
    public static Set<Permission> defaultsFor(Role role) {
        if (role == null) return EnumSet.noneOf(Permission.class);
        return switch (role) {
            case ADMIN -> EnumSet.allOf(Permission.class);
            case MANAGER -> EnumSet.of(
                    // Reports
                    REPORTS_VIEW, REPORTS_EXPORT, PROFIT_LOSS_VIEW,
                    // Stock — view + adjust, but not item CRUD or delete
                    STOCK_VIEW, STOCK_ADJUST,
                    // Schedule
                    ATTENDANCE_VIEW, SCHEDULE_MANAGE,
                    // People — visibility only
                    TEAM_VIEW,
                    // Finance — view-only on treasury, can edit expenses
                    EXPENSES_VIEW, EXPENSES_EDIT, TREASURY_VIEW,
                    // Operations
                    INCIDENTS_VIEW, INCIDENTS_FILE, INCIDENTS_RESOLVE,
                    CHECKLISTS_RUN, CHECKLISTS_CONFIGURE,
                    HACCP_LOG, HACCP_EXPORT,
                    CERTIFICATIONS_VIEW,
                    SETTINGS_VIEW);
            case CASHIER -> EnumSet.of(
                    // Cashiers can see their own work and file incidents.
                    REPORTS_VIEW,
                    ATTENDANCE_VIEW,
                    EXPENSES_VIEW,
                    INCIDENTS_FILE,
                    CHECKLISTS_RUN,
                    HACCP_LOG);
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

    /** Parse a CSV (the storage format on {@code app_user.extra_permissions}
     *  and {@code app_user.revoked_permissions}) into an EnumSet,
     *  ignoring blanks and unknown names. */
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

    /**
     * Compute the effective permission set for a (role, extras, revokes)
     * triple. Pure function — used by both authentication and the
     * "preview" path in the admin modal.
     *
     * <p>{@code revokes} only meaningfully subtracts from the role
     * defaults; the intersection with {@code extras} is also subtracted
     * so the rules read naturally: "this permission is denied for this
     * user, regardless of how it would have been granted otherwise."
     * That last bit matters mostly for ADMIN flips (which we forbid
     * elsewhere), but keeps semantics consistent for all roles.</p>
     */
    public static EnumSet<Permission> effective(
            Role role, Set<Permission> extras, Set<Permission> revokes) {
        EnumSet<Permission> out = EnumSet.copyOf(defaultsFor(role));
        if (extras != null) out.addAll(extras);
        if (revokes != null) out.removeAll(revokes);
        return out;
    }
}
