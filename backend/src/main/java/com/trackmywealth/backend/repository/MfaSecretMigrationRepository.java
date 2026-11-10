package com.trackmywealth.backend.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * #544: the statements of the startup re-encryption of {@code app_user.mfa_totp_secret}. Plain JDBC
 * rather than {@link AppUserRepository}: the migration runs before any request, reads and writes
 * the one encrypted column only, and must not load every user as an entity. Every method needs the
 * caller's transaction - the locks it takes are released at its commit or rollback.
 */
@Repository
public class MfaSecretMigrationRepository {

  // An arbitrary, reserved PostgreSQL advisory lock key for this one migration (SetupService keeps
  // its own). Transaction-scoped, so no unlock call is needed.
  private static final long MFA_KEY_MIGRATION_LOCK_KEY = 727100544001L;

  private final JdbcTemplate jdbcTemplate;

  public MfaSecretMigrationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Serializes backends that start at the same time, until the caller's transaction ends. */
  public void lockMigration() {
    jdbcTemplate.queryForObject(
        "SELECT 1 FROM pg_advisory_xact_lock(?)", Integer.class, MFA_KEY_MIGRATION_LOCK_KEY);
  }

  /**
   * Every stored secret by user id, in id order, each row locked against concurrent change until
   * the caller's transaction ends.
   */
  public Map<UUID, String> lockAllSecrets() {
    return jdbcTemplate.query(
        "SELECT id, mfa_totp_secret FROM app_user WHERE mfa_totp_secret IS NOT NULL"
            + " ORDER BY id FOR UPDATE",
        rs -> {
          Map<UUID, String> secrets = new LinkedHashMap<>();
          while (rs.next()) {
            secrets.put(rs.getObject("id", UUID.class), rs.getString("mfa_totp_secret"));
          }
          return secrets;
        });
  }

  /** Replaces one user's secret only if it is still {@code expected}; whether it did. */
  public boolean replaceSecret(UUID userId, String expected, String replacement) {
    return jdbcTemplate.update(
            "UPDATE app_user SET mfa_totp_secret = ? WHERE id = ? AND mfa_totp_secret = ?",
            replacement,
            userId,
            expected)
        == 1;
  }
}
