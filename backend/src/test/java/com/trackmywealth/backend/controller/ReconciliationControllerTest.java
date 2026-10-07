package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.OpeningBalanceResponse;
import com.trackmywealth.backend.dto.ReconciliationStatusValues;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
 * US-25-02 cash reconciliation, end to end against PostgreSQL. The tests exercise the synchronous
 * source-write hooks rather than calling the service directly: a snapshot opens a difference, a
 * later ledger correction resolves it, and a newer snapshot supersedes the old comparison.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final LocalDate OPENING_DATE = LocalDate.of(2026, 1, 1);
  private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 9, 30);

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
              "DELETE FROM reconciliation_result",
              "DELETE FROM settlement_match",
              "DELETE FROM transaction_categorization_log",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM custom_asset_valuation",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
              "DELETE FROM account_securities",
              "DELETE FROM account_mortgage",
              "DELETE FROM account_loan",
              "DELETE FROM account_custom_asset",
              "DELETE FROM account_vested_benefits",
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
  void snapshotMinusLedgerOpensA45Franc67DifferenceAndSurfacesTheWarning() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");

    AccountSnapshotResponse snapshot = recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "12345.67");

    AccountSummaryResponse account = account(token, cash.id());
    assertThat(account.reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(account.reconciliation().asOf()).isEqualTo(SNAPSHOT_DATE);
    assertThat(account.reconciliation().openDifference()).isEqualByComparingTo("45.67");
    assertThat(account.warnings())
        .contains(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);

    client(token)
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/reconciliations")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.content[0].snapshotId")
        .isEqualTo(snapshot.id().toString())
        .jsonPath("$.content[0].differenceAmount")
        .isEqualTo("45.6700")
        .jsonPath("$.content[0].probableCause")
        .isEqualTo("UNKNOWN")
        .jsonPath("$.content[0].status")
        .isEqualTo("OPEN");

    assertThat(netWorth(token).warnings())
        .contains(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
  }

  @Test
  void addingTheMissingLedgerRowResolvesTheExistingDifference() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");
    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "12345.67");

    recordTransaction(token, cash.id(), SNAPSHOT_DATE, "45.67", "INCOME");

    AccountSummaryResponse account = account(token, cash.id());
    assertThat(account.reconciliation().status()).isEqualTo(ReconciliationStatusValues.RECONCILED);
    assertThat(account.reconciliation().asOf()).isEqualTo(SNAPSHOT_DATE);
    assertThat(account.reconciliation().openDifference()).isNull();
    assertThat(account.warnings())
        .doesNotContain(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
    assertThat(
            stringValue("SELECT status FROM reconciliation_result WHERE account_id = ?", cash.id()))
        .isEqualTo("RESOLVED");
  }

  @Test
  void aNewerSnapshotSupersedesTheOlderOpenDifference() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(40), "1000.00", "INCOME");

    AccountSnapshotResponse older =
        recordSnapshot(token, cash.id(), SNAPSHOT_DATE.minusDays(30), "11100.00");
    AccountSnapshotResponse newer = recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "11200.00");

    assertThat(
            stringValue(
                "SELECT status FROM reconciliation_result WHERE snapshot_id = ?", older.id()))
        .isEqualTo("SUPERSEDED");
    assertThat(
            stringValue(
                "SELECT status FROM reconciliation_result WHERE snapshot_id = ?", newer.id()))
        .isEqualTo("OPEN");
    assertThat(account(token, cash.id()).reconciliation().asOf()).isEqualTo(SNAPSHOT_DATE);
  }

  @Test
  void exactAgreementCreatesNoOpenRowAndShowsReconciled() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");

    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "12300.00");

    assertThat(count("SELECT count(*) FROM reconciliation_result WHERE account_id = ?", cash.id()))
        .isZero();
    assertThat(account(token, cash.id()).reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void aSnapshotWithoutAnOpeningBalanceIsExplicitlyNotReconciliable() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "12300.00");

    AccountSummaryResponse account = account(token, cash.id());
    assertThat(account.reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.NOT_RECONCILABLE);
    assertThat(account.reconciliation().reason())
        .isEqualTo(ReconciliationStatusValues.NO_OPENING_BALANCE);
    assertThat(count("SELECT count(*) FROM reconciliation_result WHERE account_id = ?", cash.id()))
        .isZero();
  }

  @Test
  void removingALedgerRowAfterAnExactSnapshotReopensTheDifference() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    TransactionResponse income =
        recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "100.00", "INCOME");
    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "10100.00");
    assertThat(account(token, cash.id()).reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);

    client(token)
        .delete()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions/" + income.id())
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", income.id()))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(account(token, cash.id()).reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(account(token, cash.id()).reconciliation().openDifference())
        .isEqualByComparingTo("100.00");
  }

  @Test
  void resolvingAResultKeepsTheDifferenceItResolvedInTheHistory() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");
    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "12345.67");

    recordTransaction(token, cash.id(), SNAPSHOT_DATE, "45.67", "INCOME");

    client(token)
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/reconciliations")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.content[0].status")
        .isEqualTo("RESOLVED")
        .jsonPath("$.content[0].differenceAmount")
        .isEqualTo("45.6700")
        .jsonPath("$.content[0].probableCause")
        .isEqualTo("UNKNOWN");
  }

  @Test
  void correctingALedgerRowReEvaluatesTheSnapshot() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    TransactionResponse income =
        recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");
    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "12345.67");

    client(token)
        .put()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions/" + income.id())
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", income.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(amountCorrection(income, "2345.67"))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(account(token, cash.id()).reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void restoringARemovedRowReconcilesTheSnapshotAgain() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    recordOpeningBalance(token, cash.id(), "10000.00");
    TransactionResponse income =
        recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "100.00", "INCOME");
    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "10100.00");
    client(token)
        .delete()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions/" + income.id())
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", income.id()))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(account(token, cash.id()).reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);

    client(token)
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions/" + income.id() + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", income.id()))
        .exchange()
        .expectStatus()
        .is2xxSuccessful();

    assertThat(account(token, cash.id()).reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void changingTheOpeningBalanceReEvaluatesTheSnapshot() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    OpeningBalanceResponse opening = recordOpeningBalance(token, cash.id(), "10000.00");
    recordTransaction(token, cash.id(), SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");
    recordSnapshot(token, cash.id(), SNAPSHOT_DATE, "12345.67");

    client(token)
        .put()
        .uri("/api/v1/accounts/" + cash.id() + "/opening-balance")
        .headers(headers -> headers.setIfMatch("\"" + opening.version() + "\""))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OpeningBalanceRequest(OPENING_DATE, new BigDecimal("10045.67"), "CHF", null))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(account(token, cash.id()).reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void aDuplicatedCardPurchaseIsClassifiedOnALiability() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card =
        client(token)
            .post()
            .uri("/api/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AccountRequests.account("Visa", "CREDIT_CARD", "CHF").build())
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();
    recordOpeningBalance(token, card.id(), "500.00");
    // Inserted as an import would, bypassing the service: the same purchase arrived twice.
    insertCardPurchase(card.id(), "-80.00", SNAPSHOT_DATE.minusDays(5), "Grocer");
    insertCardPurchase(card.id(), "-20.00", SNAPSHOT_DATE.minusDays(3), "Coffee Bar");
    insertCardPurchase(card.id(), "-20.00", SNAPSHOT_DATE.minusDays(3), "Coffee Bar");

    // The statement owes 600.00; the ledger derives 620.00 because of the duplicate.
    recordSnapshot(token, card.id(), SNAPSHOT_DATE, "600.00");

    client(token)
        .get()
        .uri("/api/v1/accounts/" + card.id() + "/reconciliations")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.content[0].differenceAmount")
        .isEqualTo("-20.0000")
        .jsonPath("$.content[0].probableCause")
        .isEqualTo("DUPLICATE_ENTRY");
  }

  @Test
  void aBalanceOnlyMemberSeesTheSignalButNeitherDetailsNorHistory() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(adminToken);
    recordOpeningBalance(adminToken, cash.id(), "10000.00");
    recordTransaction(adminToken, cash.id(), SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");
    recordSnapshot(adminToken, cash.id(), SNAPSHOT_DATE, "12345.67");
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.BALANCE_ONLY);

    AccountSummaryResponse seen = account(memberToken, cash.id());
    assertThat(seen.reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(seen.reconciliation().asOf()).isNull();
    assertThat(seen.reconciliation().openDifference()).isNull();
    assertThat(seen.reconciliation().currency()).isNull();
    client(memberToken)
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/reconciliations")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  private AccountSummaryResponse createCashAccount(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account("Current Account", "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private OpeningBalanceResponse recordOpeningBalance(String token, UUID accountId, String amount) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OpeningBalanceRequest(OPENING_DATE, new BigDecimal(amount), "CHF", null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(OpeningBalanceResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // The row as read, with only its amount changed: a financial correction (US-07-03).
  private static CorrectTransactionRequest amountCorrection(
      TransactionResponse row, String amount) {
    return new CorrectTransactionRequest(
        null,
        row.transactionType(),
        row.bookingDate(),
        new BigDecimal(amount),
        row.currency(),
        row.merchantDescription(),
        row.mcc(),
        row.notes(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "Amount was mistyped");
  }

  private void insertCardPurchase(
      UUID accountId, String amount, LocalDate bookedOn, String description) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, merchant_description, source)"
                    + " SELECT workspace_id, id, 'CREDIT_CARD_PURCHASE', ?, ?, 'CHF', ?, 'MANUAL'"
                    + " FROM account WHERE id = ?")) {
      statement.setObject(1, bookedOn);
      statement.setBigDecimal(2, new BigDecimal(amount));
      statement.setString(3, description);
      statement.setObject(4, accountId);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
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

  private AccountSnapshotResponse recordSnapshot(
      String token, UUID accountId, LocalDate date, String amount) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RecordAccountSnapshotRequest(date, new BigDecimal(amount), List.of()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSnapshotResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionResponse recordTransaction(
      String token, UUID accountId, LocalDate date, String amount, String type) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                type,
                date,
                new BigDecimal(amount),
                "CHF",
                type + " reconciliation fixture",
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse account(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private NetWorthResponse netWorth(String token) {
    return client(token)
        .get()
        .uri("/api/v1/net-worth")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(NetWorthResponse.class)
        .returnResult()
        .getResponseBody();
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

  private long count(String sql, UUID id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String stringValue(String sql, UUID id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
