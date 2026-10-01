package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-28-02 / #205: denial auditing survives caller rollback without taking a second connection on
 * the request thread, abusive volume is bounded and expired rows are removed.
 */
@Testcontainers
@SpringBootTest
@Import(AuthorizationDenialAuditTransactionTest.Config.class)
class AuthorizationDenialAuditTransactionTest {

  private static final int POOL_SIZE = 4;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @org.springframework.test.context.DynamicPropertySource
  static void datasourceProperties(
      org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(POOL_SIZE));
    registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    registry.add("app.rate-limit.enabled", () -> "false");
    registry.add("spring.quartz.auto-startup", () -> "false");
    registry.add("app.authorization-denial-audit.max-writes-per-principal", () -> "20");
    registry.add("app.authorization-denial-audit.refill-period", () -> "1m");
    registry.add("app.authorization-denial-audit.max-principals", () -> "100");
    registry.add("app.authorization-denial-audit.queue-capacity", () -> "100");
    registry.add("app.authorization-denial-audit.writer-threads", () -> "1");
    registry.add("app.authorization-denial-audit.retention", () -> "30d");
    registry.add("app.authorization-denial-audit.cleanup-interval", () -> "24h");
  }

  @Autowired DataSource dataSource;
  @Autowired AuthorizationDenialLogRepository repository;
  @Autowired AuthorizationDenialAuditRetentionService retentionService;
  @Autowired AuthorizationDenialAuditService auditService;
  @Autowired DenyingTransactionalService denyingService;

  @BeforeEach
  void cleanDenials() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM authorization_denial_log");
    }
  }

  @Test
  void denialAuditCommitsEvenThoughOuterTransactionRollsBack() throws Exception {
    UUID requestedId = UUID.randomUUID();
    UUID principalUserId = insertPrincipalUser();

    assertThatThrownBy(() -> denyingService.denyInsideRollback(principalUserId, requestedId))
        .isInstanceOf(ResponseStatusException.class);

    await(() -> !repository.findByRequestedEntityId(requestedId).isEmpty());
    List<AuthorizationDenialLog> rows = repository.findByRequestedEntityId(requestedId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getPrincipalUserId()).isEqualTo(principalUserId);
    assertThat(rows.get(0).getReason()).isEqualTo("NOT_FOUND");
    assertThat(rows.get(0).getRequestedEntityType()).isEqualTo("Account");
  }

  @Test
  void concurrentDenialsDoNotNeedASecondRequestThreadConnection() throws Exception {
    UUID principalUserId = insertPrincipalUser();
    CountDownLatch allHoldingConnections = new CountDownLatch(POOL_SIZE);
    CountDownLatch denyTogether = new CountDownLatch(1);
    ExecutorService callers = Executors.newFixedThreadPool(POOL_SIZE);

    try {
      List<Future<Integer>> results =
          java.util.stream.IntStream.range(0, POOL_SIZE)
              .mapToObj(
                  ignored ->
                      callers.submit(
                          () -> {
                            try {
                              denyingService.denyAfterHoldingConnection(
                                  principalUserId,
                                  UUID.randomUUID(),
                                  allHoldingConnections,
                                  denyTogether);
                              return 500;
                            } catch (ResponseStatusException ex) {
                              return ex.getStatusCode().value();
                            }
                          }))
              .toList();

      assertThat(allHoldingConnections.await(3, TimeUnit.SECONDS))
          .as("every outer transaction acquired and held one of the four pool connections")
          .isTrue();
      denyTogether.countDown();

      for (Future<Integer> result : results) {
        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(404);
      }
    } finally {
      denyTogether.countDown();
      callers.shutdownNow();
    }

    await(
        () ->
            repository.findByPrincipalUserIdOrderByOccurredAtAsc(principalUserId).size()
                == POOL_SIZE);
  }

  @Test
  void excessiveDenialsWriteOnlyTheConfiguredBudgetAndOneSummary() throws Exception {
    UUID principalUserId = insertPrincipalUser();

    for (int i = 0; i < 100; i++) {
      ResponseStatusException denial =
          auditService.denyAsNotFound(principalUserId, "Account", UUID.randomUUID());
      assertThat(denial.getStatusCode().value()).isEqualTo(404);
      assertThat(denial.getReason())
          .isEqualTo(AuthorizationDenialAuditService.GENERIC_NOT_FOUND_DETAIL);
    }

    await(
        () ->
            repository.findByPrincipalUserIdOrderByOccurredAtAsc(principalUserId).size()
                == 21);
    List<AuthorizationDenialLog> rows =
        repository.findByPrincipalUserIdOrderByOccurredAtAsc(principalUserId);
    assertThat(rows).hasSize(21);
    assertThat(rows).filteredOn(row -> "NOT_FOUND".equals(row.getReason())).hasSize(20);
    assertThat(rows).filteredOn(row -> "RATE_LIMITED".equals(row.getReason())).hasSize(1);
    assertThat(rows)
        .filteredOn(row -> "RATE_LIMITED".equals(row.getReason()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getRequestedEntityId()).isNull();
              assertThat(row.getRequestedEntityType()).isEqualTo("AuthorizationDenial");
            });
  }

  @Test
  void retentionDeletesOnlyRowsOlderThanConfiguredPeriod() throws Exception {
    UUID principalUserId = insertPrincipalUser();
    UUID expiredId = UUID.randomUUID();
    UUID freshId = UUID.randomUUID();
    insertDenial(
        principalUserId, expiredId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
    insertDenial(
        principalUserId, freshId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(29));

    retentionService.deleteExpiredRows();

    assertThat(repository.findByRequestedEntityId(expiredId)).isEmpty();
    assertThat(repository.findByRequestedEntityId(freshId)).hasSize(1);
  }

  private UUID insertPrincipalUser() throws Exception {
    UUID userId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO app_user "
                    + "(id, email, password_hash, role, status, language, reporting_currency) "
                    + "VALUES (?, ?, 'test-hash', 'STANDARD_USER', 'ACTIVE', 'EN', 'CHF')")) {
      statement.setObject(1, userId);
      statement.setString(2, "audit-" + userId + "@example.com");
      statement.executeUpdate();
    }
    return userId;
  }

  private void insertDenial(
      UUID principalUserId, UUID requestedEntityId, OffsetDateTime occurredAt) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO authorization_denial_log "
                    + "(principal_user_id, requested_entity_type, requested_entity_id, reason,"
                    + " occurred_at) VALUES (?, 'Account', ?, 'NOT_FOUND', ?)")) {
      statement.setObject(1, principalUserId);
      statement.setObject(2, requestedEntityId);
      statement.setObject(3, occurredAt);
      statement.executeUpdate();
    }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @TestConfiguration
  static class Config {

    @Bean
    DenyingTransactionalService denyingTransactionalService(
        AuthorizationDenialAuditService auditService, JdbcTemplate jdbcTemplate) {
      return new DenyingTransactionalService(auditService, jdbcTemplate);
    }
  }

  static class DenyingTransactionalService {

    private final AuthorizationDenialAuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    DenyingTransactionalService(
        AuthorizationDenialAuditService auditService, JdbcTemplate jdbcTemplate) {
      this.auditService = auditService;
      this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    void denyInsideRollback(UUID principalUserId, UUID requestedId) {
      acquireTransactionConnection();
      throw denial(principalUserId, requestedId);
    }

    @Transactional
    void denyAfterHoldingConnection(
        UUID principalUserId,
        UUID requestedId,
        CountDownLatch allHoldingConnections,
        CountDownLatch denyTogether)
        throws InterruptedException {
      acquireTransactionConnection();
      allHoldingConnections.countDown();
      if (!denyTogether.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to release concurrent denial requests");
      }
      throw denial(principalUserId, requestedId);
    }

    private void acquireTransactionConnection() {
      assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    private ResponseStatusException denial(UUID principalUserId, UUID requestedId) {
      AuthenticatedUserPrincipal actor =
          new AuthenticatedUserPrincipal(principalUserId, "STANDARD_USER", null, UUID.randomUUID());
      return auditService.denyAsNotFound(actor, "Account", requestedId);
    }
  }
}
