package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ReconciliationStatusValues;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.PortfolioType;
import com.trackmywealth.backend.dto.PortfolioViewResponse;
import com.trackmywealth.backend.dto.PortfolioViewResponse.AccountRow;
import com.trackmywealth.backend.dto.PortfolioViewResponse.Section;
import com.trackmywealth.backend.dto.ReassignAccountPortfolioRequest;
import com.trackmywealth.backend.dto.SetStatementConfigRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdatePortfolioRequest;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.AccountRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.StatusAssertions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-04-06 (#390): reading and updating a portfolio, its suggested type, and the Portfolio View -
 * one test per acceptance criterion of the story plus the contract details around them (ETag and
 * If-Match, audited 404, type-specific row fields).
 *
 * <p>A cash, savings or pension account has a value only once it has an opening balance (its ledger
 * starts there); opening balances and holdings snapshots are seeded directly.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortfolioViewControllerTest {

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
    registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;
  @Autowired FxRateRepository fxRateRepository;
  @Autowired EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // Plain DELETEs in FK order, as PortfolioControllerTest explains: a TRUNCATE ... CASCADE
    // would reach the shared institution catalogue.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "fx_rate",
              "reconciliation_result",
              "import_batch",
              "snapshot_holding",
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
        if ("account_snapshot".equals(table)) {
          // Only the securities holding() created; reference data stays.
          statement.execute("DELETE FROM security WHERE legal_name = 'Test ETF'");
        }
      }
    }
  }

  // --- Acceptance criteria -----------------------------------------------------------------

  @Test
  void thePostFinanceViewHasASectionPerKindOfAccountInOrderAndItsTypeIsMixed() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse postFinance = createPortfolio(token, "PostFinance", "CHF");
    UUID privateAccount = createAccount(token, cash("Privatkonto", postFinance.id())).id();
    UUID savings =
        createAccount(
                token,
                AccountRequests.account("Sparkonto", "SAVINGS", "CHF")
                    .portfolioId(postFinance.id())
                    .build())
            .id();
    UUID pillar3a = createAccount(token, pillar3a("3a Konto", postFinance.id())).id();
    UUID depot =
        createAccount(
                token,
                AccountRequests.account("Depot", "SECURITIES", "CHF")
                    .portfolioId(postFinance.id())
                    .holdsPositions(true)
                    .build())
            .id();
    seedSnapshot(privateAccount, today().minusDays(10), "5000.0000", true);
    seedSnapshot(savings, today().minusDays(10), "20000.0000", true);
    seedSnapshot(pillar3a, today().minusDays(2), "30000.0000", true);

    PortfolioViewResponse view = getView(token, postFinance.id());

    // The story's text says "four sections" but lists three: two accounts share cash & savings.
    assertThat(view.sections())
        .extracting(Section::section)
        .containsExactly(
            PortfolioSection.CASH_AND_SAVINGS, PortfolioSection.PILLAR_3A, PortfolioSection.DEPOT);
    assertThat(view.sections().get(0).accounts())
        .extracting(AccountRow::accountId)
        .containsExactly(privateAccount, savings);
    assertThat(view.sections().get(0).subtotal()).isEqualByComparingTo("25000");
    assertThat(view.sections().get(1).subtotal()).isEqualByComparingTo("30000");
    assertThat(
            view.sections().stream()
                .map(Section::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(view.total())
        .isEqualByComparingTo("55000");
    assertThat(view.portfolioType()).isEqualTo(PortfolioType.MIXED);
    assertThat(view.portfolioTypeOverridden()).isFalse();
    // A depot's value needs positions valued (EPIC 15): it is left out and the total says so.
    AccountRow depotRow = view.sections().get(2).accounts().get(0);
    assertThat(depotRow.accountId()).isEqualTo(depot);
    assertThat(depotRow.valueKnown()).isFalse();
    assertThat(depotRow.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(view.complete()).isFalse();
    assertThat(view.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(view.sections().get(0).complete()).isTrue();
    assertThat(view.portfolioCurrency()).isEqualTo("CHF");
    assertThat(view.workspaceCurrency()).isEqualTo("CHF");
    assertThat(view.totalInWorkspaceCurrency()).isEqualByComparingTo(view.total());
    assertThat(getPortfolio(token, postFinance.id()).getResponseBody().portfolioType())
        .isEqualTo(PortfolioType.MIXED);
  }

  @Test
  void aSetTypeIsKeptWhenAccountsAreAddedAndClearingItGoesBackToTheSuggestion() {
    String token = bootstrapAdministrator();
    PortfolioResponse viac = createPortfolio(token, "VIAC", "CHF");
    createAccount(token, pillar3a("3a Strategie 1", viac.id()));
    createAccount(token, pillar3a("3a Strategie 2", viac.id()));
    assertThat(getPortfolio(token, viac.id()).getResponseBody().portfolioType())
        .isEqualTo(PortfolioType.PILLAR_3A);

    PortfolioResponse set = updatePortfolio(token, viac.id(), "VIAC", "BANK");
    assertThat(set.portfolioType()).isEqualTo(PortfolioType.BANK);
    assertThat(set.portfolioTypeOverridden()).isTrue();

    createAccount(
        token,
        AccountRequests.account("Depot", "SECURITIES", "CHF").portfolioId(viac.id()).build());
    assertThat(getPortfolio(token, viac.id()).getResponseBody().portfolioType())
        .isEqualTo(PortfolioType.BANK);

    PortfolioResponse cleared = updatePortfolio(token, viac.id(), "VIAC", null);
    assertThat(cleared.portfolioType()).isEqualTo(PortfolioType.MIXED);
    assertThat(cleared.portfolioTypeOverridden()).isFalse();
  }

  @Test
  void aPortfolioWithoutAnInstitutionIsCreatedNamedFreelyAndHoldsAccounts() {
    String token = bootstrapAdministrator();
    PortfolioResponse home = createPortfolio(token, "Home", "CHF");

    assertThat(home.catalogueInstitutionId()).isNull();
    assertThat(home.portfolioType()).isEqualTo(PortfolioType.BANK);
    PortfolioResponse renamed = updatePortfolio(token, home.id(), "Our home", null);
    assertThat(renamed.name()).isEqualTo("Our home");

    AccountSummaryResponse account = createAccount(token, cash("Household", home.id()));
    assertThat(account.portfolioId()).isEqualTo(home.id());
    assertThat(getView(token, home.id()).sections()).hasSize(1);
  }

  @Test
  void personalAssetsWithAPropertyAndALargerMortgageHasANegativeTotal() throws Exception {
    String token = bootstrapAdministrator();
    // No portfolio given: both land in the workspace's Personal Assets portfolio.
    UUID property =
        createAccount(
                token,
                AccountRequests.account("Wohnung", "CUSTOM_ASSET", "CHF")
                    .customAssetType("REAL_ESTATE")
                    .build())
            .id();
    recordValuation(token, property, "500000");
    AccountSummaryResponse mortgage =
        createAccount(
            token,
            AccountRequests.account("Hypothek", "MORTGAGE", "CHF")
                .originalPrincipal(new BigDecimal("600000"))
                .interestRatePercent(new BigDecimal("1.2"))
                .build());
    execute(
        "UPDATE account_mortgage SET linked_asset_account_id = ? WHERE account_id = ?",
        property,
        mortgage.id());

    PortfolioViewResponse view = getView(token, mortgage.portfolioId());

    assertThat(view.personalAssetsDefault()).isTrue();
    assertThat(view.portfolioType()).isEqualTo(PortfolioType.PERSONAL_ASSETS);
    assertThat(view.total()).isEqualByComparingTo("-100000");
    assertThat(view.sections()).hasSize(1);
    Section section = view.sections().get(0);
    assertThat(section.section()).isEqualTo(PortfolioSection.PROPERTY_AND_MORTGAGE);
    assertThat(section.subtotal()).isEqualByComparingTo("-100000");
    AccountRow mortgageRow = section.accounts().get(1);
    assertThat(mortgageRow.nature()).isEqualTo("LIABILITY");
    assertThat(mortgageRow.balance()).isEqualByComparingTo("-600000");
    assertThat(mortgageRow.linkedPropertyAccountId()).isEqualTo(property);
    // The original principal stands in for what is still owed: an approximation.
    assertThat(mortgageRow.valueStatus()).isEqualTo(ValueStatus.ESTIMATED);
    assertThat(section.accounts().get(0).linkedPropertyAccountId()).isNull();
  }

  @Test
  void aPillar3aPortfolioStillTakesADepot() {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "3a only", "CHF");
    updatePortfolio(token, portfolio.id(), "3a only", "PILLAR_3A");

    AccountSummaryResponse depot =
        createAccount(
            token,
            AccountRequests.account("Depot", "SECURITIES", "CHF")
                .portfolioId(portfolio.id())
                .build());

    assertThat(depot.portfolioId()).isEqualTo(portfolio.id());
    assertThat(getPortfolio(token, portfolio.id()).getResponseBody().portfolioType())
        .isEqualTo(PortfolioType.PILLAR_3A);
  }

  @Test
  void onlyTheCatalogueSearchIsLeftUnderInstitutions() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePortfolioRequest(null, "Old path", null, null, null, null, "CHF"))
        .exchange()
        .expectStatus()
        .is4xxClientError();
    client(token).get().uri("/api/v1/institutions/catalogue").exchange().expectStatus().isOk();
  }

  // --- Type follows the accounts --------------------------------------------------------------

  @Test
  void movingArchivingAndRestoringAnAccountChangesTheTypesOfBothPortfolios() {
    String token = bootstrapAdministrator();
    PortfolioResponse bank = createPortfolio(token, "Bank", "CHF");
    PortfolioResponse broker = createPortfolio(token, "Broker", "CHF");
    createAccount(token, cash("Konto", bank.id()));
    AccountSummaryResponse depot =
        createAccount(
            token,
            AccountRequests.account("Depot", "SECURITIES", "CHF").portfolioId(bank.id()).build());
    assertThat(typeOf(token, bank.id())).isEqualTo(PortfolioType.MIXED);

    AccountSummaryResponse moved =
        client(token)
            .post()
            .uri("/api/v1/accounts/" + depot.id() + "/reassign-portfolio")
            .header("If-Match", "\"" + depot.version() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ReassignAccountPortfolioRequest(broker.id()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(typeOf(token, bank.id())).isEqualTo(PortfolioType.BANK);
    assertThat(typeOf(token, broker.id())).isEqualTo(PortfolioType.PORTFOLIO);

    AccountSummaryResponse archived = lifecycle(token, moved.id(), "archive", moved.version());
    assertThat(typeOf(token, broker.id())).isEqualTo(PortfolioType.BANK);

    lifecycle(token, moved.id(), "restore", archived.version());
    assertThat(typeOf(token, broker.id())).isEqualTo(PortfolioType.PORTFOLIO);
  }

  // The suggested type is derived on read: an account write changes neither portfolio's row, so a
  // member who opened the rename before it still saves with the ETag read then - no 412 for a
  // change nobody made to the portfolio.
  @Test
  void accountWritesLeaveThePortfolioVersionSoAnOpenRenameStillSaves() {
    String token = bootstrapAdministrator();
    PortfolioResponse bank = createPortfolio(token, "Bank", "CHF");
    PortfolioResponse broker = createPortfolio(token, "Broker", "CHF");
    String bankEtag = getPortfolio(token, bank.id()).getResponseHeaders().getETag();
    String brokerEtag = getPortfolio(token, broker.id()).getResponseHeaders().getETag();

    AccountSummaryResponse depot =
        createAccount(
            token,
            AccountRequests.account("Depot", "SECURITIES", "CHF").portfolioId(bank.id()).build());
    AccountSummaryResponse moved =
        client(token)
            .post()
            .uri("/api/v1/accounts/" + depot.id() + "/reassign-portfolio")
            .header("If-Match", "\"" + depot.version() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ReassignAccountPortfolioRequest(broker.id()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();
    lifecycle(token, moved.id(), "archive", moved.version());

    EntityExchangeResult<PortfolioResponse> brokerNow = getPortfolio(token, broker.id());
    assertThat(brokerNow.getResponseHeaders().getETag()).isEqualTo(brokerEtag);
    assertThat(getPortfolio(token, bank.id()).getResponseHeaders().getETag()).isEqualTo(bankEtag);
    PortfolioResponse renamed =
        client(token)
            .put()
            .uri("/api/v1/portfolios/" + broker.id())
            .header("If-Match", brokerEtag)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new UpdatePortfolioRequest("Broker AG", null, null, null))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PortfolioResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(renamed.name()).isEqualTo("Broker AG");
    assertThat(renamed.portfolioType()).isEqualTo(PortfolioType.BANK);
  }

  // --- Contract details ------------------------------------------------------------------------

  @Test
  void readingAndUpdatingAPortfolioUseItsStoredVersionAsETag() {
    String token = bootstrapAdministrator();
    PortfolioResponse created = createPortfolio(token, "Yuh", "CHF");

    EntityExchangeResult<PortfolioResponse> read = getPortfolio(token, created.id());
    CurrentVersion.storedEtag(read, dataSource, "portfolio", created.id());

    EntityExchangeResult<PortfolioResponse> updated =
        client(token)
            .put()
            .uri("/api/v1/portfolios/" + created.id())
            .header("If-Match", read.getResponseHeaders().getETag())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new UpdatePortfolioRequest("Yuh", "CH", null, "CRYPTO"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PortfolioResponse.class)
            .returnResult();
    PortfolioResponse body =
        CurrentVersion.storedEtag(updated, dataSource, "portfolio", created.id());
    assertThat(body.version()).isGreaterThan(created.version());
    assertThat(body.country()).isEqualTo("CH");
  }

  @Test
  void updatingWithoutOrWithAStaleIfMatchIsRefused() {
    String token = bootstrapAdministrator();
    PortfolioResponse created = createPortfolio(token, "Yuh", "CHF");
    UpdatePortfolioRequest request = new UpdatePortfolioRequest("Yuh", null, null, null);

    client(token)
        .put()
        .uri("/api/v1/portfolios/" + created.id())
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    client(token)
        .put()
        .uri("/api/v1/portfolios/" + created.id())
        .header("If-Match", "\"" + (created.version() + 5) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
  }

  @Test
  void anUpdateMustSayWhetherTheTypeIsSetAndNameAValidType() {
    String token = bootstrapAdministrator();
    PortfolioResponse created = createPortfolio(token, "Yuh", "CHF");

    for (Map<String, Object> body :
        List.<Map<String, Object>>of(
            Map.of("name", "Yuh"),
            Map.of("name", "Yuh", "portfolioType", "SAVINGS"),
            Map.of("name", " ", "portfolioType", "BANK"))) {
      client(token)
          .put()
          .uri("/api/v1/portfolios/" + created.id())
          .header("If-Match", "\"" + created.version() + "\"")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test
  void anotherWorkspacesPortfolioIsNotFoundLikeAnUnknownOne() throws Exception {
    String token = bootstrapAdministrator();
    UUID foreign = foreignPortfolio();

    for (UUID id : List.of(foreign, UUID.randomUUID())) {
      client(token).get().uri("/api/v1/portfolios/" + id).exchange().expectStatus().isNotFound();
      client(token)
          .get()
          .uri("/api/v1/portfolios/" + id + "/view")
          .exchange()
          .expectStatus()
          .isNotFound();
      client(token)
          .put()
          .uri("/api/v1/portfolios/" + id)
          .header("If-Match", "\"0\"")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new UpdatePortfolioRequest("Taken over", null, null, null))
          .exchange()
          .expectStatus()
          .isNotFound();
    }
    assertThat(queryString("SELECT name FROM portfolio WHERE id = ?", foreign))
        .isEqualTo("Personal Assets");
    // US-28-02: each refusal is the audited 404, for the foreign id as for the unknown one.
    assertThat(deniedPortfolioLookups(foreign)).isPositive();
  }

  @Test
  void anEmptyPortfolioHasAZeroTotalAndNoSections() {
    String token = bootstrapAdministrator();
    PortfolioResponse empty = createPortfolio(token, "Empty", "CHF");

    PortfolioViewResponse view = getView(token, empty.id());

    assertThat(view.sections()).isEmpty();
    assertThat(view.total()).isEqualByComparingTo("0");
    assertThat(view.complete()).isTrue();
    assertThat(view.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(view.conversionRate()).isNull();
    assertThat(view.conversionRateDate()).isNull();
    assertThat(view.lastImport()).isNull();
  }

  @Test
  void aForeignPortfolioShowsBalancesInTheirOwnAndBothTotalCurrencies() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    UUID euro =
        createAccount(
                token, AccountRequests.account("Giro", "CASH", "EUR").portfolioId(dkb.id()).build())
            .id();
    UUID dollar =
        createAccount(
                token, AccountRequests.account("USD", "CASH", "USD").portfolioId(dkb.id()).build())
            .id();
    seedSnapshot(euro, today().minusDays(3), "1000.0000", true);
    seedSnapshot(dollar, today().minusDays(3), "200.0000", true);
    seedFxRate("EUR", "CHF", "0.9500000000");
    seedFxRate("USD", "EUR", "0.9000000000");
    seedFxRate("USD", "CHF", "0.8500000000");

    PortfolioViewResponse view = getView(token, dkb.id());

    AccountRow dollarRow = view.sections().get(0).accounts().get(1);
    assertThat(dollarRow.currency()).isEqualTo("USD");
    assertThat(dollarRow.balance()).isEqualByComparingTo("200");
    assertThat(dollarRow.valueInPortfolioCurrency()).isEqualByComparingTo("180");
    assertThat(view.total()).isEqualByComparingTo("1180");
    assertThat(view.workspaceCurrency()).isEqualTo("CHF");
    assertThat(view.totalInWorkspaceCurrency()).isEqualByComparingTo("1120");
    assertThat(view.conversionRate()).isEqualByComparingTo("0.9500000000");
    assertThat(view.conversionRateDate()).isEqualTo(today());
  }

  // FR-CON-008: the portfolio tabs and the workspace tab show the same money. Every portfolio's
  // total in the workspace currency - the empty Personal Assets one included - adds up to net
  // worth.
  @Test
  void thePortfolioTotalsInTheWorkspaceCurrencyAddUpToNetWorth() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse postFinance = createPortfolio(token, "PostFinance", "CHF");
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    UUID cash = createAccount(token, cash("Privatkonto", postFinance.id())).id();
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
    seedSnapshot(cash, today().minusDays(3), "5000.0000", true);
    seedSnapshot(giro, today().minusDays(3), "1000.0000", true);
    seedFxRate("EUR", "CHF", "0.9500000000");
    UUID personalAssets =
        UUID.fromString(
            queryString(
                "SELECT id::TEXT FROM portfolio WHERE is_personal_assets_default"
                    + " AND workspace_id = (SELECT workspace_id FROM portfolio WHERE id = ?)",
                postFinance.id()));

    BigDecimal sumOfPortfolios =
        Stream.of(personalAssets, postFinance.id(), dkb.id())
            .map(id -> getView(token, id).totalInWorkspaceCurrency())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    NetWorthResponse netWorth =
        client(token)
            .get()
            .uri("/api/v1/net-worth")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(NetWorthResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(netWorth.complete()).isTrue();
    assertThat(sumOfPortfolios)
        .isEqualByComparingTo(netWorth.netWorth())
        .isEqualByComparingTo("-4050");
  }

  // A value without an FX rate into one of the two total currencies is left out of that total; the
  // value status says so as `complete` does, and a row still shows its balance in its own currency.
  @Test
  void aMissingRateMarksTheTotalItIsLeftOutOfAndKeepsTheOwnBalance() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse dkb = createPortfolio(token, "DKB", "EUR");
    UUID giro =
        createAccount(
                token, AccountRequests.account("Giro", "CASH", "EUR").portfolioId(dkb.id()).build())
            .id();
    PortfolioResponse postFinance = createPortfolio(token, "PostFinance", "CHF");
    UUID dollar =
        createAccount(
                token,
                AccountRequests.account("USD", "CASH", "USD").portfolioId(postFinance.id()).build())
            .id();
    seedSnapshot(giro, today().minusDays(3), "1000.0000", true);
    seedSnapshot(dollar, today().minusDays(3), "200.0000", true);
    // No rate at all: EUR has none into the workspace's CHF, USD none into the portfolio's CHF.

    PortfolioViewResponse euroView = getView(token, dkb.id());
    AccountRow giroRow = euroView.sections().get(0).accounts().get(0);
    assertThat(giroRow.valueKnown()).isTrue();
    assertThat(giroRow.valueStatus()).isNotEqualTo(ValueStatus.UNKNOWN);
    assertThat(euroView.total()).isEqualByComparingTo("1000");
    assertThat(euroView.totalInWorkspaceCurrency()).isEqualByComparingTo("0");
    assertThat(euroView.complete()).isFalse();
    assertThat(euroView.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(euroView.sections().get(0).complete()).isFalse();
    assertThat(euroView.sections().get(0).valueStatus()).isEqualTo(ValueStatus.UNKNOWN);

    AccountRow dollarRow = getView(token, postFinance.id()).sections().get(0).accounts().get(0);
    assertThat(dollarRow.valueKnown()).isFalse();
    assertThat(dollarRow.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(dollarRow.valueInPortfolioCurrency()).isNull();
    assertThat(dollarRow.currency()).isEqualTo("USD");
    assertThat(dollarRow.balance()).isEqualByComparingTo("200");
  }

  @Test
  void depotAndCardRowsCarryTheirOwnDetails() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse yuh = createPortfolio(token, "Yuh", "CHF");
    UUID depot =
        createAccount(
                token,
                AccountRequests.account("Depot", "SECURITIES", "CHF")
                    .portfolioId(yuh.id())
                    .holdsPositions(true)
                    .build())
            .id();
    UUID card =
        createAccount(
                token,
                AccountRequests.account("Card", "CREDIT_CARD", "CHF").portfolioId(yuh.id()).build())
            .id();
    UUID older = seedSnapshot(depot, today().minusDays(40), null, false);
    UUID newer = seedSnapshot(depot, today().minusDays(5), null, false);
    for (int i = 0; i < 3; i++) {
      holding(older);
    }
    holding(newer);
    holding(newer);
    // A later balance-only snapshot says nothing about positions: the count stays, with its date.
    seedSnapshot(depot, today().minusDays(1), "100.0000", false);
    client(token)
        .put()
        .uri("/api/v1/accounts/" + card + "/statement-config")
        .headers(CurrentVersion.ifMatchForCard(dataSource, card))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetStatementConfigRequest(today().getDayOfMonth(), 20))
        .exchange()
        .expectStatus()
        .isOk();

    PortfolioViewResponse view = getView(token, yuh.id());

    AccountRow depotRow = view.sections().get(0).accounts().get(0);
    assertThat(view.sections().get(0).section()).isEqualTo(PortfolioSection.DEPOT);
    assertThat(depotRow.positionsCount()).isEqualTo(2);
    assertThat(depotRow.positionsAsOf()).isEqualTo(today().minusDays(5));
    assertThat(depotRow.totalReturn()).isNull();
    assertThat(depotRow.dueDate()).isNull();
    AccountRow cardRow = view.sections().get(1).accounts().get(0);
    assertThat(view.sections().get(1).section()).isEqualTo(PortfolioSection.CARDS);
    // Closed today: the current statement is due 20 days from now.
    assertThat(cardRow.dueDate()).isEqualTo(today().plusDays(20));
    assertThat(cardRow.statementDate()).isEqualTo(today());
    assertThat(cardRow.positionsCount()).isNull();
    assertThat(cardRow.positionsAsOf()).isNull();
    assertThat(cardRow.balance()).isEqualByComparingTo("0");
  }


  @Test
  void portfolioRowsShowOnlyMaskedIdentifiersAndMatchedReconciliation() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse bank = createPortfolio(token, "DKB", "CHF");
    UUID cash = createAccount(token, cash("Giro", bank.id())).id();
    seedSnapshot(cash, today().minusDays(10), "100.0000", true);
    seedSnapshot(cash, today().minusDays(1), "100.0000", false);
    // This fixture is already server-masked; the view must never read an identifier hash.
    execute("UPDATE account SET identifier_masked = ? WHERE id = ?",
        "DE89 •••• 3000", cash);
    PortfolioViewResponse view = getView(token, bank.id());
    AccountRow row = view.sections().get(0).accounts().get(0);
    assertThat(row.maskedIdentifier()).isEqualTo("DE89 •••• 3000");
    assertThat(row.reconciliationStatus()).isEqualTo(ReconciliationStatusValues.RECONCILED);
    assertThat(view.toString()).doesNotContain("370400440532013000");
    assertThat(view.institutionName()).isNull();
    assertThat(view.logoUrl()).isNull();
  }

  @Test
  void aDifferenceAndMissingOpeningAreNotMisreportedAsReconciled() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse bank = createPortfolio(token, "Bank", "CHF");
    UUID difference = createAccount(token, cash("Difference", bank.id())).id();
    UUID withoutOpening = createAccount(token, cash("No opening", bank.id())).id();
    seedSnapshot(difference, today().minusDays(5), "100.0000", true);
    seedSnapshot(difference, today().minusDays(1), "120.0000", false);
    seedSnapshot(withoutOpening, today().minusDays(1), "20.0000", false);

    List<AccountRow> rows = getView(token, bank.id()).sections().get(0).accounts();
    assertThat(rows.get(0).reconciliationStatus())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(rows.get(1).reconciliationStatus())
        .isEqualTo(ReconciliationStatusValues.NOT_RECONCILABLE);
  }

  @Test
  void latestImportUsesCommittedBatchesOnlyAndCountsImportedTransactions() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse bank = createPortfolio(token, "Bank", "CHF");
    UUID account = createAccount(token, cash("Cash", bank.id())).id();
    UUID old = UUID.randomUUID();
    UUID newest = UUID.randomUUID();
    UUID rolledBack = UUID.randomUUID();
    execute(
        "INSERT INTO import_batch (id, workspace_id, account_id, status, source_kind,"
            + " imported_row_count, committed_at) SELECT ?, workspace_id, id, 'COMMITTED',"
            + " 'CSV', 41, ? FROM account WHERE id = ?",
        old, OffsetDateTime.parse("2026-10-18T12:00:00+02:00"), account);
    execute(
        "INSERT INTO import_batch (id, workspace_id, account_id, status, source_kind,"
            + " imported_row_count, committed_at) SELECT ?, workspace_id, id, 'COMMITTED',"
            + " 'DOCUMENT', 12, ? FROM account WHERE id = ?",
        newest, OffsetDateTime.parse("2026-10-19T12:00:00+02:00"), account);
    execute(
        "INSERT INTO import_batch (id, workspace_id, account_id, status, source_kind,"
            + " imported_row_count, committed_at, rolled_back_at, rolled_back_by, rollback_reason)"
            + " SELECT ?, workspace_id, id, 'ROLLED_BACK', 'CSV', 99, ?, now(),"
            + " (SELECT id FROM app_user LIMIT 1), 'test' FROM account WHERE id = ?",
        rolledBack, OffsetDateTime.parse("2026-10-20T12:00:00+02:00"), account);

    PortfolioViewResponse.LastImport last = getView(token, bank.id()).lastImport();
    assertThat(last).isNotNull();
    assertThat(last.fileFormat()).isEqualTo("DOCUMENT");
    assertThat(last.transactionCount()).isEqualTo(12);
    assertThat(last.uploadedAt()).isNotNull();
  }

  @Test
  void moreAccountsDoNotAddPortfolioViewQueries() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse bank = createPortfolio(token, "Bank", "CHF");
    UUID first = createAccount(token, cash("Cash 0", bank.id())).id();
    seedSnapshot(first, today().minusDays(10), "100.0000", true);
    seedSnapshot(first, today().minusDays(1), "100.0000", false);
    long one = statementsFor(() -> assertThat(getView(token, bank.id()).sections().get(0).accounts()).hasSize(1));
    for (int i = 1; i <= 5; i++) {
      UUID account = createAccount(token, cash("Cash " + i, bank.id())).id();
      seedSnapshot(account, today().minusDays(10), "100.0000", true);
      seedSnapshot(account, today().minusDays(1), "100.0000", false);
    }
    long six = statementsFor(() -> assertThat(getView(token, bank.id()).sections().get(0).accounts()).hasSize(6));
    assertThat(one).isPositive();
    assertThat(six).isEqualTo(one);
  }

  private long statementsFor(Runnable action) {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    action.run();
    return statistics.getPrepareStatementCount();
  }

  // --- Review of #438 ------------------------------------------------------------------------

  @Test
  void thePersonalAssetsPortfolioAlwaysKeepsItsType() throws Exception {
    String token = bootstrapAdministrator();
    // No portfolio given: the account lands in the Personal Assets portfolio.
    UUID personalAssets =
        createAccount(token, AccountRequests.account("Bargeld", "CASH", "CHF").build())
            .portfolioId();

    for (String type : List.of("BANK", "CRYPTO", "MIXED")) {
      putPortfolio(
              token,
              personalAssets,
              Map.<String, Object>of("name", "Personal Assets", "portfolioType", type))
          .isEqualTo(HttpStatus.CONFLICT);
    }
    PortfolioResponse renamed = updatePortfolio(token, personalAssets, "Privat", null);
    assertThat(renamed.name()).isEqualTo("Privat");
    assertThat(renamed.portfolioType()).isEqualTo(PortfolioType.PERSONAL_ASSETS);
    assertThat(renamed.portfolioTypeOverridden()).isFalse();

    PortfolioResponse ordinary = createPortfolio(token, "Yuh", "CHF");
    putPortfolio(
            token,
            ordinary.id(),
            Map.<String, Object>of("name", "Yuh", "portfolioType", "PERSONAL_ASSETS"))
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(typeOf(token, ordinary.id())).isEqualTo(PortfolioType.BANK);
  }

  @Test
  void aPortfolioNameIdentifierAndLogoAreBoundedAndTheLogoIsAnHttpsUrl() {
    String token = bootstrapAdministrator();
    String tooLong = "x".repeat(CreatePortfolioRequest.MAX_NAME_LENGTH + 1);

    for (CreatePortfolioRequest refused :
        List.of(
            new CreatePortfolioRequest(null, tooLong, null, null, null, null, "CHF"),
            new CreatePortfolioRequest(
                null,
                "Yuh",
                null,
                null,
                "x".repeat(CreatePortfolioRequest.MAX_IDENTIFIER_LENGTH + 1),
                null,
                "CHF"),
            new CreatePortfolioRequest(null, "Yuh", null, null, null, "javascript:alert(1)", "CHF"),
            new CreatePortfolioRequest(
                null, "Yuh", null, null, null, "data:image/png;base64,AAAA", "CHF"),
            new CreatePortfolioRequest(
                null, "Yuh", null, null, null, "http://example.com/logo.png", "CHF"),
            new CreatePortfolioRequest(
                null,
                "Yuh",
                null,
                null,
                null,
                "https://example.com/" + "a".repeat(CreatePortfolioRequest.MAX_LOGO_URL_LENGTH),
                "CHF"))) {
      client(token)
          .post()
          .uri("/api/v1/portfolios")
          .contentType(MediaType.APPLICATION_JSON)
          .body(refused)
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    PortfolioResponse created =
        client(token)
            .post()
            .uri("/api/v1/portfolios")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new CreatePortfolioRequest(
                    null,
                    "x".repeat(CreatePortfolioRequest.MAX_NAME_LENGTH),
                    null,
                    null,
                    null,
                    "https://example.com/logo.png",
                    "CHF"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(PortfolioResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(created.logoUrl()).isEqualTo("https://example.com/logo.png");

    putPortfolio(
            token, created.id(), Map.<String, Object>of("name", tooLong, "portfolioType", "BANK"))
        .isEqualTo(HttpStatus.BAD_REQUEST);
    putPortfolio(
            token,
            created.id(),
            Map.<String, Object>of(
                "name", "Yuh", "logoUrl", "javascript:alert(1)", "portfolioType", "BANK"))
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  static Stream<Arguments> accountsOfEveryOtherKind() {
    return Stream.of(
        Arguments.of(
            AccountRequests.account("Mandat", "MANAGED_MANDATE", "CHF"),
            PortfolioSection.DEPOT,
            PortfolioType.PORTFOLIO),
        Arguments.of(
            AccountRequests.account("Bitcoin", "CRYPTO", "CHF"),
            PortfolioSection.CRYPTO,
            PortfolioType.CRYPTO),
        Arguments.of(
            AccountRequests.account("Freizuegigkeit", "VESTED_BENEFITS", "CHF"),
            PortfolioSection.PENSION,
            PortfolioType.PENSION_FUND),
        Arguments.of(
            AccountRequests.account("Riester", "PENSION", "EUR").pensionScheme("DE_RIESTER"),
            PortfolioSection.PENSION,
            PortfolioType.PENSION_FUND),
        Arguments.of(
            AccountRequests.account("Kredit", "LOAN", "CHF")
                .originalPrincipal(new BigDecimal("10000")),
            PortfolioSection.OTHER,
            PortfolioType.MIXED),
        Arguments.of(
            AccountRequests.account("Auto", "CUSTOM_ASSET", "CHF").customAssetType("VEHICLE"),
            PortfolioSection.OTHER,
            PortfolioType.MIXED));
  }

  @ParameterizedTest
  @MethodSource("accountsOfEveryOtherKind")
  void everyKindOfAccountHasItsSectionAndFamily(
      AccountRequests.Builder account, PortfolioSection section, PortfolioType type) {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "Alone", "CHF");
    createAccount(token, account.portfolioId(portfolio.id()).build());

    PortfolioViewResponse view = getView(token, portfolio.id());

    assertThat(view.sections()).extracting(Section::section).containsExactly(section);
    assertThat(view.portfolioType()).isEqualTo(type);
  }

  @Test
  void accountsAddedAtTheSameTimeAreAllCountedInTheSuggestedType() throws Exception {
    String token = bootstrapAdministrator();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        PortfolioResponse portfolio = createPortfolio(token, "Race " + round, "CHF");
        CountDownLatch start = new CountDownLatch(1);
        Future<?> cash =
            pool.submit(
                () -> {
                  start.await();
                  return createAccount(token, cash("Konto", portfolio.id()));
                });
        Future<?> depot =
            pool.submit(
                () -> {
                  start.await();
                  return createAccount(
                      token,
                      AccountRequests.account("Depot", "SECURITIES", "CHF")
                          .portfolioId(portfolio.id())
                          .build());
                });
        start.countDown();
        cash.get();
        depot.get();

        // Derived on read, after both committed: both accounts count, whatever order the two
        // requests ran in. A stored type would need a lock for this.
        assertThat(typeOf(token, portfolio.id())).isEqualTo(PortfolioType.MIXED);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private static CreateAccountRequest cash(String name, UUID portfolioId) {
    return AccountRequests.account(name, "CASH", "CHF").portfolioId(portfolioId).build();
  }

  private static CreateAccountRequest pillar3a(String name, UUID portfolioId) {
    return AccountRequests.account(name, "PENSION", "CHF")
        .portfolioId(portfolioId)
        .pensionScheme("CH_PILLAR_3A")
        .build();
  }

  private PortfolioType typeOf(String token, UUID portfolioId) {
    return getPortfolio(token, portfolioId).getResponseBody().portfolioType();
  }

  private AccountSummaryResponse lifecycle(
      String token, UUID accountId, String action, int version) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/" + action)
        .header("If-Match", "\"" + version + "\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
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

  private EntityExchangeResult<PortfolioResponse> getPortfolio(String token, UUID portfolioId) {
    return client(token)
        .get()
        .uri("/api/v1/portfolios/" + portfolioId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(PortfolioResponse.class)
        .returnResult();
  }

  private PortfolioResponse updatePortfolio(
      String token, UUID portfolioId, String name, String portfolioType) {
    int version = getPortfolio(token, portfolioId).getResponseBody().version();
    return client(token)
        .put()
        .uri("/api/v1/portfolios/" + portfolioId)
        .header("If-Match", "\"" + version + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdatePortfolioRequest(name, null, null, portfolioType))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(PortfolioResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private StatusAssertions putPortfolio(String token, UUID portfolioId, Map<String, Object> body) {
    int version = getPortfolio(token, portfolioId).getResponseBody().version();
    return client(token)
        .put()
        .uri("/api/v1/portfolios/" + portfolioId)
        .header("If-Match", "\"" + version + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus();
  }

  private long deniedPortfolioLookups(UUID portfolioId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM authorization_denial_log"
                    + " WHERE requested_entity_type = 'Portfolio' AND requested_entity_id = ?")) {
      statement.setObject(1, portfolioId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
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

  private void recordValuation(String token, UUID accountId, String value) {
    client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateCustomAssetValuationRequest(today(), new BigDecimal(value)))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  // An opening balance or an observed balance (null: a holdings-only snapshot), in the account's
  // own currency.
  private UUID seedSnapshot(UUID accountId, LocalDate date, String balance, boolean opening)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance,"
                    + " currency, is_opening_balance) SELECT workspace_id, id, ?, ?,"
                    + " native_currency, ? FROM account WHERE id = ? RETURNING id")) {
      statement.setObject(1, date);
      statement.setBigDecimal(2, balance == null ? null : new BigDecimal(balance));
      statement.setBoolean(3, opening);
      statement.setObject(4, accountId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private void holding(UUID snapshotId) throws Exception {
    UUID security = UUID.randomUUID();
    execute(
        "INSERT INTO security (id, synthetic_key, legal_name, display_name, denomination_currency)"
            + " VALUES (?, 'MANUAL-' || ?::TEXT, 'Test ETF', 'Test ETF', 'CHF')",
        security,
        security);
    execute(
        "INSERT INTO snapshot_holding (snapshot_id, security_id, quantity) VALUES (?, ?, 1)",
        snapshotId,
        security);
  }

  // A second workspace, created directly: V19's trigger gives it its Personal Assets portfolio.
  private UUID foreignPortfolio() throws Exception {
    UUID workspace = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name, currency) VALUES (?, 'Other', 'CHF')", workspace);
    return UUID.fromString(
        queryString(
            "SELECT id::TEXT FROM portfolio WHERE workspace_id = ? AND is_personal_assets_default",
            workspace));
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

  private String queryString(String sql, UUID parameter) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  // app.business-zone's default: the service's "today" is Zurich's date, not this JVM's.
  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SetupAdministratorRequest(
                "admin@example.com", "correct-horse-battery-staple", "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
