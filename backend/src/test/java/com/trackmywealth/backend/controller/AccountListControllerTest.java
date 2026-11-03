package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountListItemResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.SignedAmounts;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.AccountRequests;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * US-05-06 (#380): {@code GET /api/v1/accounts} - one test per acceptance criterion of the story,
 * plus the filters, the signed amounts and the agreement with net worth.
 *
 * <p>A cash or savings account has a value only once it has an opening balance; opening balances,
 * snapshots and custom-asset valuations are seeded directly so their dates can lie in the past.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountListControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final ParameterizedTypeReference<List<AccountListItemResponse>> ROWS =
      new ParameterizedTypeReference<>() {};

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
    // The query-count criterion reads Hibernate's statement count.
    registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;
  @Autowired FxRateRepository fxRateRepository;
  @Autowired EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // Plain DELETEs in FK order, as PortfolioControllerTest explains: a TRUNCATE ... CASCADE
    // would reach the shared institution catalogue. No test here books a transaction.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "fx_rate",
              "account_snapshot",
              "custom_asset_valuation",
              "account_securities",
              "account_credit_card",
              "account_mortgage",
              "account_loan",
              "account_pension",
              "account_vested_benefits",
              "account_custom_asset",
              "account",
              "admin_audit_log",
              "user_session",
              "refresh_token",
              "authorization_denial_log",
              "app_user",
              "workspace_member",
              "portfolio",
              "workspace")) {
        statement.execute("DELETE FROM " + table);
      }
    }
  }

  // --- Acceptance criteria -----------------------------------------------------------------

  @Test
  void everyAccountOfBothPortfoliosAppearsOnceWithItsBalanceInAccountAndWorkspaceCurrency()
      throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse postFinance = createPortfolio(token, "PostFinance", "CHF");
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    UUID privat = createAccount(token, cash("Privatkonto", "CHF", postFinance.id())).id();
    UUID giro = createAccount(token, cash("Giro", "EUR", dkb.id())).id();
    UUID kredit =
        createAccount(
                token,
                AccountRequests.account("Kredit", "LOAN", "CHF")
                    .portfolioId(dkb.id())
                    .originalPrincipal(new BigDecimal("10000"))
                    .build())
            .id();
    seedSnapshot(privat, today().minusDays(3), "5000.0000", true);
    seedSnapshot(giro, today().minusDays(3), "1000.0000", true);
    seedFxRate("EUR", "CHF", "0.9500000000");

    List<AccountListItemResponse> rows = list(token, "");

    // Oldest first, each once.
    assertThat(rows).extracting(AccountListItemResponse::id).containsExactly(privat, giro, kredit);
    Map<UUID, AccountListItemResponse> byId = byId(rows);

    AccountListItemResponse chf = byId.get(privat);
    assertThat(chf.portfolioId()).isEqualTo(postFinance.id());
    assertThat(chf.portfolioName()).isEqualTo("PostFinance");
    assertThat(chf.accountType()).isEqualTo("CASH");
    assertThat(chf.currency()).isEqualTo("CHF");
    assertThat(chf.balance()).isEqualByComparingTo("5000");
    assertThat(chf.workspaceCurrency()).isEqualTo("CHF");
    assertThat(chf.balanceInWorkspaceCurrency()).isEqualByComparingTo("5000");
    assertThat(chf.valueKnown()).isTrue();
    assertThat(chf.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(chf.asOf()).isEqualTo(today());
    assertThat(chf.archived()).isFalse();
    assertThat(chf.version()).isZero();

    AccountListItemResponse eur = byId.get(giro);
    assertThat(eur.portfolioName()).isEqualTo("DKB");
    assertThat(eur.currency()).isEqualTo("EUR");
    assertThat(eur.balance()).isEqualByComparingTo("1000");
    assertThat(eur.balanceInWorkspaceCurrency()).isEqualByComparingTo("950");

    // A liability is negative in both currencies, like in the Portfolio View.
    AccountListItemResponse loan = byId.get(kredit);
    assertThat(loan.nature()).isEqualTo("LIABILITY");
    assertThat(loan.balance()).isEqualByComparingTo("-10000");
    assertThat(loan.balanceInWorkspaceCurrency()).isEqualByComparingTo("-10000");
    assertThat(loan.valueStatus()).isEqualTo(ValueStatus.ESTIMATED);
  }

  @Test
  void aSecondMemberWhoCreatedNoneOfTheAccountsSeesExactlyTheSameList() throws Exception {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "anna@example.com");
    createStandardUser(adminToken, "ben@example.com");
    String anna = login("anna@example.com");
    String ben = login("ben@example.com");
    UUID annas = createAccount(anna, cash("Anna's account", "CHF", null)).id();
    createAccount(
        anna,
        AccountRequests.account("Anna's car", "CUSTOM_ASSET", "CHF")
            .customAssetType("VEHICLE")
            .build());
    seedSnapshot(annas, today().minusDays(1), "1234.5000", true);

    List<AccountListItemResponse> annasList = list(anna, "");

    assertThat(annasList).hasSize(2);
    assertThat(list(ben, "")).isEqualTo(annasList);
  }

  @Test
  void aPortfolioOfAnotherWorkspaceMatchesNothingExactlyLikeAnUnknownId() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse own = createPortfolio(token, "Own", "CHF");
    UUID inOwn = createAccount(token, cash("Mine", "CHF", own.id())).id();
    createAccount(token, cash("Elsewhere", "CHF", null));
    UUID foreignPortfolio = foreignPortfolioWithAnAccount();

    assertThat(list(token, "?portfolioId=" + own.id()))
        .extracting(AccountListItemResponse::id)
        .containsExactly(inOwn);
    assertThat(list(token, "?portfolioId=" + foreignPortfolio)).isEmpty();
    assertThat(list(token, "?portfolioId=" + UUID.randomUUID())).isEmpty();
  }

  @Test
  void anArchivedAccountIsListedOnlyWithArchivedTrue() {
    String token = bootstrapAdministrator();
    UUID active = createAccount(token, cash("Active", "CHF", null)).id();
    AccountSummaryResponse old = createAccount(token, cash("Old", "CHF", null));
    archive(token, old.id(), old.version());

    assertThat(list(token, "")).extracting(AccountListItemResponse::id).containsExactly(active);
    assertThat(list(token, "?archived=false"))
        .extracting(AccountListItemResponse::id)
        .containsExactly(active);
    List<AccountListItemResponse> archived = list(token, "?archived=true");
    assertThat(archived).extracting(AccountListItemResponse::id).containsExactly(old.id());
    assertThat(archived.get(0).archived()).isTrue();
  }

  @Test
  void aValueFromAnOldSnapshotIsStaleWithItsDateAndNoValueIsUnknown() throws Exception {
    String token = bootstrapAdministrator();
    UUID oldVested =
        createAccount(token, AccountRequests.account("Old FZ", "VESTED_BENEFITS", "CHF").build())
            .id();
    UUID freshVested =
        createAccount(token, AccountRequests.account("New FZ", "VESTED_BENEFITS", "CHF").build())
            .id();
    UUID oldCar =
        createAccount(
                token,
                AccountRequests.account("Car", "CUSTOM_ASSET", "CHF")
                    .customAssetType("VEHICLE")
                    .build())
            .id();
    UUID nothing = createAccount(token, cash("No opening balance", "CHF", null)).id();
    LocalDate longAgo = today().minusDays(60);
    seedSnapshot(oldVested, longAgo, "80000.0000", false);
    seedSnapshot(freshVested, today().minusDays(10), "20000.0000", false);
    seedValuation(oldCar, longAgo, "15000.0000");

    Map<UUID, AccountListItemResponse> byId = byId(list(token, ""));

    assertThat(byId.get(oldVested).valueStatus()).isEqualTo(ValueStatus.STALE);
    assertThat(byId.get(oldVested).valueSourceDate()).isEqualTo(longAgo);
    assertThat(byId.get(oldVested).valueKnown()).isTrue();
    assertThat(byId.get(oldVested).balance()).isEqualByComparingTo("80000");
    assertThat(byId.get(oldCar).valueStatus()).isEqualTo(ValueStatus.STALE);
    assertThat(byId.get(oldCar).valueSourceDate()).isEqualTo(longAgo);
    assertThat(byId.get(freshVested).valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(byId.get(freshVested).valueSourceDate()).isEqualTo(today().minusDays(10));

    AccountListItemResponse unknown = byId.get(nothing);
    assertThat(unknown.valueKnown()).isFalse();
    assertThat(unknown.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(unknown.balance()).isNull();
    assertThat(unknown.balanceInWorkspaceCurrency()).isNull();
  }

  @Test
  void fiftyAccountsAreListedWithTheSameNumberOfQueriesAsSix() throws Exception {
    String token = bootstrapAdministrator();
    createMixedAccounts(token, 1);
    long forSix = statementsFor(() -> assertThat(list(token, "")).hasSize(6));

    createMixedAccounts(token, 7); // 6 + 42 = 48, plus two more below
    createAccount(token, cash("49", "CHF", null));
    createAccount(token, cash("50", "CHF", null));
    long forFifty = statementsFor(() -> assertThat(list(token, "")).hasSize(50));

    // Not vacuous: the statistics do count this request's queries.
    assertThat(forSix).isPositive();
    assertThat(forFifty).isEqualTo(forSix);
  }

  // --- Business rule: the same figures as net worth ------------------------------------------

  @Test
  void eachBalanceInTheWorkspaceCurrencyIsTheAccountsNetWorthContribution() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    UUID giro = createAccount(token, cash("Giro", "EUR", dkb.id())).id();
    UUID privat = createAccount(token, cash("Privatkonto", "CHF", null)).id();
    createAccount(
        token,
        AccountRequests.account("Hypothek", "MORTGAGE", "CHF")
            .originalPrincipal(new BigDecimal("400000"))
            .interestRatePercent(new BigDecimal("1.5"))
            .build());
    UUID vested =
        createAccount(token, AccountRequests.account("FZ", "VESTED_BENEFITS", "CHF").build()).id();
    seedSnapshot(giro, today().minusDays(3), "1000.0000", true);
    seedSnapshot(privat, today().minusDays(3), "5000.0000", true);
    seedSnapshot(vested, today().minusDays(90), "30000.0000", false);
    seedFxRate("EUR", "CHF", "0.9500000000");

    List<AccountListItemResponse> rows = list(token, "");
    NetWorthResponse netWorth = netWorth(token);

    assertThat(netWorth.accounts()).hasSameSizeAs(rows);
    for (AccountValuation valuation : netWorth.accounts()) {
      AccountListItemResponse row = byId(rows).get(valuation.accountId());
      assertThat(row.valueKnown()).isEqualTo(valuation.valueKnown());
      if (valuation.valueKnown()) {
        assertThat(row.balanceInWorkspaceCurrency())
            .isEqualByComparingTo(SignedAmounts.of(valuation));
      }
      assertThat(row.valueStatus()).isEqualTo(ValueStatus.of(valuation));
    }
    BigDecimal sum =
        rows.stream()
            .filter(AccountListItemResponse::valueKnown)
            .map(AccountListItemResponse::balanceInWorkspaceCurrency)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(netWorth.netWorth());
  }

  // --- Filters and errors ----------------------------------------------------------------------

  @Test
  void theAccountTypeFilterNarrowsTheListAndAnUnknownTypeIsABadRequest() {
    String token = bootstrapAdministrator();
    UUID cash = createAccount(token, cash("Cash", "CHF", null)).id();
    createAccount(token, AccountRequests.account("FZ", "VESTED_BENEFITS", "CHF").build());

    assertThat(list(token, "?accountType=CASH"))
        .extracting(AccountListItemResponse::id)
        .containsExactly(cash);
    assertThat(list(token, "?accountType=CREDIT_CARD")).isEmpty();
    for (String invalid : List.of("cash", "CHECKING", "CASH|SAVINGS")) {
      client(token)
          .get()
          .uri("/api/v1/accounts?accountType=" + invalid)
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
    client(token)
        .get()
        .uri("/api/v1/accounts?portfolioId=not-a-uuid")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aWorkspaceWithoutAccountsListsNothingAndAnAnonymousCallerIsRefused() {
    String token = bootstrapAdministrator();

    assertThat(list(token, "")).isEmpty();
    anonymousClient()
        .get()
        .uri("/api/v1/accounts")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // --- Helpers ---------------------------------------------------------------------------------

  // Per round: a cash account with an opening balance, a card, a loan, a custom asset with a
  // valuation, a vested-benefits account with a snapshot and a pillar 3a account - every value
  // source valuation knows.
  private void createMixedAccounts(String token, int rounds) throws Exception {
    for (int i = 0; i < rounds; i++) {
      UUID cash = createAccount(token, cash("Cash " + i, "CHF", null)).id();
      seedSnapshot(cash, today().minusDays(5), "100.0000", true);
      createAccount(
          token,
          AccountRequests.account("Card " + i, "CREDIT_CARD", "CHF")
              .billingCurrency("CHF")
              .build());
      UUID loan =
          createAccount(
                  token,
                  AccountRequests.account("Loan " + i, "LOAN", "CHF")
                      .originalPrincipal(new BigDecimal("1000"))
                      .build())
              .id();
      seedSnapshot(loan, today().minusDays(5), "900.0000", false);
      UUID car =
          createAccount(
                  token,
                  AccountRequests.account("Car " + i, "CUSTOM_ASSET", "CHF")
                      .customAssetType("VEHICLE")
                      .build())
              .id();
      seedValuation(car, today().minusDays(5), "5000.0000");
      UUID vested =
          createAccount(token, AccountRequests.account("FZ " + i, "VESTED_BENEFITS", "CHF").build())
              .id();
      seedSnapshot(vested, today().minusDays(5), "2000.0000", false);
      createAccount(
          token,
          AccountRequests.account("3a " + i, "PENSION", "CHF")
              .pensionScheme("CH_PILLAR_3A")
              .build());
    }
  }

  private long statementsFor(Runnable action) {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    action.run();
    return statistics.getPrepareStatementCount();
  }

  private static CreateAccountRequest cash(String name, String currency, UUID portfolioId) {
    return AccountRequests.account(name, "CASH", currency).portfolioId(portfolioId).build();
  }

  private static Map<UUID, AccountListItemResponse> byId(List<AccountListItemResponse> rows) {
    return rows.stream()
        .collect(Collectors.toMap(AccountListItemResponse::id, Function.identity()));
  }

  private List<AccountListItemResponse> list(String token, String query) {
    return client(token)
        .get()
        .uri("/api/v1/accounts" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ROWS)
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

  private void archive(String token, UUID accountId, int version) {
    client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/archive")
        .header("If-Match", "\"" + version + "\"")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private PortfolioResponse createPortfolio(String token, String name, String currency) {
    return client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePortfolioRequest(null, name, null, null, null, null, currency))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(PortfolioResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createAccount(String token, CreateAccountRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // An opening balance or an observed balance, in the account's own currency.
  private void seedSnapshot(UUID accountId, LocalDate date, String balance, boolean opening)
      throws Exception {
    execute(
        "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance,"
            + " currency, is_opening_balance) SELECT workspace_id, id, ?, ?, native_currency, ?"
            + " FROM account WHERE id = ?",
        date,
        new BigDecimal(balance),
        opening,
        accountId);
  }

  private void seedValuation(UUID accountId, LocalDate date, String value) throws Exception {
    execute(
        "INSERT INTO custom_asset_valuation (account_id, valuation_date, value, currency)"
            + " SELECT id, ?, ?, native_currency FROM account WHERE id = ?",
        date,
        new BigDecimal(value),
        accountId);
  }

  // A second workspace with an account in its Personal Assets portfolio (V19's trigger creates it).
  private UUID foreignPortfolioWithAnAccount() throws Exception {
    UUID workspace = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name, currency) VALUES (?, 'Other', 'CHF')", workspace);
    UUID portfolio;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM portfolio WHERE workspace_id = ? AND is_personal_assets_default")) {
      statement.setObject(1, workspace);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        portfolio = result.getObject(1, UUID.class);
      }
    }
    execute(
        "INSERT INTO account (workspace_id, portfolio_id, name, account_type, native_currency)"
            + " VALUES (?, ?, 'Foreign secret', 'CASH', 'CHF')",
        workspace,
        portfolio);
    return portfolio;
  }

  private void seedFxRate(String base, String quote, String rate) {
    FxRate fxRate = new FxRate();
    fxRate.setBaseCurrency(base);
    fxRate.setQuoteCurrency(quote);
    fxRate.setRateDate(today());
    fxRate.setRate(new BigDecimal(rate));
    fxRate.setSource("ECB"); // app.fx.default-source
    fxRateRepository.save(fxRate);
  }

  private void execute(String sql, Object... parameters) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  // app.business-zone's default: the service's "today" is Zurich's date, not this JVM's.
  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
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
