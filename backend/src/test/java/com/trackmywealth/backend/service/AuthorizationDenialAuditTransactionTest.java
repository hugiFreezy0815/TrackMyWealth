package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-28-02: the denial audit uses REQUIRES_NEW so an endpoint transaction that aborts with its 404
 * cannot roll the security audit row back with the business transaction.
 */
@Testcontainers
@SpringBootTest
@Import(AuthorizationDenialAuditTransactionTest.Config.class)
class AuthorizationDenialAuditTransactionTest {

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
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @Autowired DataSource dataSource;
  @Autowired AuthorizationDenialLogRepository repository;
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

    List<AuthorizationDenialLog> rows = repository.findByRequestedEntityId(requestedId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getPrincipalUserId()).isEqualTo(principalUserId);
    assertThat(rows.get(0).getReason()).isEqualTo("NOT_FOUND");
    assertThat(rows.get(0).getRequestedEntityType()).isEqualTo("Account");
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

  @TestConfiguration
  static class Config {

    @Bean
    DenyingTransactionalService denyingTransactionalService(
        AuthorizationDenialAuditService auditService) {
      return new DenyingTransactionalService(auditService);
    }
  }

  static class DenyingTransactionalService {

    private final AuthorizationDenialAuditService auditService;

    DenyingTransactionalService(AuthorizationDenialAuditService auditService) {
      this.auditService = auditService;
    }

    @Transactional
    void denyInsideRollback(UUID principalUserId, UUID requestedId) {
      AuthenticatedUserPrincipal actor =
          new AuthenticatedUserPrincipal(principalUserId, "STANDARD_USER", null, UUID.randomUUID());
      throw auditService.denyAsNotFound(actor, "Account", requestedId);
    }
  }
}
