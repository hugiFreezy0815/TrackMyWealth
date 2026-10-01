package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CardStatementResponse;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SetStatementConfigRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.StatementConfigResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.MutableClock;
import com.trackmywealth.backend.testsupport.TestClockConfig;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-09-03: statement cycle configuration and the current-statement view (FR-CC-008/009).
 * Transaction-date attribution itself (a purchase counts in the month it was made) is already
 * exercised by {@link
 * SettlementMatchControllerTest#theSettlementContributesZeroToSpendingOnlyThePurchasesDo}; {@link
 * #theStatementBoundaryIsDeterministicAndInclusive} exercises the same rule at the statement-cycle
 * level, which is this story's own edge case.
 *
 * <p>"Today" is a {@link MutableClock} substituted for the application's real one, so the "current
 * period" computation (which depends on today's date) is deterministic rather than drifting with
 * the calendar.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
class CardStatementControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Zurich");

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

  @Autowired MutableClock clock;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM settlement_match");
      statement.execute("DELETE FROM transaction_category_split");
      // US-08-01: every categorized row has a log row referencing it.
      statement.execute("DELETE FROM transaction_categorization_log");
      statement.execute("DELETE FROM transaction");
      for (String table :
          List.of(
              "sharing_grant",
              "account_ownership",
              "custom_asset_valuation",
              "account_custom_asset",
              "account_credit_card",
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

  // --- Configuring the cycle -------------------------------------------------------------------

  @Test
  void settingAndReadingBackTheStatementConfig() {
    String token = bootstrapAdministrator();
    UUID card = createCard(token);

    assertThat(getConfig(token, card)).isEqualTo(new StatementConfigResponse(card, null, null));

    StatementConfigResponse set = setConfig(token, card, 5, 20, HttpStatus.OK);
    assertThat(set).isEqualTo(new StatementConfigResponse(card, 5, 20));
    assertThat(getConfig(token, card)).isEqualTo(set);

    // null clears each field independently, same convention as settlement-source.
    StatementConfigResponse cleared = setConfig(token, card, null, null, HttpStatus.OK);
    assertThat(cleared).isEqualTo(new StatementConfigResponse(card, null, null));
  }

  @Test
  void anOutOfRangeStatementDayOrANegativeOffsetIsRejected() {
    String token = bootstrapAdministrator();
    UUID card = createCard(token);

    setConfig(token, card, 0, 10, HttpStatus.UNPROCESSABLE_CONTENT);
    setConfig(token, card, 32, 10, HttpStatus.UNPROCESSABLE_CONTENT);
    setConfig(token, card, 5, -1, HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(getConfig(token, card)).isEqualTo(new StatementConfigResponse(card, null, null));
  }

  @Test
  void onlyACreditCardAccountHasAStatementCycle() {
    String token = bootstrapAdministrator();
    UUID cash = createAccount(token, "Everyday Checking", "CASH", "CHF").id();

    setConfig(token, cash, 5, 10, HttpStatus.UNPROCESSABLE_CONTENT);
    client(token)
        .get()
        .uri("/api/v1/accounts/" + cash + "/statement-config")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    client(token)
        .get()
        .uri("/api/v1/accounts/" + cash + "/statement")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void theCurrentStatementIsUnavailableUntilBothFieldsAreConfigured() {
    String token = bootstrapAdministrator();
    UUID card = createCard(token);
    setToday(clock, LocalDate.of(2026, 9, 22));

    statement(token, card, HttpStatus.UNPROCESSABLE_CONTENT);

    setConfig(token, card, 5, null, HttpStatus.OK);
    statement(token, card, HttpStatus.UNPROCESSABLE_CONTENT); // statementDay alone is not enough

    setConfig(token, card, 5, 20, HttpStatus.OK);
    statement(token, card, HttpStatus.OK);
  }

  // --- Current period, closing balance, due date ------------------------------------------------

  @Test
  void theCurrentPeriodIsTheMostRecentlyClosedCycleWithItsClosingBalanceAndDueDate() {
    String token = bootstrapAdministrator();
    UUID card = createCard(token);
    setConfig(token, card, 5, 20, HttpStatus.OK);
    purchase(token, card, "-700.00", LocalDate.of(2026, 8, 28)); // in the closed period
    purchase(token, card, "-500.00", LocalDate.of(2026, 9, 5)); // on the boundary itself
    purchase(token, card, "-999.00", LocalDate.of(2026, 9, 6)); // in the next, still-open period
    setToday(clock, LocalDate.of(2026, 9, 22)); // well after the Sept 5 close

    CardStatementResponse current = statement(token, card, HttpStatus.OK);

    assertThat(current.periodStart()).isEqualTo(LocalDate.of(2026, 8, 6));
    assertThat(current.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 5));
    assertThat(current.dueDate()).isEqualTo(LocalDate.of(2026, 9, 25));
    assertThat(current.currency()).isEqualTo("CHF");
    // Only the two purchases in [Aug 6, Sep 5] count - not the Sep 6 one in the next period.
    assertThat(current.closingBalance()).isEqualByComparingTo("1200.00");
  }

  @Test
  void theStatementBoundaryIsDeterministicAndInclusive() {
    // The story's own edge case: a purchase booked exactly on the closing day. It belongs to the
    // period ending that day, not the one starting the next day.
    String token = bootstrapAdministrator();
    UUID card = createCard(token);
    setConfig(token, card, 5, 10, HttpStatus.OK);
    purchase(token, card, "-50.00", LocalDate.of(2026, 9, 5));
    setToday(clock, LocalDate.of(2026, 9, 5)); // today is itself the closing day

    CardStatementResponse current = statement(token, card, HttpStatus.OK);

    assertThat(current.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 5));
    assertThat(current.closingBalance()).isEqualByComparingTo("50.00");
  }

  @Test
  void beforeThisMonthsCloseTheCurrentPeriodIsStillLastMonths() {
    String token = bootstrapAdministrator();
    UUID card = createCard(token);
    setConfig(token, card, 5, 10, HttpStatus.OK);
    setToday(clock, LocalDate.of(2026, 9, 4)); // one day before September's close

    CardStatementResponse current = statement(token, card, HttpStatus.OK);

    assertThat(current.periodStart()).isEqualTo(LocalDate.of(2026, 7, 6));
    assertThat(current.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 5));
  }

  @Test
  void aStatementDayBeyondTheMonthsLengthClampsToTheMonthsLastDay() {
    // statementDay = 31 in a 28-day February closes on the 28th, not error or roll into March.
    String token = bootstrapAdministrator();
    UUID card = createCard(token);
    setConfig(token, card, 31, 0, HttpStatus.OK);
    setToday(clock, LocalDate.of(2027, 3, 15)); // 2027 is not a leap year

    CardStatementResponse current = statement(token, card, HttpStatus.OK);

    assertThat(current.periodStart()).isEqualTo(LocalDate.of(2027, 2, 1));
    assertThat(current.periodEnd()).isEqualTo(LocalDate.of(2027, 2, 28));
    assertThat(current.dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
  }

  // --- Paid status -------------------------------------------------------------------------------

  @Test
  void aStatementWithNothingOwedIsTriviallyPaid() {
    String token = bootstrapAdministrator();
    UUID card = createCard(token);
    setConfig(token, card, 5, 20, HttpStatus.OK);
    setToday(clock, LocalDate.of(2026, 9, 22));

    assertThat(statement(token, card, HttpStatus.OK).paid()).isTrue();
  }

  @Test
  void anUnpaidClosingBalanceIsNotPaidUntilAConfirmedSettlementCoversIt() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    setConfig(token, a.card(), 5, 20, HttpStatus.OK);
    purchase(token, a.card(), "-1200.00", LocalDate.of(2026, 8, 28));
    setToday(clock, LocalDate.of(2026, 9, 22));

    assertThat(statement(token, a.card(), HttpStatus.OK).paid()).isFalse();

    // Paid off within the statement's own window (after the Sep 5 close, by the Sep 25 due date).
    withdrawal(token, a.current(), "-1200.00", LocalDate.of(2026, 9, 10));
    cardCredit(token, a.card(), "1200.00", LocalDate.of(2026, 9, 10));

    assertThat(statement(token, a.card(), HttpStatus.OK).paid()).isTrue();
  }

  @Test
  void aPartialPaymentDoesNotMarkTheStatementPaid() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    setConfig(token, a.card(), 5, 20, HttpStatus.OK);
    purchase(token, a.card(), "-1200.00", LocalDate.of(2026, 8, 28));
    setToday(clock, LocalDate.of(2026, 9, 22));

    // Not a settlement candidate at all (it doesn't equal the card's balance) - just spending.
    withdrawal(token, a.current(), "-600.00", LocalDate.of(2026, 9, 10));

    assertThat(statement(token, a.card(), HttpStatus.OK).paid()).isFalse();
  }

  @Test
  void aSettlementBookedAfterTheNextPeriodsCloseDoesNotWronglyMarkThisOneAsPaid() {
    // Regression: with a generous dueDateOffsetDays, an uncapped window would let a settlement
    // booked well into the *next* cycle still satisfy this period's paid check just because the
    // amount happens to match. The window must stay capped short of the next period's own close.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    setConfig(token, a.card(), 5, 40, HttpStatus.OK); // due date offset exceeds a monthly cycle
    purchase(token, a.card(), "-1200.00", LocalDate.of(2026, 8, 10));
    setToday(clock, LocalDate.of(2026, 9, 20)); // "current" = the period closing Sep 5

    // Booked Oct 10: after Sep 5's naive due date (Sep 5 + 40 = Oct 15, so still "inside" an
    // uncapped window) but also after Oct 5, the *next* period's own close.
    withdrawal(token, a.current(), "-1200.00", LocalDate.of(2026, 10, 10));
    cardCredit(token, a.card(), "1200.00", LocalDate.of(2026, 10, 10));

    CardStatementResponse current = statement(token, a.card(), HttpStatus.OK);
    assertThat(current.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 5));
    assertThat(current.closingBalance()).isEqualByComparingTo("1200.00");
    assertThat(current.paid()).isFalse();
  }

  // --- Authorization (US-03-03)
  // -------------------------------------------------------------------

  @Test
  void readingTheCycleNeedsBalanceOnlyButSettingItNeedsEdit() {
    String adminToken = bootstrapAdministrator();
    UUID card = createCard(adminToken);
    setConfig(adminToken, card, 5, 20, HttpStatus.OK);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");

    client(bobToken)
        .get()
        .uri("/api/v1/accounts/" + card + "/statement-config")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND); // no grant at all: deny-as-not-found

    grant(adminToken, bobMemberId, card, AccessLevelValues.BALANCE_ONLY);
    assertThat(getConfig(bobToken, card)).isEqualTo(new StatementConfigResponse(card, 5, 20));
    setConfig(bobToken, card, 6, 15, HttpStatus.NOT_FOUND); // BALANCE_ONLY is not enough to write

    grant(adminToken, bobMemberId, card, AccessLevelValues.EDIT);
    assertThat(setConfig(bobToken, card, 6, 15, HttpStatus.OK))
        .isEqualTo(new StatementConfigResponse(card, 6, 15));
  }

  // --- helpers -------------------------------------------------------------------------------

  // Sets "today" unambiguously in the app's own business zone (noon avoids any DST edge).
  private static void setToday(MutableClock clock, LocalDate date) {
    clock.set(date.atTime(12, 0).atZone(BUSINESS_ZONE).toInstant());
  }

  private record Accounts(UUID card, UUID current) {}

  private Accounts accountsWithSource(String token) {
    UUID card = createCard(token);
    UUID current = createAccount(token, "Everyday Checking", "CASH", "CHF").id();
    client(token)
        .put()
        .uri("/api/v1/accounts/" + card + "/settlement-source")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetSettlementSourceRequest(current))
        .exchange()
        .expectStatus()
        .isOk();
    return new Accounts(card, current);
  }

  private UUID createCard(String token) {
    return createAccount(token, "Visa Gold", "CREDIT_CARD", "CHF").id();
  }

  private AccountSummaryResponse createAccount(
      String token, String name, String accountType, String currency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, accountType, currency).build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionResponse record(
      String token, UUID accountId, String type, String amount, LocalDate date) {
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
                null,
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

  private TransactionResponse purchase(String token, UUID card, String amount, LocalDate date) {
    return record(token, card, "CREDIT_CARD_PURCHASE", amount, date);
  }

  private TransactionResponse withdrawal(
      String token, UUID account, String amount, LocalDate date) {
    return record(token, account, "WITHDRAWAL", amount, date);
  }

  private TransactionResponse cardCredit(String token, UUID card, String amount, LocalDate date) {
    return record(token, card, "SETTLEMENT", amount, date);
  }

  private StatementConfigResponse setConfig(
      String token,
      UUID card,
      Integer statementDay,
      Integer dueDateOffsetDays,
      HttpStatus expected) {
    RestTestClient.ResponseSpec response =
        client(token)
            .put()
            .uri("/api/v1/accounts/" + card + "/statement-config")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SetStatementConfigRequest(statementDay, dueDateOffsetDays))
            .exchange();
    response.expectStatus().isEqualTo(expected);
    return expected == HttpStatus.OK
        ? response.expectBody(StatementConfigResponse.class).returnResult().getResponseBody()
        : null;
  }

  private StatementConfigResponse getConfig(String token, UUID card) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + card + "/statement-config")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(StatementConfigResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private CardStatementResponse statement(String token, UUID card, HttpStatus expected) {
    RestTestClient.ResponseSpec response =
        client(token).get().uri("/api/v1/accounts/" + card + "/statement").exchange();
    response.expectStatus().isEqualTo(expected);
    return expected == HttpStatus.OK
        ? response.expectBody(CardStatementResponse.class).returnResult().getResponseBody()
        : null;
  }

  private void grant(String token, UUID memberId, UUID accountId, String level) {
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
        assertThat(resultSet.next()).as("app_user " + email).isTrue();
        return (UUID) resultSet.getObject(1);
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
