package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
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
 * US-09-01: credit-card purchases are individually tracked and roll up as a liability. The DoD's
 * test is {@link #theOutstandingBalanceIsSubtractedFromNetWorthNeverAdded} (records a purchase and
 * asserts both the card balance and workspace net worth); the rest pin the AC edges.
 *
 * <p>The ledger is cash-direction signed: a purchase of CHF 85.00 is sent and stored as {@code
 * -85.00}, and the card's outstanding balance (a {@code LIABILITY}, so a positive "amount owed") is
 * its negated sum.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String CREDIT_CARD_PURCHASE = "CREDIT_CARD_PURCHASE";
  private static final String TEST_CATEGORY_CODE = "US_09_01_TEST_CATEGORY";

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
    // transaction first - it references account/workspace/app_user and must go before its parents.
    // Only this test's own category row is removed: V19 seeds system categories other tests and
    // the app itself rely on.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM fx_rate");
      statement.execute("DELETE FROM transaction_category_split");
      // US-08-01: every categorized row has a log row referencing it.
      statement.execute("DELETE FROM transaction_categorization_log");
      statement.execute("DELETE FROM transaction");
      statement.execute("DELETE FROM category WHERE code = '" + TEST_CATEGORY_CODE + "'");
      for (String table :
          List.of(
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
              "app_user",
              "workspace_member",
              "financial_institution",
              "workspace")) {
        statement.execute("DELETE FROM " + table);
      }
    }
  }

  // --- AC #1: recorded individually, balance reflects it immediately -------------------------

  @Test
  void recordingACardPurchaseCreatesALedgerRowAndTheBalanceReflectsItImmediately() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    TransactionResponse created =
        recordPurchase(token, card.id(), "-85.00", "Migros", "5411")
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.accountId()).isEqualTo(card.id());
    assertThat(created.transactionType()).isEqualTo(CREDIT_CARD_PURCHASE);
    assertThat(created.amount()).isEqualByComparingTo("-85.00");
    assertThat(created.currency()).isEqualTo("CHF");
    assertThat(created.merchantDescription()).isEqualTo("Migros");
    assertThat(created.mcc()).isEqualTo("5411");
    assertThat(created.source()).isEqualTo("MANUAL");
    assertThat(created.bookingDate()).isEqualTo(today());

    AccountValuation balance = balance(token, card.id());
    assertThat(balance.valueKnown()).isTrue();
    assertThat(balance.nature()).isEqualTo("LIABILITY");
    assertThat(balance.currency()).isEqualTo("CHF");
    assertThat(balance.value()).isEqualByComparingTo("85.00");
  }

  @Test
  void severalPurchasesAccumulateIntoTheOutstandingBalanceAndAreListedNewestFirst() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    recordPurchaseOn(token, card.id(), "-85.00", today().minusDays(2));
    recordPurchaseOn(token, card.id(), "-15.50", today());

    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("100.50");

    List<TransactionResponse> listed = listTransactions(token, card.id(), "").content();
    assertThat(listed).extracting(TransactionResponse::amount).hasSize(2);
    assertThat(listed.get(0).amount()).isEqualByComparingTo("-15.50");
    assertThat(listed.get(1).amount()).isEqualByComparingTo("-85.00");
  }

  @Test
  void aPurchaseBookedInTheFutureDoesNotCountUntilItsBookingDate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    recordPurchaseOn(token, card.id(), "-85.00", today());
    recordPurchaseOn(token, card.id(), "-40.00", today().plusDays(3));

    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("85.00");
  }

  // --- AC #2: liability, subtracted from net worth, never added ------------------------------

  @Test
  void theOutstandingBalanceIsSubtractedFromNetWorthNeverAdded() {
    // DoD: records a purchase, asserts both the card balance and workspace net worth.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    AccountSummaryResponse asset = createCustomAsset(token);
    recordValuation(token, asset.id(), "1000.00");
    recordPurchaseOn(token, card.id(), "-85.00", today());

    NetWorthResponse netWorth = netWorth(token);

    assertThat(netWorth.reportingCurrency()).isEqualTo("CHF");
    assertThat(netWorth.totalAssets()).isEqualByComparingTo("1000.00");
    assertThat(netWorth.totalLiabilities()).isEqualByComparingTo("85.00");
    // 1000 - 85, not 1000 + 85.
    assertThat(netWorth.netWorth()).isEqualByComparingTo("915.00");
    assertThat(netWorth.complete()).isTrue();
    assertThat(netWorth.accounts())
        .filteredOn(a -> a.accountId().equals(card.id()))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.nature()).isEqualTo("LIABILITY");
              assertThat(a.value()).isEqualByComparingTo("85.00");
            });
  }

  @Test
  void aCardOnItsOwnMakesNetWorthNegative() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    recordPurchaseOn(token, card.id(), "-85.00", today());

    NetWorthResponse netWorth = netWorth(token);

    // FR-INS-SUM-001's rule applies here too: a negative figure is shown, never clamped to zero.
    assertThat(netWorth.netWorth()).isEqualByComparingTo("-85.00");
  }

  @Test
  void theInstitutionSummaryCountsTheCardAsALiabilityToo() {
    // Guards the extraction of value resolution out of InstitutionService: a card under an
    // institution now has a value source, and must show up in its liabilities.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    recordPurchaseOn(token, card.id(), "-85.00", today());

    InstitutionSummaryResponse summary =
        client(token)
            .get()
            .uri("/api/v1/institutions/" + card.financialInstitutionId() + "/summary")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(InstitutionSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(summary.totalLiabilities()).isEqualByComparingTo("85.00");
    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
    assertThat(summary.netValue()).isEqualByComparingTo("-85.00");
    assertThat(summary.complete()).isTrue();
  }

  // --- Empty ledger ---------------------------------------------------------------------------

  @Test
  void aCardWithNoLedgerHistoryHasAKnownZeroBalance() {
    // Product decision: a card with nothing recorded owes exactly CHF 0 - a known value, so it does
    // not make net worth incomplete.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    AccountSummaryResponse asset = createCustomAsset(token);
    recordValuation(token, asset.id(), "1000.00");

    AccountValuation balance = balance(token, card.id());
    assertThat(balance.valueKnown()).isTrue();
    assertThat(balance.nature()).isEqualTo("LIABILITY");
    assertThat(balance.value()).isEqualByComparingTo("0");

    NetWorthResponse netWorth = netWorth(token);
    assertThat(netWorth.complete()).isTrue();
    assertThat(netWorth.netWorth()).isEqualByComparingTo("1000.00");
    assertThat(netWorth.totalLiabilities()).isEqualByComparingTo("0");
    assertThat(netWorth.accounts()).extracting(AccountValuation::accountId).contains(card.id());
  }

  @Test
  void aCardWhosePurchasesNetToExactlyZeroIsAKnownZero() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    insertLedgerRow(card.id(), "-50.00", false);
    insertLedgerRow(card.id(), "50.00", false);

    AccountValuation balance = balance(token, card.id());
    assertThat(balance.valueKnown()).isTrue();
    assertThat(balance.value()).isEqualByComparingTo("0");
  }

  // --- Append-only ledger: a void keeps both rows, so both must count ------------------------

  @Test
  void aVoidedPurchaseAndItsReversingEntryNetToZero() {
    // FR-LIF-002: "both records remain in the ledger". Summing only non-voided rows would drop the
    // voided original and count the +85.00 reversal alone - a balance of -85.00 (a phantom credit)
    // instead of the correct 0.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    insertLedgerRow(card.id(), "-85.00", true);
    insertLedgerRow(card.id(), "85.00", false);
    insertLedgerRow(card.id(), "-20.00", false);

    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("20.00");
  }

  // --- AC #3: MCC retained separately from the reporting category ----------------------------

  @Test
  void theMccIsRetainedSeparatelyFromTheReportingCategory() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    TransactionResponse created =
        recordPurchase(token, card.id(), "-85.00", "Migros", "5411")
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    // What EPIC 08's categorization will do: set category_id. It must leave the MCC untouched.
    UUID categoryId = insertTestCategory();
    assignCategory(created.id(), categoryId);

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT category_id, raw_source_data ->> 'mcc' AS mcc FROM transaction WHERE id ="
                    + " ?")) {
      statement.setObject(1, created.id());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        assertThat((UUID) resultSet.getObject("category_id")).isEqualTo(categoryId);
        assertThat(resultSet.getString("mcc")).isEqualTo("5411");
      }
    }
    // ...and the API still reports the source MCC after the category was assigned.
    List<TransactionResponse> listed = listTransactions(token, card.id(), "").content();
    assertThat(listed).singleElement().satisfies(t -> assertThat(t.mcc()).isEqualTo("5411"));
  }

  @Test
  void aPurchaseWithoutAnMccStoresNoSourceData() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    TransactionResponse created =
        recordPurchase(token, card.id(), "-85.00", "Kiosk", null)
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.mcc()).isNull();
  }

  // --- Rejections -----------------------------------------------------------------------------

  @Test
  void aNonNegativePurchaseAmountIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    recordPurchase(token, card.id(), "85.00", "Migros", null)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    recordPurchase(token, card.id(), "0.00", "Migros", null)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aPurchaseOnANonCardAccountIsRejectedByTheServiceLayer() {
    // No DB trigger guards transaction.account_id against the account's type (trg_extension_type_
    // guard only fires on the extension tables), so the service check is the only defence.
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    recordPurchase(token, cash.id(), "-85.00", "Migros", null)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(cash.id())).isZero();
  }

  // --- US-09-04: foreign-currency card purchases -----------------------------------------------

  @Test
  void aForeignCurrencyPurchaseWithAnExplicitRateRetainsTheOriginalAmountAndConvertsTheBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token); // billed in CHF
    recordPurchaseOn(token, card.id(), "-30.00", today()); // an ordinary CHF purchase too

    TransactionResponse created =
        postTransaction(
                token,
                card.id(),
                foreignPurchase("-50.00", "EUR", "Hotel", "1.1000000000", null, null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    // AC1: both the original currency/amount and the applied rate are retained as sent.
    assertThat(created.currency()).isEqualTo("EUR");
    assertThat(created.amount()).isEqualByComparingTo("-50.00");
    assertThat(created.fxRateToAccountCurrency()).isEqualByComparingTo("1.1000000000");
    assertThat(created.fxRateEstimated()).isFalse();

    // The CHF-billed amount is derivable from the rate (50.00 * 1.10 = 55.00) - and the card's
    // balance sums the *converted* figure, not the raw EUR amount mixed in with the CHF one
    // (30.00 CHF + 55.00 CHF-equivalent = 85.00, not a nonsensical 30 + 50 = 80).
    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("85.00");
  }

  @Test
  void aDisclosedBilledAmountDerivesTheRateInstead() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    TransactionResponse created =
        postTransaction(
                token, card.id(), foreignPurchase("-50.00", "EUR", "Hotel", null, "-55.00", null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.fxRateToAccountCurrency()).isEqualByComparingTo("1.1000000000");
    assertThat(created.fxRateEstimated()).isFalse();
    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("55.00");
  }

  @Test
  void withNeitherARateNorABilledAmountAGenericDailyRateIsUsedAndFlaggedEstimated() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    seedFxRate("EUR", "CHF", today(), "1.0500000000");

    TransactionResponse created =
        postTransaction(
                token, card.id(), foreignPurchase("-50.00", "EUR", "Hotel", null, null, null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.fxRateToAccountCurrency()).isEqualByComparingTo("1.0500000000");
    assertThat(created.fxRateEstimated()).isTrue(); // PR-011: a generic fallback, not disclosed
    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("52.50");
  }

  @Test
  void withNoDerivableRateAtAllTheRequestIsRejectedNotSilentlyRecordedWithoutOne() {
    // The story's own edge case: never leave fx_rate_to_account_currency null with no indication
    // why. Deliberately no seedFxRate: EUR/CHF has no rate at all to fall back to.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    postTransaction(token, card.id(), foreignPurchase("-50.00", "EUR", "Hotel", null, null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(card.id())).isZero();
  }

  @Test
  void anExplicitRateAndABilledAmountTogetherIsRejectedAsAmbiguous() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    postTransaction(
            token, card.id(), foreignPurchase("-50.00", "EUR", "Hotel", "1.10", "-55.00", null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void fxFieldsAreRejectedOnAnOrdinarySameCurrencyPurchase() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    postTransaction(
            token, card.id(), foreignPurchase("-50.00", "CHF", "Migros", "1.10", null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    postTransaction(
            token, card.id(), foreignPurchase("-50.00", "CHF", "Migros", null, null, "2.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aDisclosedForeignTransactionFeeIsItsOwnLinkedFeeRowNotFoldedIntoThePurchase() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    TransactionResponse purchase =
        postTransaction(
                token,
                card.id(),
                foreignPurchase("-50.00", "EUR", "Hotel", "1.1000000000", null, "2.50"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    // The purchase itself carries no fee - it stays exactly the disclosed original amount.
    assertThat(purchase.amount()).isEqualByComparingTo("-50.00");

    List<TransactionResponse> ledger = listTransactions(token, card.id(), "").content();
    assertThat(ledger).hasSize(2);
    TransactionResponse fee =
        ledger.stream().filter(t -> "FEE".equals(t.transactionType())).findFirst().orElseThrow();
    assertThat(fee.amount()).isEqualByComparingTo("-2.50"); // negated: it increases what is owed
    assertThat(fee.currency()).isEqualTo("CHF"); // the card's own billing currency, not EUR
    assertThat(fee.relatedTransactionId()).isEqualTo(purchase.id());

    // Both the converted purchase (55.00) and the fee (2.50) count toward what is owed.
    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("57.50");

    // The story's own purpose - "see the true cost including any FX fee" - means it shows up in
    // spending on its own, not silently folded away: two lines, one per currency (US-09-02's
    // cash-flow view is per-currency, not converted). isEqualByComparingTo, not equals: the DB sum
    // and a literal "50.00" need not share the same BigDecimal scale to be the same value.
    CashFlowResponse cashFlow = cashFlow(token, today().toString().substring(0, 7));
    assertThat(cashFlow.spending()).hasSize(2);
    assertThat(cashFlow.spending())
        .filteredOn(s -> s.currency().equals("EUR"))
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("50.00"));
    assertThat(cashFlow.spending())
        .filteredOn(s -> s.currency().equals("CHF"))
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("2.50"));
  }

  // --- Review fixes: billing_currency vs nativeCurrency, rounding, overflow, replay -----------

  @Test
  void aCardsBalanceIsInItsBillingCurrencyEvenWhenItDiffersFromNativeCurrency() {
    // Regression: the balance used to be reported as if it were in account.nativeCurrency even
    // when the card's billing_currency (what the ledger is actually converted to) differs.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCardWithBillingCurrency(token, "USD", "EUR");

    // Matches billing_currency (EUR): domestic from the card's own point of view, so no FX rate
    // is stored at all - exactly the case that used to be silently mislabeled as USD.
    postTransaction(token, card.id(), foreignPurchase("-100.00", "EUR", "Hotel", null, null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    AccountValuation balance = balance(token, card.id());
    assertThat(balance.nativeCurrency()).isEqualTo("EUR"); // billing_currency, not USD
    assertThat(balance.currency()).isEqualTo("EUR");
    assertThat(balance.value()).isEqualByComparingTo("100.00");
  }

  @Test
  void aCardsBalanceNeverCarriesMoreThanFourDecimalPlaces() {
    // Regression: amount (NUMERIC 20,4) * fxRateToAccountCurrency (NUMERIC 20,10) can carry far
    // more than 4 decimal places once summed - the same-currency read path must still round.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    postTransaction(
            token, card.id(), foreignPurchase("-50.00", "EUR", "Hotel", "1.0345678912", null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    AccountValuation balance = balance(token, card.id());
    assertThat(balance.value()).isEqualByComparingTo("51.7284"); // 50.00 * 1.0345678912, HALF_UP
    assertThat(balance.value().scale()).isLessThanOrEqualTo(4);
  }

  @Test
  void aBilledAmountThatWouldOverflowTheRateColumnIsRejectedNotA500() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    postTransaction(
            token,
            card.id(),
            foreignPurchase("-0.0001", "EUR", "Hotel", null, "-9999999999.9999", null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(card.id())).isZero();
  }

  @Test
  void aReplayWithADifferentBilledAmountIsAConflictNotASilentReplay() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    String key = UUID.randomUUID().toString();
    postTransaction(token, card.id(), foreignPurchaseWithKey("-50.00", "EUR", "-55.00", null, key))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    postTransaction(token, card.id(), foreignPurchaseWithKey("-50.00", "EUR", "-60.00", null, key))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(countTransactions(card.id())).isEqualTo(1);
  }

  @Test
  void aReplayWithADifferentFeeAmountIsAConflictNotASilentReplay() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    seedFxRate("EUR", "CHF", today(), "1.0500000000"); // neither rate nor billedAmount given below
    String key = UUID.randomUUID().toString();
    postTransaction(token, card.id(), foreignPurchaseWithKey("-50.00", "EUR", null, "2.50", key))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    postTransaction(token, card.id(), foreignPurchaseWithKey("-50.00", "EUR", null, "5.00", key))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(countTransactions(card.id())).isEqualTo(2); // the original purchase + its fee row
  }

  private CreateTransactionRequest foreignPurchaseWithKey(
      String amount, String currency, String billedAmount, String feeAmount, String externalId) {
    return TransactionRequests.cash(
        CREDIT_CARD_PURCHASE,
        today(),
        new BigDecimal(amount),
        currency,
        null,
        null,
        null,
        externalId,
        null,
        billedAmount == null ? null : new BigDecimal(billedAmount),
        feeAmount == null ? null : new BigDecimal(feeAmount));
  }

  private CashFlowResponse cashFlow(String token, String month) {
    return client(token)
        .get()
        .uri("/api/v1/cash-flow?month=" + month)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private CreateTransactionRequest foreignPurchase(
      String amount,
      String currency,
      String merchant,
      String fxRateToAccountCurrency,
      String billedAmount,
      String feeAmount) {
    return TransactionRequests.cash(
        CREDIT_CARD_PURCHASE,
        today(),
        new BigDecimal(amount),
        currency,
        merchant,
        null,
        null,
        null,
        fxRateToAccountCurrency == null ? null : new BigDecimal(fxRateToAccountCurrency),
        billedAmount == null ? null : new BigDecimal(billedAmount),
        feeAmount == null ? null : new BigDecimal(feeAmount));
  }

  @Test
  void anUnsupportedTransactionTypeIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    postTransaction(
            token,
            card.id(),
            TransactionRequests.cash(
                "EXPENSE",
                today(),
                new BigDecimal("-5.00"),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aMalformedMccOrTooPreciseAmountIsABadRequest() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    recordPurchase(token, card.id(), "-85.00", "Migros", "54")
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
    recordPurchase(token, card.id(), "-85.00", "Migros", "ABCD")
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
    // Money is NUMERIC(20,4): a fifth decimal would be silently rounded by the DB, not rejected.
    recordPurchase(token, card.id(), "-85.00001", "Migros", null)
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void anArchivedCardRejectsNewPurchases() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    client(token)
        .post()
        .uri("/api/v1/accounts/" + card.id() + "/archive")
        .exchange()
        .expectStatus()
        .isOk();

    recordPurchase(token, card.id(), "-85.00", "Migros", null)
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void anUnknownAccountIsNotFoundOnEveryEndpoint() {
    String token = bootstrapAdministrator();
    UUID unknown = UUID.randomUUID();

    recordPurchase(token, unknown, "-85.00", null, null)
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    client(token)
        .get()
        .uri("/api/v1/accounts/" + unknown + "/transactions")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    client(token)
        .get()
        .uri("/api/v1/accounts/" + unknown + "/balance")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theEndpointsRequireAuthentication() {
    anonymousClient()
        .get()
        .uri("/api/v1/net-worth")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // --- Authorization (US-03-03) ---------------------------------------------------------------

  @Test
  void aMemberWithNoGrantCannotSeeOrRecordAnything() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(adminToken);
    recordPurchaseOn(adminToken, card.id(), "-85.00", today());
    createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");

    recordPurchase(bobToken, card.id(), "-1.00", null, null)
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    client(bobToken)
        .get()
        .uri("/api/v1/accounts/" + card.id() + "/transactions")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    client(bobToken)
        .get()
        .uri("/api/v1/accounts/" + card.id() + "/balance")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(countTransactions(card.id())).isEqualTo(1);
  }

  @Test
  void balanceOnlyMaySeeTheBalanceButNeitherTheTransactionsNorRecord() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(adminToken);
    recordPurchaseOn(adminToken, card.id(), "-85.00", today());
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");
    grantOnAccount(adminToken, bobMemberId, card.id(), AccessLevelValues.BALANCE_ONLY);

    assertThat(balance(bobToken, card.id()).value()).isEqualByComparingTo("85.00");
    client(bobToken)
        .get()
        .uri("/api/v1/accounts/" + card.id() + "/transactions")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    recordPurchase(bobToken, card.id(), "-1.00", null, null)
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void readMayListButNotRecordAndEditMayRecord() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(adminToken);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");

    grantOnAccount(adminToken, bobMemberId, card.id(), AccessLevelValues.READ);
    client(bobToken)
        .get()
        .uri("/api/v1/accounts/" + card.id() + "/transactions")
        .exchange()
        .expectStatus()
        .isOk();
    recordPurchase(bobToken, card.id(), "-1.00", null, null)
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);

    grantOnAccount(adminToken, bobMemberId, card.id(), AccessLevelValues.EDIT);
    recordPurchase(bobToken, card.id(), "-1.00", null, null)
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void netWorthLeavesOutAccountsTheCallerMayNotSee() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(adminToken);
    AccountSummaryResponse asset = createCustomAsset(adminToken);
    recordValuation(adminToken, asset.id(), "1000.00");
    recordPurchaseOn(adminToken, card.id(), "-85.00", today());
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");
    grantOnAccount(adminToken, bobMemberId, card.id(), AccessLevelValues.BALANCE_ONLY);

    NetWorthResponse bobs = netWorth(bobToken);

    // Bob sees only the card he was granted - the 1000.00 asset must not leak into his total.
    assertThat(bobs.accounts()).extracting(AccountValuation::accountId).containsExactly(card.id());
    assertThat(bobs.totalAssets()).isEqualByComparingTo("0");
    assertThat(bobs.netWorth()).isEqualByComparingTo("-85.00");
    assertThat(netWorth(adminToken).netWorth()).isEqualByComparingTo("915.00");
  }

  // --- Idempotent recording (a retried request must not double the debt) ----------------------

  @Test
  void retryingAPurchaseWithTheSameExternalIdReturnsTheOriginalInsteadOfRecordingItTwice() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    String key = UUID.randomUUID().toString();

    TransactionResponse first = recordWithKey(token, card.id(), "-85.00", key);
    TransactionResponse retry = recordWithKey(token, card.id(), "-85.00", key);

    assertThat(first.externalId()).isEqualTo(key);
    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(countTransactions(card.id())).isEqualTo(1);
    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("85.00");
  }

  @Test
  void theSameExternalIdWithDifferentFinancialFieldsIsAConflictNotASilentReplay() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    String key = UUID.randomUUID().toString();
    recordWithKey(token, card.id(), "-85.00", key);

    postTransaction(token, card.id(), purchaseWithKey("-90.00", key))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(countTransactions(card.id())).isEqualTo(1);
  }

  @Test
  void aReplayStillAnswersAfterTheCardHasBeenArchived() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    String key = UUID.randomUUID().toString();
    TransactionResponse first = recordWithKey(token, card.id(), "-85.00", key);
    client(token)
        .post()
        .uri("/api/v1/accounts/" + card.id() + "/archive")
        .exchange()
        .expectStatus()
        .isOk();

    // The first request succeeded; its retry must not turn into a 409 just because the card was
    // archived in between.
    assertThat(recordWithKey(token, card.id(), "-85.00", key).id()).isEqualTo(first.id());
  }

  @Test
  void anExternalIdIsScopedToItsAccount() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cardA = createCard(token);
    AccountSummaryResponse cardB = createCard(token);
    String key = UUID.randomUUID().toString();

    recordWithKey(token, cardA.id(), "-85.00", key);
    recordWithKey(token, cardB.id(), "-85.00", key);

    assertThat(countTransactions(cardA.id())).isEqualTo(1);
    assertThat(countTransactions(cardB.id())).isEqualTo(1);
  }

  // --- Paging --------------------------------------------------------------------------------

  @Test
  void theTransactionListIsPagedNewestFirstAndIgnoresAClientSuppliedSort() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    recordPurchaseOn(token, card.id(), "-10.00", today().minusDays(2));
    recordPurchaseOn(token, card.id(), "-20.00", today().minusDays(1));
    recordPurchaseOn(token, card.id(), "-30.00", today());

    // sort=bookingDate,asc would put the oldest first if it were honoured.
    PageOf<TransactionResponse> first =
        listTransactions(token, card.id(), "?size=2&sort=bookingDate,asc");
    PageOf<TransactionResponse> second =
        listTransactions(token, card.id(), "?size=2&page=1&sort=bookingDate,asc");

    assertThat(first.totalElements()).isEqualTo(3);
    assertThat(first.content())
        .extracting(TransactionResponse::bookingDate)
        .containsExactly(today(), today().minusDays(1));
    assertThat(second.content())
        .extracting(TransactionResponse::bookingDate)
        .containsExactly(today().minusDays(2));
  }

  @Test
  void aSortOnAnUnknownPropertyIsIgnoredNotA500() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    recordPurchaseOn(token, card.id(), "-10.00", today());

    assertThat(listTransactions(token, card.id(), "?sort=doesNotExist,asc").content()).hasSize(1);
  }

  @Test
  void anMccStoredAsANumberByAnImporterStillReadsBackAsAFourDigitCode() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    insertLedgerRowWithSourceData(card.id(), "-1.00", "{\"mcc\": 742}");
    insertLedgerRowWithSourceData(card.id(), "-2.00", "{\"mcc\": \"5411\"}");
    insertLedgerRowWithSourceData(card.id(), "-3.00", "{\"other\": true}");

    assertThat(listTransactions(token, card.id(), "").content())
        .extracting(TransactionResponse::mcc)
        .containsExactlyInAnyOrder("0742", "5411", null);
  }

  // --- Where a figure came from / how sure it is ---------------------------------------------

  @Test
  void theBalanceSaysWhereItsFigureCameFrom() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    AccountSummaryResponse asset = createCustomAsset(token);
    AccountSummaryResponse cash = createCashAccount(token);
    recordValuation(token, asset.id(), "1000.00");

    assertThat(balance(token, card.id()).valueBasis()).isEqualTo(ValueBasisValues.LEDGER_EMPTY);
    recordPurchaseOn(token, card.id(), "-85.00", today());
    assertThat(balance(token, card.id()).valueBasis()).isEqualTo(ValueBasisValues.LEDGER);
    assertThat(balance(token, asset.id()).valueBasis())
        .isEqualTo(ValueBasisValues.MANUAL_VALUATION);
    // No value source at all: not known, so no basis either.
    AccountValuation unknown = balance(token, cash.id());
    assertThat(unknown.valueKnown()).isFalse();
    assertThat(unknown.valueBasis()).isNull();
  }

  @Test
  void netWorthIsApproximateWhileAnIncludedFigureIsAnAssumptionAndExactOnceItIsMeasured() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    AccountSummaryResponse asset = createCustomAsset(token);
    recordValuation(token, asset.id(), "1000.00");

    // An empty card ledger is an assumed zero: known, so complete - but not exact.
    NetWorthResponse assumed = netWorth(token);
    assertThat(assumed.complete()).isTrue();
    assertThat(assumed.approximate()).isTrue();

    recordPurchaseOn(token, card.id(), "-85.00", today());
    NetWorthResponse measured = netWorth(token);
    assertThat(measured.complete()).isTrue();
    assertThat(measured.approximate()).isFalse();
  }

  @Test
  void aLoanCountedAtItsOriginalPrincipalMakesNetWorthApproximate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse mortgage =
        client(token)
            .post()
            .uri("/api/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                AccountRequests.account("Home Mortgage", "MORTGAGE", "CHF")
                    .originalPrincipal(new BigDecimal("500000.00"))
                    .interestRatePercent(new BigDecimal("1.5"))
                    .build())
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    AccountValuation balance = balance(token, mortgage.id());
    assertThat(balance.valueBasis()).isEqualTo(ValueBasisValues.ORIGINAL_PRINCIPAL);
    NetWorthResponse netWorth = netWorth(token);
    assertThat(netWorth.complete()).isTrue();
    assertThat(netWorth.approximate()).isTrue();
    assertThat(netWorth.totalLiabilities()).isEqualByComparingTo("500000.00");
  }

  // --- Net worth across currencies -----------------------------------------------------------

  @Test
  void aForeignCurrencyCardIsConvertedIntoTheReportingCurrencyInNetWorth() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "EUR Card", "CREDIT_CARD", "EUR", null);
    recordPurchaseIn(token, card.id(), "-100.00", "EUR");
    seedFxRate("EUR", "CHF", today(), "0.9500000000");

    // The account's own balance stays in its native currency...
    AccountValuation balance = balance(token, card.id());
    assertThat(balance.currency()).isEqualTo("EUR");
    assertThat(balance.value()).isEqualByComparingTo("100.00");

    // ...and net worth converts it into the caller's reporting currency, subtracting it.
    NetWorthResponse netWorth = netWorth(token);
    assertThat(netWorth.reportingCurrency()).isEqualTo("CHF");
    assertThat(netWorth.totalLiabilities()).isEqualByComparingTo("95.00");
    assertThat(netWorth.netWorth()).isEqualByComparingTo("-95.00");
    assertThat(netWorth.complete()).isTrue();
    assertThat(netWorth.accounts())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.conversionRate()).isEqualByComparingTo("0.95");
              assertThat(a.conversionRateCarriedForward()).isFalse();
            });
  }

  @Test
  void aForeignCurrencyCardWithNoFxRateDegradesToUnknownNotAFailedRequest() {
    // The FX-degradation regression (#78) on the new /net-worth path: a missing rate must leave the
    // one account out and flag the figure incomplete - not roll the shared transaction back and
    // 500.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "EUR Card", "CREDIT_CARD", "EUR", null);
    AccountSummaryResponse asset = createCustomAsset(token);
    recordValuation(token, asset.id(), "1000.00");
    recordPurchaseIn(token, card.id(), "-100.00", "EUR");
    // Deliberately no seedFxRate: EUR/CHF has no rate at all.

    NetWorthResponse netWorth = netWorth(token);

    assertThat(netWorth.complete()).isFalse();
    assertThat(netWorth.totalLiabilities()).isEqualByComparingTo("0");
    assertThat(netWorth.netWorth()).isEqualByComparingTo("1000.00");
    assertThat(netWorth.accounts())
        .filteredOn(a -> a.accountId().equals(card.id()))
        .singleElement()
        .satisfies(a -> assertThat(a.valueKnown()).isFalse());
  }

  // --- US-07-01: manually recorded cash transactions -------------------------------------------

  @Test
  void aCashExpenseIsRecordedAsManualWithTodaysBookingDateAndNoSecurity() {
    // The story's DoD: a CHF 45.00 expense on a CASH account, end to end through the API and DB.
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    TransactionResponse created =
        postTransaction(token, cash.id(), cashTransaction("EXPENSE", "-45.00", "CHF"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.transactionType()).isEqualTo("EXPENSE");
    assertThat(created.source()).isEqualTo("MANUAL");
    assertThat(created.bookingDate()).isEqualTo(today());
    assertThat(created.amount()).isEqualByComparingTo("-45.00");
    assertThat(created.fxRateToAccountCurrency()).isNull();
    assertThat(
            jdbcUuid(
                "SELECT id FROM transaction WHERE id = ? AND security_id IS NULL", created.id()))
        .isEqualTo(created.id());

    List<TransactionResponse> listed = listTransactions(token, cash.id(), "").content();
    assertThat(listed).extracting(TransactionResponse::id).containsExactly(created.id());
  }

  @Test
  void everyCashTypeIsAcceptedWithItsOwnSignAndRejectedWithTheOpposite() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    for (String outflow : List.of("EXPENSE", "WITHDRAWAL", "FEE", "TAX")) {
      postTransaction(token, cash.id(), cashTransaction(outflow, "10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      postTransaction(token, cash.id(), cashTransaction(outflow, "0.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      postTransaction(token, cash.id(), cashTransaction(outflow, "-10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.CREATED);
    }
    for (String inflow : List.of("INCOME", "DEPOSIT", "INTEREST", "REFUND")) {
      postTransaction(token, cash.id(), cashTransaction(inflow, "-10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      postTransaction(token, cash.id(), cashTransaction(inflow, "0.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      postTransaction(token, cash.id(), cashTransaction(inflow, "10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.CREATED);
    }
    assertThat(countTransactions(cash.id())).isEqualTo(8); // only the eight correctly-signed rows
  }

  @Test
  void typesThatBelongToLaterStoriesAreRejectedOnACashAccount() {
    // DEBT_REPAYMENT -> US-10-02; a PENSION_CONTRIBUTION without its pension account is incomplete;
    // BUY/SELL/DIVIDEND need an account that holds positions.
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    for (String type :
        List.of(
            "DEBT_REPAYMENT", "PENSION_CONTRIBUTION", "BUY", "SELL", "DIVIDEND", "NOT_A_TYPE")) {
      postTransaction(token, cash.id(), cashTransaction(type, "-10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
    assertThat(countTransactions(cash.id())).isZero();
  }

  @Test
  void aCardAccountRejectsCashTypes() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    for (String type : List.of("INCOME", "DEPOSIT", "REFUND", "FEE", "TAX", "WITHDRAWAL")) {
      postTransaction(token, card.id(), cashTransaction(type, "-10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
    assertThat(countTransactions(card.id())).isZero();
  }

  @Test
  void aForeignCurrencyCashEntryWithAnExplicitRateKeepsTheOriginalAmountAndTheRate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    TransactionResponse created =
        postTransaction(
                token, cash.id(), foreignCash("EXPENSE", "-50.00", "EUR", "1.0800000000", null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.currency()).isEqualTo("EUR");
    assertThat(created.amount()).isEqualByComparingTo("-50.00");
    assertThat(created.fxRateToAccountCurrency()).isEqualByComparingTo("1.08");
    assertThat(created.fxRateEstimated()).isFalse();
  }

  @Test
  void aDisclosedBilledAmountDerivesTheRateForACashEntryToo() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    TransactionResponse created =
        postTransaction(token, cash.id(), foreignCash("EXPENSE", "-50.00", "EUR", null, "-54.00"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.fxRateToAccountCurrency()).isEqualByComparingTo("1.08");
    assertThat(created.fxRateEstimated()).isFalse();
  }

  @Test
  void aForeignCurrencyCashEntryWithNoRateFallsBackToTheDailyRateFlaggedEstimated() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    seedFxRate("EUR", "CHF", today(), "1.0500000000");

    TransactionResponse created =
        postTransaction(token, cash.id(), foreignCash("INCOME", "20.00", "EUR", null, null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.fxRateToAccountCurrency()).isEqualByComparingTo("1.05");
    assertThat(created.fxRateEstimated()).isTrue();
  }

  @Test
  void aForeignCurrencyCashEntryWithNoDerivableRateIsRejectedNotRecordedWithoutOne() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    postTransaction(token, cash.id(), foreignCash("EXPENSE", "-50.00", "EUR", null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(cash.id())).isZero();
  }

  @Test
  void fxFieldsAndAForeignFeeAreRejectedWhereTheyDoNotApplyToACashEntry() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    seedFxRate("EUR", "CHF", today(), "1.0500000000");

    // A rate on a same-currency entry has nothing to convert.
    postTransaction(token, cash.id(), foreignCash("EXPENSE", "-50.00", "CHF", "1.10", null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    // feeAmount is a card-issuer concept; on a cash account the fee is its own FEE transaction.
    postTransaction(
            token,
            cash.id(),
            TransactionRequests.cash(
                "EXPENSE",
                today(),
                new BigDecimal("-50.00"),
                "EUR",
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("2.00")))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(cash.id())).isZero();
  }

  @Test
  void aCashEntryOnAnArchivedAccountIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    client(token)
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/archive")
        .exchange()
        .expectStatus()
        .is2xxSuccessful();

    postTransaction(token, cash.id(), cashTransaction("EXPENSE", "-5.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void aCashEntryRetriedWithTheSameExternalIdIsRecordedOnce() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    CreateTransactionRequest request =
        TransactionRequests.cash(
            "EXPENSE",
            today(),
            new BigDecimal("-45.00"),
            "CHF",
            "Coop",
            null,
            null,
            "key-1",
            null,
            null,
            null);

    UUID first =
        postTransaction(token, cash.id(), request)
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody()
            .id();
    UUID second =
        postTransaction(token, cash.id(), request)
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody()
            .id();

    assertThat(second).isEqualTo(first);
    assertThat(countTransactions(cash.id())).isEqualTo(1);
  }

  @Test
  void aReadOnlyMemberCannotRecordACashEntryButAnEditorCan() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(adminToken);
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.READ);
    postTransaction(memberToken, cash.id(), cashTransaction("EXPENSE", "-5.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND); // same as the card endpoints: no hint the account exists
    assertThat(countTransactions(cash.id())).isZero();

    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.EDIT);
    postTransaction(memberToken, cash.id(), cashTransaction("EXPENSE", "-5.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private CreateTransactionRequest cashTransaction(String type, String amount, String currency) {
    return foreignCash(type, amount, currency, null, null);
  }

  private CreateTransactionRequest foreignCash(
      String type, String amount, String currency, String rate, String billedAmount) {
    return TransactionRequests.cash(
        type,
        today(),
        new BigDecimal(amount),
        currency,
        null,
        null,
        null,
        null,
        rate == null ? null : new BigDecimal(rate),
        billedAmount == null ? null : new BigDecimal(billedAmount),
        null);
  }

  @Test
  void cashExpensesAndTaxCountAsSpendingButInflowsDoNot() {
    // Review finding: EXPENSE/TAX were accepted but missing from SPENDING_TYPES, so a recorded
    // CHF 45 expense showed as 0 spending with no incomplete flag.
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    for (CreateTransactionRequest request :
        List.of(
            cashTransaction("EXPENSE", "-45.00", "CHF"),
            cashTransaction("TAX", "-5.00", "CHF"),
            cashTransaction("INCOME", "3000.00", "CHF"),
            cashTransaction("INTEREST", "1.20", "CHF"))) {
      postTransaction(token, cash.id(), request).expectStatus().isEqualTo(HttpStatus.CREATED);
    }

    CashFlowResponse flow = cashFlow(token, YearMonth.from(today()).toString());

    assertThat(flow.spending())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.currency()).isEqualTo("CHF");
              assertThat(s.amount()).isEqualByComparingTo("50.00");
            });
    assertThat(flow.complete()).isTrue();
  }

  @Test
  void aCustodianAccountTakesOnlyItsOwnCashMovements() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES", "CHF", null);

    for (String outflow : List.of("WITHDRAWAL", "FEE", "TAX")) {
      postTransaction(token, depot.id(), cashTransaction(outflow, "-10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.CREATED);
    }
    for (String inflow : List.of("DEPOSIT", "INTEREST")) {
      postTransaction(token, depot.id(), cashTransaction(inflow, "10.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.CREATED);
    }
    postTransaction(token, depot.id(), cashTransaction("EXPENSE", "-10.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    postTransaction(token, depot.id(), cashTransaction("INCOME", "10.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    postTransaction(token, depot.id(), cashTransaction("REFUND", "10.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(depot.id())).isEqualTo(5);
  }

  @Test
  void aLoanOrPensionTakesNoCashTypesBecauseTheyHaveTheirOwn() {
    // INTEREST is forced positive, which on a liability would move the balance the wrong way;
    // DEBT_REPAYMENT (US-10-02) and PENSION_CONTRIBUTION (US-10-01) are the types for these.
    String token = bootstrapAdministrator();
    AccountSummaryResponse loan = createLoan(token);
    AccountSummaryResponse pension = createPension(token);

    for (AccountSummaryResponse account : List.of(loan, pension)) {
      for (String type : List.of("INTEREST", "EXPENSE", "INCOME", "WITHDRAWAL", "FEE", "TAX")) {
        String amount = List.of("INTEREST", "INCOME").contains(type) ? "10.00" : "-10.00";
        postTransaction(token, account.id(), cashTransaction(type, amount, "CHF"))
            .expectStatus()
            .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      }
      assertThat(countTransactions(account.id())).isZero();
    }
  }

  // --- US-07-01 part 2: investment transactions ------------------------------------------------
  //
  // These pin the HTTP behaviour end to end. Several rules overlap (a wrong sign usually also
  // breaks the amount formula), so which rule rejects each request is asserted separately, with
  // its message, in TransactionServiceTest.

  @Test
  void aSecurityBuyRecordsQuantityPriceFeeAndItsNetCashImpactEndToEnd() {
    // The story's DoD: BUY 10 shares at CHF 100 with a CHF 5 fee on a depot.
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);
    LocalDate tradeDate = today().minusDays(2);

    postTransaction(
            token,
            depot.id(),
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                security,
                "10",
                "100",
                "5",
                tradeDate,
                today(),
                null,
                null))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    // Read back from the database, not the POST's echo.
    TransactionResponse stored = listTransactions(token, depot.id(), "").content().get(0);
    assertThat(stored.transactionType()).isEqualTo("BUY");
    assertThat(stored.source()).isEqualTo("MANUAL");
    assertThat(stored.securityId()).isEqualTo(security);
    assertThat(stored.quantity()).isEqualByComparingTo("10");
    assertThat(stored.unitPrice()).isEqualByComparingTo("100");
    assertThat(stored.feeAmount()).isEqualByComparingTo("5");
    assertThat(stored.amount()).isEqualByComparingTo("-1005.00");
    // FR-TRX-008: both dates retained distinctly.
    assertThat(stored.tradeDate()).isEqualTo(tradeDate);
    assertThat(stored.settlementDate()).isEqualTo(today());
    assertThat(stored.fxRateToAccountCurrency()).isNull();
    assertThat(stored.grossAmount()).isNull();
    // The fee is part of the trade, not a FEE row of its own.
    assertThat(countTransactions(depot.id())).isEqualTo(1);
  }

  @Test
  void aSaleCarriesANegativeQuantityAndItsProceedsNetOfCosts() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    TransactionResponse sale =
        postTransaction(token, depot.id(), trade("SELL", security, "-4", "110", "3", "437.00"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(sale.quantity()).isEqualByComparingTo("-4");
    assertThat(sale.amount()).isEqualByComparingTo("437.00");
  }

  @Test
  void aSaleWhoseCostsExceedItsProceedsIsRecordedWithItsNegativeOrZeroCashLeg() {
    // Selling a near-worthless remnant: 1 share at 0.50 with a 5.00 fee costs 4.50 in cash.
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    postTransaction(token, depot.id(), trade("SELL", security, "-1", "0.50", "5", "-4.50"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    // Proceeds exactly equal to costs: a zero cash leg.
    postTransaction(token, depot.id(), trade("SELL", security, "-1", "5", "5", "0.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    // No rate follows from a zero amount, so a billed amount cannot stand in for one.
    expectUnprocessable(
        token,
        depot.id(),
        foreignTrade("SELL", "0.00", "USD", security, "-1", "5", "5", null, "0.00"));
    assertThat(countTransactions(depot.id())).isEqualTo(2);
  }

  @Test
  void tradeSignsFollowTheirDirection() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    // The story's edge case: a negative quantity on a BUY.
    expectUnprocessable(token, depot.id(), trade("BUY", security, "-10", "100", null, "-1000.00"));
    expectUnprocessable(token, depot.id(), trade("BUY", security, "10", "100", null, "1000.00"));
    expectUnprocessable(token, depot.id(), trade("SELL", security, "4", "110", null, "440.00"));
    // A sale's sign follows from its figures: -4 x 110 is 440 in, not 440 out.
    expectUnprocessable(token, depot.id(), trade("SELL", security, "-4", "110", null, "-440.00"));
    expectUnprocessable(token, depot.id(), trade("BUY", security, "10", "-100", null, "-1000.00"));
    expectUnprocessable(token, depot.id(), trade("BUY", security, "10", "100", "-5", "-995.00"));
    assertThat(countTransactions(depot.id())).isZero();
  }

  @Test
  void aTradeAmountMustAgreeWithQuantityTimesPriceWithinStatementRounding() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    // 10 x 100 + 5 = 1005, not 1010: a typo, rejected (0.01 + 10 x 0.005 = 0.06 allowed).
    expectUnprocessable(token, depot.id(), trade("BUY", security, "10", "100", "5", "-1010.00"));
    // 3 x 33.3333 = 99.9999, booked as 100.00 on the statement: accepted.
    postTransaction(token, depot.id(), trade("BUY", security, "3", "33.3333", null, "-100.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    assertThat(countTransactions(depot.id())).isEqualTo(1);
  }

  @Test
  void aLargeTradeAtARoundedAveragePriceIsAcceptedButATypoIsNot() {
    // Review finding: 25,000 shares at an exact 12.345678, shown as 12.3457. The booked amount is
    // 308,641.95; the shown price gives 308,642.50. A fixed 0.01 rejected this real statement.
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    postTransaction(
            token, depot.id(), trade("BUY", security, "25000", "12.3457", null, "-308641.95"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    // The allowance is 0.01 + 25,000 x 0.00005 = 1.26, so a slip of ten is still caught.
    expectUnprocessable(
        token, depot.id(), trade("BUY", security, "25000", "12.3457", null, "-308652.50"));
    assertThat(countTransactions(depot.id())).isEqualTo(1);
  }

  @Test
  void aCurrencyWithoutCentsAllowsItsOwnMinorUnit() {
    // A fractional JPY savings-plan buy: 1.2345 x 2,345 = 2,894.9025, booked as 2,895 yen.
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    postTransaction(
            token,
            depot.id(),
            foreignTrade("BUY", "-2895", "JPY", security, "1.2345", "2345", null, "0.0056", null))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    expectUnprocessable(
        token,
        depot.id(),
        foreignTrade("BUY", "-2900", "JPY", security, "1.2345", "2345", null, "0.0056", null));
  }

  @Test
  void aTradeNeedsAnExistingSecurityAQuantityAndAPrice() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    expectUnprocessable(token, depot.id(), trade("BUY", null, "10", "100", null, "-1000.00"));
    expectUnprocessable(
        token, depot.id(), trade("BUY", UUID.randomUUID(), "10", "100", null, "-1000.00"));
    expectUnprocessable(token, depot.id(), trade("BUY", security, null, "100", null, "-1000.00"));
    expectUnprocessable(token, depot.id(), trade("BUY", security, "10", null, null, "-1000.00"));
    assertThat(countTransactions(depot.id())).isZero();
  }

  @Test
  void investmentTypesNeedAnAccountThatHoldsPositionsWhateverItsType() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);
    AccountSummaryResponse plainPension = createPension(token);
    AccountSummaryResponse fundPension = createPensionHoldingPositions(token);
    // After the accounts: creating a security needs EDIT on an active account (US-12-01).
    UUID security = createSecurity(token);

    for (AccountSummaryResponse account : List.of(cash, plainPension)) {
      expectUnprocessable(
          token, account.id(), trade("BUY", security, "10", "100", null, "-1000.00"));
    }
    // A 3a that holds funds trades like a depot; its contribution limit is about contributions.
    postTransaction(token, fundPension.id(), trade("BUY", security, "10", "100", null, "-1000.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void aReadOnlyMemberCannotTradeButAnEditorCan() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(adminToken, "CHF");
    UUID security = createSecurity(adminToken);
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    grantOnAccount(adminToken, memberId, depot.id(), AccessLevelValues.READ);
    postTransaction(memberToken, depot.id(), trade("BUY", security, "10", "100", null, "-1000.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND); // no hint the account exists, as for cash entries
    assertThat(countTransactions(depot.id())).isZero();

    grantOnAccount(adminToken, memberId, depot.id(), AccessLevelValues.EDIT);
    postTransaction(memberToken, depot.id(), trade("BUY", security, "10", "100", null, "-1000.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void aDividendKeepsGrossWithheldAndNetDistinguishable() {
    // FR-TAXR-001's example: a gross dividend of 100 with 35% withheld puts 65 in the account.
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    postTransaction(
            token, depot.id(), dividend("65.00", security, "200", "0.50", "100.00", "35.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    TransactionResponse stored = listTransactions(token, depot.id(), "").content().get(0);
    assertThat(stored.amount()).isEqualByComparingTo("65.00");
    assertThat(stored.grossAmount()).isEqualByComparingTo("100.00");
    assertThat(stored.taxWithheldAmount()).isEqualByComparingTo("35.00");
    assertThat(stored.netAmount()).isEqualByComparingTo("65.00");
    assertThat(stored.quantity()).isEqualByComparingTo("200");
  }

  @Test
  void aDividendWithoutWithholdingNeedsOnlyItsAmount() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    TransactionResponse recorded =
        postTransaction(token, depot.id(), dividend("42.10", security, null, null, null, null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(recorded.grossAmount()).isNull();
    assertThat(recorded.netAmount()).isNull();
  }

  @Test
  void dividendFiguresThatDoNotReconcileAreRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    // 100 - 30 is 70, not the 65 received.
    expectUnprocessable(
        token, depot.id(), dividend("65.00", security, null, null, "100.00", "30.00"));
    expectUnprocessable(token, depot.id(), dividend("65.00", security, null, null, "100.00", null));
    expectUnprocessable(token, depot.id(), dividend("65.00", security, null, null, null, "35.00"));
    // 60 - (-5) is 65, but a withholding is never negative.
    expectUnprocessable(
        token, depot.id(), dividend("65.00", security, null, null, "60.00", "-5.00"));
    // 200 x 0.50 = 100 gross, but 65 arrived and no withholding was stated.
    expectUnprocessable(token, depot.id(), dividend("65.00", security, "200", "0.50", null, null));
    expectUnprocessable(token, depot.id(), dividend("65.00", security, "-200", null, null, null));
    expectUnprocessable(token, depot.id(), dividend("65.00", security, "200", "0", null, null));
    expectUnprocessable(token, depot.id(), dividend("-65.00", security, null, null, null, null));
    expectUnprocessable(token, depot.id(), dividend("65.00", null, null, null, null, null));
    // A dividend has no trade or settlement date, nor a trade's fee.
    expectUnprocessable(
        token,
        depot.id(),
        investment(
            "DIVIDEND", "65.00", "CHF", security, null, null, null, today(), null, null, null));
    expectUnprocessable(
        token,
        depot.id(),
        investment(
            "DIVIDEND", "65.00", "CHF", security, null, null, null, null, today(), null, null));
    expectUnprocessable(
        token,
        depot.id(),
        investment("DIVIDEND", "65.00", "CHF", security, null, null, "1", null, null, null, null));
    assertThat(countTransactions(depot.id())).isZero();
  }

  @Test
  void investmentFieldsAreRejectedOnEveryOtherType() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    expectUnprocessable(
        token,
        depot.id(),
        investment("DEPOSIT", "100.00", "CHF", security, null, null, null, null, null, null, null));
    expectUnprocessable(
        token,
        depot.id(),
        investment("FEE", "-5.00", "CHF", null, "10", null, null, null, null, null, null));
    expectUnprocessable(
        token,
        depot.id(),
        investment(
            "INTEREST", "65.00", "CHF", null, null, null, null, null, null, "100.00", "35.00"));
    // A trade's fee travels on the trade; a standalone fee is its own FEE row, without feeAmount.
    expectUnprocessable(
        token,
        depot.id(),
        investment("FEE", "-5.00", "CHF", null, null, null, "5", null, null, null, null));
    // Gross and withheld belong to a dividend, not a trade.
    expectUnprocessable(
        token,
        depot.id(),
        investment(
            "BUY", "-1000.00", "CHF", security, "10", "100", null, null, null, "1000.00", "0"));
    assertThat(countTransactions(depot.id())).isZero();
  }

  @Test
  void tradeSettlementAndBookingDatesAreOrdered() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    expectUnprocessable(
        token,
        depot.id(),
        investment(
            "BUY",
            "-1000.00",
            "CHF",
            security,
            "10",
            "100",
            null,
            today(),
            today().minusDays(1),
            null,
            null));
    // Booked today, traded tomorrow: the cash cannot be booked before the trade happened.
    expectUnprocessable(
        token,
        depot.id(),
        investment(
            "BUY",
            "-1000.00",
            "CHF",
            security,
            "10",
            "100",
            null,
            today().plusDays(1),
            null,
            null,
            null));
    // Traded two days before booking and settling on the booking day: the ordinary case.
    postTransaction(
            token,
            depot.id(),
            investment(
                "BUY",
                "-1000.00",
                "CHF",
                security,
                "10",
                "100",
                null,
                today().minusDays(2),
                today(),
                null,
                null))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void aForeignCurrencyBuyKeepsTheTradeCurrencyAndTheBrokersRate() {
    // A CHF depot whose cash is also held in USD: the trade stays in USD; the rate is the one the
    // broker disclosed, not an estimate, and the fee stays on the trade.
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    TransactionResponse bought =
        postTransaction(
                token,
                depot.id(),
                foreignTrade("BUY", "-1005.00", "USD", security, "10", "100", "5", "0.9", null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(bought.currency()).isEqualTo("USD");
    assertThat(bought.amount()).isEqualByComparingTo("-1005.00");
    assertThat(bought.fxRateToAccountCurrency()).isEqualByComparingTo("0.9");
    assertThat(bought.fxRateEstimated()).isFalse();
    assertThat(bought.feeAmount()).isEqualByComparingTo("5");
    assertThat(countTransactions(depot.id())).isEqualTo(1);
  }

  @Test
  void aForeignCurrencyTradeWithoutADisclosedRateFallsBackToTheDailyRateFlaggedEstimated() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);
    seedFxRate("USD", "CHF", today(), "0.88");

    TransactionResponse bought =
        postTransaction(
                token,
                depot.id(),
                investment(
                    "BUY", "-1000.00", "USD", security, "10", "100", null, null, null, null, null))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(bought.fxRateToAccountCurrency()).isEqualByComparingTo("0.88");
    assertThat(bought.fxRateEstimated()).isTrue();
  }

  @Test
  void aRetriedTradeIsRecordedOnceAndTheSameKeyForADifferentTradeIsAConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);
    UUID otherSecurity = createSecurity(token, "US0378331005");
    String key = UUID.randomUUID().toString();
    CreateTransactionRequest original =
        keyed(
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                security,
                "10",
                "100",
                "5",
                today().minusDays(1),
                today(),
                null,
                null),
            key);

    for (int attempt = 0; attempt < 2; attempt++) {
      postTransaction(token, depot.id(), original).expectStatus().isEqualTo(HttpStatus.CREATED);
    }
    assertThat(countTransactions(depot.id())).isEqualTo(1);

    // Each variant differs from the recorded trade in exactly one frozen field. The replay check
    // runs before validation, so a variant need not be a valid trade on its own.
    List<CreateTransactionRequest> differentTrades =
        List.of(
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                security,
                "20",
                "100",
                "5",
                today().minusDays(1),
                today(),
                null,
                null),
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                otherSecurity,
                "10",
                "100",
                "5",
                today().minusDays(1),
                today(),
                null,
                null),
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                security,
                "10",
                "100.5",
                "5",
                today().minusDays(1),
                today(),
                null,
                null),
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                security,
                "10",
                "100",
                "6",
                today().minusDays(1),
                today(),
                null,
                null),
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                security,
                "10",
                "100",
                "5",
                today().minusDays(2),
                today(),
                null,
                null),
            investment(
                "BUY",
                "-1005.00",
                "CHF",
                security,
                "10",
                "100",
                "5",
                today().minusDays(1),
                null,
                null,
                null));
    for (CreateTransactionRequest different : differentTrades) {
      postTransaction(token, depot.id(), keyed(different, key))
          .expectStatus()
          .isEqualTo(HttpStatus.CONFLICT);
    }
    assertThat(countTransactions(depot.id())).isEqualTo(1);
  }

  @Test
  void aRetriedDividendWithDifferentWithholdingIsAConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);
    String key = UUID.randomUUID().toString();

    postTransaction(
            token,
            depot.id(),
            keyed(dividend("65.00", security, null, null, "100.00", "35.00"), key))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    postTransaction(
            token,
            depot.id(),
            keyed(dividend("65.00", security, null, null, "100.00", "35.00"), key))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    // Same net cash, but a different gross and withholding: another advice.
    postTransaction(
            token,
            depot.id(),
            keyed(dividend("65.00", security, null, null, "90.00", "25.00"), key))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(countTransactions(depot.id())).isEqualTo(1);
  }

  @Test
  void tradesAndDividendsAreNotSpending() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);
    postTransaction(token, depot.id(), trade("BUY", security, "10", "100", "5", "-1005.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    postTransaction(token, depot.id(), dividend("20.00", security, null, null, null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    assertThat(cashFlow(token, YearMonth.from(today()).toString()).spending()).isEmpty();
  }

  // --- V36: the schema holds the same shape for every writer ------------------------------------

  @Test
  void theSchemaRejectsMalformedInvestmentRowsFromAnyWriter() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);

    assertSchemaRejects(
        "transaction_trade_shape",
        depot.id(),
        "'BUY', CURRENT_DATE, -1000, NULL, 10, 100, NULL, NULL, NULL, NULL, NULL",
        null);
    assertSchemaRejects(
        "transaction_trade_quantity_sign",
        depot.id(),
        "'BUY', CURRENT_DATE, -1000, ?, -10, 100, NULL, NULL, NULL, NULL, NULL",
        security);
    assertSchemaRejects(
        "transaction_trade_quantity_sign",
        depot.id(),
        "'SELL', CURRENT_DATE, 1000, ?, 10, 100, NULL, NULL, NULL, NULL, NULL",
        security);
    assertSchemaRejects(
        "transaction_price_and_fee_are_magnitudes",
        depot.id(),
        "'BUY', CURRENT_DATE, -995, ?, 10, 100, -5, NULL, NULL, NULL, NULL",
        security);
    assertSchemaRejects(
        "transaction_trade_dates_ordered",
        depot.id(),
        "'BUY', CURRENT_DATE, -1000, ?, 10, 100, NULL, CURRENT_DATE + 1, NULL, NULL, NULL",
        security);
    assertSchemaRejects(
        "transaction_dividend_shape",
        depot.id(),
        "'DIVIDEND', CURRENT_DATE, 65, ?, NULL, NULL, NULL, CURRENT_DATE, NULL, NULL, NULL",
        security);
    assertSchemaRejects(
        "transaction_dividend_sign",
        depot.id(),
        "'DIVIDEND', CURRENT_DATE, -65, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL",
        security);
    assertSchemaRejects(
        "transaction_withholding_reconciles",
        depot.id(),
        "'DIVIDEND', CURRENT_DATE, 65, ?, NULL, NULL, NULL, NULL, 100, 30, 65",
        security);
    assertSchemaRejects(
        "transaction_cash_and_card_carry_no_investment_fields",
        depot.id(),
        "'DEPOSIT', CURRENT_DATE, 100, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL",
        security);
    assertThat(countTransactions(depot.id())).isZero();
  }

  @Test
  void aReversingRowMirrorsTheSignsOfTheTradeItReverses() throws Exception {
    // FR-LIF-002: the (not yet built) void path inserts the same type with amounts negated and
    // replaces_transaction_id set. V36's sign rules must not block it; its shape rules still hold.
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createDepot(token, "CHF");
    UUID security = createSecurity(token);
    UUID original =
        postTransaction(token, depot.id(), trade("BUY", security, "10", "100", "5", "-1005.00"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody()
            .id();
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", depot.id());

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, security_id, quantity, unit_price,"
                    + " fee_amount, replaces_transaction_id) VALUES (?, ?, 'BUY', CURRENT_DATE,"
                    + " 1005, 'CHF', ?, -10, 100, 5, ?)")) {
      statement.setObject(1, workspaceId);
      statement.setObject(2, depot.id());
      statement.setObject(3, security);
      statement.setObject(4, original);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    assertThat(countTransactions(depot.id())).isEqualTo(2);
  }

  private AccountSummaryResponse createDepot(String token, String currency) {
    return createAccount(token, "Depot", "SECURITIES", currency, null);
  }

  private AccountSummaryResponse createPensionHoldingPositions(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account("VIAC 3a", "PENSION", "CHF")
                .holdsPositions(true)
                .pensionScheme("CH_PILLAR_3A")
                .build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // The story's own ISIN. The master is shared, so a later test finds the same record (200).
  private UUID createSecurity(String token) {
    return createSecurity(token, "IE00B4L5Y983");
  }

  private UUID createSecurity(String token, String isin) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                isin, "iShares Core MSCI World", "USD", "ETF", "EQUITY", null, null, null, null))
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private CreateTransactionRequest trade(
      String type,
      UUID securityId,
      String quantity,
      String unitPrice,
      String feeAmount,
      String amount) {
    return investment(
        type, amount, "CHF", securityId, quantity, unitPrice, feeAmount, null, null, null, null);
  }

  private CreateTransactionRequest tradeWithKey(
      UUID securityId, String quantity, String amount, String externalId) {
    return new CreateTransactionRequest(
        "BUY",
        today(),
        new BigDecimal(amount),
        "CHF",
        null,
        null,
        null,
        externalId,
        null,
        null,
        null,
        securityId,
        new BigDecimal(quantity),
        new BigDecimal("100"),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private CreateTransactionRequest investment(
      String type,
      String amount,
      String currency,
      UUID securityId,
      String quantity,
      String unitPrice,
      String feeAmount,
      LocalDate tradeDate,
      LocalDate settlementDate,
      String grossAmount,
      String taxWithheldAmount) {
    return new CreateTransactionRequest(
        type,
        today(),
        new BigDecimal(amount),
        currency,
        null,
        null,
        null,
        null,
        null,
        null,
        decimal(feeAmount),
        securityId,
        decimal(quantity),
        decimal(unitPrice),
        tradeDate,
        settlementDate,
        decimal(grossAmount),
        decimal(taxWithheldAmount),
        null,
        null);
  }

  private CreateTransactionRequest dividend(
      String amount,
      UUID securityId,
      String quantity,
      String unitPrice,
      String grossAmount,
      String taxWithheldAmount) {
    return investment(
        "DIVIDEND",
        amount,
        "CHF",
        securityId,
        quantity,
        unitPrice,
        null,
        null,
        null,
        grossAmount,
        taxWithheldAmount);
  }

  // A trade in a currency other than the account's, with the broker's rate or billed amount.
  private CreateTransactionRequest foreignTrade(
      String type,
      String amount,
      String currency,
      UUID securityId,
      String quantity,
      String unitPrice,
      String feeAmount,
      String fxRate,
      String billedAmount) {
    return new CreateTransactionRequest(
        type,
        today(),
        new BigDecimal(amount),
        currency,
        null,
        null,
        null,
        null,
        decimal(fxRate),
        decimal(billedAmount),
        decimal(feeAmount),
        securityId,
        decimal(quantity),
        decimal(unitPrice),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  // The same request under an idempotency key.
  private static CreateTransactionRequest keyed(CreateTransactionRequest request, String key) {
    return new CreateTransactionRequest(
        request.transactionType(),
        request.bookingDate(),
        request.amount(),
        request.currency(),
        request.merchantDescription(),
        request.mcc(),
        request.notes(),
        key,
        request.fxRateToAccountCurrency(),
        request.billedAmount(),
        request.feeAmount(),
        request.securityId(),
        request.quantity(),
        request.unitPrice(),
        request.tradeDate(),
        request.settlementDate(),
        request.grossAmount(),
        request.taxWithheldAmount(),
        null,
        null);
  }

  // A 422 and nothing recorded. Which rule rejected the request is asserted on the service
  // (TransactionServiceTest): a ResponseStatusException's reason does not reach the HTTP body.
  private void expectUnprocessable(String token, UUID accountId, CreateTransactionRequest request) {
    int before = countTransactions(accountId);
    postTransaction(token, accountId, request)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(accountId)).isEqualTo(before);
  }

  // Inserts a ledger row directly, bypassing TransactionService as another writer would, and
  // expects V36's named check constraint to refuse it. valuesAfterAccount lists, in order:
  // transaction_type, booking_date, amount, security_id, quantity, unit_price, fee_amount,
  // trade_date, gross_amount, tax_withheld_amount, net_amount; a "?" takes securityId.
  private void assertSchemaRejects(
      String constraint, UUID accountId, String valuesAfterAccount, UUID securityId) {
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", accountId);
    String sql =
        "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
            + " amount, security_id, quantity, unit_price, fee_amount, trade_date, gross_amount,"
            + " tax_withheld_amount, net_amount, currency) VALUES (?, ?, "
            + valuesAfterAccount
            + ", 'CHF')";
    assertThatThrownBy(
            () -> {
              try (Connection connection = dataSource.getConnection();
                  PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, workspaceId);
                statement.setObject(2, accountId);
                if (valuesAfterAccount.contains("?")) {
                  statement.setObject(3, securityId);
                }
                statement.executeUpdate();
              }
            })
        .as(constraint)
        .hasMessageContaining("\"" + constraint + "\"");
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private RestTestClient.ResponseSpec recordPurchase(
      String token, UUID accountId, String amount, String merchant, String mcc) {
    return postTransaction(
        token,
        accountId,
        TransactionRequests.cash(
            CREDIT_CARD_PURCHASE,
            today(),
            new BigDecimal(amount),
            "CHF",
            merchant,
            mcc,
            null,
            null,
            null,
            null,
            null));
  }

  private void recordPurchaseOn(String token, UUID accountId, String amount, LocalDate date) {
    postTransaction(
            token,
            accountId,
            TransactionRequests.cash(
                CREDIT_CARD_PURCHASE,
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
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private RestTestClient.ResponseSpec postTransaction(
      String token, UUID accountId, CreateTransactionRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private AccountValuation balance(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
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

  private AccountSummaryResponse createCard(String token) {
    return createAccount(token, "Visa Gold", "CREDIT_CARD", null);
  }

  // US-09-04: a card whose billing_currency is set independently of its nativeCurrency
  // (CreateAccountRequest lets the two legitimately differ).
  private AccountSummaryResponse createCardWithBillingCurrency(
      String token, String nativeCurrency, String billingCurrency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account("Business Card", "CREDIT_CARD", nativeCurrency)
                .billingCurrency(billingCurrency)
                .build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createCashAccount(String token) {
    return createAccount(token, "Everyday Checking", "CASH", null);
  }

  private AccountSummaryResponse createLoan(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account("Car Loan", "LOAN", "CHF")
                .originalPrincipal(new BigDecimal("10000"))
                .build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createPension(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account("Pillar 3a", "PENSION", "CHF")
                .pensionScheme("CH_PILLAR_3A")
                .build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createCustomAsset(String token) {
    return createAccount(token, "Vintage Car", "CUSTOM_ASSET", "VEHICLE");
  }

  private AccountSummaryResponse createAccount(
      String token, String name, String accountType, String customAssetType) {
    return createAccount(token, name, accountType, "CHF", customAssetType);
  }

  private AccountSummaryResponse createAccount(
      String token, String name, String accountType, String currency, String customAssetType) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account(name, accountType, currency)
                .customAssetType(customAssetType)
                .build())
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
    return jdbcUuid("SELECT workspace_member_id FROM app_user WHERE email = ?", email);
  }

  // The zone app.business-zone defaults to: what the service treats as "today", so a test's own
  // idea of today must not depend on the machine's zone (CI runs in UTC, a laptop in CET).
  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private CreateTransactionRequest purchaseWithKey(String amount, String externalId) {
    return TransactionRequests.cash(
        CREDIT_CARD_PURCHASE,
        today(),
        new BigDecimal(amount),
        "CHF",
        null,
        null,
        null,
        externalId,
        null,
        null,
        null);
  }

  private TransactionResponse recordWithKey(
      String token, UUID accountId, String amount, String externalId) {
    return postTransaction(token, accountId, purchaseWithKey(amount, externalId))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void recordPurchaseIn(String token, UUID accountId, String amount, String currency) {
    postTransaction(
            token,
            accountId,
            TransactionRequests.cash(
                CREDIT_CARD_PURCHASE,
                today(),
                new BigDecimal(amount),
                currency,
                null,
                null,
                null,
                null,
                null,
                null,
                null))
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private PageOf<TransactionResponse> listTransactions(String token, UUID accountId, String query) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  // Only the two members of Spring Data's page JSON these tests read; the rest is ignored.
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PageOf<T>(List<T> content, long totalElements) {}

  private void seedFxRate(String base, String quote, LocalDate date, String rate) {
    FxRate fxRate = new FxRate();
    fxRate.setBaseCurrency(base);
    fxRate.setQuoteCurrency(quote);
    fxRate.setRateDate(date);
    fxRate.setRate(new BigDecimal(rate));
    fxRate.setSource("MANUAL"); // matches app.fx.default-source's test-time default
    fxRateRepository.save(fxRate);
  }

  // A ledger row whose raw_source_data is whatever an importer (EPIC 07) might have written.
  private void insertLedgerRowWithSourceData(UUID accountId, String amount, String sourceJson) {
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", accountId);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                    + " amount, currency, raw_source_data) VALUES (?, ?, 'CREDIT_CARD_PURCHASE',"
                    + " CURRENT_DATE, ?, 'CHF', CAST(? AS jsonb))")) {
      statement.setObject(1, workspaceId);
      statement.setObject(2, accountId);
      statement.setBigDecimal(3, new BigDecimal(amount));
      statement.setString(4, sourceJson);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Direct ledger inserts, the way US-07-02's void path leaves the table: a voided original keeps
  // its financial fields and gets voided_at and a reason (V39 requires one), and the reversing row
  // is an ordinary new row of the opposite sign.
  private void insertLedgerRow(UUID accountId, String amount, boolean voided) {
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", accountId);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                    + " amount, currency, voided_at, void_reason) VALUES (?, ?,"
                    + " 'CREDIT_CARD_PURCHASE', CURRENT_DATE, ?, 'CHF', CASE WHEN ? THEN now() END,"
                    + " CASE WHEN ? THEN 'test' END)")) {
      statement.setObject(1, workspaceId);
      statement.setObject(2, accountId);
      statement.setBigDecimal(3, new BigDecimal(amount));
      statement.setBoolean(4, voided);
      statement.setBoolean(5, voided);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID insertTestCategory() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO category (code, name_en, name_de) VALUES (?, 'Groceries test',"
                    + " 'Lebensmittel test') RETURNING id")) {
      statement.setString(1, TEST_CATEGORY_CODE);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return (UUID) resultSet.getObject("id");
      }
    }
  }

  private void assignCategory(UUID transactionId, UUID categoryId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE transaction SET category_id = ? WHERE id = ?")) {
      statement.setObject(1, categoryId);
      statement.setObject(2, transactionId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private int countTransactions(UUID accountId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM transaction WHERE account_id = ?")) {
      statement.setObject(1, accountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID jdbcUuid(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as(sql).isTrue();
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
