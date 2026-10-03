package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdateWorkspaceCurrencyRequest;
import com.trackmywealth.backend.dto.WorkspaceResponse;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.AccountRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
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

/**
 * US-06-05: workspace display currency and ad-hoc conversion. Point-in-time account values use the
 * valuation date; transaction-derived cash flow uses each row's booking date. Original transaction
 * currency and amount are never rewritten for presentation.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceCurrencyControllerTest {

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
  @Autowired FxRateRepository fxRateRepository;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "fx_rate",
              "fx_rate_currency_in_use",
              "transaction_category_split",
              "transaction_categorization_log",
              "transaction",
              "sharing_grant",
              "account_ownership",
              "custom_asset_valuation",
              "account_custom_asset",
              "account_credit_card",
              "account_mortgage",
              "account_loan",
              "account_securities",
              "account_pension",
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

  @Test
  void workspaceCurrencyIsVersionedValidatedAndProtectedByIfMatch() {
    String token = bootstrapAdministrator("CHF");
    EntityExchangeResult<WorkspaceResponse> initial = getWorkspace(token);

    assertThat(initial.getResponseBody().currency()).isEqualTo("CHF");
    String initialEtag = initial.getResponseHeaders().getETag();
    assertThat(initialEtag).isNotBlank();

    EntityExchangeResult<WorkspaceResponse> updated =
        client(token)
            .put()
            .uri("/api/v1/workspace/currency")
            .header("If-Match", initialEtag)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new UpdateWorkspaceCurrencyRequest("USD"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(WorkspaceResponse.class)
            .returnResult();

    assertThat(updated.getResponseBody().currency()).isEqualTo("USD");
    assertThat(updated.getResponseHeaders().getETag()).isNotEqualTo(initialEtag);

    client(token)
        .put()
        .uri("/api/v1/workspace/currency")
        .header("If-Match", initialEtag)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateWorkspaceCurrencyRequest("EUR"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);

    client(token)
        .put()
        .uri("/api/v1/workspace/currency")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateWorkspaceCurrencyRequest("EUR"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

    client(token)
        .put()
        .uri("/api/v1/workspace/currency")
        .header("If-Match", updated.getResponseHeaders().getETag())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateWorkspaceCurrencyRequest("ZZZ"))
        .exchange()
        .expectStatus()
        .isBadRequest();

    // A valid ISO 4217 code that is no money (gold): no rate source quotes it, so every total
    // would be unknown for good.
    client(token)
        .put()
        .uri("/api/v1/workspace/currency")
        .header("If-Match", updated.getResponseHeaders().getETag())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateWorkspaceCurrencyRequest("XAU"))
        .exchange()
        .expectStatus()
        .isBadRequest();
    assertThat(getWorkspace(token).getResponseBody().currency()).isEqualTo("USD");
  }

  // #223: the workspace currency is a currency figures are shown in, so the FX import must store
  // its cross rates (V54's currencies in use), even when no account or transaction uses it.
  @Test
  void aNewWorkspaceCurrencyBecomesACurrencyInUse() throws Exception {
    String token = bootstrapAdministrator("CHF");
    String etag = getWorkspace(token).getResponseHeaders().getETag();

    client(token)
        .put()
        .uri("/api/v1/workspace/currency")
        .header("If-Match", etag)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateWorkspaceCurrencyRequest("JPY"))
        .exchange()
        .expectStatus()
        .isOk();

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        java.sql.ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM fx_rate_currency_in_use WHERE currency = 'JPY'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void anyActiveLoginMemberUsesAndMayChangeTheSameWorkspaceCurrency() {
    String adminToken = bootstrapAdministrator("CHF");
    createStandardUser(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    EntityExchangeResult<WorkspaceResponse> memberWorkspace = getWorkspace(memberToken);
    assertThat(memberWorkspace.getResponseBody().currency()).isEqualTo("CHF");

    client(memberToken)
        .put()
        .uri("/api/v1/workspace/currency")
        .header("If-Match", memberWorkspace.getResponseHeaders().getETag())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateWorkspaceCurrencyRequest("USD"))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(getWorkspace(adminToken).getResponseBody().currency()).isEqualTo("USD");
    assertThat(netWorth(adminToken, null).currency()).isEqualTo("USD");
    assertThat(netWorth(memberToken, null).currency()).isEqualTo("USD");
  }

  @Test
  void netWorthInstitutionAndAccountBalanceUseValuationDateFxAndAllowAdHocCurrency() {
    String token = bootstrapAdministrator("CHF");
    AccountSummaryResponse asset = createCustomAsset(token, "EUR");
    recordValuation(token, asset.id(), "100.00");

    seedFxRate("EUR", "CHF", today(), "1.1000000000");
    seedFxRate("EUR", "USD", today().minusDays(1), "1.2000000000");

    AccountValuation nativeBalance = balance(token, asset.id(), null);
    assertThat(nativeBalance.currency()).isEqualTo("EUR");
    assertThat(nativeBalance.value()).isEqualByComparingTo("100.00");

    AccountValuation usdBalance = balance(token, asset.id(), "USD");
    assertThat(usdBalance.currency()).isEqualTo("USD");
    assertThat(usdBalance.value()).isEqualByComparingTo("120.0000");
    assertThat(usdBalance.conversionRateDate()).isEqualTo(today());
    assertThat(usdBalance.conversionRateCarriedForward()).isTrue();

    NetWorthResponse workspaceDefault = netWorth(token, null);
    assertThat(workspaceDefault.currency()).isEqualTo("CHF");
    assertThat(workspaceDefault.netWorth()).isEqualByComparingTo("110.0000");

    NetWorthResponse adHoc = netWorth(token, "USD");
    assertThat(adHoc.currency()).isEqualTo("USD");
    assertThat(adHoc.netWorth()).isEqualByComparingTo("120.0000");

    InstitutionSummaryResponse institutionDefault =
        institutionSummary(token, asset.financialInstitutionId(), null);
    assertThat(institutionDefault.containerCurrency()).isEqualTo("CHF");
    assertThat(institutionDefault.currency()).isEqualTo("CHF");
    assertThat(institutionDefault.netValue()).isEqualByComparingTo("110.0000");

    InstitutionSummaryResponse institutionAdHoc =
        institutionSummary(token, asset.financialInstitutionId(), "USD");
    // The container's own currency stays visible; only the display currency changes.
    assertThat(institutionAdHoc.containerCurrency()).isEqualTo("CHF");
    assertThat(institutionAdHoc.currency()).isEqualTo("USD");
    assertThat(institutionAdHoc.netValue()).isEqualByComparingTo("120.0000");
    assertThat(institutionAdHoc.accounts())
        .singleElement()
        .satisfies(
            contribution -> {
              assertThat(contribution.value()).isEqualByComparingTo("120.0000");
              assertThat(contribution.conversionRate()).isEqualByComparingTo("1.2000000000");
              assertThat(contribution.conversionRateDate()).isEqualTo(today());
              assertThat(contribution.conversionRateCarriedForward()).isTrue();
            });
  }

  // AC 1: a container in EUR inside a CHF workspace - the summary defaults to EUR, not to the
  // workspace currency, while net worth over the same account is in CHF.
  @Test
  void aContainerDefaultsToItsOwnCurrencyNotTheWorkspaces() {
    String token = bootstrapAdministrator("CHF");
    UUID euroBank = createInstitution(token, "Euro Bank", "EUR");
    AccountSummaryResponse asset = createCustomAsset(token, "USD", euroBank);
    recordValuation(token, asset.id(), "100.00");
    seedFxRate("EUR", "USD", today(), "1.2500000000");
    seedFxRate("EUR", "CHF", today(), "1.1000000000");

    InstitutionSummaryResponse summary = institutionSummary(token, euroBank, null);
    assertThat(summary.containerCurrency()).isEqualTo("EUR");
    assertThat(summary.currency()).isEqualTo("EUR");
    // USD 100 at EUR/USD 1.25 is EUR 80.
    assertThat(summary.netValue()).isEqualByComparingTo("80.0000");

    NetWorthResponse netWorth = netWorth(token, null);
    assertThat(netWorth.currency()).isEqualTo("CHF");
    assertThat(netWorth.complete()).isTrue();
  }

  // AC 5 on the two point-in-time views: an account with no rate into the requested currency is
  // unknown, never zero or 1:1, the response is incomplete, and every other account still counts.
  @Test
  void anAdHocCurrencyWithoutARateMakesOnlyThatAccountUnknown() {
    String token = bootstrapAdministrator("CHF");
    AccountSummaryResponse euro = createCustomAsset(token, "EUR");
    recordValuation(token, euro.id(), "100.00");
    AccountSummaryResponse yen = createCustomAsset(token, "JPY");
    recordValuation(token, yen.id(), "5000.00");
    // No EUR/JPY rate exists, so JPY cannot be shown in EUR.

    NetWorthResponse netWorth = netWorth(token, "EUR");
    assertThat(netWorth.currency()).isEqualTo("EUR");
    assertThat(netWorth.complete()).isFalse();
    assertThat(netWorth.netWorth()).isEqualByComparingTo("100.00");
    assertThat(netWorth.accounts())
        .filteredOn(account -> account.accountId().equals(yen.id()))
        .singleElement()
        .satisfies(
            account -> {
              assertThat(account.valueKnown()).isFalse();
              assertThat(account.value()).isNull();
            });

    InstitutionSummaryResponse summary =
        institutionSummary(token, euro.financialInstitutionId(), "EUR");
    assertThat(summary.complete()).isFalse();
    assertThat(summary.netValue()).isEqualByComparingTo("100.00");
    assertThat(summary.accounts())
        .filteredOn(contribution -> contribution.accountId().equals(yen.id()))
        .singleElement()
        .satisfies(contribution -> assertThat(contribution.value()).isNull());
  }

  @Test
  void anEmptyCurrencyParameterMeansTheDefault() {
    String token = bootstrapAdministrator("CHF");

    assertThat(netWorth(token, "").currency()).isEqualTo("CHF");
    assertThat(cashFlow(token, YearMonth.from(today()), "").complete()).isTrue();
  }

  @Test
  void cashFlowKeepsOriginalAmountsWithoutOverrideAndUsesEachBookingDaysFxWithOverride()
      throws Exception {
    String token = bootstrapAdministrator("CHF");
    AccountSummaryResponse cash = createCashAccount(token);
    YearMonth month = YearMonth.from(today());
    LocalDate first = month.atDay(1);
    LocalDate second = month.atDay(2);

    insertExpense(cash.id(), first, "-10.00", "EUR");
    insertExpense(cash.id(), second, "-10.00", "EUR");
    seedFxRate("EUR", "USD", first, "1.1000000000");
    seedFxRate("EUR", "USD", second, "1.2000000000");

    CashFlowResponse original = cashFlow(token, month, null);
    assertThat(original.spending())
        .singleElement()
        .satisfies(
            amount -> {
              assertThat(amount.currency()).isEqualTo("EUR");
              assertThat(amount.amount()).isEqualByComparingTo("20.00");
              assertThat(amount.valueKnown()).isTrue();
            });
    assertThat(original.complete()).isTrue();

    CashFlowResponse converted = cashFlow(token, month, "USD");
    assertThat(converted.spending())
        .singleElement()
        .satisfies(
            amount -> {
              assertThat(amount.currency()).isEqualTo("USD");
              assertThat(amount.amount()).isEqualByComparingTo("23.0000");
              assertThat(amount.valueKnown()).isTrue();
              assertThat(amount.conversionRateCarriedForward()).isFalse();
            });

    assertThat(transactionAmount(cash.id(), first)).isEqualByComparingTo("-10.00");
    assertThat(transactionCurrency(cash.id(), first)).isEqualTo("EUR");
  }

  @Test
  void missingCashFlowFxMakesOnlyThatFigureUnknownNeverZero() throws Exception {
    String token = bootstrapAdministrator("CHF");
    AccountSummaryResponse cash = createCashAccount(token);
    YearMonth month = YearMonth.from(today());
    insertExpense(cash.id(), month.atDay(1), "-10.00", "EUR");

    CashFlowResponse converted = cashFlow(token, month, "USD");

    assertThat(converted.spending())
        .singleElement()
        .satisfies(
            amount -> {
              assertThat(amount.currency()).isEqualTo("USD");
              assertThat(amount.amount()).isNull();
              assertThat(amount.valueKnown()).isFalse();
            });
    assertThat(converted.income()).isEmpty();
    assertThat(converted.saving()).isEmpty();
    // PR-011: an unknown figure makes the month incomplete, though nothing is pending review.
    assertThat(converted.pendingReview()).isEmpty();
    assertThat(converted.complete()).isFalse();
    // The same month in its original currency is complete.
    assertThat(cashFlow(token, month, null).complete()).isTrue();
  }

  @Test
  void invalidAdHocCurrencyIsRejectedEvenWhenThereAreNoAccounts() {
    String token = bootstrapAdministrator("CHF");

    client(token)
        .get()
        .uri("/api/v1/net-worth?currency=ZZZ")
        .exchange()
        .expectStatus()
        .isBadRequest();
    client(token)
        .get()
        .uri("/api/v1/cash-flow?month=" + YearMonth.from(today()) + "&currency=ZZZ")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  private EntityExchangeResult<WorkspaceResponse> getWorkspace(String token) {
    return client(token)
        .get()
        .uri("/api/v1/workspace")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(WorkspaceResponse.class)
        .returnResult();
  }

  private NetWorthResponse netWorth(String token, String currency) {
    String query = currency == null ? "" : "?currency=" + currency;
    return client(token)
        .get()
        .uri("/api/v1/net-worth" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(NetWorthResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private InstitutionSummaryResponse institutionSummary(
      String token, UUID institutionId, String currency) {
    String query = currency == null ? "" : "?currency=" + currency;
    return client(token)
        .get()
        .uri("/api/v1/institutions/" + institutionId + "/summary" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(InstitutionSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountValuation balance(String token, UUID accountId, String currency) {
    String query = currency == null ? "" : "?currency=" + currency;
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
        .returnResult()
        .getResponseBody();
  }

  private CashFlowResponse cashFlow(String token, YearMonth month, String currency) {
    String query = "?month=" + month + (currency == null ? "" : "&currency=" + currency);
    return client(token)
        .get()
        .uri("/api/v1/cash-flow" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createCustomAsset(String token, String currency) {
    return createCustomAsset(token, currency, null);
  }

  // institutionId null: the workspace's Personal Assets container.
  private AccountSummaryResponse createCustomAsset(
      String token, String currency, UUID institutionId) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account("Vintage Car " + currency, "CUSTOM_ASSET", currency)
                .customAssetType("VEHICLE")
                .financialInstitutionId(institutionId)
                .build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createInstitution(String token, String name, String containerCurrency) {
    return client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                null, name, "DE", "BANK", null, null, containerCurrency))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(FinancialInstitutionSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private AccountSummaryResponse createCashAccount(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account("Cash", "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void recordValuation(String token, UUID accountId, String value) {
    client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateCustomAssetValuationRequest(today(), new BigDecimal(value)))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private void insertExpense(UUID accountId, LocalDate bookingDate, String amount, String currency)
      throws Exception {
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", accountId);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                    + " amount, currency) VALUES (?, ?, 'EXPENSE', ?, ?, ?)")) {
      statement.setObject(1, workspaceId);
      statement.setObject(2, accountId);
      statement.setObject(3, bookingDate);
      statement.setBigDecimal(4, new BigDecimal(amount));
      statement.setString(5, currency);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private BigDecimal transactionAmount(UUID accountId, LocalDate bookingDate) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT amount FROM transaction WHERE account_id = ? AND booking_date = ?")) {
      statement.setObject(1, accountId);
      statement.setObject(2, bookingDate);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getBigDecimal(1);
      }
    }
  }

  private String transactionCurrency(UUID accountId, LocalDate bookingDate) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT currency FROM transaction WHERE account_id = ? AND booking_date = ?")) {
      statement.setObject(1, accountId);
      statement.setObject(2, bookingDate);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private UUID jdbcUuid(String sql, UUID id) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return (UUID) resultSet.getObject(1);
      }
    }
  }

  private void seedFxRate(String base, String quote, LocalDate date, String rate) {
    FxRate fxRate = new FxRate();
    fxRate.setBaseCurrency(base);
    fxRate.setQuoteCurrency(quote);
    fxRate.setRateDate(date);
    fxRate.setRate(new BigDecimal(rate));
    fxRate.setSource("ECB");
    fxRateRepository.saveAndFlush(fxRate);
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

  private String bootstrapAdministrator(String currency) {
    return anonymousClient()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Workspace A", currency))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
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
