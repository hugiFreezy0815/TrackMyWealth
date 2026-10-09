package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-02-05/FR-TEN-007: administration rights never confer financial-data access. The administrator
 * is one of the attack personas (FR-TEN-010): without a workspace link it reaches no financial data
 * at all, and as an administrator of one workspace it is denied another workspace's data exactly as
 * a standard user is - the same status and body, so the denial says nothing more.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdministratorFinancialAccessTest {

  private static final String PASSWORD = "correct-horse-battery-staple";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM transaction_categorization_log",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM authorization_denial_log",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  @Test
  void anAdministratorWithoutAWorkspaceLinkReachesNoFinancialData() throws Exception {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    execute("UPDATE app_user SET workspace_member_id = NULL WHERE email = ?", "admin@example.com");

    for (String path : financialReadPaths(account.id())) {
      client(adminToken).get().uri(path).exchange().expectStatus().isNotFound();
    }
    client(adminToken)
        .post()
        .uri("/api/v1/accounts/" + account.id() + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            "{\"transactionType\":\"EXPENSE\",\"bookingDate\":\"2026-09-01\","
                + "\"amount\":-10,\"currency\":\"CHF\"}")
        .exchange()
        .expectStatus()
        .isNotFound();
    // Administration itself still works: the two domains are separate (FR-TEN-007).
    client(adminToken).get().uri("/api/v1/admin/reference-data").exchange().expectStatus().isOk();
  }

  @Test
  void anAdministratorIsDeniedAnotherWorkspacesDataExactlyLikeAStandardUser() throws Exception {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    UUID foreignAccount = accountInAnotherWorkspace();

    for (String path : financialReadPaths(foreignAccount)) {
      String asAdmin = denial(adminToken, path);
      String asMember = denial(memberToken, path);
      assertThat(asAdmin).as(path).isEqualTo(asMember);
    }
  }

  private static List<String> financialReadPaths(UUID accountId) {
    String account = "/api/v1/accounts/" + accountId;
    return List.of(
        account,
        account + "/transactions",
        account + "/balance",
        account + "/snapshots",
        account + "/transactions/deleted");
  }

  // Status and body of a denied read, without what differs per request by design: the timestamp
  // and the correlation id. The path stays in: both callers ask for the same one, so it is
  // identical in both bodies.
  private String denial(String token, String path) {
    return client(token)
            .get()
            .uri(path)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody()
            .replaceAll("\"timestamp\":\"[^\"]*\",?", "")
            .replaceAll("\"correlationId\":\"[^\"]*\",?", "")
        + " (404)";
  }

  private AccountSummaryResponse createAccount(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account("Checking", "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // Workspace B, set up directly: this instance's API only ever bootstraps one workspace.
  private UUID accountInAnotherWorkspace() throws Exception {
    UUID workspace = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", workspace);
    UUID account = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO account (id, workspace_id, financial_institution_id, account_type,"
                    + " name, native_currency) SELECT ?, ?, id, 'CASH', 'B checking', 'CHF'"
                    + " FROM financial_institution WHERE workspace_id = ?")) {
      statement.setObject(1, account);
      statement.setObject(2, workspace);
      statement.setObject(3, workspace);
      statement.executeUpdate();
    }
    return account;
  }

  private void execute(String sql, Object parameter) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      statement.executeUpdate();
    }
  }

  private UUID createStandardUser(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
    return (UUID) query("SELECT workspace_member_id FROM app_user WHERE email = ?", email);
  }

  private Object query(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getObject(1) : null;
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String login(String email) {
    return anonymousClient()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens()
        .accessToken();
  }

  private String bootstrapAdministrator() {
    return anonymousClient()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Workspace A", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
