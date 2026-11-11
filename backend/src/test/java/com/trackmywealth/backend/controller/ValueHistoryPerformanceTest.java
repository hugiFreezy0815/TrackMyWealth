package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Docker-backed history performance acceptance fixture: 20 cash accounts and 50,000 ledger rows.
 * Run explicitly with HISTORY_PERF=true ./mvnw -B -Dtest=ValueHistoryPerformanceTest test. This is
 * intentionally separate from ordinary verification; timing is environment-specific.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "HISTORY_PERF", matches = "true")
class ValueHistoryPerformanceTest {

  private static final int ACCOUNTS = 20;
  private static final int TRANSACTIONS_PER_ACCOUNT = 2500;
  private static final int WARMUP_REQUESTS = 20;
  private static final int MEASURED_REQUESTS = 25;
  private static final double MAX_P95_MS = 800.0;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
    registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @LocalServerPort int port;
  @Autowired DataSource dataSource;
  @Autowired EntityManagerFactory entityManagerFactory;

  @Test
  void oneYearHistoryP95IsUnderEightHundredMilliseconds() throws Exception {
    RestTestClient anonymous =
        RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
    String token =
        anonymous
            .post()
            .uri("/api/v1/setup/administrator")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new SetupAdministratorRequest(
                    "history-benchmark@example.com", "correct-horse-battery-staple",
                    "History benchmark", "CHF"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(AuthTokensResponse.class)
            .returnResult()
            .getResponseBody()
            .accessToken();
    RestTestClient client =
        RestTestClient.bindToServer()
            .baseUrl("http://localhost:%d".formatted(port))
            .defaultHeader("Authorization", "Bearer " + token)
            .build();
    List<UUID> accountIds = new ArrayList<>();
    for (int i = 0; i < ACCOUNTS; i++) {
      AccountSummaryResponse account =
          client
              .post()
              .uri("/api/v1/accounts")
              .contentType(MediaType.APPLICATION_JSON)
              .body(AccountRequests.account("Benchmark cash " + i, "CASH", "CHF").build())
              .exchange()
              .expectStatus()
              .isCreated()
              .expectBody(AccountSummaryResponse.class)
              .returnResult()
              .getResponseBody();
      accountIds.add(account.id());
    }
    assertThat(accountIds).hasSize(ACCOUNTS);
    LocalDate today = LocalDate.now(ZoneId.of("Europe/Zurich"));
    seedFiftyThousandTransactions(accountIds, today);

    // Use a fixed warm-up so JVM compilation and database startup do not dominate steady-state p95.
    for (int i = 0; i < WARMUP_REQUESTS; i++) {
      history(client);
    }
    SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    sessionFactory.getStatistics().clear();
    List<Double> samples = new ArrayList<>();
    for (int i = 0; i < MEASURED_REQUESTS; i++) {
      long start = System.nanoTime();
      ValueHistoryResponse response = history(client);
      double millis = (System.nanoTime() - start) / 1_000_000.0;
      samples.add(millis);
      assertThat(response.points()).hasSizeBetween(52, 54);
      assertThat(response.points().get(response.points().size() - 1).date()).isEqualTo(today);
    }
    System.out.println("HISTORY_PERF measured requests in order (ms): " + samples);
    Collections.sort(samples);
    double p50 = samples.get((int) Math.ceil(0.50 * samples.size()) - 1);
    double p95 = samples.get((int) Math.ceil(0.95 * samples.size()) - 1);
    long queryCount = sessionFactory.getStatistics().getPrepareStatementCount();
    System.out.printf(
        "HISTORY_PERF period=1Y p50=%.2fms, p95=%.2fms, min=%.2fms, max=%.2fms, "
            + "hibernatePreparedStatements=%d, statementsPerRequest=%.1f, "
            + "accounts=%d, transactions=%d%n",
        p50,
        p95,
        samples.get(0),
        samples.get(samples.size() - 1),
        queryCount,
        (double) queryCount / samples.size(),
        ACCOUNTS,
        ACCOUNTS * TRANSACTIONS_PER_ACCOUNT);
    assertThat(p95)
        .as("1Y history p95 for 20 accounts and 50,000 transactions")
        .isLessThan(MAX_P95_MS);
  }

  private ValueHistoryResponse history(RestTestClient client) {
    return client
        .get()
        .uri("/api/v1/net-worth/history?period=1Y")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ValueHistoryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void seedFiftyThousandTransactions(List<UUID> accountIds, LocalDate today)
      throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      for (UUID accountId : accountIds) {
        try (PreparedStatement opening =
            connection.prepareStatement(
                "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance,"
                    + " currency, is_opening_balance) SELECT workspace_id, id, ?, 10000.00,"
                    + " native_currency, TRUE FROM account WHERE id = ?")) {
          opening.setObject(1, today.minusYears(1).minusDays(1));
          opening.setObject(2, accountId);
          assertThat(opening.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement transactions =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency) "
                    + "SELECT a.workspace_id, a.id, 'INCOME', CAST(? AS date) - (n % 365),"
                    + " 1.00, a.native_currency FROM account a"
                    + " CROSS JOIN generate_series(1, 2500) AS n WHERE a.id = ?")) {
          transactions.setObject(1, today);
          transactions.setObject(2, accountId);
          assertThat(transactions.executeUpdate()).isEqualTo(TRANSACTIONS_PER_ACCOUNT);
        }
      }
      connection.commit();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement plan =
            connection.prepareStatement(
                "EXPLAIN (ANALYZE, BUFFERS) SELECT account_id, booking_date, SUM(amount)"
                    + " FROM transaction WHERE booking_date BETWEEN ? AND ?"
                    + " GROUP BY account_id, booking_date")) {
      plan.setObject(1, today.minusYears(1));
      plan.setObject(2, today);
      try (ResultSet rows = plan.executeQuery()) {
        while (rows.next()) {
          System.out.println("HISTORY_PERF SQL_PLAN " + rows.getString(1));
        }
      }
    }
  }
}
