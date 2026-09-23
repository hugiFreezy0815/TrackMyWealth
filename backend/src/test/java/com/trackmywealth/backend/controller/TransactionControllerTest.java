package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
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
    return new CreateTransactionRequest(
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
    return new CreateTransactionRequest(
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
            new CreateTransactionRequest(
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
                new CreateAccountRequest(
                    null,
                    "Home Mortgage",
                    "MORTGAGE",
                    "CHF",
                    null,
                    null,
                    new BigDecimal("500000.00"),
                    new BigDecimal("1.5"),
                    null,
                    null))
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
    // TRANSFER/DEBT_REPAYMENT/PENSION_CONTRIBUTION -> US-10-01; BUY/SELL/DIVIDEND -> US-12-01.
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createCashAccount(token);

    for (String type :
        List.of(
            "TRANSFER",
            "DEBT_REPAYMENT",
            "PENSION_CONTRIBUTION",
            "BUY",
            "SELL",
            "DIVIDEND",
            "NOT_A_TYPE")) {
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
            new CreateTransactionRequest(
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
        new CreateTransactionRequest(
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
    return new CreateTransactionRequest(
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

  // --- helpers ---------------------------------------------------------------------------------

  private RestTestClient.ResponseSpec recordPurchase(
      String token, UUID accountId, String amount, String merchant, String mcc) {
    return postTransaction(
        token,
        accountId,
        new CreateTransactionRequest(
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
            new CreateTransactionRequest(
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
            new CreateAccountRequest(
                null,
                "Business Card",
                "CREDIT_CARD",
                nativeCurrency,
                null,
                billingCurrency,
                null,
                null,
                null,
                null))
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
            new CreateAccountRequest(
                null,
                "Car Loan",
                "LOAN",
                "CHF",
                null,
                null,
                new BigDecimal("10000"),
                null,
                null,
                null))
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
            new CreateAccountRequest(
                null, "Pillar 3a", "PENSION", "CHF", null, null, null, null, "CH_PILLAR_3A", null))
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
            new CreateAccountRequest(
                null, name, accountType, currency, null, null, null, null, null, customAssetType))
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
    return new CreateTransactionRequest(
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
            new CreateTransactionRequest(
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

  // Direct ledger inserts, the way the (not yet built) void path of US-07-02 would leave the table:
  // a voided original keeps its financial fields and gets voided_at set, and the reversing row is
  // an ordinary new row of the opposite sign.
  private void insertLedgerRow(UUID accountId, String amount, boolean voided) {
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", accountId);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                    + " amount, currency, voided_at) VALUES (?, ?, 'CREDIT_CARD_PURCHASE',"
                    + " CURRENT_DATE, ?, 'CHF', CASE WHEN ? THEN now() END)")) {
      statement.setObject(1, workspaceId);
      statement.setObject(2, accountId);
      statement.setBigDecimal(3, new BigDecimal(amount));
      statement.setBoolean(4, voided);
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
