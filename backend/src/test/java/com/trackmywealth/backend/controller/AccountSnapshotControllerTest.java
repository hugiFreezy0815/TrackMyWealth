package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.SnapshotHoldingRequest;
import com.trackmywealth.backend.dto.SnapshotHoldingResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-25-01: manual snapshot entry (FR-REC-006). The DoD's test is {@link
 * #aDepotSnapshotWithHoldingsCreatesHoldingRowsAlongsideTheBalance}; AC #1 is {@link
 * #typingTodaysBalanceCreatesAManualSnapshot}. The rest pin the duplicate/"update today's snapshot"
 * edge case and the validation rules documented on {@code AccountSnapshotService}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountSnapshotControllerTest {

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
      for (String table :
          List.of(
              "snapshot_holding",
              "account_snapshot",
              "security_field_provenance",
              "security_asset_class_weight",
              "security_identifier",
              "security",
              "sharing_grant",
              "account_ownership",
              "account_securities",
              "account",
              "admin_audit_log",
              "user_session",
              "refresh_token",
              "authorization_denial_log",
              "app_user",
              "workspace_member",
              "financial_institution",
              "workspace")) {
        statement.execute("DELETE FROM " + table);
      }
    }
  }

  // --- AC #1 --------------------------------------------------------------------------------

  @Test
  void typingTodaysBalanceCreatesAManualSnapshot() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse postFinance = createAccount(token, "PostFinance", "CASH");

    AccountSnapshotResponse created =
        record(token, postFinance.id(), balanceOnly(today(), "12345.67"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AccountSnapshotResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.accountId()).isEqualTo(postFinance.id());
    assertThat(created.snapshotDate()).isEqualTo(today());
    assertThat(created.balance()).isEqualByComparingTo("12345.67");
    assertThat(created.currency()).isEqualTo("CHF"); // derived from the account, not sent
    assertThat(created.source()).isEqualTo("MANUAL");
    assertThat(created.openingBalance()).isFalse();
    assertThat(created.updatedAt()).isNull();
    assertThat(created.holdings()).isEmpty();
    assertThat(
            count(
                "SELECT count(*) FROM account_snapshot WHERE account_id = ? AND source = 'MANUAL'"
                    + " AND balance = 12345.67",
                postFinance.id()))
        .isEqualTo(1);
  }

  // --- AC #2 / DoD --------------------------------------------------------------------------

  @Test
  void aDepotSnapshotWithHoldingsCreatesHoldingRowsAlongsideTheBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Swissquote Depot", "SECURITIES");
    SecurityResponse vt = createSecurity(token, "US9220427424", "Vanguard Total World");
    SecurityResponse nesn = createSecurity(token, "CH0038863350", "Nestle");

    AccountSnapshotResponse created =
        record(
                token,
                depot.id(),
                new RecordAccountSnapshotRequest(
                    today(),
                    new BigDecimal("1520.40"),
                    List.of(
                        new SnapshotHoldingRequest(
                            vt.id(), new BigDecimal("42.5"), new BigDecimal("4210.00"), null),
                        new SnapshotHoldingRequest(nesn.id(), new BigDecimal("10"), null, null))))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AccountSnapshotResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.balance()).isEqualByComparingTo("1520.40");
    assertThat(created.holdings())
        .extracting(SnapshotHoldingResponse::securityDisplayName)
        .containsExactly("Nestle", "Vanguard Total World");
    SnapshotHoldingResponse vtHolding = created.holdings().get(1);
    assertThat(vtHolding.isin()).isEqualTo("US9220427424");
    assertThat(vtHolding.quantity()).isEqualByComparingTo("42.5");
    assertThat(vtHolding.reportedCostBasis()).isEqualByComparingTo("4210.00");
    assertThat(vtHolding.costBasisIsEstimated()).isFalse();
    // FR-REC-008: a position without a reported cost basis is stored without one, not as zero.
    assertThat(created.holdings().get(0).reportedCostBasis()).isNull();

    assertThat(count("SELECT count(*) FROM snapshot_holding WHERE snapshot_id = ?", created.id()))
        .isEqualTo(2);
    assertThat(get(token, depot.id(), created.id()).holdings()).isEqualTo(created.holdings());
  }

  @Test
  void aDepotStatementMayListPositionsWithoutABalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");
    SecurityResponse vt = createSecurity(token, "US9220427424", "Vanguard Total World");

    AccountSnapshotResponse created =
        record(
                token,
                depot.id(),
                new RecordAccountSnapshotRequest(today(), null, List.of(holding(vt.id(), "42.5"))))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AccountSnapshotResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.balance()).isNull();
    assertThat(created.holdings()).hasSize(1);
  }

  // --- Edge case: second snapshot for the same account/date/source --------------------------

  @Test
  void aSecondSnapshotForTheSameDateIsAConflictNamingTheExistingOne() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    UUID first = recordOk(token, cash.id(), balanceOnly(today(), "12345.67")).id();

    Map<String, Object> problem =
        record(token, cash.id(), balanceOnly(today(), "12400.00"))
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    // The client offers "update today's snapshot" with this id instead of failing silently.
    assertThat(problem).containsEntry("existingSnapshotId", first.toString());
    assertThat(count("SELECT count(*) FROM account_snapshot WHERE account_id = ?", cash.id()))
        .isEqualTo(1);
  }

  @Test
  void updatingTodaysSnapshotReplacesItsBalanceAndHoldings() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");
    SecurityResponse vt = createSecurity(token, "US9220427424", "Vanguard Total World");
    SecurityResponse nesn = createSecurity(token, "CH0038863350", "Nestle");
    AccountSnapshotResponse original =
        recordOk(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(
                today(),
                new BigDecimal("100.00"),
                List.of(holding(vt.id(), "40"), holding(nesn.id(), "10"))));

    AccountSnapshotResponse replaced =
        replace(
                token,
                depot.id(),
                original.id(),
                new ReplaceAccountSnapshotRequest(
                    new BigDecimal("150.00"), List.of(holding(vt.id(), "42.5"))))
            .expectStatus()
            .isOk()
            .expectBody(AccountSnapshotResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(replaced.id()).isEqualTo(original.id());
    assertThat(replaced.snapshotDate()).isEqualTo(today());
    assertThat(replaced.balance()).isEqualByComparingTo("150.00");
    assertThat(replaced.createdAt()).isEqualTo(original.createdAt());
    assertThat(replaced.updatedAt()).isNotNull();
    assertThat(replaced.holdings()).hasSize(1);
    assertThat(replaced.holdings().get(0).securityId()).isEqualTo(vt.id());
    assertThat(replaced.holdings().get(0).quantity()).isEqualByComparingTo("42.5");
    assertThat(get(token, depot.id(), original.id())).isEqualTo(replaced);
    assertThat(count("SELECT count(*) FROM snapshot_holding WHERE snapshot_id = ?", original.id()))
        .isEqualTo(1);
  }

  // #207 / FR-CNC-001: two clients read the same snapshot; the second, stale replacement is
  // rejected and the first one is kept. Without If-Match the replacement is not attempted at all.
  @Test
  void aStaleSnapshotReplacementIsRejectedAndTheFirstIsKept() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token, "Cash", "CASH");
    AccountSnapshotResponse readByBoth =
        recordOk(token, account.id(), balanceOnly(today(), "100.00"));

    AccountSnapshotResponse first =
        CurrentVersion.storedEtag(
            replace(
                    token,
                    account.id(),
                    readByBoth.id(),
                    balanceOnlyReplacement("150.00"),
                    ifMatch(readByBoth.version()))
                .expectStatus()
                .isOk()
                .expectBody(AccountSnapshotResponse.class)
                .returnResult(),
            dataSource,
            "account_snapshot",
            readByBoth.id());
    assertThat(first.version()).isGreaterThan(readByBoth.version());

    replace(
            token,
            account.id(),
            readByBoth.id(),
            balanceOnlyReplacement("200.00"),
            ifMatch(readByBoth.version()))
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    assertThat(get(token, account.id(), readByBoth.id()).balance()).isEqualByComparingTo("150.00");

    replace(token, account.id(), readByBoth.id(), balanceOnlyReplacement("300.00"), null)
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");
  }

  @Test
  void aProviderReportedSnapshotCannotBeReplaced() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    UUID documentSnapshot = insertSnapshot(cash.id(), "DOCUMENT", "CHF");

    replace(
            token,
            cash.id(),
            documentSnapshot,
            new ReplaceAccountSnapshotRequest(new BigDecimal("1.00"), List.of()))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // --- Reads --------------------------------------------------------------------------------

  @Test
  void listingSnapshotsReturnsThemNewestFirst() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    recordOk(token, cash.id(), balanceOnly(today().minusMonths(1), "100.00"));
    recordOk(token, cash.id(), balanceOnly(today(), "200.00"));

    List<AccountSnapshotResponse> snapshots =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + cash.id() + "/snapshots")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<AccountSnapshotResponse>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(snapshots)
        .extracting(AccountSnapshotResponse::snapshotDate)
        .containsExactly(today(), today().minusMonths(1));
  }

  @Test
  void aSnapshotIsNotFoundUnderAnotherAccount() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    AccountSummaryResponse other = createAccount(token, "UBS", "CASH");
    UUID snapshot = recordOk(token, cash.id(), balanceOnly(today(), "1.00")).id();

    client(token)
        .get()
        .uri("/api/v1/accounts/" + other.id() + "/snapshots/" + snapshot)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    replace(
            token,
            other.id(),
            snapshot,
            new ReplaceAccountSnapshotRequest(new BigDecimal("2.00"), List.of()))
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void recordingForAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();

    record(token, UUID.randomUUID(), balanceOnly(today(), "1.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  // --- Validation ---------------------------------------------------------------------------

  @Test
  void aFutureDatedSnapshotIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");

    record(token, cash.id(), balanceOnly(today().plusDays(1), "1.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aSnapshotBeforeTheAccountWasOpenedIsRejected() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    execute("UPDATE account SET opened_at = ? WHERE id = ?", today().minusMonths(1), cash.id());

    record(token, cash.id(), balanceOnly(today().minusMonths(2), "1.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    record(token, cash.id(), balanceOnly(today().minusMonths(1), "1.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void holdingsOnAnAccountThatHoldsNoPositionsAreRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    SecurityResponse vt = createSecurity(token, "US9220427424", "Vanguard Total World");

    record(
            token,
            cash.id(),
            new RecordAccountSnapshotRequest(
                today(), new BigDecimal("1.00"), List.of(holding(vt.id(), "1"))))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aSnapshotWithNeitherBalanceNorHoldingsIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");

    record(token, depot.id(), new RecordAccountSnapshotRequest(today(), null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void theSameSecurityTwiceInOneSnapshotIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");
    SecurityResponse vt = createSecurity(token, "US9220427424", "Vanguard Total World");

    record(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(
                today(), null, List.of(holding(vt.id(), "1"), holding(vt.id(), "2"))))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void anUnknownSecurityIsRejectedAndNothingIsWritten() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");

    record(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(
                today(), null, List.of(holding(UUID.randomUUID(), "1"))))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(count("SELECT count(*) FROM account_snapshot WHERE account_id = ?", depot.id()))
        .isZero();
  }

  @Test
  void aNonPositiveQuantityIsABadRequest() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");
    SecurityResponse vt = createSecurity(token, "US9220427424", "Vanguard Total World");

    record(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(today(), null, List.of(holding(vt.id(), "0"))))
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void aNullHoldingLineIsABadRequest() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");

    client(token)
        .post()
        .uri("/api/v1/accounts/" + depot.id() + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"snapshotDate\":\"" + today() + "\",\"holdings\":[null]}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  // --- Authorization ------------------------------------------------------------------------

  @Test
  void aReadOnlyMemberCanSeeSnapshotsButOnlyAnEditorCanRecordThem() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(adminToken, "PostFinance", "CASH");
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    record(memberToken, cash.id(), balanceOnly(today(), "1.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);

    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.READ);
    record(memberToken, cash.id(), balanceOnly(today(), "1.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND); // no hint the account exists, as for transactions
    client(memberToken)
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/snapshots")
        .exchange()
        .expectStatus()
        .isOk();

    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.EDIT);
    record(memberToken, cash.id(), balanceOnly(today(), "1.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  // --- V33 currency guard (defense-in-depth: the service never sends another currency) -------

  @Test
  void theDatabaseRejectsASnapshotInAnotherCurrencyThanTheAccounts() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");

    assertThatThrownBy(() -> insertSnapshot(cash.id(), "DOCUMENT", "EUR"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("account_snapshot_currency_mismatch");
  }

  // --- Helpers ------------------------------------------------------------------------------

  private static RecordAccountSnapshotRequest balanceOnly(LocalDate date, String balance) {
    return new RecordAccountSnapshotRequest(date, new BigDecimal(balance), List.of());
  }

  private static SnapshotHoldingRequest holding(UUID securityId, String quantity) {
    return new SnapshotHoldingRequest(securityId, new BigDecimal(quantity), null, null);
  }

  private RestTestClient.ResponseSpec record(
      String token, UUID accountId, RecordAccountSnapshotRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private AccountSnapshotResponse recordOk(
      String token, UUID accountId, RecordAccountSnapshotRequest request) {
    return record(token, accountId, request)
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSnapshotResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec replace(
      String token, UUID accountId, UUID snapshotId, ReplaceAccountSnapshotRequest request) {
    return client(token)
        .put()
        .uri("/api/v1/accounts/" + accountId + "/snapshots/" + snapshotId)
        .headers(CurrentVersion.ifMatch(dataSource, "account_snapshot", snapshotId))
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private RestTestClient.ResponseSpec replace(
      String token,
      UUID accountId,
      UUID snapshotId,
      ReplaceAccountSnapshotRequest request,
      String ifMatch) {
    return client(token)
        .put()
        .uri("/api/v1/accounts/" + accountId + "/snapshots/" + snapshotId)
        .headers(
            headers -> {
              if (ifMatch != null) {
                headers.setIfMatch(ifMatch);
              }
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private static ReplaceAccountSnapshotRequest balanceOnlyReplacement(String balance) {
    return new ReplaceAccountSnapshotRequest(new BigDecimal(balance), List.of());
  }

  private static String ifMatch(int version) {
    return "\"" + version + "\"";
  }

  private AccountSnapshotResponse get(String token, UUID accountId, UUID snapshotId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/snapshots/" + snapshotId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSnapshotResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createAccount(String token, String name, String accountType) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, accountType, "CHF").build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private SecurityResponse createSecurity(String token, String isin, String displayName) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                isin, displayName, "CHF", "EQUITY", "EQUITY", null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void grantOnAccount(String token, UUID memberId, UUID accountId, String level) {
    client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                memberId, ScopeTypeValues.ACCOUNT, accountId, null, level))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private UUID createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return (UUID) resultSet.getObject(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // Inserts as the provider-side import would, bypassing the service - which only ever writes
  // MANUAL snapshots in the account's own currency.
  private UUID insertSnapshot(UUID accountId, String source, String currency) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO account_snapshot (id, workspace_id, account_id, snapshot_date,"
                    + " balance, currency, source) SELECT ?, workspace_id, id, ?, 1.00, ?, ?"
                    + " FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setObject(2, today());
      statement.setString(3, currency);
      statement.setString(4, source);
      statement.setObject(5, accountId);
      statement.executeUpdate();
    }
    return id;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql, UUID parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // The zone app.business-zone defaults to, as in TransactionControllerTest.
  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
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
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "CHF"))
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
