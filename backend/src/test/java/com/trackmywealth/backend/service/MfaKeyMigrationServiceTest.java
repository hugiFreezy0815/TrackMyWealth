package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.config.MfaProperties;
import com.trackmywealth.backend.repository.MfaSecretMigrationRepository;
import com.trackmywealth.backend.testsupport.RowLevelSecurityRole;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #544: the startup re-encryption on the real Flyway schema (its version and updated_at triggers),
 * run as an RLS-bound runtime role - so the test fails if that role cannot see or update every
 * user's secret, as it would in a role-split installation.
 */
@Testcontainers
class MfaKeyMigrationServiceTest {

  private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
  private static final String RUNTIME_ROLE = "mfa_migration_runtime";
  private static final String RUNTIME_PASSWORD = "mfa_migration_password";
  // Fixed ids, so the rows are read in a known order (the migration reads by id).
  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  private static JdbcTemplate admin;

  private final MfaEncryptionService configuredKey =
      new MfaEncryptionService(new MfaProperties(randomKey()), "self-hosted");
  private final MfaEncryptionService placeholder =
      new MfaEncryptionService(new MfaProperties(""), "self-hosted");

  @BeforeAll
  static void migrateSchemaAndCreateRuntimeRole() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
    try (Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      RowLevelSecurityRole.createWithLogin(connection, RUNTIME_ROLE, RUNTIME_PASSWORD);
    }
    admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  @BeforeEach
  void removeUsers() {
    admin.update("DELETE FROM app_user");
  }

  @Test
  void reencryptsLegacySecretsLeavesCurrentOnesUntouchedAndChangesNothingOnRestart() {
    String legacyCiphertext = placeholder.encrypt(TOTP_SECRET);
    String currentCiphertext = configuredKey.encrypt("OTHERSECRET23456");
    insertUser(FIRST, legacyCiphertext);
    insertUser(SECOND, currentCiphertext);
    insertUser(THIRD, null);

    assertThat(migration(configuredKey).migrate()).isEqualTo(1);

    String rotated = secret(FIRST);
    assertThat(rotated).isNotEqualTo(legacyCiphertext);
    assertThat(configuredKey.decrypt(rotated)).isEqualTo(TOTP_SECRET);
    assertThat(version(FIRST)).isEqualTo(1);
    // Never written at all: the version trigger would have bumped it.
    assertThat(secret(SECOND)).isEqualTo(currentCiphertext);
    assertThat(version(SECOND)).isZero();
    assertThat(secret(THIRD)).isNull();

    assertThat(migration(configuredKey).migrate()).isZero();
    assertThat(secret(FIRST)).isEqualTo(rotated);
    assertThat(version(FIRST)).isEqualTo(1);
  }

  @Test
  void anUndecryptableSecretFailsWithoutRevealingItAndRollsBackRowsAlreadyReencrypted() {
    // FIRST is re-encrypted before SECOND fails, so the rollback has something to undo.
    String legacyCiphertext = placeholder.encrypt(TOTP_SECRET);
    String unknownKeyCiphertext =
        new MfaEncryptionService(new MfaProperties(randomKey()), "self-hosted")
            .encrypt(TOTP_SECRET);
    insertUser(FIRST, legacyCiphertext);
    insertUser(SECOND, unknownKeyCiphertext);

    assertThatThrownBy(() -> migration(configuredKey).migrate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be decrypted with the configured or legacy key")
        .message()
        .doesNotContain(unknownKeyCiphertext, SECOND.toString(), TOTP_SECRET);

    assertThat(secret(FIRST)).isEqualTo(legacyCiphertext);
    assertThat(version(FIRST)).isZero();
    assertThat(secret(SECOND)).isEqualTo(unknownKeyCiphertext);
  }

  @Test
  void corruptCiphertextAlsoFailsTheMigration() {
    insertUser(FIRST, "not-valid-base64");

    assertThatThrownBy(() -> migration(configuredKey).migrate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be decrypted with the configured or legacy key");
    assertThat(secret(FIRST)).isEqualTo("not-valid-base64");
  }

  @Test
  void backendsStartingTogetherReencryptEachSecretOnce() throws Exception {
    insertUser(FIRST, placeholder.encrypt(TOTP_SECRET));
    insertUser(SECOND, placeholder.encrypt("OTHERSECRET23456"));
    int backends = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(backends);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < backends; i++) {
        MfaKeyMigrationService backend = migration(configuredKey);
        Callable<Integer> run =
            () -> {
              start.await();
              return backend.migrate();
            };
        results.add(executor.submit(run));
      }
      start.countDown();
      int rotated = 0;
      for (Future<Integer> result : results) {
        rotated += result.get(30, TimeUnit.SECONDS);
      }

      assertThat(rotated).isEqualTo(2);
    } finally {
      executor.shutdownNow();
    }
    assertThat(version(FIRST)).isEqualTo(1);
    assertThat(version(SECOND)).isEqualTo(1);
    assertThat(configuredKey.decrypt(secret(FIRST))).isEqualTo(TOTP_SECRET);
    assertThat(configuredKey.decrypt(secret(SECOND))).isEqualTo("OTHERSECRET23456");
  }

  @Test
  void aServerStillOnThePlaceholderKeyLeavesItsSecretsAlone() {
    String original = placeholder.encrypt(TOTP_SECRET);
    insertUser(FIRST, original);

    assertThat(migration(placeholder).migrate()).isZero();
    assertThat(secret(FIRST)).isEqualTo(original);
  }

  /** A migration as the backend runs it, connected as the RLS-bound runtime role. */
  private static MfaKeyMigrationService migration(MfaEncryptionService encryptionService) {
    DriverManagerDataSource runtime =
        new DriverManagerDataSource(postgres.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
    return new MfaKeyMigrationService(
        new MfaSecretMigrationRepository(new JdbcTemplate(runtime)),
        new DataSourceTransactionManager(runtime),
        encryptionService);
  }

  private static void insertUser(UUID id, String secret) {
    admin.update(
        "INSERT INTO app_user (id, email, password_hash, mfa_totp_secret)"
            + " VALUES (?, ?, 'synthetic-hash', ?)",
        id,
        id + "@example.invalid",
        secret);
  }

  private static String secret(UUID id) {
    return admin.queryForObject(
        "SELECT mfa_totp_secret FROM app_user WHERE id = ?", String.class, id);
  }

  private static int version(UUID id) {
    return admin.queryForObject("SELECT version FROM app_user WHERE id = ?", Integer.class, id);
  }

  private static String randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
