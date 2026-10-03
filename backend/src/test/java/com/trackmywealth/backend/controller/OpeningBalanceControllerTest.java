package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.OpeningBalanceResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * US-25-04: a dated opening balance (FR-REC-007) and the "ledger from opening balance" value
 * source. One test per acceptance criterion, named after it, plus the DoD's net worth/institution
 * summary, authorization, cross-workspace and If-Match cases. The value arithmetic itself is pinned
 * without a database in {@code AccountValuationServiceTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpeningBalanceControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String MANUAL = "MANUAL";
  private static final String IMPORTED = "CSV";

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

  // The opening date of most tests: far enough back for rows on both sides of it.
  private final LocalDate openingDate = today().minusDays(60);

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM settlement_match",
              // reversals first: they reference their originals
              "DELETE FROM transaction WHERE replaces_transaction_id IS NOT NULL",
              "DELETE FROM transaction",
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM custom_asset_valuation",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
              "DELETE FROM account_securities",
              "DELETE FROM account_mortgage",
              "DELETE FROM account_loan",
              "DELETE FROM account_custom_asset",
              "DELETE FROM account_vested_benefits",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM authorization_denial_log",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  // --- AC: no opening balance, unchanged behaviour ------------------------------------------

  @Test
  void aCashAccountWithLedgerRowsButNoOpeningBalanceHasAnUnknownValue() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    insertTransaction(cash.id(), "EXPENSE", "-120.00", openingDate.plusDays(1), MANUAL);

    AccountValuation balance = balance(token, cash.id());

    assertThat(balance.valueKnown()).isFalse();
    assertThat(balance.value()).isNull();
    assertThat(balance.warnings()).isEmpty();
  }

  // --- AC: opening balance plus the ledger after it -----------------------------------------

  @Test
  void theBalanceIsTheOpeningBalancePlusTheLedgerAfterItAndNetWorthIncludesIt() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    // On the opening date: already contained in the balance, so not added again.
    insertTransaction(cash.id(), "EXPENSE", "-50.00", openingDate, MANUAL);
    // After it: -1,234.55 in total.
    insertTransaction(cash.id(), "EXPENSE", "-1000.00", openingDate.plusDays(1), MANUAL);
    insertTransaction(cash.id(), "EXPENSE", "-300.00", openingDate.plusDays(10), IMPORTED);
    insertTransaction(cash.id(), "INCOME", "65.45", today(), MANUAL);

    OpeningBalanceResponse recorded =
        recordOk(token, cash.id(), request(openingDate, "10000.00", "CHF"));

    assertThat(recorded.accountId()).isEqualTo(cash.id());
    assertThat(recorded.date()).isEqualTo(openingDate);
    assertThat(recorded.balance()).isEqualByComparingTo("10000.00");
    assertThat(recorded.currency()).isEqualTo("CHF");
    assertThat(recorded.warnings()).isEmpty();

    AccountValuation balance = balance(token, cash.id());
    assertThat(balance.valueKnown()).isTrue();
    assertThat(balance.value()).isEqualByComparingTo("8765.45");
    assertThat(balance.valueBasis()).isEqualTo(ValueBasisValues.LEDGER_FROM_OPENING_BALANCE);

    NetWorthResponse netWorth = netWorth(token);
    assertThat(netWorth.totalAssets()).isEqualByComparingTo("8765.45");
    assertThat(netWorth.netWorth()).isEqualByComparingTo("8765.45");
    assertThat(netWorth.complete()).isTrue();
    assertThat(netWorth.approximate()).isFalse();
    assertThat(netWorth.warnings()).isEmpty();

    InstitutionSummaryResponse summary = institutionSummary(token, cash.financialInstitutionId());
    assertThat(summary.totalAssets()).isEqualByComparingTo("8765.45");
    assertThat(summary.complete()).isTrue();
    assertThat(summary.accounts())
        .singleElement()
        .satisfies(
            contribution -> {
              assertThat(contribution.valueKnown()).isTrue();
              assertThat(contribution.valueInContainerCurrency()).isEqualByComparingTo("8765.45");
            });
  }

  // --- AC: before the opening date the value is unknown, not zero ----------------------------

  @Test
  void aBalanceReadBeforeTheOpeningDateIsUnknownAndOnItIsTheOpeningBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    insertTransaction(cash.id(), "EXPENSE", "-200.00", openingDate.plusDays(5), MANUAL);
    recordOk(token, cash.id(), request(openingDate, "10000.00", "CHF"));

    AccountValuation dayBefore = balance(token, cash.id(), openingDate.minusDays(1));
    assertThat(dayBefore.valueKnown()).isFalse();
    assertThat(dayBefore.value()).isNull();

    assertThat(balance(token, cash.id(), openingDate).value()).isEqualByComparingTo("10000.00");
    assertThat(balance(token, cash.id(), openingDate.plusDays(5)).value())
        .isEqualByComparingTo("9800.00");
  }

  @Test
  void aBalanceReadForAFutureDateIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");

    client(token)
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/balance?asOf=" + today().plusDays(1))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  // --- AC: the guard against rows before the opening date ------------------------------------

  @Test
  void anOpeningBalanceAfterExistingTransactionsIsRejectedWithTheEarliestBookingDate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    LocalDate earliest = openingDate.minusDays(20);
    insertTransaction(cash.id(), "EXPENSE", "-40.00", earliest, MANUAL);
    insertTransaction(cash.id(), "EXPENSE", "-60.00", openingDate.minusDays(3), MANUAL);
    // On the opening date: contained in the balance, not "before" it.
    insertTransaction(cash.id(), "EXPENSE", "-5.00", openingDate, MANUAL);

    record(token, cash.id(), request(openingDate, "1000.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("OPENING_BALANCE_AFTER_FIRST_TRANSACTION")
        .jsonPath("$.transactionCount")
        .isEqualTo(2)
        .jsonPath("$.earliestBookingDate")
        .isEqualTo(earliest.toString());
    assertThat(count("SELECT count(*) FROM account_snapshot WHERE account_id = ?", cash.id()))
        .isZero();

    // Moving the date back to the earliest row is accepted without an acknowledgement.
    assertThat(recordOk(token, cash.id(), request(earliest, "1000.00", "CHF")).warnings())
        .isEmpty();
  }

  @Test
  void acknowledgedEarlierTransactionsAreLeftOutAndWarnedAboutOnTheAccountAndTheHeadline() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    insertTransaction(cash.id(), "EXPENSE", "-40.00", openingDate.minusDays(20), MANUAL);
    insertTransaction(cash.id(), "EXPENSE", "-100.00", openingDate.plusDays(1), MANUAL);

    OpeningBalanceResponse recorded =
        recordOk(
            token,
            cash.id(),
            new OpeningBalanceRequest(openingDate, new BigDecimal("1000.00"), "CHF", true));

    String warning = DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE;
    assertThat(recorded.warnings()).containsExactly(warning);
    AccountValuation balance = balance(token, cash.id());
    assertThat(balance.value()).isEqualByComparingTo("900.00"); // the -40.00 is not counted
    assertThat(balance.warnings()).containsExactly(warning);
    assertThat(account(token, cash.id()).warnings()).containsExactly(warning);
    NetWorthResponse netWorth = netWorth(token);
    assertThat(netWorth.warnings()).containsExactly(warning);
    assertThat(netWorth.accounts().get(0).warnings()).containsExactly(warning);
    InstitutionSummaryResponse summary = institutionSummary(token, cash.financialInstitutionId());
    assertThat(summary.warnings()).containsExactly(warning);
    assertThat(summary.accounts().get(0).warnings()).containsExactly(warning);
  }

  // --- AC: a holding account stores it but stays unknown -------------------------------------

  @Test
  void aSecuritiesAccountStoresTheOpeningCashBalanceButItsValueStaysUnknown() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Swissquote Depot", "SECURITIES");

    OpeningBalanceResponse recorded =
        recordOk(token, depot.id(), request(openingDate, "2500.00", "CHF"));

    assertThat(get(token, depot.id())).isEqualTo(recorded);
    assertThat(balance(token, depot.id()).valueKnown()).isFalse();
  }

  // --- AC: types with a value source of their own --------------------------------------------

  @Test
  void aMortgageOrACustomAssetTakesNoOpeningBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse mortgage =
        createAccount(
            token,
            AccountRequests.account("Home Mortgage", "MORTGAGE", "CHF")
                .originalPrincipal(BigDecimal.valueOf(500000))
                .interestRatePercent(BigDecimal.valueOf(1.5))
                .build());
    AccountSummaryResponse home =
        createAccount(
            token,
            AccountRequests.account("Family Home", "CUSTOM_ASSET", "CHF")
                .customAssetType("REAL_ESTATE")
                .build());

    for (UUID accountId : List.of(mortgage.id(), home.id())) {
      record(token, accountId, request(openingDate, "1.00", "CHF"))
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("OPENING_BALANCE_NOT_APPLICABLE");
    }
  }

  @Test
  void anAccountWithoutALedgerIsWorthItsOpeningBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse vested =
        createAccount(
            token, AccountRequests.account("Vested Benefits", "VESTED_BENEFITS", "CHF").build());

    recordOk(token, vested.id(), request(openingDate, "84000.00", "CHF"));

    AccountValuation valuation = balance(token, vested.id());
    assertThat(valuation.value()).isEqualByComparingTo("84000.00");
    assertThat(valuation.valueBasis()).isEqualTo(ValueBasisValues.LATEST_SNAPSHOT);
    assertThat(valuation.valueSourceDate()).isEqualTo(openingDate);
  }

  // #232 review: without a ledger, the snapshots are the account's only record of its value - a
  // newer one supersedes the opening balance, and its date says how old the figure is.
  @Test
  void anAccountWithoutALedgerIsWorthItsLatestSnapshotNotItsOpeningBalanceForever() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse vested =
        createAccount(
            token, AccountRequests.account("Vested Benefits", "VESTED_BENEFITS", "CHF").build());
    recordOk(token, vested.id(), request(openingDate, "84000.00", "CHF"));
    LocalDate later = openingDate.plusDays(30);
    recordSnapshot(token, vested.id(), later, "86500.00");

    AccountValuation now = balance(token, vested.id());
    assertThat(now.value()).isEqualByComparingTo("86500.00");
    assertThat(now.valueSourceDate()).isEqualTo(later);
    // As of a day between the two, the opening balance is still the latest figure.
    AccountValuation between = balance(token, vested.id(), later.minusDays(1));
    assertThat(between.value()).isEqualByComparingTo("84000.00");
    assertThat(between.valueSourceDate()).isEqualTo(openingDate);
    // Before anything was recorded, nothing is known.
    assertThat(balance(token, vested.id(), openingDate.minusDays(1)).valueKnown()).isFalse();
    assertThat(netWorth(token).totalAssets()).isEqualByComparingTo("86500.00");
  }

  // --- AC: If-Match and currency -------------------------------------------------------------

  @Test
  void replacingNeedsTheCurrentVersionAndAStaleOneIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    OpeningBalanceResponse recorded =
        recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF"));

    OpeningBalanceResponse replaced =
        replaceOk(token, cash.id(), request(openingDate.minusDays(1), "1200.00", "CHF"), recorded);
    assertThat(replaced.id()).isEqualTo(recorded.id());
    assertThat(replaced.date()).isEqualTo(openingDate.minusDays(1));
    assertThat(replaced.balance()).isEqualByComparingTo("1200.00");
    assertThat(replaced.updatedAt()).isNotNull();
    assertThat(replaced.version()).isGreaterThan(recorded.version());

    replace(token, cash.id(), request(openingDate, "1.00", "CHF"), ifMatch(recorded.version()))
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    replace(token, cash.id(), request(openingDate, "1.00", "CHF"), null)
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    assertThat(get(token, cash.id()).balance()).isEqualByComparingTo("1200.00");
  }

  @Test
  void anOpeningBalanceInAnotherCurrencyIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");

    record(token, cash.id(), request(openingDate, "1000.00", "EUR"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    record(token, cash.id(), request(openingDate, "1000.00", "XYZ")).expectStatus().isBadRequest();
  }

  @Test
  void aSecondOpeningBalanceIsAConflictNamingTheFirst() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    OpeningBalanceResponse first =
        recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF"));

    record(token, cash.id(), request(openingDate.minusDays(1), "2000.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.existingOpeningBalanceId")
        .isEqualTo(first.id().toString());
  }

  // #241 review: two first opening balances at the same moment. Whichever check the loser hits -
  // the service's lookup or V58's unique index at the flush - it gets a 409, never a 500, and only
  // one row exists. Repeated on fresh accounts so the index path is exercised in practice too.
  @Test
  void twoConcurrentFirstOpeningBalancesYieldOneCreatedAndOneConflict() throws Exception {
    String token = bootstrapAdministrator();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        UUID accountId = createAccount(token, "Race " + round, "CASH").id();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt =
            () -> {
              start.await();
              return record(token, accountId, request(openingDate, "1000.00", "CHF"))
                  .returnResult(String.class)
                  .getStatus()
                  .value();
            };
        Future<Integer> first = pool.submit(attempt);
        Future<Integer> second = pool.submit(attempt);
        start.countDown();

        assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());
        assertThat(
                count(
                    "SELECT count(*) FROM account_snapshot"
                        + " WHERE account_id = ? AND is_opening_balance",
                    accountId))
            .isEqualTo(1);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void theDateIsNotInTheFutureNorBeforeTheAccountWasOpened() throws SQLException {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");

    record(token, cash.id(), request(today().plusDays(1), "1.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    execute("UPDATE account SET opened_at = ? WHERE id = ?", openingDate, cash.id());
    record(token, cash.id(), request(openingDate.minusDays(1), "1.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  // --- AC: removed transactions do not count -------------------------------------------------

  @Test
  void removedTransactionsAfterTheOpeningDateDoNotCount() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF"));
    insertTransaction(cash.id(), "EXPENSE", "-100.00", openingDate.plusDays(1), MANUAL);
    UUID typo = insertTransaction(cash.id(), "EXPENSE", "-999.00", openingDate.plusDays(2), MANUAL);
    UUID duplicate =
        insertTransaction(cash.id(), "EXPENSE", "-250.00", openingDate.plusDays(3), IMPORTED);
    assertThat(balance(token, cash.id()).value()).isEqualByComparingTo("-349.00");

    removeTransaction(token, cash.id(), typo, null); // manual: soft delete
    removeTransaction(token, cash.id(), duplicate, "duplicate"); // imported: void + reversal

    assertThat(balance(token, cash.id()).value()).isEqualByComparingTo("900.00");
  }

  // #241 review: a row recorded later but booked before the opening balance is left out of the
  // value. The member learns it from the row itself, at once - not only from the account.
  @Test
  void aTransactionBookedBeforeTheOpeningBalanceSaysSoOnTheRow() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF"));

    TransactionResponse before = recordTransaction(token, cash.id(), openingDate.minusDays(5));
    TransactionResponse onTheDay = recordTransaction(token, cash.id(), openingDate);
    TransactionResponse after = recordTransaction(token, cash.id(), openingDate.plusDays(1));

    assertThat(before.warnings())
        .containsExactly(DataQualityWarningValues.BOOKED_BEFORE_OPENING_BALANCE);
    assertThat(onTheDay.warnings()).isEmpty(); // contained in the balance by convention
    assertThat(after.warnings()).isEmpty();
    // The list says the same, and only the later row counts.
    client(token)
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.content[?(@.id == '" + before.id() + "')].warnings[0]")
        .isEqualTo(DataQualityWarningValues.BOOKED_BEFORE_OPENING_BALANCE)
        .jsonPath("$.content[?(@.id == '" + after.id() + "')].warnings.length()")
        .isEqualTo(0);
    assertThat(balance(token, cash.id()).value()).isEqualByComparingTo("990.00");
    assertThat(account(token, cash.id()).warnings())
        .containsExactly(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
  }

  @Test
  void removedTransactionsBeforeTheOpeningDateTriggerNeitherTheGuardNorTheWarning() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    UUID typo = insertTransaction(cash.id(), "EXPENSE", "-10.00", openingDate.minusDays(5), MANUAL);
    removeTransaction(token, cash.id(), typo, null);

    assertThat(recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF")).warnings())
        .isEmpty();
  }

  // --- Credit cards ----------------------------------------------------------------------------

  @Test
  void aCardWithAnOpeningBalanceOwesItPlusLaterPurchases() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "Visa", "CREDIT_CARD");
    insertTransaction(card.id(), "CREDIT_CARD_PURCHASE", "-80.00", openingDate.plusDays(2), MANUAL);
    insertTransaction(card.id(), "CREDIT_CARD_PURCHASE", "-20.00", openingDate.plusDays(3), MANUAL);

    // A liability's opening balance is the positive amount owed, as in a snapshot.
    recordOk(token, card.id(), request(openingDate, "500.00", "CHF"));

    AccountValuation balance = balance(token, card.id());
    assertThat(balance.nature()).isEqualTo("LIABILITY");
    assertThat(balance.value()).isEqualByComparingTo("600.00");
    assertThat(balance.valueBasis()).isEqualTo(ValueBasisValues.LEDGER_FROM_OPENING_BALANCE);
    assertThat(netWorth(token).totalLiabilities()).isEqualByComparingTo("600.00");
    assertThat(balance(token, card.id(), openingDate.minusDays(1)).valueKnown()).isFalse();
  }

  @Test
  void aCardsOpeningBalanceAndSnapshotsAreInItsBillingCurrency() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card =
        createAccount(
            token,
            AccountRequests.account("EUR Card", "CREDIT_CARD", "CHF")
                .billingCurrency("EUR")
                .build());

    record(token, card.id(), request(openingDate, "300.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(recordOk(token, card.id(), request(openingDate, "300.00", "EUR")).currency())
        .isEqualTo("EUR");
    AccountSnapshotResponse snapshot =
        client(token)
            .post()
            .uri("/api/v1/accounts/" + card.id() + "/snapshots")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new RecordAccountSnapshotRequest(today(), new BigDecimal("310.00"), List.of()))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(AccountSnapshotResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(snapshot.currency()).isEqualTo("EUR");
    assertThat(balance(token, card.id()).nativeCurrency()).isEqualTo("EUR");
  }

  @Test
  void theDatabaseKeepsACardSnapshotInItsBillingCurrency() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card =
        createAccount(
            token,
            AccountRequests.account("EUR Card", "CREDIT_CARD", "CHF")
                .billingCurrency("EUR")
                .build());

    assertThatThrownBy(() -> insertSnapshot(card.id(), "CHF"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("account_snapshot_currency_mismatch");
  }

  // --- V58 guards (defense-in-depth: the service never writes these) -------------------------

  @Test
  void theDatabaseAllowsOneOpeningBalancePerAccountAndOnlyAsAManualBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF"));

    assertThatThrownBy(() -> insertOpeningBalance(cash.id(), openingDate.minusDays(1), MANUAL))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uq_account_snapshot_opening_balance");
    AccountSummaryResponse savings = createAccount(token, "Savings", "SAVINGS");
    assertThatThrownBy(() -> insertOpeningBalance(savings.id(), openingDate, "DOCUMENT"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_account_snapshot_opening_balance_manual");
  }

  // --- The opening balance among the snapshots ---------------------------------------------

  @Test
  void aRegularSnapshotOnTheSameDateConflictsBothWays() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    AccountSnapshotResponse snapshot = recordSnapshot(token, cash.id(), openingDate, "999.00");

    record(token, cash.id(), request(openingDate, "1000.00", "CHF"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.existingSnapshotId")
        .isEqualTo(snapshot.id().toString());

    OpeningBalanceResponse opening =
        recordOk(token, cash.id(), request(openingDate.minusDays(1), "1000.00", "CHF"));
    client(token)
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new RecordAccountSnapshotRequest(
                openingDate.minusDays(1), new BigDecimal("1.00"), List.of()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.existingSnapshotId")
        .isEqualTo(opening.id().toString());

    // Replacing the opening balance onto the snapshot's date is the same conflict.
    replace(token, cash.id(), request(openingDate, "1000.00", "CHF"), ifMatch(opening.version()))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void theOpeningBalanceIsListedAmongTheSnapshotsButOnlyEditableThroughItsOwnEndpoint() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    OpeningBalanceResponse opening =
        recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF"));

    AccountSnapshotResponse[] snapshots =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + cash.id() + "/snapshots")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSnapshotResponse[].class)
            .returnResult()
            .getResponseBody();
    assertThat(snapshots)
        .singleElement()
        .satisfies(
            snapshot -> {
              assertThat(snapshot.id()).isEqualTo(opening.id());
              assertThat(snapshot.openingBalance()).isTrue();
              assertThat(snapshot.source()).isEqualTo(MANUAL);
              assertThat(snapshot.holdings()).isEmpty();
            });

    client(token)
        .put()
        .uri("/api/v1/accounts/" + cash.id() + "/snapshots/" + opening.id())
        .headers(headers -> headers.setIfMatch(ifMatch(opening.version())))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReplaceAccountSnapshotRequest(new BigDecimal("1.00"), List.of()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(get(token, cash.id()).balance()).isEqualByComparingTo("1000.00");
  }

  // --- Delete ----------------------------------------------------------------------------------

  @Test
  void deletingTheOpeningBalanceMakesTheValueUnknownAgain() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "PostFinance", "CASH");
    OpeningBalanceResponse opening =
        recordOk(token, cash.id(), request(openingDate, "1000.00", "CHF"));

    delete(token, cash.id(), null).expectStatus().isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    delete(token, cash.id(), ifMatch(opening.version())).expectStatus().isNoContent();

    // Its own code, so a client knows to offer "record one" rather than "no such account".
    client(token)
        .get()
        .uri(openingBalanceUri(cash.id()))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.OPENING_BALANCE_NOT_RECORDED);
    delete(token, cash.id(), ifMatch(opening.version()))
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.OPENING_BALANCE_NOT_RECORDED);
    assertThat(balance(token, cash.id()).valueKnown()).isFalse();
  }

  // --- Authorization -------------------------------------------------------------------------

  @Test
  void aBalanceOnlyMemberSeesTheResultingBalanceButNotTheOpeningBalance() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(adminToken, "PostFinance", "CASH");
    recordOk(adminToken, cash.id(), request(openingDate, "1000.00", "CHF"));
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.BALANCE_ONLY);
    assertThat(balance(memberToken, cash.id()).value()).isEqualByComparingTo("1000.00");
    client(memberToken)
        .get()
        .uri(openingBalanceUri(cash.id()))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void aReadOnlyMemberCanSeeTheOpeningBalanceButOnlyAnEditorCanRecordIt() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(adminToken, "PostFinance", "CASH");
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    // No grant at all: the plain NOT_FOUND an unknown id gets - never OPENING_BALANCE_NOT_RECORDED,
    // which would confirm the account exists.
    client(memberToken)
        .get()
        .uri(openingBalanceUri(cash.id()))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.NOT_FOUND);
    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.READ);
    record(memberToken, cash.id(), request(openingDate, "1.00", "CHF"))
        .expectStatus()
        .isNotFound(); // no hint the account exists, as for snapshots
    OpeningBalanceResponse recorded =
        recordOk(adminToken, cash.id(), request(openingDate, "1.00", "CHF"));
    assertThat(get(memberToken, cash.id())).isEqualTo(recorded);
    delete(memberToken, cash.id(), ifMatch(recorded.version())).expectStatus().isNotFound();
  }

  // --- Helpers ------------------------------------------------------------------------------

  private static OpeningBalanceRequest request(LocalDate date, String balance, String currency) {
    return new OpeningBalanceRequest(date, new BigDecimal(balance), currency, null);
  }

  private static String openingBalanceUri(UUID accountId) {
    return "/api/v1/accounts/" + accountId + "/opening-balance";
  }

  private RestTestClient.ResponseSpec record(
      String token, UUID accountId, OpeningBalanceRequest request) {
    return client(token)
        .post()
        .uri(openingBalanceUri(accountId))
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private OpeningBalanceResponse recordOk(
      String token, UUID accountId, OpeningBalanceRequest request) {
    EntityExchangeResult<OpeningBalanceResponse> result =
        record(token, accountId, request)
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(OpeningBalanceResponse.class)
            .returnResult();
    OpeningBalanceResponse response = result.getResponseBody();
    return CurrentVersion.storedEtag(result, dataSource, "account_snapshot", response.id());
  }

  private RestTestClient.ResponseSpec replace(
      String token, UUID accountId, OpeningBalanceRequest request, String ifMatch) {
    return client(token)
        .put()
        .uri(openingBalanceUri(accountId))
        .headers(
            headers -> {
              if (ifMatch != null) {
                headers.setIfMatch(ifMatch);
              }
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private OpeningBalanceResponse replaceOk(
      String token, UUID accountId, OpeningBalanceRequest request, OpeningBalanceResponse read) {
    EntityExchangeResult<OpeningBalanceResponse> result =
        replace(token, accountId, request, ifMatch(read.version()))
            .expectStatus()
            .isOk()
            .expectBody(OpeningBalanceResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(result, dataSource, "account_snapshot", read.id());
  }

  private RestTestClient.ResponseSpec delete(String token, UUID accountId, String ifMatch) {
    return client(token)
        .delete()
        .uri(openingBalanceUri(accountId))
        .headers(
            headers -> {
              if (ifMatch != null) {
                headers.setIfMatch(ifMatch);
              }
            })
        .exchange();
  }

  private OpeningBalanceResponse get(String token, UUID accountId) {
    return client(token)
        .get()
        .uri(openingBalanceUri(accountId))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(OpeningBalanceResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static String ifMatch(int version) {
    return "\"" + version + "\"";
  }

  private AccountValuation balance(String token, UUID accountId) {
    return balance(token, accountId, null);
  }

  private AccountValuation balance(String token, UUID accountId, LocalDate asOf) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance" + (asOf == null ? "" : "?asOf=" + asOf))
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

  private InstitutionSummaryResponse institutionSummary(String token, UUID institutionId) {
    return client(token)
        .get()
        .uri("/api/v1/institutions/" + institutionId + "/summary")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(InstitutionSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse account(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSnapshotResponse recordSnapshot(
      String token, UUID accountId, LocalDate date, String balance) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RecordAccountSnapshotRequest(date, new BigDecimal(balance), List.of()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSnapshotResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionResponse recordTransaction(String token, UUID accountId, LocalDate date) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                "EXPENSE",
                date,
                new BigDecimal("-10.00"),
                "CHF",
                "Coffee",
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

  private void removeTransaction(String token, UUID accountId, UUID transactionId, String reason) {
    client(token)
        .delete()
        .uri(
            "/api/v1/accounts/"
                + accountId
                + "/transactions/"
                + transactionId
                + (reason == null ? "" : "?reason=" + reason))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", transactionId))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private AccountSummaryResponse createAccount(String token, String name, String accountType) {
    return createAccount(token, AccountRequests.account(name, accountType, "CHF").build());
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

  // A ledger row as the API or an import would leave it, on any date. A MANUAL row is
  // soft-deleted when removed, an imported (CSV) one voided with a reversal (US-07-02).
  private UUID insertTransaction(
      UUID accountId, String type, String amount, LocalDate bookedOn, String source) {
    UUID id = UUID.randomUUID();
    try {
      execute(
          "INSERT INTO transaction (id, workspace_id, account_id, transaction_type, booking_date,"
              + " amount, currency, source) SELECT ?, workspace_id, id, ?, ?, ?, 'CHF', ? FROM"
              + " account WHERE id = ?",
          id,
          type,
          bookedOn,
          new BigDecimal(amount),
          source,
          accountId);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  // Bypasses the service, as a provider import would.
  private void insertSnapshot(UUID accountId, String currency) throws SQLException {
    execute(
        "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance, currency,"
            + " source) SELECT workspace_id, id, ?, 1.00, ?, 'DOCUMENT' FROM account WHERE id = ?",
        today(),
        currency,
        accountId);
  }

  private void insertOpeningBalance(UUID accountId, LocalDate date, String source)
      throws SQLException {
    execute(
        "INSERT INTO account_snapshot (workspace_id, account_id, snapshot_date, balance, currency,"
            + " source, is_opening_balance) SELECT workspace_id, id, ?, 1.00, 'CHF', ?, TRUE FROM"
            + " account WHERE id = ?",
        date,
        source,
        accountId);
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
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return (UUID) resultSet.getObject(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql, UUID parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // The zone app.business-zone defaults to, as in TransactionControllerTest.
  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
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
