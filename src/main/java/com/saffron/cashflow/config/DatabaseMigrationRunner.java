package com.saffron.cashflow.config;

import com.saffron.cashflow.domain.AuditAction;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Schema/data fixes for existing databases — not application seed data. */
@Configuration
public class DatabaseMigrationRunner {

    @Bean
    @Order(0)
    CommandLineRunner runMigrations(JdbcTemplate jdbc) {
        return args -> {
            try {
                jdbc.update("UPDATE app_user SET pay_type = 'HOURLY' WHERE pay_type IS NULL");
            } catch (Exception ignored) {
                // pay_type column added on first Hibernate schema update
            }
            migrateRoleConstraint(jdbc);
            migrateUserCredentials(jdbc);
            migrateAuditLogActionConstraint(jdbc);
            migrateExpenseInvoices(jdbc);
            migrateSalaryPayments(jdbc);
            migratePayRateHistory(jdbc);
            migrateDeliverySettledToCard(jdbc);
            migrateManualDeliveryIncome(jdbc);
            migrateStandaloneExpenses(jdbc);
            migrateAdminTelegramDispatch(jdbc);
            migrateStockManagement(jdbc);
            migrateOperationsBackbone(jdbc);
            migrateUserPermissions(jdbc);
            migrateMenuRecipes(jdbc);
            migrateRestaurantClosures(jdbc);
        };
    }

