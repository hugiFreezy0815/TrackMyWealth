package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

  @BeforeEach
  void cleanDatabase() throws Exception {
    // transaction first - it references account/workspace/app_user and must go before its parents.
    // Only this test's own category row is removed: V19 seeds system categories other tests and
    // the app itself rely on.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
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
    assertThat(created.bookingDate()).isEqualTo(LocalDate.now());

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
    recordPurchaseOn(token, card.id(), "-85.00", LocalDate.now().minusDays(2));
    recordPurchaseOn(token, card.id(), "-15.50", LocalDate.now());

    assertThat(balance(token, card.id()).value()).isEqualByComparingTo("100.50");

    List<TransactionResponse> listed =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + card.id() + "/transactions")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<TransactionResponse>>() {})
            .returnResult()
            .getResponseBody();
    assertThat(listed).extracting(TransactionResponse::amount).hasSize(2);
    assertThat(listed.get(0).amount()).isEqualByComparingTo("-15.50");
    assertThat(listed.get(1).amount()).isEqualByComparingTo("-85.00");
  }

  @Test
  void aPurchaseBookedInTheFutureDoesNotCountUntilItsBookingDate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createCard(token);
    recordPurchaseOn(token, card.id(), "-85.00", LocalDate.now());
    recordPurchaseOn(token, card.id(), "-40.00", LocalDate.now().plusDays(3));

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
    recordPurchaseOn(token, card.id(), "-85.00", LocalDate.now());

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
    recordPurchaseOn(token, card.id(), "-85.00", LocalDate.now());

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
    recordPurchaseOn(token, card.id(), "-85.00", LocalDate.now());

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
    List<TransactionResponse> listed =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + card.id() + "/transactions")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<TransactionResponse>>() {})
            .returnResult()
            .getResponseBody();
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
                LocalDate.now(),
                new BigDecimal("-50.00"),
                "EUR",
                "Hotel",
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
                "EXPENSE", LocalDate.now(), new BigDecimal("-5.00"), "CHF", null, null, null))
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
    recordPurchaseOn(adminToken, card.id(), "-85.00", LocalDate.now());
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
    recordPurchaseOn(adminToken, card.id(), "-85.00", LocalDate.now());
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
    recordPurchaseOn(adminToken, card.id(), "-85.00", LocalDate.now());
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

  // --- helpers ---------------------------------------------------------------------------------

  private RestTestClient.ResponseSpec recordPurchase(
      String token, UUID accountId, String amount, String merchant, String mcc) {
    return postTransaction(
        token,
        accountId,
        new CreateTransactionRequest(
            CREDIT_CARD_PURCHASE,
            LocalDate.now(),
            new BigDecimal(amount),
            "CHF",
            merchant,
            mcc,
            null));
  }

  private void recordPurchaseOn(String token, UUID accountId, String amount, LocalDate date) {
    postTransaction(
            token,
            accountId,
            new CreateTransactionRequest(
                CREDIT_CARD_PURCHASE, date, new BigDecimal(amount), "CHF", null, null, null))
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
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, name, accountType, "CHF", null, null, null, null, null, customAssetType))
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
        .body(new CreateCustomAssetValuationRequest(LocalDate.now(), new BigDecimal(value)))
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
