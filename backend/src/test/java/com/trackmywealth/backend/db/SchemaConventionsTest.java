package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies US-28-03's Definition of Done directly against the migrated schema: "A schema-linting
 * script... run in CI asserting no SERIAL-typed primary key exists anywhere" - FR-TEN-005 requires
 * every externally visible identifier to be a non-enumerable UUID.
 */
@Testcontainers
class SchemaConventionsTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static Connection connection;

  @BeforeAll
  static void migrate() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();

    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void closeConnection() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  void noColumnUsesASerialOrIdentitySequenceType() throws Exception {
    // A SERIAL/BIGSERIAL/SMALLSERIAL column is sugar for "INTEGER DEFAULT
    // nextval(...)" backed by an owned sequence - this query finds exactly that pattern
    // directly from the catalog, rather than trusting column type names (which report the
    // underlying integer type, not "serial").
    String sql =
        """
        SELECT n.nspname AS schema, c.relname AS table_name, a.attname AS column_name
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_depend d ON d.refobjid = c.oid AND d.refobjsubid = a.attnum
        JOIN pg_class seq ON seq.oid = d.objid
        WHERE c.relkind IN ('r', 'p') -- 'p' = partitioned table
          AND seq.relkind = 'S'
          AND d.deptype = 'a'
          AND n.nspname NOT IN ('pg_catalog', 'information_schema')
        """;

    List<String> offendingColumns = new ArrayList<>();
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        offendingColumns.add(
            rs.getString("schema")
                + "."
                + rs.getString("table_name")
                + "."
                + rs.getString("column_name"));
      }
    }

    assertThat(offendingColumns)
        .as(
            "FR-TEN-005: every primary key must be a non-enumerable UUID"
                + " (gen_random_uuid()), never a SERIAL/BIGSERIAL/IDENTITY sequence")
        .isEmpty();
  }

  @Test
  void everyDomainTablesPrimaryKeyIncludesAUuidColumn() throws Exception {
    // Grouped by table rather than asserting every PK column is UUID-typed: `price`/`fx_rate`/
    // `daily_valuation` use a composite primary key of (id UUID, <date column>) purely because
    // PostgreSQL requires the partition key in any unique constraint on a partitioned table (see
    // database-schema.md section 5) - the date column there is a partitioning artifact, not a
    // second identifier, so it must not fail this check on its own.
    String sql =
        """
        SELECT n.nspname AS schema, c.relname AS table_name,
               array_agg(t.typname ORDER BY a.attname) AS pk_column_types
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
        JOIN pg_type t ON t.oid = a.atttypid
        WHERE i.indisprimary
          AND c.relkind IN ('r', 'p') -- 'p' = partitioned table (price/fx_rate/daily_valuation)
          AND n.nspname = 'public'
        GROUP BY n.nspname, c.relname
        """;

    // Tables deliberately excluded from the UUID-primary-key rule, and why:
    //  - qrtz_* (V90) and flyway_schema_history: framework-owned infrastructure tables (Quartz,
    //    Flyway itself), not this project's domain data at all.
    //  - gics_structure_version, trading_calendar, trading_calendar_holiday (V18): shared,
    //    global reference/lookup data - the same category as institution_catalogue, explicitly
    //    documented in database-schema.md section 4 as outside RLS/tenancy entirely. FR-TEN-005's
    //    enumerability concern is about tenant-owned records; a trading calendar keyed by its
    //    real-world code (e.g. "NYSE") isn't tenant data and has nothing to enumerate.
    var exemptTables =
        java.util.Set.of(
            "gics_structure_version",
            "trading_calendar",
            "trading_calendar_holiday",
            "flyway_schema_history");

    List<String> tablesWithNoUuidInPrimaryKey = new ArrayList<>();
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        String tableName = rs.getString("table_name");
        if (tableName.toLowerCase().startsWith("qrtz_") || exemptTables.contains(tableName)) {
          continue;
        }
        java.sql.Array pkColumnTypes = rs.getArray("pk_column_types");
        List<Object> types = List.of((Object[]) pkColumnTypes.getArray());
        if (!types.contains("uuid")) {
          tablesWithNoUuidInPrimaryKey.add(rs.getString("schema") + "." + tableName + " " + types);
        }
      }
    }

    assertThat(tablesWithNoUuidInPrimaryKey)
        .as("FR-TEN-005: every domain table's primary key must include a UUID identifier column")
        .isEmpty();
  }

  @Test
  void everyUuidPrimaryKeyColumnDefaultsToGenRandomUuid() throws Exception {
    // US-28-03's second acceptance criterion is stricter than "is UUID-typed": it must be UUIDv4
    // (random), not a time-based (v1) variant that would leak creation order. gen_random_uuid()
    // (pgcrypto pre-PG13, built into core from PG13 onward - see V1's extension comment) is
    // documented by PostgreSQL to generate exactly that: "a version 4 (random) UUID." Pinning
    // every UUID PK column's default to this one specific function is what actually verifies
    // v4-ness at the schema level, rather than merely "some UUID."
    //
    // Excludes PK columns that are also a foreign key (e.g. account_securities.account_id,
    // savings_rate_methodology.household_id - a "shared primary key" 1:1 extension table, or
    // security_identifier.security_id as part of a composite PK): these don't generate a new
    // identity, they inherit the referenced row's already-v4 UUID, so requiring their own
    // gen_random_uuid() default would be wrong, not merely redundant.
    String sql =
        """
        SELECT n.nspname AS schema, c.relname AS table_name, a.attname AS column_name,
               pg_get_expr(ad.adbin, ad.adrelid) AS default_expr
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
        JOIN pg_type t ON t.oid = a.atttypid
        LEFT JOIN pg_attrdef ad ON ad.adrelid = c.oid AND ad.adnum = a.attnum
        WHERE i.indisprimary
          AND c.relkind IN ('r', 'p') -- 'p' = partitioned table
          AND n.nspname = 'public'
          AND t.typname = 'uuid'
          AND NOT EXISTS (
            SELECT 1 FROM pg_constraint fk
            WHERE fk.conrelid = c.oid AND fk.contype = 'f' AND a.attnum = ANY(fk.conkey)
          )
        """;

    List<String> offendingColumns = new ArrayList<>();
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        String defaultExpr = rs.getString("default_expr");
        if (!"gen_random_uuid()".equals(defaultExpr)) {
          offendingColumns.add(
              rs.getString("schema")
                  + "."
                  + rs.getString("table_name")
                  + "."
                  + rs.getString("column_name")
                  + " (default: "
                  + defaultExpr
                  + ")");
        }
      }
    }

    assertThat(offendingColumns)
        .as(
            "FR-TEN-005: every UUID primary key column must default to gen_random_uuid() (a"
                + " version 4/random UUID), never a time-based or externally-supplied-only"
                + " generator")
        .isEmpty();
  }
}
