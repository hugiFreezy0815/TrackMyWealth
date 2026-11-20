package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.IdempotencyService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * US-29-17 (#629) against a real PostgreSQL: the {@code Idempotency-Key} (ADR 0006) on every
 * creating JSON POST that takes it. For each operation a retry with the same key and body answers
 * the first 201 again and creates nothing, and the same key with another body is a 422. For one
 * operation each: a 409 while the first request still runs, a failed create that keeps no key, and
 * a create without the header that behaves as before. Every fixture is synthetic.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotentCreateIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String CREATE_UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";

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
  @Autowired IdempotencyService idempotencyService;
  @Autowired ObjectMapper objectMapper;

  private String token;
  private UUID cash;
  private UUID mortgage;
  private UUID asset;
  private UUID category;

  /** One supported operation: where it posts, two different bodies and what it creates. */
  private record Create(
      String operation, String path, Object body, Object otherBody, String countSql) {}

  @BeforeEach
  void cleanDatabaseAndSignIn() {
    for (String sql :
        List.of(
            "DELETE FROM idempotency_key",
            LedgerCleanup.withGuardsOff("DELETE FROM loan_payment"),
            "DELETE FROM amortisation_schedule_entry",
            "DELETE FROM custom_asset_valuation",
            "DELETE FROM reconciliation_result",
            "DELETE FROM settlement_match",
            "DELETE FROM transfer_detection_fx_pending",
            "DELETE FROM transaction_categorization_log",
            "DELETE FROM categorization_rule",
            LedgerCleanup.DELETE_ALL_TRANSACTIONS,
            "DELETE FROM workspace_category_override",
            "DELETE FROM category WHERE workspace_id IS NOT NULL AND parent_category_id IN"
                + " (SELECT id FROM category WHERE workspace_id IS NOT NULL)",
            "DELETE FROM category WHERE workspace_id IS NOT NULL",
            "DELETE FROM import_batch",
            "DELETE FROM import_template",
            "DELETE FROM snapshot_holding",
            "DELETE FROM account_snapshot",
            "DELETE FROM account_credit_card",
            "DELETE FROM account_mortgage",
            "DELETE FROM account_loan",
            "DELETE FROM account_custom_asset",
            "DELETE FROM account",
            "DELETE FROM admin_audit_log",
            "DELETE FROM user_session",
            "DELETE FROM refresh_token",
            "DELETE FROM authorization_denial_log",
            "DELETE FROM app_user",
            "DELETE FROM workspace_member",
            "DELETE FROM portfolio",
            "DELETE FROM workspace",
            LedgerCleanup.SETTLE_LEDGER_STATISTICS)) {
      update(sql);
    }
    token = bootstrapAdministrator();
    cash = createAccount(AccountRequests.account("Privatkonto", "CASH", "CHF").build());
    mortgage =
        createAccount(
            AccountRequests.account("Home Mortgage", "MORTGAGE", "CHF")
                .originalPrincipal(new BigDecimal("500000.00"))
                .interestRatePercent(new BigDecimal("2.0"))
                .build());
    asset =
        createAccount(
            AccountRequests.account("Vintage Car", "CUSTOM_ASSET", "CHF")
                .customAssetType("VEHICLE")
                .build());
    category =
        UUID.fromString(
            post("/api/v1/categories", null, Map.of("nameEn", "Coffee", "nameDe", "Kaffee"))
                .expectStatus()
                .isCreated()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody()
                .get("id")
                .toString());
  }

  private static final String[] OPERATIONS = {
    "createAccount",
    "createAmortisationEntry",
    "recordOpeningBalance",
    "recordLoanRepayment",
    "recordAccountSnapshot",
    "recordTransaction",
    "recordAccountValuation",
    "createCategory",
    "createCategorizationRule",
    "createImportTemplate",
    "createPortfolio"
  };

  static Stream<String> operations() {
    return Stream.of(OPERATIONS);
  }

  private Create create(String operation) {
    return creates()
        .filter(create -> create.operation().equals(operation))
        .findFirst()
        .orElseThrow();
  }

  private Stream<Create> creates() {
    String today = LocalDate.now(ZoneId.of("Europe/Zurich")).toString();
    String accounts = "/api/v1/accounts/";
    return Stream.of(
        new Create(
            "createAccount",
            "/api/v1/accounts",
            Map.of("name", "Savings", "accountType", "CASH", "nativeCurrency", "CHF"),
            Map.of("name", "Holiday", "accountType", "CASH", "nativeCurrency", "CHF"),
            "SELECT count(*) FROM account"),
        new Create(
            "createAmortisationEntry",
            accounts + mortgage + "/amortisation-schedule",
            amortisation("2027-01-31"),
            amortisation("2027-02-28"),
            "SELECT count(*) FROM amortisation_schedule_entry"),
        new Create(
            "recordOpeningBalance",
            accounts + cash + "/opening-balance",
            Map.of("date", "2026-01-01", "balance", "1000.00", "currency", "CHF"),
            Map.of("date", "2026-01-01", "balance", "2000.00", "currency", "CHF"),
            "SELECT count(*) FROM account_snapshot WHERE is_opening_balance"),
        new Create(
            "recordLoanRepayment",
            accounts + mortgage + "/repayments",
            repayment(today, "1500.00"),
            repayment(today, "1600.00"),
            "SELECT count(*) FROM loan_payment"),
        new Create(
            "recordAccountSnapshot",
            accounts + cash + "/snapshots",
            Map.of("snapshotDate", "2026-01-31", "balance", "1234.00"),
            Map.of("snapshotDate", "2026-02-28", "balance", "1234.00"),
            "SELECT count(*) FROM account_snapshot"),
        new Create(
            "recordTransaction",
            accounts + cash + "/transactions",
            transaction(today, "-12.50"),
            transaction(today, "-80.00"),
            "SELECT count(*) FROM transaction"),
        new Create(
            "recordAccountValuation",
            accounts + asset + "/valuations",
            Map.of("valuationDate", "2026-01-31", "value", "25000.00"),
            Map.of("valuationDate", "2026-01-31", "value", "26000.00"),
            "SELECT count(*) FROM custom_asset_valuation"),
        new Create(
            "createCategory",
            "/api/v1/categories",
            Map.of("nameEn", "Hobbies", "nameDe", "Hobbys"),
            Map.of("nameEn", "Garden", "nameDe", "Garten"),
            "SELECT count(*) FROM category WHERE workspace_id IS NOT NULL"),
        new Create(
            "createCategorizationRule",
            "/api/v1/categorization-rules",
            Map.of("matchType", "MERCHANT", "matchValue", "coffee shop", "categoryId", category),
            Map.of("matchType", "MERCHANT", "matchValue", "bakery", "categoryId", category),
            "SELECT count(*) FROM categorization_rule"),
        new Create(
            "createImportTemplate",
            "/api/v1/import-templates",
            template("Simple"),
            template("Other"),
            "SELECT count(*) FROM import_template"),
        new Create(
            "createPortfolio",
            "/api/v1/portfolios",
            Map.of("name", "Manual bank", "portfolioCurrency", "CHF"),
            Map.of("name", "Other bank", "portfolioCurrency", "CHF"),
            "SELECT count(*) FROM portfolio"));
  }

  /** AC 1: the retry answers the first 201 (body and ETag) and creates nothing. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("operations")
  void aRetryOfAFinishedCreateAnswersTheFirstResponseAndCreatesNothing(String operation) {
    Create create = create(operation);
    long before = count(create.countSql());

    EntityExchangeResult<byte[]> first = created(post(create.path(), CREATE_UUID, create.body()));
    long afterFirst = count(create.countSql());
    EntityExchangeResult<byte[]> retry =
        created(post(create.path(), CREATE_UUID.toUpperCase(Locale.ROOT), create.body()));

    assertThat(afterFirst).isEqualTo(before + 1);
    assertThat(count(create.countSql())).isEqualTo(afterFirst);
    assertThat(json(retry)).isEqualTo(json(first));
    assertThat(retry.getResponseHeaders().getETag())
        .isEqualTo(first.getResponseHeaders().getETag());
  }

  /** AC 2: the key reused for another body is a 422, and the second body creates nothing. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("operations")
  void theSameKeyForADifferentRequestIsUnprocessable(String operation) {
    Create create = create(operation);
    created(post(create.path(), CREATE_UUID, create.body()));
    long afterFirst = count(create.countSql());

    post(create.path(), CREATE_UUID, create.otherBody())
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IDEMPOTENCY_KEY_MISMATCH");

    assertThat(count(create.countSql())).isEqualTo(afterFirst);
  }

  /**
   * The request is compared as the server reads it: another key order and a field sent as null
   * instead of left out are the same request; another decimal scale is not.
   */
  @Test
  void theBodyIsComparedAsTheServerReadsIt() {
    String path = "/api/v1/accounts/" + asset + "/valuations";
    created(post(path, CREATE_UUID, "{\"valuationDate\":\"2026-01-31\",\"value\":\"25000.00\"}"));

    created(
        post(path, CREATE_UUID, "{ \"value\": \"25000.00\", \"valuationDate\": \"2026-01-31\" }"));
    post(path, CREATE_UUID, "{\"valuationDate\":\"2026-01-31\",\"value\":\"25000.0\"}")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    String accounts = "/api/v1/accounts";
    String other = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";
    created(
        post(
            accounts,
            other,
            "{\"name\":\"Savings\",\"accountType\":\"CASH\",\"nativeCurrency\":\"CHF\"}"));
    created(
        post(
            accounts,
            other,
            "{\"name\":\"Savings\",\"accountType\":\"CASH\",\"nativeCurrency\":\"CHF\","
                + "\"openedAt\":null}"));

    assertThat(count("SELECT count(*) FROM custom_asset_valuation")).isEqualTo(1L);
    assertThat(count("SELECT count(*) FROM account WHERE name = 'Savings'")).isEqualTo(1L);
  }

  /** AC 3: while the first request holds the key, a retry is a 409 with Retry-After. */
  @Test
  void theSameKeyWhileTheFirstRequestStillRunsIsAConflict() {
    CreateAccountRequest request = AccountRequests.account("Savings", "CASH", "CHF").build();
    AuthenticatedUserPrincipal member = member();
    as(
        member,
        () ->
            idempotencyService.claim(
                "createAccount",
                CREATE_UUID,
                IdempotencyService.fingerprint(
                    objectMapper.writeValueAsBytes(request), "createAccount"),
                member));
    long before = count("SELECT count(*) FROM account");

    post("/api/v1/accounts", CREATE_UUID, request)
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectHeader()
        .valueEquals(HttpHeaders.RETRY_AFTER, "2")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IDEMPOTENCY_KEY_IN_USE");

    assertThat(count("SELECT count(*) FROM account")).isEqualTo(before);
  }

  /** A failed create created nothing: it keeps no key, and its retry runs again. */
  @Test
  void aFailedCreateKeepsNoKeyAndItsRetryRunsAgain() {
    Map<String, Object> request = transaction("2026-01-15", "-12.50");
    String path = "/api/v1/accounts/" + cash + "/transactions";
    String etag = archive(cash);

    post(path, CREATE_UUID, request)
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("ACCOUNT_ARCHIVED");
    assertThat(count("SELECT count(*) FROM idempotency_key")).isZero();

    restore(cash, etag);
    created(post(path, CREATE_UUID, request));
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(1L);
    assertThat(count("SELECT count(*) FROM idempotency_key WHERE status = 'COMPLETED'"))
        .isEqualTo(1L);
  }

  /** AC 4: without the header, every request creates, as before. */
  @Test
  void aCreateWithoutTheHeaderBehavesAsBefore() {
    long before = count("SELECT count(*) FROM account");
    Map<String, String> request =
        Map.of("name", "Savings", "accountType", "CASH", "nativeCurrency", "CHF");

    created(post("/api/v1/accounts", null, request));
    created(post("/api/v1/accounts", null, request));

    assertThat(count("SELECT count(*) FROM account")).isEqualTo(before + 2);
    assertThat(count("SELECT count(*) FROM idempotency_key")).isZero();
  }

  /** The same key for another operation is another key: the scope includes the operation. */
  @Test
  void theSameKeyOnAnotherOperationIsANewRequest() {
    created(
        post(
            "/api/v1/accounts",
            CREATE_UUID,
            Map.of("name", "Savings", "accountType", "CASH", "nativeCurrency", "CHF")));
    created(
        post(
            "/api/v1/portfolios",
            CREATE_UUID,
            Map.of("name", "Manual bank", "portfolioCurrency", "CHF")));

    assertThat(count("SELECT count(*) FROM idempotency_key")).isEqualTo(2L);
  }

  // --- requests --------------------------------------------------------------------------------

  private static Map<String, Object> amortisation(String dueDate) {
    return Map.of(
        "dueDate",
        dueDate,
        "interestComponent",
        "800.00",
        "principalComponent",
        "2000.00",
        "remainingBalance",
        "498000.00");
  }

  private Map<String, Object> repayment(String bookingDate, String amount) {
    return Map.of(
        "payingAccountId",
        cash,
        "bookingDate",
        bookingDate,
        "amount",
        amount,
        "currency",
        "CHF",
        "interestAmount",
        "300.00");
  }

  private static Map<String, Object> transaction(String bookingDate, String amount) {
    return Map.of(
        "transactionType",
        "EXPENSE",
        "bookingDate",
        bookingDate,
        "amount",
        amount,
        "currency",
        "CHF",
        "merchantDescription",
        "Coffee shop");
  }

  private static Map<String, Object> template(String name) {
    return Map.of(
        "name",
        name,
        "delimiter",
        ",",
        "fixedCurrency",
        "EUR",
        "columnMapping",
        Map.of("bookingDate", "Datum", "amount", "Betrag", "description", "Text"),
        "headerColumns",
        List.of("Datum", "Betrag", "Text"));
  }

  private RestTestClient.ResponseSpec post(String path, String key, Object body) {
    RestTestClient.RequestBodySpec request =
        client().post().uri(path).contentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      request = request.header(IdempotencyService.HEADER, key);
    }
    return request.body(body).exchange();
  }

  private static EntityExchangeResult<byte[]> created(RestTestClient.ResponseSpec response) {
    return response.expectStatus().isCreated().expectBody().returnResult();
  }

  private Object json(EntityExchangeResult<byte[]> result) {
    return objectMapper.readTree(new String(result.getResponseBody(), StandardCharsets.UTF_8));
  }

  private UUID createAccount(CreateAccountRequest request) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  /** Archives the account; returns the restored account's If-Match. */
  private String archive(UUID accountId) {
    String etag =
        client()
            .get()
            .uri("/api/v1/accounts/{id}", accountId)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(byte[].class)
            .getResponseHeaders()
            .getETag();
    return client()
        .post()
        .uri("/api/v1/accounts/{id}/archive", accountId)
        .header(HttpHeaders.IF_MATCH, etag)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(byte[].class)
        .getResponseHeaders()
        .getETag();
  }

  private void restore(UUID accountId, String etag) {
    client()
        .post()
        .uri("/api/v1/accounts/{id}/restore", accountId)
        .header(HttpHeaders.IF_MATCH, etag)
        .exchange()
        .expectStatus()
        .isOk();
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + token)
        .build();
  }

  private AuthenticatedUserPrincipal member() {
    UUID userId = (UUID) single("SELECT id FROM app_user WHERE email = 'admin@example.com'");
    UUID workspaceId = (UUID) single("SELECT workspace_id FROM account WHERE id = '" + cash + "'");
    return new AuthenticatedUserPrincipal(userId, "SYSTEM_ADMINISTRATOR", workspaceId, null, "EN");
  }

  private static <T> T as(AuthenticatedUserPrincipal principal, Supplier<T> call) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    try {
      return call.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  // --- database ----------------------------------------------------------------------------------

  private long count(String sql) {
    return (Long) single(sql);
  }

  private Object single(String sql) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getObject(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void update(String sql) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