    /**
     * Calendar of explicitly-closed days (holidays, renovations, etc.).
     *
     * <p>Consulted by the shift-create gap check to bypass the "previous
     * shift must be submitted" rule: closure days don't need a report.</p>
     *
     * <p>Idempotent — safe to run on every boot.</p>
     */
    private static void migrateRestaurantClosures(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS restaurant_closure (
                  closure_date DATE PRIMARY KEY,
                  reason VARCHAR(200) NOT NULL,
                  created_by VARCHAR(36),
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL
                )
                """);
    }

    /**
     * Recipe / cost-card storage. Two tables:
     * <ul>
     *   <li>{@code menu_recipe} — the cost card itself (yield, target
     *       food-cost %, VAT, optional waste %, optional link to a
     *       {@code menu_item}).</li>
     *   <li>{@code menu_recipe_ingredient} — line items pointing at
     *       {@code stock_item} rows with quantity, unit, and an
     *       optional waste override.</li>
     * </ul>
     * The ingredient table has {@code ON DELETE CASCADE} so wiping a
     * recipe takes its lines with it. We keep the foreign key to
     * {@code stock_item} loose (no SQL FK) so archiving a stock item
     * doesn't cascade — orphaned ingredient rows are detected at read
     * time and flagged in the UI. Idempotent.
     */
    private static void migrateMenuRecipes(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS menu_recipe (
                  id VARCHAR(36) PRIMARY KEY,
                  name VARCHAR(160) NOT NULL,
                  menu_item_id VARCHAR(36),
                  yield_quantity NUMERIC(12,3) NOT NULL DEFAULT 1,
                  yield_unit VARCHAR(24) NOT NULL DEFAULT 'piece',
                  target_food_cost_pct NUMERIC(5,2) DEFAULT 30.00,
                  vat_rate_pct NUMERIC(5,2) NOT NULL DEFAULT 8.00,
                  waste_pct NUMERIC(5,2),
                  notes TEXT,
                  active BOOLEAN NOT NULL DEFAULT true,
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_menu_recipe_menu_item ON menu_recipe (menu_item_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_menu_recipe_active ON menu_recipe (active)");
        // ─── v2 columns: labor, packaging, overhead, alt targets ────
        jdbc.execute("ALTER TABLE menu_recipe ADD COLUMN IF NOT EXISTS labor_minutes_per_unit NUMERIC(8,2)");
        jdbc.execute("ALTER TABLE menu_recipe ADD COLUMN IF NOT EXISTS labor_rate_per_hour NUMERIC(12,2)");
        jdbc.execute("ALTER TABLE menu_recipe ADD COLUMN IF NOT EXISTS packaging_cost_per_unit NUMERIC(12,4)");
        jdbc.execute("ALTER TABLE menu_recipe ADD COLUMN IF NOT EXISTS overhead_pct NUMERIC(5,2)");
        jdbc.execute("ALTER TABLE menu_recipe ADD COLUMN IF NOT EXISTS target_prime_cost_pct NUMERIC(5,2)");
        jdbc.execute("ALTER TABLE menu_recipe ADD COLUMN IF NOT EXISTS min_margin_pct NUMERIC(5,2)");

        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS menu_recipe_ingredient (
                  id VARCHAR(36) PRIMARY KEY,
                  recipe_id VARCHAR(36) NOT NULL REFERENCES menu_recipe(id) ON DELETE CASCADE,
                  stock_item_id VARCHAR(36) NOT NULL,
                  quantity NUMERIC(14,4) NOT NULL DEFAULT 0,
                  unit VARCHAR(16) NOT NULL DEFAULT 'pcs',
                  waste_pct NUMERIC(5,2),
                  sort_order INTEGER NOT NULL DEFAULT 0,
                  note VARCHAR(240)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_recipe_ingredient_recipe ON menu_recipe_ingredient (recipe_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_recipe_ingredient_stock ON menu_recipe_ingredient (stock_item_id)");
        // ─── v2: sub-recipes (relax NOT NULL on stock_item_id) ──────
        jdbc.execute("ALTER TABLE menu_recipe_ingredient ALTER COLUMN stock_item_id DROP NOT NULL");
        jdbc.execute("ALTER TABLE menu_recipe_ingredient ADD COLUMN IF NOT EXISTS sub_recipe_id VARCHAR(36)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_recipe_ingredient_sub ON menu_recipe_ingredient (sub_recipe_id)");

        // ─── v2: cost snapshots (append-only history) ───────────────
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS menu_recipe_cost_snapshot (
                  id VARCHAR(36) PRIMARY KEY,
                  recipe_id VARCHAR(36) NOT NULL REFERENCES menu_recipe(id) ON DELETE CASCADE,
                  food_cost NUMERIC(14,4),
                  prime_cost NUMERIC(14,4),
                  fully_loaded_cost NUMERIC(14,4),
                  cost_per_unit NUMERIC(14,4),
                  suggested_price NUMERIC(14,4),
                  achieved_food_cost_pct NUMERIC(6,2),
                  margin_pct NUMERIC(6,2),
                  source VARCHAR(16) NOT NULL DEFAULT 'SAVE',
                  note VARCHAR(240),
                  taken_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_recipe_snapshot_recipe ON menu_recipe_cost_snapshot (recipe_id, taken_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_recipe_snapshot_source ON menu_recipe_cost_snapshot (source)");
    }

    /**
     * Per-user permission overlay. Adds two TEXT columns to
     * {@code app_user} that store CSVs of
     * {@link com.saffron.cashflow.domain.Permission} enum names:
     * <ul>
     *   <li>{@code extra_permissions} — keys an admin has granted on top
     *       of the user's role defaults.</li>
     *   <li>{@code revoked_permissions} — keys the user would otherwise
     *       inherit from their role default but that an admin has
     *       explicitly denied. Enables "manager minus payroll" or
     *       "cashier plus stock" overrides without inventing new
     *       roles.</li>
     * </ul>
     * Both columns are nullable; NULL and empty string both decode to
     * "no overrides". Idempotent.
     */
    private static void migrateUserPermissions(JdbcTemplate jdbc) {
        jdbc.execute(
                "ALTER TABLE app_user ADD COLUMN IF NOT EXISTS extra_permissions TEXT");
        jdbc.execute(
                "ALTER TABLE app_user ADD COLUMN IF NOT EXISTS revoked_permissions TEXT");
    }

    /**
     * Operations & compliance backbone (Section E of the roadmap):
     * incidents, employee certifications, checklists, HACCP, and 2FA.
     * Idempotent — every CREATE uses {@code IF NOT EXISTS}.
     */
    private static void migrateOperationsBackbone(JdbcTemplate jdbc) {
        // -------- E4: Incident log --------
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS incident (
                  id VARCHAR(36) PRIMARY KEY,
                  title VARCHAR(200) NOT NULL,
                  category VARCHAR(60),
                  occurred_on DATE NOT NULL,
                  severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
                  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
                  description TEXT,
                  estimated_cost NUMERIC(12,2),
                  photo_path VARCHAR(255),
                  reported_by_id VARCHAR(36) NOT NULL,
                  assignee_id VARCHAR(36),
                  resolved_at TIMESTAMPTZ,
                  resolved_by_id VARCHAR(36),
                  resolution_notes TEXT,
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_incident_status ON incident (status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_incident_occurred ON incident (occurred_on DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_incident_assignee ON incident (assignee_id)");

        // -------- E3: Employee certifications --------
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS employee_cert (
                  id VARCHAR(36) PRIMARY KEY,
                  user_id VARCHAR(36) NOT NULL,
                  type VARCHAR(60) NOT NULL,
                  number VARCHAR(120),
                  issuer VARCHAR(160),
                  issued_on DATE,
                  expires_on DATE,
                  notes TEXT,
                  file_path VARCHAR(255),
                  last_warning_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_employee_cert_user ON employee_cert (user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_employee_cert_expires ON employee_cert (expires_on)");

        // -------- E2: Checklist templates + runs --------
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS checklist_template (
                  id VARCHAR(36) PRIMARY KEY,
                  name VARCHAR(160) NOT NULL,
                  type VARCHAR(16) NOT NULL,
                  role VARCHAR(40),
                  description TEXT,
                  items TEXT NOT NULL,
                  active BOOLEAN NOT NULL DEFAULT true,
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_checklist_template_type ON checklist_template (type)");

        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS checklist_run (
                  id VARCHAR(36) PRIMARY KEY,
                  template_id VARCHAR(36) NOT NULL REFERENCES checklist_template(id) ON DELETE CASCADE,
                  run_date DATE NOT NULL,
                  completed_by_id VARCHAR(36),
                  responses TEXT NOT NULL,
                  total_items INT NOT NULL DEFAULT 0,
                  completed_items INT NOT NULL DEFAULT 0,
                  notes TEXT,
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_checklist_run_date ON checklist_run (run_date DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_checklist_run_template ON checklist_run (template_id)");

        // -------- E1: HACCP logs --------
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS haccp_log (
                  id VARCHAR(36) PRIMARY KEY,
                  kind VARCHAR(40) NOT NULL,
                  recorded_on DATE NOT NULL,
                  recorded_at TIMESTAMPTZ NOT NULL,
                  recorded_by_id VARCHAR(36) NOT NULL,
                  location VARCHAR(120),
                  temperature_c NUMERIC(5,2),
                  status VARCHAR(16) NOT NULL DEFAULT 'OK',
                  notes TEXT,
                  photo_path VARCHAR(255),
                  data TEXT,
                  created_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_haccp_log_kind ON haccp_log (kind, recorded_on DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_haccp_log_date ON haccp_log (recorded_on DESC)");

        // -------- E5: 2FA --------
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS user_totp (
                  user_id VARCHAR(36) PRIMARY KEY,
                  secret_b32 VARCHAR(64) NOT NULL,
                  enabled BOOLEAN NOT NULL DEFAULT false,
                  enabled_at TIMESTAMPTZ,
                  last_used_at TIMESTAMPTZ,
                  backup_codes_hash VARCHAR(2000)
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS user_session (
                  id VARCHAR(36) PRIMARY KEY,
                  user_id VARCHAR(36) NOT NULL,
                  token_hash VARCHAR(128) NOT NULL UNIQUE,
                  user_agent VARCHAR(255),
                  ip VARCHAR(64),
                  created_at TIMESTAMPTZ NOT NULL,
                  last_seen_at TIMESTAMPTZ NOT NULL,
                  revoked_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_user_session_user ON user_session (user_id, created_at DESC)");
    }

    /**
     * Stock management: tracks on-hand inventory for menu items / raw
     * ingredients, with a movement log that records every change (sales,
     * manual adjustments, deliveries, waste). Idempotent — safe to run
     * on every boot.
     */
    private static void migrateStockManagement(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS stock_item (
                  id VARCHAR(36) PRIMARY KEY,
                  name VARCHAR(160) NOT NULL,
                  sku VARCHAR(64),
                  unit VARCHAR(16) NOT NULL DEFAULT 'pcs',
                  menu_item_id VARCHAR(36),
                  category VARCHAR(40),
                  on_hand NUMERIC(14,3) NOT NULL DEFAULT 0,
                  low_stock_threshold NUMERIC(14,3),
                  par_level NUMERIC(14,3),
                  unit_cost NUMERIC(12,2),
                  notes TEXT,
                  active BOOLEAN NOT NULL DEFAULT true,
                  last_movement_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_stock_item_active ON stock_item (active)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_stock_item_menu ON stock_item (menu_item_id)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_stock_item_sku ON stock_item (LOWER(sku)) WHERE sku IS NOT NULL");

        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS stock_movement (
                  id VARCHAR(36) PRIMARY KEY,
                  stock_item_id VARCHAR(36) NOT NULL REFERENCES stock_item(id) ON DELETE CASCADE,
                  type VARCHAR(24) NOT NULL,
                  delta NUMERIC(14,3) NOT NULL,
                  balance_after NUMERIC(14,3) NOT NULL,
                  reference_type VARCHAR(40),
                  reference_id VARCHAR(64),
                  reason VARCHAR(500),
                  user_id VARCHAR(36),
                  reverted BOOLEAN NOT NULL DEFAULT false,
                  reverted_by_id VARCHAR(36),
                  reverted_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_stock_movement_item ON stock_movement (stock_item_id, created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_stock_movement_ref ON stock_movement (reference_type, reference_id)");
        // Idempotency for POS-driven decrements: one movement per (pos sale id).
        jdbc.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_stock_movement_pos_sale "
                        + "ON stock_movement (reference_id) "
                        + "WHERE reference_type = 'POS_SALE'");
    }

    private static void migrateAdminTelegramDispatch(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS admin_telegram_dispatch (
                  id VARCHAR(255) PRIMARY KEY,
                  dedupe_key VARCHAR(200) NOT NULL UNIQUE,
                  preview VARCHAR(500) NOT NULL,
                  sent_at TIMESTAMPTZ NOT NULL
                )
                """);
    }

    private static void migrateManualDeliveryIncome(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS manual_delivery_income (
                  id VARCHAR(255) PRIMARY KEY,
                  effective_date DATE NOT NULL,
                  platform VARCHAR(16) NOT NULL,
                  gross_amount NUMERIC(12,2) NOT NULL,
                  settled_to_card NUMERIC(12,2),
                  notes TEXT,
                  created_by VARCHAR(255) NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_manual_delivery_income_date "
                        + "ON manual_delivery_income (effective_date DESC)");
    }

    private static void migrateStandaloneExpenses(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE expense_item ADD COLUMN IF NOT EXISTS effective_date DATE");
        jdbc.update(
                """
                UPDATE expense_item ei
                SET effective_date = de.entry_date
                FROM daily_entry de
                WHERE ei.entry_id = de.id
                  AND ei.effective_date IS NULL
                """);
        jdbc.update("UPDATE expense_item SET effective_date = CURRENT_DATE WHERE effective_date IS NULL");
        try {
            jdbc.execute("ALTER TABLE expense_item ALTER COLUMN effective_date SET NOT NULL");
        } catch (Exception ex) {
            System.err.println("Warning: expense_item effective_date NOT NULL: " + ex.getMessage());
        }
        try {
            jdbc.execute("ALTER TABLE expense_item ALTER COLUMN entry_id DROP NOT NULL");
        } catch (Exception ex) {
            System.err.println("Warning: expense_item entry_id nullable: " + ex.getMessage());
        }
        try {
            jdbc.execute("ALTER TABLE receipt_file ALTER COLUMN entry_id DROP NOT NULL");
        } catch (Exception ex) {
            System.err.println("Warning: receipt_file entry_id nullable: " + ex.getMessage());
        }
    }

    private static void migrateDeliverySettledToCard(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE daily_entry ADD COLUMN IF NOT EXISTS wolt_settled_to_card NUMERIC(12,2)");
        jdbc.execute("ALTER TABLE daily_entry ADD COLUMN IF NOT EXISTS bolt_settled_to_card NUMERIC(12,2)");
        jdbc.execute("ALTER TABLE daily_entry ADD COLUMN IF NOT EXISTS uber_eats_settled_to_card NUMERIC(12,2)");
        jdbc.execute("ALTER TABLE daily_entry ADD COLUMN IF NOT EXISTS glovo_settled_to_card NUMERIC(12,2)");
        jdbc.execute("ALTER TABLE daily_entry ADD COLUMN IF NOT EXISTS other_settled_to_card NUMERIC(12,2)");
    }

    private static void migratePayRateHistory(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS pay_rate_change (
                  id VARCHAR(255) PRIMARY KEY,
                  user_id VARCHAR(255) NOT NULL REFERENCES app_user(id),
                  pay_type VARCHAR(16) NOT NULL,
                  pay_amount NUMERIC(10,2) NOT NULL,
                  effective_from DATE NOT NULL,
                  notes TEXT,
                  created_by VARCHAR(255) NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_pay_rate_change_user_effective "
                        + "ON pay_rate_change (user_id, effective_from DESC)");
        int seeded = jdbc.update(
                """
                INSERT INTO pay_rate_change (
                  id, user_id, pay_type, pay_amount, effective_from, notes, created_by, created_at
                )
                SELECT
                  u.id || '-pay-migrated',
                  u.id,
                  COALESCE(u.pay_type, 'HOURLY'),
                  u.hourly_rate,
                  COALESCE(u.start_date, CURRENT_DATE),
                  'Migrated from current pay',
                  COALESCE(
                    (SELECT id FROM app_user WHERE role = 'ADMIN' ORDER BY created_at LIMIT 1),
                    u.id
                  ),
                  NOW()
                FROM app_user u
                WHERE u.hourly_rate IS NOT NULL
                  AND u.hourly_rate > 0
                  AND u.role = 'CASHIER'
                  AND NOT EXISTS (
                    SELECT 1 FROM pay_rate_change p WHERE p.user_id = u.id
                  )
                """);
        if (seeded > 0) {
            System.out.println("Database: seeded " + seeded + " pay_rate_change row(s) from existing cashiers");
        }
    }

    private static void migrateSalaryPayments(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS salary_payment (
                  id VARCHAR(255) PRIMARY KEY,
                  user_id VARCHAR(255) NOT NULL REFERENCES app_user(id),
                  amount NUMERIC(12,2) NOT NULL,
                  paid_date DATE NOT NULL,
                  payment_source VARCHAR(16) NOT NULL,
                  period_from DATE,
                  period_to DATE,
                  notes TEXT,
                  created_by VARCHAR(255) NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_salary_payment_paid_date ON salary_payment (paid_date)");
    }

    private static void migrateExpenseInvoices(JdbcTemplate jdbc) {
        try {
            jdbc.execute("ALTER TABLE receipt_file ADD COLUMN IF NOT EXISTS expense_item_id VARCHAR(255)");
            if (columnExists(jdbc, "expense_item", "receipt_file_id")) {
                jdbc.update(
                        """
                        UPDATE receipt_file rf
                        SET expense_item_id = ei.id
                        FROM expense_item ei
                        WHERE ei.receipt_file_id = rf.id
                          AND (rf.expense_item_id IS NULL OR rf.expense_item_id = '')
                        """);
                jdbc.execute("ALTER TABLE expense_item DROP COLUMN IF EXISTS receipt_file_id");
            }
        } catch (Exception ex) {
            System.err.println("Warning: expense invoice migration: " + ex.getMessage());
        }
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """,
                Integer.class,
                table,
                column);
        return n != null && n > 0;
    }

    private static void migrateAuditLogActionConstraint(JdbcTemplate jdbc) {
        String allowed = Arrays.stream(AuditAction.values())
                .map(a -> "'" + a.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_action_check");
            jdbc.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN ("
                            + allowed
                            + "))");
            System.out.println("Database: audit_log_action_check updated (" + AuditAction.values().length + " actions)");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to migrate audit_log action constraint: " + ex.getMessage(), ex);
        }
    }

    private static void migrateUserCredentials(JdbcTemplate jdbc) {
        try {
            jdbc.execute("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS username VARCHAR(32)");
            jdbc.execute("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT true");
            jdbc.execute("ALTER TABLE app_user ALTER COLUMN email DROP NOT NULL");
            jdbc.update(
                    """
                    UPDATE app_user
                    SET username = LOWER(SPLIT_PART(email, '@', 1))
                    WHERE (username IS NULL OR username = '')
                      AND email IS NOT NULL AND email <> ''
                    """);
            jdbc.update(
                    """
                    UPDATE app_user
                    SET username = 'user_' || SUBSTRING(id, 1, 8)
                    WHERE username IS NULL OR username = ''
                    """);
            jdbc.update("UPDATE app_user SET must_change_password = true WHERE must_change_password IS NULL");
            deduplicateUsernames(jdbc);
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_username ON app_user (username)");
        } catch (Exception ex) {
            System.err.println("Warning: user credentials migration: " + ex.getMessage());
        }
    }

    private static void deduplicateUsernames(JdbcTemplate jdbc) {
        int renamed = jdbc.update(
                """
                WITH ranked AS (
                  SELECT id,
                         ROW_NUMBER() OVER (
                           PARTITION BY username
                           ORDER BY CASE role WHEN 'ADMIN' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END,
                                    created_at NULLS LAST,
                                    id
                         ) AS rn
                  FROM app_user
                  WHERE username IS NOT NULL AND username <> ''
                )
                UPDATE app_user u
                SET username = LEFT(u.username, 24) || '_' || SUBSTRING(u.id, 1, 6)
                FROM ranked r
                WHERE u.id = r.id AND r.rn > 1
                """);
        if (renamed > 0) {
            System.out.println("Database: renamed " + renamed + " duplicate username(s)");
        }
    }

    private static void migrateRoleConstraint(JdbcTemplate jdbc) {
        try {
            jdbc.execute("ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_role_check");
            jdbc.execute(
                    "ALTER TABLE app_user ADD CONSTRAINT app_user_role_check "
                            + "CHECK (role IN ('ADMIN', 'MANAGER', 'CASHIER'))");
        } catch (Exception ex) {
            System.err.println("Warning: could not update app_user role constraint: " + ex.getMessage());
        }
    }
}
