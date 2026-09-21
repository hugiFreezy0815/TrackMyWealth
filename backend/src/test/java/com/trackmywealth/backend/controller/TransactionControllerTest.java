package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
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

  @Test
  void aForeignCurrencyPurchaseIsRejectedUntilUs0904() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    postTransaction(
            token,
            card.id(),
            new CreateTransactionRequest(
                CREDIT_CARD_PURCHASE,
                today(),
                new BigDecimal("-50.00"),
                "EUR",
                "Hotel",
                null,
                null,
                null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(countTransactions(card.id())).isZero();
  }

  @Test
  void anUnsupportedTransactionTypeIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);

    postTransaction(
            token,
            card.id(),
            new CreateTransactionRequest(
                "EXPENSE", today(), new BigDecimal("-5.00"), "CHF", null, null, null, null))
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
            null));
  }

  private void recordPurchaseOn(String token, UUID accountId, String amount, LocalDate date) {
    postTransaction(
            token,
            accountId,
            new CreateTransactionRequest(
                CREDIT_CARD_PURCHASE, date, new BigDecimal(amount), "CHF", null, null, null, null))
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

  private AccountSummaryResponse createCashAccount(String token) {
    return createAccount(token, "Everyday Checking", "CASH", null);
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
        CREDIT_CARD_PURCHASE, today(), new BigDecimal(amount), "CHF", null, null, null, externalId);
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
