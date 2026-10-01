package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import java.sql.Connection;
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
  void denialAuditCommitsEvenThoughOuterTransactionRollsBack() {
    UUID requestedId = UUID.randomUUID();

    assertThatThrownBy(() -> denyingService.denyInsideRollback(requestedId))
        .isInstanceOf(ResponseStatusException.class);

    List<AuthorizationDenialLog> rows = repository.findByRequestedEntityId(requestedId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getReason()).isEqualTo("NOT_FOUND");
    assertThat(rows.get(0).getRequestedEntityType()).isEqualTo("Account");
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
    void denyInsideRollback(UUID requestedId) {
      throw auditService.denyAsNotFound(null, "Account", requestedId);
    }
  }
}
