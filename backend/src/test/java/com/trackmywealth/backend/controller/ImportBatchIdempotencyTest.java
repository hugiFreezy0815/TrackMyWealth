package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.repository.IdempotencyKeyRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.IdempotencyService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.RowLevelSecurityRole;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-29-14 (#574) against a real PostgreSQL: the statement upload with an {@code Idempotency-Key}
 * (ADR 0006). Replay of a finished upload, 422 for the key on another request, 409 while the first
 * request still runs, expiry after 24 hours, a failed upload that keeps no key, the per-user scope,
 * and an upload without the header that behaves as before. Every fixture is synthetic.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportBatchIdempotencyTest {

  // Bound by RLS, unlike the application's own role in these tests (see RowLevelSecurityRole).
  private static final String RLS_ROLE = "idempotency_rls_test";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String KEY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  private static final byte[] STATEMENT = csv("2026-01-05;-12.50;CHF;Coffee shop;R-1;5814");
  private static final byte[] OTHER_STATEMENT = csv("2026-01-06;-80.00;CHF;Groceries;R-2;5411");

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
  @Autowired IdempotencyKeyRepository keys;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;

  private String token;
  private UUID account;

  @BeforeEach
  void cleanDatabaseAndSignIn() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM idempotency_key",
              "DELETE FROM reconciliation_result",
              "DELETE FROM settlement_match",
              "DELETE FROM transfer_detection_fx_pending",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM import_row_raw",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM import_file",
              "DELETE FROM import_batch",
              "DELETE FROM import_template",
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM account_credit_card",
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
        statement.execute(sql);
      }
    }
    token = bootstrapAdministrator();
    account = createAccount(token, "Privatkonto");
  }

  /** AC 1: the second upload answers the first one's 201 and creates no second batch. */
  @Test
  void aRetryOfAFinishedUploadAnswersTheFirstResponseAndCreatesNothing() {
    EntityExchangeResult<ImportBatchResponse> first = created(upload(token, KEY, STATEMENT));
    EntityExchangeResult<ImportBatchResponse> retry = created(upload(token, KEY, STATEMENT));

    assertThat(retry.getResponseBody()).isEqualTo(first.getResponseBody());
    assertThat(retry.getResponseHeaders().getLocation())
        .isEqualTo(first.getResponseHeaders().getLocation());
    assertThat(retry.getResponseHeaders().getETag())
        .isEqualTo(first.getResponseHeaders().getETag());
    assertThat(batchCount()).isEqualTo(1L);
    assertThat(single("SELECT status FROM idempotency_key")).isEqualTo("COMPLETED");
  }

  /** A key differing only in letter case is the same key. */
  @Test
  void theKeyIsComparedIgnoringLetterCase() {
    ImportBatchResponse first = created(upload(token, KEY, STATEMENT)).getResponseBody();

    ImportBatchResponse retry =
        created(upload(token, KEY.toUpperCase(Locale.ROOT), STATEMENT)).getResponseBody();

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(batchCount()).isEqualTo(1L);
  }

  /** AC 2: the same key with another file, or for another account, is a 422 with a stable code. */
  @Test
  void theSameKeyForADifferentRequestIsUnprocessable() {
    created(upload(token, KEY, STATEMENT));
    UUID savings = createAccount(token, "Sparkonto");

    upload(token, account, KEY, OTHER_STATEMENT)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
    upload(token, savings, KEY, STATEMENT)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
    assertThat(batchCount()).isEqualTo(1L);
  }

  /**
   * AC 3: while the first upload waits for the account's upload lock (held here), a retry with the
   * key is a 409 with Retry-After; once the first finishes, the retry gets its 201.
   */
  @Test
  void theSameKeyWhileTheFirstRequestStillRunsIsAConflict() throws Exception {
    CompletableFuture<ImportBatchResponse> first;
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement statement =
          lock.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        statement.setString(1, "import-upload:" + account);
        statement.execute();
      }
      first =
          CompletableFuture.supplyAsync(
              () -> created(upload(token, KEY, STATEMENT)).getResponseBody());
      awaitInProgressClaim();

      upload(token, KEY, STATEMENT)
          .expectStatus()
          .isEqualTo(HttpStatus.CONFLICT)
          .expectHeader()
          .valueEquals(HttpHeaders.RETRY_AFTER, "2")
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("IDEMPOTENCY_KEY_IN_USE");
      lock.rollback();
    }

    ImportBatchResponse finished = first.get(30, TimeUnit.SECONDS);
    assertThat(created(upload(token, KEY, STATEMENT)).getResponseBody().id())
        .isEqualTo(finished.id());
    assertThat(batchCount()).isEqualTo(1L);
  }

  /** AC 4: after 24 hours the key is a new request; the purge removes only expired keys. */
  @Test
  void aKeyOlderThan24HoursIsANewRequestAndIsPurged() {
    ImportBatchResponse first = created(upload(token, KEY, STATEMENT)).getResponseBody();
    update("UPDATE idempotency_key SET expires_at = now() - interval '1 second'");

    ImportBatchResponse second = created(upload(token, KEY, OTHER_STATEMENT)).getResponseBody();

    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(batchCount()).isEqualTo(2L);
    String other = "0f8fad5b-d9cb-469f-a165-70867728950e";
    created(upload(token, other, STATEMENT));
    update(
        "UPDATE idempotency_key SET expires_at = now() - interval '1 second' WHERE"
            + " idempotency_key = '"
            + KEY
            + "'");

    idempotencyService.deleteExpiredKeys();

    assertThat(rowsOf("SELECT idempotency_key FROM idempotency_key"))
        .extracting(row -> row.get("idempotency_key"))
        .containsExactly(other);
  }

  /** A server that stopped mid-request leaves a claim that the next request takes over. */
  @Test
  void anAbandonedClaimIsTakenOver() {
    created(upload(token, KEY, STATEMENT));
    update("DELETE FROM import_file");
    update("DELETE FROM import_batch");
    update(
        "UPDATE idempotency_key SET status = 'IN_PROGRESS', response_status = NULL,"
            + " response_body = NULL, created_at = now() - interval '16 minutes'");

    created(upload(token, KEY, STATEMENT));

    assertThat(batchCount()).isEqualTo(1L);
    assertThat(single("SELECT status FROM idempotency_key")).isEqualTo("COMPLETED");
  }

  /** A failed upload created nothing: it keeps no key, and its retry runs again. */
  @Test
  void aFailedUploadKeepsNoKey() {
    for (int attempt = 0; attempt < 2; attempt++) {
      upload(token, KEY, new byte[0])
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("IMPORT_FILE_EMPTY");
    }
    assertThat(single("SELECT count(*) FROM idempotency_key")).isEqualTo(0L);
    assertThat(batchCount()).isZero();
  }

  /** AC 5: without the header, every upload is a new batch, as before. */
  @Test
  void anUploadWithoutTheHeaderBehavesAsBefore() {
    created(upload(token, null, STATEMENT));
    created(upload(token, null, STATEMENT));

    assertThat(batchCount()).isEqualTo(2L);
    assertThat(single("SELECT count(*) FROM idempotency_key")).isEqualTo(0L);
  }

  @Test
  void aKeyThatIsNoUuidIsRejected() {
    upload(token, "retry-1", STATEMENT)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_FAILED");
    assertThat(batchCount()).isZero();
  }

  /** Business rule: a key of another user never replays a response. */
  @Test
  void anotherUsersKeyNeverReplays() {
    ImportBatchResponse mine = created(upload(token, KEY, STATEMENT)).getResponseBody();
    String otherUser = createUserAndLogin("second@example.com");
    UUID theirAccount = createAccount(otherUser, "Giro");

    ImportBatchResponse theirs =
        created(upload(otherUser, theirAccount, KEY, STATEMENT)).getResponseBody();

    assertThat(theirs.id()).isNotEqualTo(mine.id());
    assertThat(theirs.accountId()).isEqualTo(theirAccount);
    assertThat(single("SELECT count(*) FROM idempotency_key")).isEqualTo(2L);
  }

  /**
   * The tests above connect as a superuser, which RLS does not bind. Here the key statements run as
   * an RLS-bound role: they work in the member's workspace, another workspace sees nothing, and the
   * purge (V124's SECURITY DEFINER function) works without any workspace context, as the scheduler
   * runs it.
   */
  @Test
  void theKeyStatementsHoldUnderRowLevelSecurity() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      RowLevelSecurityRole.create(connection, RLS_ROLE);
      statement.execute(
          "GRANT EXECUTE ON FUNCTION purge_expired_idempotency_keys(TIMESTAMPTZ, INTEGER) TO "
              + RLS_ROLE);
    }
    UUID userId = (UUID) single("SELECT id FROM app_user WHERE email = 'admin@example.com'");
    UUID workspaceId = (UUID) single("SELECT workspace_id FROM account");
    AuthenticatedUserPrincipal member =
        new AuthenticatedUserPrincipal(userId, "SYSTEM_ADMINISTRATOR", workspaceId, null, "EN");
    OffsetDateTime now = OffsetDateTime.now();
    String fingerprint = "0".repeat(64);

    boolean storedUnderRls =
        as(
            member,
            () ->
                RowLevelSecurityRole.call(
                    transactionManager,
                    entityManager,
                    RLS_ROLE,
                    () -> {
                      int inserted =
                          keys.insertIfAbsent(
                              workspaceId, userId, "op", KEY, fingerprint, now, now.plusDays(1));
                      UUID id =
                          keys.lockByScope(workspaceId, userId, "op", KEY).orElseThrow().getId();
                      return inserted == 1 && keys.complete(id, (short) 201, "{}") == 1;
                    }));
    assertThat(storedUnderRls).isTrue();

    AuthenticatedUserPrincipal stranger =
        new AuthenticatedUserPrincipal(userId, "STANDARD_USER", UUID.randomUUID(), null, "EN");
    boolean visibleElsewhere =
        as(
            stranger,
            () ->
                RowLevelSecurityRole.call(
                    transactionManager,
                    entityManager,
                    RLS_ROLE,
                    () -> keys.lockByScope(workspaceId, userId, "op", KEY).isPresent()));
    assertThat(visibleElsewhere).isFalse();

    update("UPDATE idempotency_key SET expires_at = now() - interval '1 second'");
    Integer purged =
        RowLevelSecurityRole.call(
            transactionManager,
            entityManager,
            RLS_ROLE,
            () -> keys.purgeExpired(OffsetDateTime.now(), 10));
    assertThat(purged).isEqualTo(1);
    assertThat(single("SELECT count(*) FROM idempotency_key")).isEqualTo(0L);
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

  // --- requests --------------------------------------------------------------------------------

  private RestTestClient.ResponseSpec upload(String bearer, String key, byte[] content) {
    return upload(bearer, account, key, content);
  }

  private RestTestClient.ResponseSpec upload(
      String bearer, UUID accountId, String key, byte[] content) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    RestTestClient.RequestBodySpec request =
        client(bearer)
            .post()
            .uri("/api/v1/accounts/" + accountId + "/imports")
            .contentType(MediaType.MULTIPART_FORM_DATA);
    if (key != null) {
      request = request.header(IdempotencyService.HEADER, key);
    }
    return request.body(body).exchange();
  }

  private static EntityExchangeResult<ImportBatchResponse> created(
      RestTestClient.ResponseSpec response) {
    return response.expectStatus().isCreated().expectBody(ImportBatchResponse.class).returnResult();
  }

  private void awaitInProgressClaim() throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (!Long.valueOf(1)
        .equals(single("SELECT count(*) FROM idempotency_key WHERE status = 'IN_PROGRESS'"))) {
      assertThat(Instant.now()).as("the first upload claimed the key").isBefore(deadline);
      Thread.sleep(50);
    }
  }

  private UUID createAccount(String bearer, String name) {
    return client(bearer)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private String createUserAndLogin(String email) {
    client(token)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(UserSummaryResponse.class);
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
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient client(String bearer) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + bearer)
        .build();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private static byte[] csv(String... rows) {
    return ("Date;Amount;Currency;Text;Reference;MCC\n" + String.join("\n", rows) + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  // --- database ----------------------------------------------------------------------------------

  private long batchCount() {
    return (Long) single("SELECT count(*) FROM import_batch");
  }

  private void update(String sql) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private Object single(String sql) {
    List<Map<String, Object>> rows = rowsOf(sql);
    assertThat(rows).hasSize(1);
    return rows.get(0).values().iterator().next();
  }

  private List<Map<String, Object>> rowsOf(String sql) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      int columns = resultSet.getMetaData().getColumnCount();
      while (resultSet.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= columns; i++) {
          row.put(resultSet.getMetaData().getColumnName(i), resultSet.getObject(i));
        }
        rows.add(row);
      }
      return rows;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
