package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.NetWorthSectionResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PortfolioListItemResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.ReassignAccountPortfolioRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.dto.ValueHistoryWarningValues;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
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
 * US-11-05 (#481): {@code GET /api/v1/net-worth} broken down by the Portfolio View's sections - one
 * test per acceptance criterion of the story plus the rules around them (net property, signed
 * liabilities, worst value status, empty workspace).
 *
 * <p>Every test checks that the subtotals add up to {@code netWorth}: the client must never add
 * them up itself (FR-API-010), so the server's two figures must agree. Opening balances are seeded
 * directly, as in {@code PortfolioViewControllerTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NetWorthControllerTest {

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
    // Plain DELETEs in FK order, as PortfolioControllerTest explains: a TRUNCATE ... CASCADE
    // would reach the shared institution catalogue.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(LedgerCleanup.DELETE_ALL_TRANSACTIONS);
      for (String table :
          List.of(
              "fx_rate",
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
      }
    }
  }

  // --- Acceptance criteria -----------------------------------------------------------------

  // AC 1 and 2. The accounts are created in the reverse of the Portfolio View's order, so the
  // sections can only come back in that order if the server sorts them.
  @Test
  void sectionsFollowThePortfolioViewOrderAndAddUpToNetWorth() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "PostFinance", "CHF");
    UUID depot = createAccount(token, depot("Depot", portfolio.id())).id();
    UUID pillar = createAccount(token, pillar3a("Pillar 3a", portfolio.id())).id();
    UUID cash = createAccount(token, cash("Cash", portfolio.id())).id();
    seedOpeningBalance(depot, "50000");
    seedOpeningBalance(pillar, "20000");
    seedOpeningBalance(cash, "10000");

    NetWorthResponse response = netWorth(token, null);

    assertThat(response.sections())
        .extracting(NetWorthSectionResponse::section)
        .containsExactly(
            PortfolioSection.CASH_AND_SAVINGS, PortfolioSection.PILLAR_3A, PortfolioSection.DEPOT);
    assertThat(response.sections())
        .extracting(NetWorthSectionResponse::subtotal)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("10000"), new BigDecimal("20000"), new BigDecimal("50000"));
    assertThat(response.sections())
        .extracting(NetWorthSectionResponse::accountCount)
        .containsExactly(1, 1, 1);
    assertThat(response.sections()).allMatch(NetWorthSectionResponse::complete);
    assertThat(response.sections())
        .extracting(NetWorthSectionResponse::valueStatus)
        .containsOnly(ValueStatus.CURRENT);
    assertThat(response.netWorth()).isEqualByComparingTo("80000");
    assertSectionsAddUpToNetWorth(response);
  }

  // AC 3. A depot's value needs its positions valued (EPIC 15), so a depot holding positions has
  // none yet: it is counted in its section, left out of the subtotal, and marks it incomplete.
  @Test
  void anUnknownValueIsCountedButLeftOutOfItsSectionsSubtotal() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "PostFinance", "CHF");
    UUID valued = createAccount(token, depot("Depot", portfolio.id())).id();
    createAccount(
        token,
        AccountRequests.account("Positions depot", "SECURITIES", "CHF")
            .portfolioId(portfolio.id())
            .holdsPositions(true)
            .build());
    UUID cash = createAccount(token, cash("Cash", portfolio.id())).id();
    seedOpeningBalance(valued, "50000");
    seedOpeningBalance(cash, "10000");

    NetWorthResponse response = netWorth(token, null);

    assertThat(response.sections()).hasSize(2);
    NetWorthSectionResponse cashSection = response.sections().get(0);
    assertThat(cashSection.section()).isEqualTo(PortfolioSection.CASH_AND_SAVINGS);
    assertThat(cashSection.complete()).isTrue();
    assertThat(cashSection.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    NetWorthSectionResponse depotSection = response.sections().get(1);
    assertThat(depotSection.section()).isEqualTo(PortfolioSection.DEPOT);
    assertThat(depotSection.accountCount()).isEqualTo(2);
    assertThat(depotSection.subtotal()).isEqualByComparingTo("50000");
    assertThat(depotSection.complete()).isFalse();
    assertThat(depotSection.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(response.complete()).isFalse();
    assertSectionsAddUpToNetWorth(response);
  }

  // AC 4. The workspace currency is CHF; the subtotal is converted exactly as netWorth is.
  @Test
  void sectionsUseTheRequestedCurrencyAndTheSameFxRateAsNetWorth() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "DKB", "EUR");
    UUID giro =
        createAccount(
                token,
                AccountRequests.account("Giro", "CASH", "EUR").portfolioId(portfolio.id()).build())
            .id();
    seedOpeningBalance(giro, "1000");
    seedFxRate("EUR", "CHF", "0.9500000000");

    NetWorthResponse chf = netWorth(token, null);
    NetWorthResponse eur = netWorth(token, "EUR");

    assertThat(chf.currency()).isEqualTo("CHF");
    assertThat(chf.sections()).hasSize(1);
    assertThat(chf.sections().get(0).subtotal()).isEqualByComparingTo("950");
    assertThat(chf.sections().get(0).complete()).isTrue();
    assertSectionsAddUpToNetWorth(chf);
    assertThat(eur.currency()).isEqualTo("EUR");
    assertThat(eur.sections()).hasSize(1);
    assertThat(eur.sections().get(0).subtotal()).isEqualByComparingTo("1000");
    assertSectionsAddUpToNetWorth(eur);
  }

  // --- Business rules ----------------------------------------------------------------------

  // As in the Portfolio View, property and its mortgage share one section, net of the mortgage.
  @Test
  void thePropertyAndMortgageSectionIsNetOfTheMortgage() {
    String token = bootstrapAdministrator();
    UUID property =
        createAccount(
                token,
                AccountRequests.account("Home", "CUSTOM_ASSET", "CHF")
                    .customAssetType("REAL_ESTATE")
                    .build())
            .id();
    recordValuation(token, property, today(), "500000");
    createAccount(
        token,
        AccountRequests.account("Mortgage", "MORTGAGE", "CHF")
            .originalPrincipal(new BigDecimal("600000"))
            .interestRatePercent(new BigDecimal("1.2"))
            .build());

    NetWorthResponse response = netWorth(token, null);

    assertThat(response.sections()).hasSize(1);
    NetWorthSectionResponse propertySection = response.sections().get(0);
    assertThat(propertySection.section()).isEqualTo(PortfolioSection.PROPERTY_AND_MORTGAGE);
    assertThat(propertySection.subtotal()).isEqualByComparingTo("-100000");
    assertThat(propertySection.accountCount()).isEqualTo(2);
    assertThat(propertySection.complete()).isTrue();
    assertSectionsAddUpToNetWorth(response);
  }

  // FR-CC-003: a card's outstanding balance is subtracted, in its own section as in netWorth.
  @Test
  void aCardSectionIsNegative() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "Yuh", "CHF");
    UUID cash = createAccount(token, cash("Cash", portfolio.id())).id();
    UUID card =
        createAccount(
                token,
                AccountRequests.account("Card", "CREDIT_CARD", "CHF")
                    .portfolioId(portfolio.id())
                    .build())
            .id();
    seedOpeningBalance(cash, "10000");
    seedOpeningBalance(card, "1500");

    NetWorthResponse response = netWorth(token, null);

    assertThat(response.sections())
        .extracting(NetWorthSectionResponse::section)
        .containsExactly(PortfolioSection.CASH_AND_SAVINGS, PortfolioSection.CARDS);
    assertThat(response.sections().get(1).subtotal()).isEqualByComparingTo("-1500");
    assertThat(response.netWorth()).isEqualByComparingTo("8500");
    assertSectionsAddUpToNetWorth(response);
  }

  // A section's status is the worst of its accounts': one value older than
  // app.valuation.snapshot-stale-after (P45D) makes the whole section STALE, still complete.
  @Test
  void aSectionTakesTheWorstValueStatusOfItsAccounts() {
    String token = bootstrapAdministrator();
    UUID gold = createAccount(token, customAsset("Gold", "PRECIOUS_METAL")).id();
    UUID car = createAccount(token, customAsset("Car", "VEHICLE")).id();
    recordValuation(token, gold, today(), "12000");
    recordValuation(token, car, today().minusDays(100), "8000");

    NetWorthResponse response = netWorth(token, null);

    assertThat(response.sections()).hasSize(1);
    NetWorthSectionResponse other = response.sections().get(0);
    assertThat(other.section()).isEqualTo(PortfolioSection.OTHER);
    assertThat(other.accountCount()).isEqualTo(2);
    assertThat(other.subtotal()).isEqualByComparingTo("20000");
    assertThat(other.complete()).isTrue();
    assertThat(other.valueStatus()).isEqualTo(ValueStatus.STALE);
    assertSectionsAddUpToNetWorth(response);
  }

  @Test
  void aWorkspaceWithoutAccountsHasNoSections() {
    String token = bootstrapAdministrator();

    NetWorthResponse response = netWorth(token, null);

    assertThat(response.sections()).isEmpty();
    assertThat(response.netWorth()).isEqualByComparingTo("0");
    assertThat(response.complete()).isTrue();
  }

  // --- US-11-06 history acceptance checks -----------------------------------------------------

  @Test
  void yearlyHistoryIsBoundedAndItsFinalPointEqualsTheCurrentNetWorth() {
    String token = bootstrapAdministrator();

    ValueHistoryResponse history = history(token, "1Y");
    NetWorthResponse current = netWorth(token, null);

    assertThat(history.points()).hasSizeLessThanOrEqualTo(54);
    assertThat(history.points().get(history.points().size() - 1).date()).isEqualTo(today());
    assertThat(history.points().get(history.points().size() - 1).value())
        .isEqualByComparingTo(current.netWorth());
    assertThat(history.change().percent()).isNull();
    assertThat(history.complete()).isTrue();
    assertThat(history.sections()).isEmpty();
  }

  @Test
  void declaredOpeningDateRemovesYearlyInceptionWarningAndEarlierEvidenceStillCounts()
      throws Exception {
    String token = bootstrapAdministrator();
    LocalDate yearStart = today().minusYears(1);
    UUID baseline =
        createAccount(
                token,
                AccountRequests.account("Established", "CASH", "CHF")
                    .openedAt(yearStart.minusDays(30))
                    .build())
            .id();
    seedOpeningBalance(baseline, "2000", yearStart.minusDays(30));
    AccountSummaryResponse recent =
        createAccount(token, AccountRequests.account("Recent", "CASH", "CHF").build());
    LocalDate evidence = today().minusDays(30);
    seedOpeningBalance(recent.id(), "1000", evidence);

    ValueHistoryResponse uncertain = history(token, "1Y");
    assertThat(uncertain.warningCodes()).contains(ValueHistoryWarningValues.INCEPTION_UNCERTAIN);
    assertThat(uncertain.points().get(0).value()).isEqualByComparingTo("2000");
    // #607 AC 1: Recent enters the tracked history with its first balance - a capital movement,
    // no gain. The uncertain start level no longer hides the return.
    assertThat(uncertain.change().capitalMovements()).isEqualByComparingTo("1000");
    assertThat(uncertain.change().returnPercent()).isEqualByComparingTo("0.00");
    assertThat(uncertain.change().returnFrom()).isEqualTo(yearStart);
    assertThat(uncertain.sections())
        .singleElement()
        .satisfies(section -> assertThat(section.change().returnPercent()).isZero());

    for (LocalDate openedAt : List.of(yearStart.plusDays(1), evidence.plusDays(14))) {
      recent =
          client(token)
              .put()
              .uri("/api/v1/accounts/" + recent.id())
              .header("If-Match", "\"" + recent.version() + "\"")
              .contentType(MediaType.APPLICATION_JSON)
              .body(new UpdateAccountRequest("Recent", "CASH", "CHF", null, openedAt, null))
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(AccountSummaryResponse.class)
              .returnResult()
              .getResponseBody();
      ValueHistoryResponse known = history(token, "1Y");
      assertThat(known.warningCodes())
          .doesNotContain(ValueHistoryWarningValues.INCEPTION_UNCERTAIN);
      assertThat(known.points().get(0).value()).isEqualByComparingTo("2000");
      assertThat(known.change().percent()).isEqualByComparingTo("50");
      // An opening date before the first balance (VALUE_UNKNOWN until then) or after it does not
      // move the tracking start: the return stays 0 %.
      assertThat(known.change().returnPercent()).isEqualByComparingTo("0.00");
      assertThat(known.change().returnFrom()).isEqualTo(yearStart);
      LocalDate lastEvidenceDay = evidence.plusDays(13);
      assertThat(known.points())
          .filteredOn(point -> !point.date().isBefore(evidence))
          .filteredOn(point -> !point.date().isAfter(lastEvidenceDay))
          .extracting(ValueHistoryResponse.Point::value)
          .anyMatch(value -> value.compareTo(new BigDecimal("3000")) == 0);
    }
  }

  // #607 AC 2: 100 / (2,000 + 30/365 × 1,000) = 4.80 %; the gold section alone gained 5 %.
  @Test
  void periodReturnWeightsAnAccountEnteringTrackingByTheTimeItWasHeld() throws Exception {
    String token = bootstrapAdministrator();
    LocalDate yearStart = today().minusYears(1);
    UUID gold = createAccount(token, customAsset("Gold", "PRECIOUS_METAL")).id();
    recordValuation(token, gold, yearStart.minusDays(10), "2000.00");
    recordValuation(token, gold, today(), "2100.00");
    UUID cash = createAccount(token, AccountRequests.account("Cash", "CASH", "CHF").build()).id();
    seedOpeningBalance(cash, "1000", today().minusDays(30));

    ValueHistoryResponse history = history(token, "1Y");

    assertThat(history.change().amount()).isEqualByComparingTo("1100");
    assertThat(history.change().returnPercent()).isEqualByComparingTo("4.80");
    assertThat(history.change().returnFrom()).isEqualTo(yearStart);
    assertThat(history.change().percent()).isEqualByComparingTo("55");
    assertThat(history.sections())
        .extracting(section -> section.change().returnPercent())
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactlyInAnyOrder(new BigDecimal("0.00"), new BigDecimal("5.00"));
  }

  // #607 AC 3: the workspace is tracked only since its first balance, so the return starts there.
  @Test
  void periodReturnStartsAtTheEarliestTrackingStartWhenThatIsInsideThePeriod() throws Exception {
    String token = bootstrapAdministrator();
    LocalDate firstBalance = today().minusDays(34);
    UUID cash = createAccount(token, AccountRequests.account("Cash", "CASH", "CHF").build()).id();
    seedOpeningBalance(cash, "5000", firstBalance);

    ValueHistoryResponse history = history(token, "1Y");

    assertThat(history.from()).isEqualTo(today().minusYears(1));
    assertThat(history.change().returnFrom()).isEqualTo(firstBalance);
    assertThat(history.change().returnPercent()).isEqualByComparingTo("0.00");
  }

  // #607 AC 4: salary and spending are net saving, a flow - the wealth change is +2,000, the
  // return 0 %.
  @Test
  void netSavingChangesWealthButNotTheReturn() throws Exception {
    String token = bootstrapAdministrator();
    UUID cash = createAccount(token, AccountRequests.account("Cash", "CASH", "CHF").build()).id();
    seedOpeningBalance(cash, "10000", today().minusYears(1).minusDays(5));
    seedBooking(cash, "INCOME", today().minusDays(200), "5000");
    seedBooking(cash, "EXPENSE", today().minusDays(100), "-3000");

    ValueHistoryResponse history = history(token, "1Y");

    assertThat(history.change().amount()).isEqualByComparingTo("2000");
    assertThat(history.change().contributions()).isEqualByComparingTo("2000");
    assertThat(history.change().returnPercent()).isEqualByComparingTo("0.00");
  }

  // #607 AC 7: an account that starts at an opening balance of 0 has nothing to earn a return on.
  @Test
  void periodReturnIsUnknownForADenominatorOfZero() throws Exception {
    String token = bootstrapAdministrator();
    UUID cash = createAccount(token, AccountRequests.account("Cash", "CASH", "CHF").build()).id();
    seedOpeningBalance(cash, "0", today().minusDays(3));

    ValueHistoryResponse history = history(token, "1W");

    assertThat(history.change().returnPercent()).isNull();
    assertThat(history.change().returnFrom()).isEqualTo(today().minusDays(3));
  }

  // #607 (PO 2026-11-20): interest and fees are what the money earned or cost - return. A deposit
  // of unknown origin and a tax bill from a cash account are flows.
  // 80 / (10,000 + 150/365 × 5,000 − 50/365 × 1,000) = 0.67 %.
  @Test
  void interestAndFeesAreReturnWhileDepositsAndTaxBillsAreFlows() throws Exception {
    String token = bootstrapAdministrator();
    UUID cash = createAccount(token, AccountRequests.account("Cash", "CASH", "CHF").build()).id();
    seedOpeningBalance(cash, "10000", today().minusYears(1).minusDays(5));
    seedBooking(cash, "INTEREST", today().minusDays(200), "100");
    seedBooking(cash, "DEPOSIT", today().minusDays(150), "5000");
    seedBooking(cash, "FEE", today().minusDays(100), "-20");
    seedBooking(cash, "TAX", today().minusDays(50), "-1000");

    ValueHistoryResponse history = history(token, "1Y");

    assertThat(history.change().amount()).isEqualByComparingTo("4080");
    assertThat(history.change().returnPercent()).isEqualByComparingTo("0.67");
    // The breakdown keeps #477's net saving: interest in, fee and tax out, the deposit not in it.
    assertThat(history.change().contributions()).isEqualByComparingTo("-920");
  }

  // #607: a card without an opening balance is tracked from its inception (its first booking
  // here), so its fee lowers the return: −20 / (10,000 − 200) = −0.20 %.
  @Test
  void aCardWithoutAnOpeningBalanceIsTrackedFromItsInception() throws Exception {
    String token = bootstrapAdministrator();
    LocalDate yearStart = today().minusYears(1);
    UUID cash = createAccount(token, AccountRequests.account("Cash", "CASH", "CHF").build()).id();
    seedOpeningBalance(cash, "10000", yearStart.minusDays(5));
    UUID card =
        createAccount(token, AccountRequests.account("Visa", "CREDIT_CARD", "CHF").build()).id();
    seedBooking(card, "CREDIT_CARD_PURCHASE", yearStart.minusDays(30), "-200");
    seedBooking(card, "FEE", today().minusDays(50), "-20");

    ValueHistoryResponse history = history(token, "1Y");

    assertThat(history.change().returnFrom()).isEqualTo(yearStart);
    assertThat(history.change().returnPercent()).isEqualByComparingTo("-0.20");
  }

  // #607: each flow converts at its own day's rate, each value at its day's: the EUR 1,000 held
  // all year gains CHF 200 of FX. 200 / (1,000 + 100/365 × 600) = 17.18 %.
  @Test
  void aForeignCurrencyFlowConvertsAtItsBookingDaysRate() throws Exception {
    String token = bootstrapAdministrator();
    LocalDate yearStart = today().minusYears(1);
    seedFxRateOn("EUR", "CHF", yearStart, "1.00");
    seedFxRateOn("EUR", "CHF", today().minusDays(100), "1.20");
    seedFxRateOn("EUR", "CHF", today(), "1.20");
    UUID euro = createAccount(token, AccountRequests.account("Euro", "CASH", "EUR").build()).id();
    seedOpeningBalance(euro, "1000", yearStart.minusDays(5));
    seedBooking(euro, "INCOME", today().minusDays(100), "500");

    ValueHistoryResponse history = history(token, "1Y");

    assertThat(history.change().returnPercent()).isEqualByComparingTo("17.18");
  }

  // #607 AC 3 for a portfolio: its return starts with its own first tracked account, not the
  // workspace's.
  @Test
  void aPortfoliosReturnStartsAtItsOwnEarliestTrackingStart() throws Exception {
    String token = bootstrapAdministrator();
    LocalDate yearStart = today().minusYears(1);
    UUID gold = createAccount(token, customAsset("Gold", "PRECIOUS_METAL")).id();
    recordValuation(token, gold, yearStart.minusDays(10), "2000.00");
    UUID portfolio = createPortfolio(token, "Savings", "CHF").id();
    UUID cash = createAccount(token, cash("Savings cash", portfolio)).id();
    seedOpeningBalance(cash, "5000", today().minusDays(34));

    ValueHistoryResponse portfolioHistory = portfolioHistory(token, portfolio, "1Y");
    ValueHistoryResponse workspaceHistory = history(token, "1Y");

    assertThat(portfolioHistory.change().returnFrom()).isEqualTo(today().minusDays(34));
    assertThat(portfolioHistory.change().returnPercent()).isEqualByComparingTo("0.00");
    assertThat(workspaceHistory.change().returnFrom()).isEqualTo(yearStart);
  }

  @Test
  void historicalSnapshotValuesAreStepsAndEstimatedBetweenObservations() {
    String token = bootstrapAdministrator();
    UUID gold = createAccount(token, customAsset("Gold", "PRECIOUS_METAL")).id();
    recordValuation(token, gold, today().minusDays(25), "10000.00");
    recordValuation(token, gold, today().minusDays(5), "12000.00");

    ValueHistoryResponse history = history(token, "1M");

    var between =
        history.points().stream()
            .filter(point -> point.date().equals(today().minusDays(15)))
            .findFirst()
            .orElseThrow();
    assertThat(between.value()).isEqualByComparingTo("10000");
    assertThat(between.estimated()).isTrue();
    assertThat(history.points().get(history.points().size() - 1).value())
        .isEqualByComparingTo("12000");
    assertThat(history.complete()).isFalse();
    assertThat(history.warnings()).anyMatch(warning -> warning.contains("Gold"));
    assertThat(history.warningCodes())
        .containsExactly(ValueHistoryWarningValues.INCEPTION_UNCERTAIN);
  }

  @Test
  void anAccountWithoutAnFxRateIsAValueUnknownCodeWithoutItsName() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "DKB", "EUR");
    UUID cash =
        createAccount(
                token,
                AccountRequests.account("Private euro cash", "CASH", "EUR")
                    .portfolioId(portfolio.id())
                    .build())
            .id();
    seedOpeningBalance(cash, "1000.00");

    ValueHistoryResponse history = history(token, "1W");

    assertThat(history.complete()).isFalse();
    assertThat(history.warnings()).anyMatch(w -> w.startsWith("Private euro cash: value unknown"));
    assertThat(history.warningCodes())
        .contains(ValueHistoryWarningValues.VALUE_UNKNOWN)
        .noneMatch(code -> code.contains("Private"));
    // #607 AC 6: a value unknown inside the tracked history leaves the return unknown.
    assertThat(history.change().returnPercent()).isNull();
    assertThat(history.change().returnFrom()).isNotNull();
  }

  @Test
  void portfolioHistoryUsesItsOwnCurrencyWhileWorkspaceHistoryUsesHistoricalFx() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "DKB", "EUR");
    UUID cash =
        createAccount(
                token,
                AccountRequests.account("Euro cash", "CASH", "EUR")
                    .portfolioId(portfolio.id())
                    .build())
            .id();
    seedOpeningBalance(cash, "1000.00");
    seedFxRate("EUR", "CHF", "0.9500000000");

    ValueHistoryResponse portfolioHistory =
        client(token)
            .get()
            .uri("/api/v1/portfolios/" + portfolio.id() + "/history?period=1W")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ValueHistoryResponse.class)
            .returnResult()
            .getResponseBody();
    ValueHistoryResponse workspaceHistory = history(token, "1W");

    assertThat(portfolioHistory.currency()).isEqualTo("EUR");
    assertThat(workspaceHistory.currency()).isEqualTo("CHF");
    assertThat(portfolioHistory.points().get(portfolioHistory.points().size() - 1).value())
        .isEqualByComparingTo("1000");
    assertThat(workspaceHistory.points().get(workspaceHistory.points().size() - 1).value())
        .isEqualByComparingTo("950");
    assertThat(portfolioHistory.sections()).isEmpty();
    assertThat(workspaceHistory.sections())
        .extracting(ValueHistoryResponse.SectionChange::section)
        .contains(PortfolioSection.CASH_AND_SAVINGS);
  }

  @Test
  void newlyCreatedAccountWithoutHistoricalEvidenceDoesNotAppearBeforeCreation() throws Exception {
    String token = bootstrapAdministrator();
    UUID cash =
        createAccount(token, cash("New cash", createPortfolio(token, "Bank", "CHF").id())).id();
    seedOpeningBalance(cash, "2000");

    ValueHistoryResponse history = history(token, "1M");
    // Backdated opening snapshots are legitimate evidence, but the value must not be
    // fabricated before the first dated source.
    LocalDate earliest = today().minusDays(3);
    assertThat(
            history.points().stream()
                .filter(point -> point.date().isBefore(earliest))
                .map(ValueHistoryResponse.Point::value))
        .allSatisfy(value -> assertThat(value).isEqualByComparingTo("0"));
    assertThat(history.points().get(history.points().size() - 1).value())
        .isEqualByComparingTo("2000");
  }

  @Test
  void migratedBaselineBeforePeriodIsAuthoritativeForRecentHistory() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "Migrated portfolio", "CHF");
    UUID accountId = createAccount(token, cash("Migrated cash", portfolio.id())).id();
    seedOpeningBalance(accountId, "2000", today().minusDays(21));
    simulateMigratedBaseline(accountId, today().minusDays(14));

    ValueHistoryResponse response = portfolioHistory(token, portfolio.id(), "1W");

    assertThat(response.complete()).isTrue();
    assertThat(response.warnings()).noneMatch(w -> w.contains("may have been restated"));
    assertThat(response.warningCodes())
        .doesNotContain(ValueHistoryWarningValues.LIFECYCLE_UNVERIFIED);
    assertThat(valueOn(response, today())).isEqualByComparingTo("2000");
  }

  @Test
  void migratedBaselineStillWarnsBeforeTrackingBegan() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "Migration uncertainty", "CHF");
    UUID accountId = createAccount(token, cash("Old cash", portfolio.id())).id();
    seedOpeningBalance(accountId, "2000", today().minusDays(21));
    simulateMigratedBaseline(accountId, today().minusDays(3));

    ValueHistoryResponse response = portfolioHistory(token, portfolio.id(), "1W");

    assertThat(response.complete()).isFalse();
    assertThat(response.warnings()).anyMatch(w -> w.contains("may have been restated"));
    assertThat(response.warningCodes())
        .containsExactly(ValueHistoryWarningValues.LIFECYCLE_UNVERIFIED);
    assertThat(valueOn(response, today())).isEqualByComparingTo("2000");
  }

  @Test
  void unrelatedPortfolioInceptionCannotInvalidateTheRequestedPortfolio() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolioA = createPortfolio(token, "Established", "CHF");
    PortfolioResponse portfolioB = createPortfolio(token, "Recent", "CHF");
    UUID known = createAccount(token, cash("Established cash", portfolioA.id())).id();
    UUID uncertain = createAccount(token, cash("Newly observed cash", portfolioB.id())).id();
    seedOpeningBalance(known, "3000", today().minusDays(14));
    seedOpeningBalance(uncertain, "1000", today().minusDays(3));

    ValueHistoryResponse established = portfolioHistory(token, portfolioA.id(), "1W");
    ValueHistoryResponse recent = portfolioHistory(token, portfolioB.id(), "1W");

    assertThat(established.complete()).isTrue();
    assertThat(established.warnings()).noneMatch(w -> w.contains("Newly observed cash"));
    assertThat(valueOn(established, today())).isEqualByComparingTo("3000");
    assertThat(recent.complete()).isFalse();
    assertThat(recent.warnings()).anyMatch(w -> w.contains("inception before first activity"));
    // The codes are stable and never carry an account name; the sentences above do.
    assertThat(recent.warningCodes())
        .containsExactly(ValueHistoryWarningValues.INCEPTION_UNCERTAIN);
    assertThat(established.warningCodes()).isEmpty();
  }

  @Test
  void maximumPortfolioHistoryStartsWithHistoricalMembershipNotOtherActivity() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse older = createPortfolio(token, "Older", "CHF");
    PortfolioResponse newer = createPortfolio(token, "Newer", "CHF");
    AccountSummaryResponse oldAccount = createAccount(token, cash("Old", older.id()));
    UUID newAccount = createAccount(token, cash("New", newer.id())).id();
    LocalDate oldStart = today().minusDays(400);
    LocalDate newStart = today().minusDays(5);
    seedOpeningBalance(oldAccount.id(), "1000", oldStart);
    seedOpeningBalance(newAccount, "2000", newStart);

    // A later reassignment must not pull the destination portfolio back to the original
    // account's much earlier inception.
    client(token)
        .post()
        .uri("/api/v1/accounts/" + oldAccount.id() + "/reassign-portfolio")
        .header("If-Match", "\"" + oldAccount.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReassignAccountPortfolioRequest(newer.id()))
        .exchange()
        .expectStatus()
        .isOk();

    ValueHistoryResponse oldHistory = portfolioHistory(token, older.id(), "MAX");
    ValueHistoryResponse newHistory = portfolioHistory(token, newer.id(), "MAX");

    assertThat(oldHistory.from()).isEqualTo(oldStart);
    assertThat(newHistory.from()).isEqualTo(newStart);
    assertThat(oldHistory.points().get(0).date()).isEqualTo(oldStart);
    assertThat(newHistory.points().get(0).date()).isEqualTo(newStart);
    assertThat(oldHistory.points()).hasSizeLessThanOrEqualTo(400);
    assertThat(newHistory.points()).hasSizeLessThanOrEqualTo(400);
  }

  @Test
  void foreignWorkspacePortfolioHistoryReturnsNotFound() throws Exception {
    String token = bootstrapAdministrator();
    UUID foreignWorkspace = UUID.randomUUID();
    UUID foreignPortfolio;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement context =
              connection.prepareStatement(
                  "SELECT set_config('app.current_workspace_id', ?, true)");
          PreparedStatement insert =
              connection.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, ?)")) {
        context.setString(1, foreignWorkspace.toString());
        context.execute();
        insert.setObject(1, foreignWorkspace);
        insert.setString(2, "Foreign history workspace");
        insert.executeUpdate();
      }
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT id FROM portfolio WHERE workspace_id = ? AND is_personal_assets_default = true")) {
        select.setObject(1, foreignWorkspace);
        try (ResultSet rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          foreignPortfolio = rows.getObject(1, UUID.class);
        }
      }
      connection.commit();
    }

    client(token)
        .get()
        .uri("/api/v1/portfolios/" + foreignPortfolio + "/history?period=1M")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void historyUsesDifferentFxRatesOnDifferentDates() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "EUR Bank", "EUR");
    UUID cash =
        createAccount(
                token,
                AccountRequests.account("EUR savings", "CASH", "EUR")
                    .portfolioId(portfolio.id())
                    .build())
            .id();
    seedOpeningBalance(cash, "1000");
    seedFxRateOn("EUR", "CHF", today().minusDays(2), "1.00");
    seedFxRateOn("EUR", "CHF", today(), "1.20");

    ValueHistoryResponse history = history(token, "1W");
    assertThat(
            history.points().stream()
                .filter(point -> point.date().equals(today().minusDays(2)))
                .findFirst()
                .orElseThrow()
                .value())
        .isEqualByComparingTo("1000");
    assertThat(history.points().get(history.points().size() - 1).value())
        .isEqualByComparingTo("1200");
  }

  @Test
  void historicalPortfolioReassignmentPreservesThePreviousPortfolioValues() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse source = createPortfolio(token, "Source", "CHF");
    PortfolioResponse destination = createPortfolio(token, "Destination", "CHF");
    AccountSummaryResponse account = createAccount(token, cash("Moving account", source.id()));
    seedOpeningBalance(account.id(), "5000", today().minusDays(9));

    client(token)
        .post()
        .uri("/api/v1/accounts/" + account.id() + "/reassign-portfolio")
        .header("If-Match", "\"" + account.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReassignAccountPortfolioRequest(destination.id()))
        .exchange()
        .expectStatus()
        .isOk();

    ValueHistoryResponse oldPortfolio = portfolioHistory(token, source.id(), "1W");
    ValueHistoryResponse newPortfolio = portfolioHistory(token, destination.id(), "1W");
    assertThat(valueOn(oldPortfolio, today().minusDays(2))).isEqualByComparingTo("5000");
    assertThat(valueOn(oldPortfolio, today())).isEqualByComparingTo("0");
    assertThat(valueOn(newPortfolio, today().minusDays(2))).isEqualByComparingTo("0");
    assertThat(valueOn(newPortfolio, today())).isEqualByComparingTo("5000");
    assertThat(oldPortfolio.change().capitalMovements()).isEqualByComparingTo("-5000");
    assertThat(newPortfolio.change().capitalMovements()).isEqualByComparingTo("5000");
    assertThat(oldPortfolio.change().marketAndFx()).isEqualByComparingTo("0");
    assertThat(newPortfolio.change().marketAndFx()).isEqualByComparingTo("0");
  }

  @Test
  void archiveAndRestoreRetainThePreviousArchivedInterval() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "History", "CHF");
    AccountSummaryResponse account =
        createAccount(token, cash("Temporarily archived", portfolio.id()));
    seedOpeningBalance(account.id(), "5000");

    AccountSummaryResponse archived =
        client(token)
            .post()
            .uri("/api/v1/accounts/" + account.id() + "/archive")
            .header("If-Match", "\"" + account.version() + "\"")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    // Backdate the two recorded state events to simulate an archive interval spanning
    // business days; the HTTP endpoints still perform the actual transitions.
    backdateAccountState(account.id(), 0, today().minusDays(4));
    backdateAccountState(account.id(), 1, today().minusDays(2));

    client(token)
        .post()
        .uri("/api/v1/accounts/" + account.id() + "/restore")
        .header("If-Match", "\"" + archived.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();

    ValueHistoryResponse history = portfolioHistory(token, portfolio.id(), "1W");
    assertThat(valueOn(history, today().minusDays(3))).isEqualByComparingTo("5000");
    assertThat(valueOn(history, today().minusDays(1))).isEqualByComparingTo("0");
    assertThat(valueOn(history, today())).isEqualByComparingTo("5000");
    assertThat(history.change().marketAndFx()).isEqualByComparingTo("0");
    // Archiving takes the account's value out of the portfolio and restoring brings it back.
    assertThat(history.change().returnPercent()).isEqualByComparingTo("0.00");
  }

  @Test
  void internalTransferBetweenPortfoliosDoesNotBecomeMarketPerformance() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse source = createPortfolio(token, "Sending portfolio", "CHF");
    PortfolioResponse destination = createPortfolio(token, "Receiving portfolio", "CHF");
    UUID from = createAccount(token, cash("Source cash", source.id())).id();
    UUID to = createAccount(token, cash("Destination cash", destination.id())).id();
    seedOpeningBalance(from, "7000", today().minusDays(9));
    seedOpeningBalance(to, "3000", today().minusDays(9));

    seedTransfer(from, to, "-5000");
    seedTransfer(to, from, "5000");

    ValueHistoryResponse sourceHistory = portfolioHistory(token, source.id(), "1W");
    ValueHistoryResponse destHistory = portfolioHistory(token, destination.id(), "1W");
    ValueHistoryResponse workspaceHistory = history(token, "1W");

    assertThat(valueOn(sourceHistory, today().minusDays(2))).isEqualByComparingTo("7000");
    assertThat(valueOn(sourceHistory, today())).isEqualByComparingTo("2000");
    assertThat(valueOn(destHistory, today().minusDays(2))).isEqualByComparingTo("3000");
    assertThat(valueOn(destHistory, today())).isEqualByComparingTo("8000");
    assertThat(sourceHistory.change().capitalMovements()).isEqualByComparingTo("-5000");
    assertThat(destHistory.change().capitalMovements()).isEqualByComparingTo("5000");
    assertThat(sourceHistory.change().marketAndFx()).isEqualByComparingTo("0");
    assertThat(destHistory.change().marketAndFx()).isEqualByComparingTo("0");
    assertThat(valueOn(workspaceHistory, today().minusDays(2)))
        .isEqualByComparingTo(valueOn(workspaceHistory, today()));
    assertThat(workspaceHistory.change().marketAndFx()).isEqualByComparingTo("0");
    // #607 AC 5: both legs are inside the workspace, so the transfer is no flow there; each
    // portfolio sees it as money leaving or entering. No return either way.
    assertThat(workspaceHistory.change().returnPercent()).isEqualByComparingTo("0.00");
    assertThat(sourceHistory.change().returnPercent()).isEqualByComparingTo("0.00");
    assertThat(destHistory.change().returnPercent()).isEqualByComparingTo("0.00");
  }

  @Test
  void unresolvedTransferIsReflectedInHistoryCompleteness() throws Exception {
    String token = bootstrapAdministrator();
    PortfolioResponse portfolio = createPortfolio(token, "Needs review", "CHF");
    UUID cash = createAccount(token, cash("Cash", portfolio.id())).id();
    seedOpeningBalance(cash, "2000");
    seedUnresolvedTransfer(cash);

    ValueHistoryResponse portfolioHistory = portfolioHistory(token, portfolio.id(), "1W");
    ValueHistoryResponse workspaceHistory = history(token, "1W");
    assertThat(portfolioHistory.complete()).isFalse();
    assertThat(portfolioHistory.warnings()).anyMatch(w -> w.contains("pending review"));
    assertThat(portfolioHistory.warningCodes())
        .contains(
            ValueHistoryWarningValues.ATTRIBUTION_PENDING_REVIEW,
            ValueHistoryWarningValues.CAPITAL_MOVEMENTS_UNAVAILABLE);
    assertThat(workspaceHistory.warningCodes())
        .contains(ValueHistoryWarningValues.ATTRIBUTION_PENDING_REVIEW);
    assertThat(portfolioHistory.change().marketAndFx()).isNull();
    assertThat(workspaceHistory.complete()).isFalse();
    assertThat(workspaceHistory.change().marketAndFx()).isNull();
  }

  @Test
  void invalidHistoryPeriodIsRejectedWithValidationFailed() {
    String token = bootstrapAdministrator();

    client(token)
        .get()
        .uri("/api/v1/net-worth/history?period=2Y")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("VALIDATION_FAILED"));
  }

  @Test
  void portfolioListIncludesOneMonthChangeComputedOnTheServer() {
    String token = bootstrapAdministrator();

    PortfolioListItemResponse[] rows =
        client(token)
            .get()
            .uri("/api/v1/portfolios")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(
                new ParameterizedTypeReference<PageResponse<PortfolioListItemResponse>>() {})
            .returnResult()
            .getResponseBody()
            .content()
            .toArray(PortfolioListItemResponse[]::new);

    assertThat(rows).isNotEmpty();
    assertThat(rows[0].change()).isNotNull();
    assertThat(rows[0].change().period()).isEqualTo("1M");
    assertThat(rows[0].change().percent()).isNull();
  }

  private ValueHistoryResponse portfolioHistory(String token, UUID portfolioId, String period) {
    return client(token)
        .get()
        .uri("/api/v1/portfolios/" + portfolioId + "/history?period=" + period)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ValueHistoryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static BigDecimal valueOn(ValueHistoryResponse history, LocalDate date) {
    return history.points().stream()
        .filter(point -> point.date().equals(date))
        .findFirst()
        .orElseThrow()
        .value();
  }

  private ValueHistoryResponse history(String token, String period) {
    return client(token)
        .get()
        .uri("/api/v1/net-worth/history?period=" + period)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ValueHistoryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private static void assertSectionsAddUpToNetWorth(NetWorthResponse response) {
    assertThat(
            response.sections().stream()
                .map(NetWorthSectionResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(response.netWorth());
  }

  private static CreateAccountRequest cash(String name, UUID portfolioId) {
    return AccountRequests.account(name, "CASH", "CHF").portfolioId(portfolioId).build();
  }

  private static CreateAccountRequest pillar3a(String name, UUID portfolioId) {
    return AccountRequests.account(name, "PENSION", "CHF")
        .portfolioId(portfolioId)
        .pensionScheme("CH_PILLAR_3A")
        .build();
  }

  // SECURITIES holds positions by default, and such a depot has no value until its positions are
  // valued (EPIC 15). Turned off, the depot is valued by its opening balance and ledger instead.
  private static CreateAccountRequest depot(String name, UUID portfolioId) {
    return AccountRequests.account(name, "SECURITIES", "CHF")
        .portfolioId(portfolioId)
        .holdsPositions(false)
        .build();
  }

  private static CreateAccountRequest customAsset(String name, String customAssetType) {
    return AccountRequests.account(name, "CUSTOM_ASSET", "CHF")
        .customAssetType(customAssetType)
        .build();
  }

  private NetWorthResponse netWorth(String token, String currency) {
    return client(token)
        .get()
        .uri(currency == null ? "/api/v1/net-worth" : "/api/v1/net-worth?currency=" + currency)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(NetWorthResponse.class)
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

  private void recordValuation(String token, UUID accountId, LocalDate date, String value) {
    client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateCustomAssetValuationRequest(date, new BigDecimal(value)))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  // An opening balance a few days back, in the account's own currency. It never goes stale
  // (#380), so the account's value is CURRENT.
  private void seedOpeningBalance(UUID accountId, String balance) throws Exception {
    seedOpeningBalance(accountId, balance, today().minusDays(3));
  }

  private void seedOpeningBalance(UUID accountId, String balance, LocalDate snapshotDate)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance,"
                    + " currency, is_opening_balance) SELECT workspace_id, id, ?, ?,"
                    + " native_currency, TRUE FROM account WHERE id = ?")) {
      statement.setObject(1, snapshotDate);
      statement.setBigDecimal(2, new BigDecimal(balance));
      statement.setObject(3, accountId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void backdateAccountState(UUID accountId, int position, LocalDate date) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE account_history_state SET effective_at = ? WHERE id = ("
                    + "SELECT id FROM account_history_state WHERE account_id = ? ORDER BY event_sequence"
                    + " OFFSET ? LIMIT 1)")) {
      statement.setObject(
          1, date.atTime(12, 0).atZone(ZoneId.of("Europe/Zurich")).toOffsetDateTime());
      statement.setObject(2, accountId);
      statement.setInt(3, position);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void simulateMigratedBaseline(UUID accountId, LocalDate date) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE account_history_state SET verified = FALSE, effective_at = ?"
                    + " WHERE id = (SELECT id FROM account_history_state"
                    + " WHERE account_id = ? ORDER BY event_sequence LIMIT 1)")) {
      statement.setObject(
          1, date.atTime(12, 0).atZone(ZoneId.of("Europe/Zurich")).toOffsetDateTime());
      statement.setObject(2, accountId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void seedTransfer(UUID accountId, UUID counterparty, String amount) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, is_internal_transfer, counterparty_account_id)"
                    + " SELECT workspace_id, id, 'TRANSFER', ?, ?, native_currency, TRUE, ?"
                    + " FROM account WHERE id = ?")) {
      statement.setObject(1, today().minusDays(1));
      statement.setBigDecimal(2, new BigDecimal(amount));
      statement.setObject(3, counterparty);
      statement.setObject(4, accountId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void seedBooking(UUID accountId, String type, LocalDate date, String amount)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, is_internal_transfer)"
                    + " SELECT workspace_id, id, ?, ?, ?, native_currency, FALSE"
                    + " FROM account WHERE id = ?")) {
      statement.setString(1, type);
      statement.setObject(2, date);
      statement.setBigDecimal(3, new BigDecimal(amount));
      statement.setObject(4, accountId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void seedUnresolvedTransfer(UUID accountId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, is_internal_transfer)"
                    + " SELECT workspace_id, id, 'TRANSFER', ?, -100, native_currency, FALSE"
                    + " FROM account WHERE id = ?")) {
      statement.setObject(1, today().minusDays(1));
      statement.setObject(2, accountId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private void seedFxRate(String base, String quote, String rate) {
    seedFxRateOn(base, quote, today(), rate);
  }

  private void seedFxRateOn(String base, String quote, LocalDate date, String rate) {
    FxRate fxRate = new FxRate();
    fxRate.setBaseCurrency(base);
    fxRate.setQuoteCurrency(quote);
    fxRate.setRateDate(date);
    fxRate.setRate(new BigDecimal(rate));
    fxRate.setSource("ECB"); // app.fx.default-source
    fxRateRepository.save(fxRate);
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
