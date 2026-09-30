package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EPIC-29 (#149, #175, #176): the wire conventions, end to end.
 *
 * <ul>
 *   <li>One error shape for every error class - {@code application/problem+json} with a {@code
 *       detail}, a stable {@code code} and the {@code correlationId} - with a 404 that says nothing
 *       about another workspace (US-28-03).
 *   <li>A correlation id on every response, taken from a well-formed {@code X-Correlation-Id} or
 *       generated.
 *   <li>Every decimal as a plain string with its full precision, in both directions.
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiConventionsIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

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

  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction",
              "DELETE FROM category WHERE workspace_id IS NOT NULL",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_securities",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  // --- the error envelope (#149, #175) -----------------------------------------------------------

  @Test
  void aValidationFailureNamesEachRejectedField() {
    String token = bootstrapAdministrator();

    JsonNode problem =
        problem(
            client(token)
                .post()
                .uri("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"nameEn\":\"\",\"nameDe\":\"Hobby\"}")
                .exchange(),
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED");

    assertThat(problem.path("errors").findValuesAsString("field")).contains("nameEn");
  }

  @Test
  void aMissingTokenIsUnauthenticatedAndAWrongRoleForbidden() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "member@example.com");

    problem(
        anonymousClient().get().uri("/api/v1/accounts").exchange(),
        HttpStatus.UNAUTHORIZED,
        "UNAUTHENTICATED");
    problem(
        client(login("member@example.com")).get().uri("/api/v1/admin/reference-data").exchange(),
        HttpStatus.FORBIDDEN,
        "FORBIDDEN");
  }

  @Test
  void aForeignResourceIsNotFoundExactlyLikeOneThatDoesNotExist() throws Exception {
    String token = bootstrapAdministrator();
    UUID foreign = accountInAnotherWorkspace();

    JsonNode missing =
        problem(
            client(token).get().uri("/api/v1/accounts/" + UUID.randomUUID()).exchange(),
            HttpStatus.NOT_FOUND,
            "NOT_FOUND");
    JsonNode hidden =
        problem(
            client(token).get().uri("/api/v1/accounts/" + foreign).exchange(),
            HttpStatus.NOT_FOUND,
            "NOT_FOUND");

    assertThat(hidden.path("detail").asString()).isEqualTo(missing.path("detail").asString());
  }

  @Test
  void aConflictAndABrokenRuleExplainThemselves() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");
    String category = "{\"nameEn\":\"Hobby\",\"nameDe\":\"Hobby\"}";
    client(token)
        .post()
        .uri("/api/v1/categories")
        .contentType(MediaType.APPLICATION_JSON)
        .body(category)
        .exchange()
        .expectStatus()
        .isCreated();

    JsonNode conflict =
        problem(
            client(token)
                .post()
                .uri("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(category)
                .exchange(),
            HttpStatus.CONFLICT,
            "CONFLICT");
    assertThat(conflict.path("detail").asString()).contains("already exists");

    JsonNode rule =
        problem(
            postTransaction(token, cash.id(), expense("45.00")),
            HttpStatus.UNPROCESSABLE_CONTENT,
            "UNPROCESSABLE");
    assertThat(rule.path("detail").asString()).contains("amount must be negative");
  }

  @Test
  void aClientCanTellAnArchivedAccountFromOtherConflicts() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");
    client(token)
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/archive")
        .exchange()
        .expectStatus()
        .isOk();

    problem(
        postTransaction(token, cash.id(), expense("-45.00")),
        HttpStatus.CONFLICT,
        "ACCOUNT_ARCHIVED");
  }

  @Test
  void aRequestTheSecurityFirewallRejectsGetsTheSameShape() {
    String token = bootstrapAdministrator();
    // Sent as given - a client would normalise these paths - so they reach the firewall as is.
    for (String path : List.of("/api/v1/accounts;x=1", "/api/v1/accounts/%2e%2e/x")) {
      URI uri = URI.create("http://localhost:" + port + path);
      problem(client(token).get().uri(uri).exchange(), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
      problem(
          anonymousClient().get().uri(uri).exchange(), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }
  }

  // --- correlation ids
  // ----------------------------------------------------------------------------

  @Test
  void aWellFormedCorrelationIdIsEchoedAndAnythingElseReplaced() {
    String token = bootstrapAdministrator();

    String echoed =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + UUID.randomUUID())
            .header("X-Correlation-Id", "mobile-7f3a9c21")
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectHeader()
            .valueEquals("X-Correlation-Id", "mobile-7f3a9c21")
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(objectMapper.readTree(echoed).path("correlationId").asString())
        .isEqualTo("mobile-7f3a9c21");

    String replaced =
        client(token)
            .get()
            .uri("/api/v1/categories")
            .header("X-Correlation-Id", "x")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst("X-Correlation-Id");
    assertThat(replaced).isNotEqualTo("x").hasSize(36); // a generated UUID
  }

  // --- decimals as strings (#149, #176)
  // ------------------------------------------------------------

  @Test
  void everyDecimalTravelsAsAStringWithItsFullPrecision() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "SECURITIES");
    UUID security = createSecurity(token);
    // 12345678.1234567891 x 1.0000000001 = 12345678.12469135691..., statement-rounded to 4 places.
    String buy =
        """
        {"transactionType":"BUY","bookingDate":"%s","amount":"-12345678.1247","currency":"CHF",
         "securityId":"%s","quantity":"12345678.1234567891","unitPrice":"1.0000000001",
         "externalId":"exact-buy"}
        """
            .formatted(today(), security);

    String created =
        postTransaction(token, depot.id(), buy)
            .expectStatus()
            .isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    JsonNode row = objectMapper.readTree(created);
    assertThat(row.path("quantity").isString()).isTrue();
    assertThat(row.path("quantity").asString()).isEqualTo("12345678.1234567891");
    assertThat(row.path("unitPrice").asString()).isEqualTo("1.0000000001");
    assertThat(row.path("amount").asString()).isEqualTo("-12345678.1247");
    // A replay sending back exactly what it received is the same transaction, not a conflict.
    postTransaction(token, depot.id(), buy).expectStatus().isCreated();
  }

  @Test
  void numbersAreStillAcceptedOnRequestsAndReadBackAsStrings() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");

    String created =
        postTransaction(
                token,
                cash.id(),
                """
                {"transactionType":"EXPENSE","bookingDate":"%s","amount":-10.5,"currency":"EUR",
                 "fxRateToAccountCurrency":0.9412345678}
                """
                    .formatted(today()))
            .expectStatus()
            .isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    JsonNode row = objectMapper.readTree(created);
    assertThat(row.path("amount").asString()).isEqualTo("-10.5");
    assertThat(row.path("fxRateToAccountCurrency").asString()).isEqualTo("0.9412345678");
  }

  // --- helpers ---------------------------------------------------------------------------------

  // Asserts the one error shape and returns the body for the caller's own checks.
  private JsonNode problem(RestTestClient.ResponseSpec response, HttpStatus status, String code) {
    EntityExchangeResult<String> result =
        response
            .expectStatus()
            .isEqualTo(status)
            .expectHeader()
            .contentType(PROBLEM)
            .expectBody(String.class)
            .returnResult();
    String correlationHeader = result.getResponseHeaders().getFirst("X-Correlation-Id");
    JsonNode problem = objectMapper.readTree(result.getResponseBody());
    assertThat(problem.path("status").asInt()).isEqualTo(status.value());
    assertThat(problem.path("code").asString()).isEqualTo(code);
    assertThat(problem.path("detail").asString()).isNotBlank();
    assertThat(problem.path("correlationId").asString()).isEqualTo(correlationHeader);
    return problem;
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private static String expense(String amount) {
    return """
        {"transactionType":"EXPENSE","bookingDate":"%s","amount":"%s","currency":"CHF"}
        """
        .formatted(today(), amount);
  }

  private RestTestClient.ResponseSpec postTransaction(String token, UUID accountId, String json) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
        .exchange();
  }

  private AccountSummaryResponse createAccount(String token, String type) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, type, type, "CHF", null, null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createSecurity(String token) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                "IE00B4L5Y983",
                "iShares Core MSCI World",
                "USD",
                "ETF",
                "EQUITY",
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private UUID accountInAnotherWorkspace() throws Exception {
    UUID workspace = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'B')")) {
        statement.setObject(1, workspace);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO account (id, workspace_id, financial_institution_id, account_type,"
                  + " name, native_currency) SELECT ?, ?, id, 'CASH', 'B', 'CHF'"
                  + " FROM financial_institution WHERE workspace_id = ?")) {
        statement.setObject(1, account);
        statement.setObject(2, workspace);
        statement.setObject(3, workspace);
        statement.executeUpdate();
      }
    }
    return account;
  }

  private void createStandardUser(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
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
