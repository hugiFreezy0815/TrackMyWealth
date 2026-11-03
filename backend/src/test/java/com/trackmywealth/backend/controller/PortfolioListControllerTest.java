package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.PortfolioListItemResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.PortfolioType;
import com.trackmywealth.backend.dto.PortfolioViewResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
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
 * US-04-05 (#381): {@code GET /api/v1/portfolios} - one test per acceptance criterion of the story,
 * plus its business rule (the totals add up to net worth), the agreement with each portfolio's
 * View, the tab order and the query count.
 *
 * <p>A cash account has a value only once it has an opening balance; opening balances and
 * custom-asset valuations are seeded directly.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortfolioListControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final ParameterizedTypeReference<List<PortfolioListItemResponse>> ROWS =
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
    // The query-count test reads Hibernate's statement count.
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
  void eachPortfolioShowsTheCountAndTotalOfItsActiveAccountsToEveryMember() throws Exception {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "anna@example.com");
    createStandardUser(adminToken, "ben@example.com");
    String anna = login("anna@example.com");
    String ben = login("ben@example.com");
    PortfolioResponse postFinance = createPortfolio(anna, "PostFinance", "CHF");
    UUID privat = createAccount(anna, cash("Privatkonto", postFinance.id())).id();
    UUID spar = createAccount(anna, cash("Sparkonto", postFinance.id())).id();
    AccountSummaryResponse closed = createAccount(anna, cash("Altes Konto", postFinance.id()));
    seedOpeningBalance(privat, "5000.0000");
    seedOpeningBalance(spar, "20000.0000");
    seedOpeningBalance(closed.id(), "999.0000");
    archive(anna, closed.id(), closed.version());

    List<PortfolioListItemResponse> annasList = list(anna);

    PortfolioListItemResponse row = byId(annasList).get(postFinance.id());
    // The archived account counts towards neither the count nor the total.
    assertThat(row.activeAccountCount()).isEqualTo(2);
    assertThat(row.total()).isEqualByComparingTo("25000");
    assertThat(row.totalInWorkspaceCurrency()).isEqualByComparingTo("25000");
    assertThat(row.complete()).isTrue();
    assertThat(row.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    // US-03-06: a member who created none of it sees exactly the same list.
    assertThat(list(ben)).isEqualTo(annasList);
  }

  @Test
  void aMortgageLargerThanThePropertyInPersonalAssetsMakesItsTotalNegative() throws Exception {
    String token = bootstrapAdministrator();
    // No portfolio given: both land in the workspace's Personal Assets portfolio.
    UUID property =
        createAccount(
                token,
                AccountRequests.account("Wohnung", "CUSTOM_ASSET", "CHF")
                    .customAssetType("REAL_ESTATE")
                    .build())
            .id();
    seedValuation(property, today().minusDays(1), "500000.0000");
    createAccount(
        token,
        AccountRequests.account("Hypothek", "MORTGAGE", "CHF")
            .originalPrincipal(new BigDecimal("600000"))
            .interestRatePercent(new BigDecimal("1.2"))
            .build());

    PortfolioListItemResponse personalAssets = list(token).get(0);

    assertThat(personalAssets.personalAssetsDefault()).isTrue();
    assertThat(personalAssets.portfolioType()).isEqualTo(PortfolioType.PERSONAL_ASSETS);
    assertThat(personalAssets.activeAccountCount()).isEqualTo(2);
    assertThat(personalAssets.total()).isEqualByComparingTo("-100000");
    assertThat(personalAssets.totalInWorkspaceCurrency()).isEqualByComparingTo("-100000");
  }

  @Test
  void oneAccountWithAnEstimatedValueMakesThePortfolioEstimated() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse bank = createPortfolio(token, "Bank", "CHF");
    UUID giro = createAccount(token, cash("Giro", bank.id())).id();
    seedOpeningBalance(giro, "1000.0000");
    // Without a statement, the original principal stands in for what is still owed.
    createAccount(
        token,
        AccountRequests.account("Kredit", "LOAN", "CHF")
            .portfolioId(bank.id())
            .originalPrincipal(new BigDecimal("400"))
            .build());

    PortfolioListItemResponse row = byId(list(token)).get(bank.id());

    assertThat(row.valueStatus()).isEqualTo(ValueStatus.ESTIMATED);
    assertThat(row.complete()).isTrue();
    assertThat(row.total()).isEqualByComparingTo("600");
  }

  @Test
  void anEmptyPortfolioIsListedWithCountZeroAndTotalZero() {
    String token = bootstrapAdministrator();
    PortfolioResponse empty = createPortfolio(token, "Empty", "EUR");

    PortfolioListItemResponse row = byId(list(token)).get(empty.id());

    assertThat(row.activeAccountCount()).isZero();
    assertThat(row.total()).isEqualByComparingTo("0");
    assertThat(row.totalInWorkspaceCurrency()).isEqualByComparingTo("0");
    assertThat(row.complete()).isTrue();
    assertThat(row.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(row.portfolioType()).isEqualTo(PortfolioType.BANK);
  }

  // --- Business rule: the totals add up to net worth -----------------------------------------

  @Test
  void thePortfolioTotalsInTheWorkspaceCurrencyAddUpToNetWorth() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse postFinance = createPortfolio(token, "PostFinance", "CHF");
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    UUID privat = createAccount(token, cash("Privatkonto", postFinance.id())).id();
    UUID giro =
        createAccount(
                token, AccountRequests.account("Giro", "CASH", "EUR").portfolioId(dkb.id()).build())
            .id();
    createAccount(
        token,
        AccountRequests.account("Kredit", "LOAN", "CHF")
            .portfolioId(dkb.id())
            .originalPrincipal(new BigDecimal("10000"))
            .build());
    UUID car =
        createAccount(
                token,
                AccountRequests.account("Auto", "CUSTOM_ASSET", "CHF")
                    .customAssetType("VEHICLE")
                    .build())
            .id();
    seedOpeningBalance(privat, "5000.0000");
    seedOpeningBalance(giro, "1000.0000");
    seedValuation(car, today().minusDays(1), "8000.0000");
    seedFxRate("EUR", "CHF", "0.9500000000");

    List<PortfolioListItemResponse> rows = list(token);
    NetWorthResponse netWorth = netWorth(token);

    assertThat(netWorth.complete()).isTrue();
    assertThat(
            rows.stream()
                .map(PortfolioListItemResponse::totalInWorkspaceCurrency)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(netWorth.netWorth())
        .isEqualByComparingTo("3950");
    // DKB in the workspace's CHF: the 1,000 EUR giro at 0.95, less the 10,000 CHF loan.
    PortfolioListItemResponse dkbRow = byId(rows).get(dkb.id());
    assertThat(dkbRow.portfolioCurrency()).isEqualTo("EUR");
    assertThat(dkbRow.workspaceCurrency()).isEqualTo("CHF");
    assertThat(dkbRow.totalInWorkspaceCurrency()).isEqualByComparingTo("-9050");
  }

  // --- Design: same figures as the View, order, fields, authorization ------------------------

  @Test
  void eachRowShowsTheSameTotalsTypeAndStatusAsThePortfoliosView() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    PortfolioResponse viac = createPortfolio(token, "VIAC", "CHF");
    UUID giro =
        createAccount(
                token, AccountRequests.account("Giro", "CASH", "EUR").portfolioId(dkb.id()).build())
            .id();
    UUID dollar =
        createAccount(
                token, AccountRequests.account("USD", "CASH", "USD").portfolioId(dkb.id()).build())
            .id();
    createAccount(
        token,
        AccountRequests.account("3a", "PENSION", "CHF")
            .portfolioId(viac.id())
            .pensionScheme("CH_PILLAR_3A")
            .build());
    seedOpeningBalance(giro, "1000.0000");
    seedOpeningBalance(dollar, "200.0000");
    seedFxRate("EUR", "CHF", "0.9500000000");
    seedFxRate("USD", "EUR", "0.9000000000");
    seedFxRate("USD", "CHF", "0.8500000000");

    List<PortfolioListItemResponse> rows = list(token);

    assertThat(rows).hasSize(3);
    for (PortfolioListItemResponse row : rows) {
      PortfolioViewResponse view = getView(token, row.id());
      assertThat(row.total()).isEqualByComparingTo(view.total());
      assertThat(row.totalInWorkspaceCurrency())
          .isEqualByComparingTo(view.totalInWorkspaceCurrency());
      assertThat(row.complete()).isEqualTo(view.complete());
      assertThat(row.valueStatus()).isEqualTo(view.valueStatus());
      assertThat(row.portfolioType()).isEqualTo(view.portfolioType());
      assertThat(row.portfolioCurrency()).isEqualTo(view.portfolioCurrency());
      assertThat(row.asOf()).isEqualTo(view.asOf());
    }
    PortfolioListItemResponse dkbRow = byId(rows).get(dkb.id());
    assertThat(dkbRow.total()).isEqualByComparingTo("1180");
    assertThat(dkbRow.totalInWorkspaceCurrency()).isEqualByComparingTo("1120");
    // A 3a account without an opening balance has no value yet: VIAC's total says so.
    PortfolioListItemResponse viacRow = byId(rows).get(viac.id());
    assertThat(viacRow.portfolioType()).isEqualTo(PortfolioType.PILLAR_3A);
    assertThat(viacRow.complete()).isFalse();
    assertThat(viacRow.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
  }

  @Test
  void portfoliosAreListedInCreationOrderWithTheirOwnFieldsAsInTheSingleRead() throws Exception {
    String token = bootstrapAdministrator();
    UUID postFinanceEntry =
        queryUuid("SELECT id FROM institution_catalogue WHERE name = 'PostFinance'");
    PortfolioResponse fromCatalogue =
        createPortfolio(
            token,
            new CreatePortfolioRequest(
                postFinanceEntry, "PostFinance 3a", null, null, null, null, null));
    PortfolioResponse custom =
        createPortfolio(
            token,
            new CreatePortfolioRequest(
                null,
                "Wallet",
                "DE",
                "CRYPTO_EXCHANGE",
                null,
                "https://example.com/logo.png",
                "EUR"));
    PortfolioResponse typed = createPortfolio(token, "Typed", "CHF");
    PortfolioResponse typedNow = setType(token, typed, "PENSION_FUND");

    List<PortfolioListItemResponse> rows = list(token);

    assertThat(rows)
        .extracting(PortfolioListItemResponse::id)
        .containsExactly(rows.get(0).id(), fromCatalogue.id(), custom.id(), typed.id());
    assertThat(rows.get(0).personalAssetsDefault()).isTrue();
    assertThat(rows.get(0).institutionType()).isEqualTo("PERSONAL_ASSETS");
    assertSameAs(rows.get(1), fromCatalogue);
    assertThat(rows.get(1).catalogueInstitutionId()).isEqualTo(postFinanceEntry);
    assertSameAs(rows.get(2), custom);
    assertThat(rows.get(2).logoUrl()).isEqualTo("https://example.com/logo.png");
    assertThat(rows.get(2).country()).isEqualTo("DE");
    assertSameAs(rows.get(3), typedNow);
    assertThat(rows.get(3).portfolioType()).isEqualTo(PortfolioType.PENSION_FUND);
    assertThat(rows.get(3).portfolioTypeOverridden()).isTrue();
  }

  @Test
  void anotherWorkspacesPortfoliosAreNeverListedAndAnAnonymousCallerIsRefused() throws Exception {
    String token = bootstrapAdministrator();
    UUID foreign = foreignPortfolioWithAnAccount();

    List<PortfolioListItemResponse> rows = list(token);

    // Only this workspace's own Personal Assets portfolio, empty.
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).id()).isNotEqualTo(foreign);
    assertThat(rows.get(0).activeAccountCount()).isZero();
    anonymousClient()
        .get()
        .uri("/api/v1/portfolios")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aMissingRateIntoTheWorkspaceCurrencyMarksOnlyThatPortfolioIncomplete() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    PortfolioResponse postFinance = createPortfolio(token, "PostFinance", "CHF");
    UUID giro =
        createAccount(
                token, AccountRequests.account("Giro", "CASH", "EUR").portfolioId(dkb.id()).build())
            .id();
    UUID privat = createAccount(token, cash("Privatkonto", postFinance.id())).id();
    seedOpeningBalance(giro, "1000.0000");
    seedOpeningBalance(privat, "500.0000");
    // No EUR rate: the giro has a value in DKB's EUR, but none in the workspace's CHF.

    Map<UUID, PortfolioListItemResponse> rows = byId(list(token));

    PortfolioListItemResponse dkbRow = rows.get(dkb.id());
    assertThat(dkbRow.total()).isEqualByComparingTo("1000");
    assertThat(dkbRow.totalInWorkspaceCurrency()).isEqualByComparingTo("0");
    assertThat(dkbRow.complete()).isFalse();
    assertThat(dkbRow.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    PortfolioListItemResponse postFinanceRow = rows.get(postFinance.id());
    assertThat(postFinanceRow.complete()).isTrue();
    assertThat(postFinanceRow.valueStatus()).isEqualTo(ValueStatus.CURRENT);
  }

  @Test
  void morePortfoliosAndAccountsAreListedWithTheSameNumberOfQueries() throws Exception {
    String token = bootstrapAdministrator();
    createPortfolioWithAccounts(token, "First");
    long forTwo = statementsFor(() -> assertThat(list(token)).hasSize(2));

    for (int i = 0; i < 5; i++) {
      createPortfolioWithAccounts(token, "More " + i);
    }
    long forSeven = statementsFor(() -> assertThat(list(token)).hasSize(7));

    // Not vacuous: the statistics do count this request's queries.
    assertThat(forTwo).isPositive();
    assertThat(forSeven).isEqualTo(forTwo);
  }

  // --- Helpers ---------------------------------------------------------------------------------

  // A cash account with an opening balance, a loan, a vehicle with a valuation and a pillar 3a
  // account: every value source the valuation and the placements read.
  private void createPortfolioWithAccounts(String token, String name) throws Exception {
    UUID portfolio = createPortfolio(token, name, "CHF").id();
    UUID cash = createAccount(token, cash("Cash " + name, portfolio)).id();
    seedOpeningBalance(cash, "100.0000");
    createAccount(
        token,
        AccountRequests.account("Loan " + name, "LOAN", "CHF")
            .portfolioId(portfolio)
            .originalPrincipal(new BigDecimal("1000"))
            .build());
    UUID car =
        createAccount(
                token,
                AccountRequests.account("Car " + name, "CUSTOM_ASSET", "CHF")
                    .portfolioId(portfolio)
                    .customAssetType("VEHICLE")
                    .build())
            .id();
    seedValuation(car, today().minusDays(2), "5000.0000");
    createAccount(
        token,
        AccountRequests.account("3a " + name, "PENSION", "CHF")
            .portfolioId(portfolio)
            .pensionScheme("CH_PILLAR_3A")
            .build());
  }

  private long statementsFor(Runnable action) {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    action.run();
    return statistics.getPrepareStatementCount();
  }

  private static void assertSameAs(PortfolioListItemResponse row, PortfolioResponse single) {
    assertThat(row.id()).isEqualTo(single.id());
    assertThat(row.version()).isEqualTo(single.version());
    assertThat(row.name()).isEqualTo(single.name());
    assertThat(row.catalogueInstitutionId()).isEqualTo(single.catalogueInstitutionId());
    assertThat(row.institutionType()).isEqualTo(single.institutionType());
    assertThat(row.country()).isEqualTo(single.country());
    assertThat(row.logoUrl()).isEqualTo(single.logoUrl());
    assertThat(row.portfolioCurrency()).isEqualTo(single.portfolioCurrency());
    assertThat(row.portfolioType()).isEqualTo(single.portfolioType());
    assertThat(row.portfolioTypeOverridden()).isEqualTo(single.portfolioTypeOverridden());
    assertThat(row.personalAssetsDefault()).isEqualTo(single.personalAssetsDefault());
  }

  private static CreateAccountRequest cash(String name, UUID portfolioId) {
    return AccountRequests.account(name, "CASH", "CHF").portfolioId(portfolioId).build();
  }

  private static Map<UUID, PortfolioListItemResponse> byId(List<PortfolioListItemResponse> rows) {
    return rows.stream()
        .collect(Collectors.toMap(PortfolioListItemResponse::id, Function.identity()));
  }

  private List<PortfolioListItemResponse> list(String token) {
    return client(token)
        .get()
        .uri("/api/v1/portfolios")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ROWS)
        .returnResult()
        .getResponseBody();
  }

  private PortfolioViewResponse getView(String token, UUID portfolioId) {
    return client(token)
        .get()
        .uri("/api/v1/portfolios/" + portfolioId + "/view")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(PortfolioViewResponse.class)
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

  private PortfolioResponse setType(String token, PortfolioResponse portfolio, String type) {
    return client(token)
        .put()
        .uri("/api/v1/portfolios/" + portfolio.id())
        .header("If-Match", "\"" + portfolio.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("name", portfolio.name(), "portfolioType", type))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(PortfolioResponse.class)
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
    return createPortfolio(
        token, new CreatePortfolioRequest(null, name, null, null, null, null, currency));
  }

  private PortfolioResponse createPortfolio(String token, CreatePortfolioRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
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

  // An opening balance a few days back, in the account's own currency.
  private void seedOpeningBalance(UUID accountId, String balance) throws Exception {
    execute(
        "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance,"
            + " currency, is_opening_balance) SELECT workspace_id, id, ?, ?, native_currency,"
            + " TRUE FROM account WHERE id = ?",
        today().minusDays(3),
        new BigDecimal(balance),
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
    UUID portfolio =
        queryUuid(
            "SELECT id FROM portfolio WHERE workspace_id = '"
                + workspace
                + "' AND is_personal_assets_default");
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

  private UUID queryUuid(String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getObject(1, UUID.class);
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
