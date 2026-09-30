package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SetTransactionCategoryRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SettlementDetectionService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-02: removing a transaction the way its provenance requires - a manual row is soft-deleted
 * (T1) and restorable for 30 days, an imported row is voided and reversed (T2). The DoD's tests are
 * {@link #aManualTransactionIsSoftDeletedAndRestorable} (T1), {@link
 * #anImportedTransactionIsVoidedWithAReversingEntry} (T2) and {@link
 * #theLedgerTriggerRejectsAFinancialUpdateWhateverTheTier}; T3 arrives with reconciliation
 * (US-25-02). Imports do not exist yet, so an imported row is inserted the way one would leave it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionRemovalControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String PURCHASE = "CREDIT_CARD_PURCHASE";
  private static final String SOFT_DELETE = "SOFT_DELETE";
  private static final String VOID = "VOID";

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

  @Autowired SettlementDetectionService settlementDetectionService;

  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM settlement_match",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM categorization_rule",
              // reversals first: they reference their originals
              "DELETE FROM transaction WHERE replaces_transaction_id IS NOT NULL",
              "DELETE FROM transaction WHERE related_transaction_id IS NOT NULL",
              "DELETE FROM transaction",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
              "DELETE FROM account_securities",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  // --- T1: manual entry, soft delete ------------------------------------------------------------

  @Test
  void aManualTransactionIsSoftDeletedAndRestorable() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse kept = record(token, card.id(), purchase("-20.00"));
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));
    assertThat(typo.removal()).isEqualTo(SOFT_DELETE);
    assertThat(balance(token, card.id())).isEqualByComparingTo("105.00");

    TransactionRemovalResponse removed = remove(token, card.id(), typo.id(), null);

    assertThat(removed.removal()).isEqualTo(SOFT_DELETE);
    assertThat(removed.reversals()).isEmpty();
    assertThat(removed.affected())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(typo.id());
              assertThat(row.deletedAt()).isNotNull();
              assertThat(row.removal()).isNull();
            });
    // Hidden from every view and figure; no compensating entry.
    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(kept.id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("20.00");
    assertThat(spending(token)).isEqualByComparingTo("20.00");
    assertThat(countRows(card.id())).isEqualTo(2);
    assertThat(restorable(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(typo.id());

    TransactionRemovalResponse restored = restore(token, card.id(), typo.id());

    assertThat(restored.affected()).extracting(TransactionResponse::id).containsExactly(typo.id());
    assertThat(list(token, card.id())).hasSize(2);
    assertThat(balance(token, card.id())).isEqualByComparingTo("105.00");
    assertThat(restorable(token, card.id())).isEmpty();
  }

  @Test
  void aSoftDeleteCannotBeRestoredAfterThirtyDays() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));
    remove(token, card.id(), typo.id(), null);
    execute(
        "UPDATE transaction SET deleted_at = now() - interval '31 days' WHERE id = ?", typo.id());

    assertThat(restorable(token, card.id())).isEmpty();
    client(token)
        .post()
        .uri(rowUri(card.id(), typo.id()) + "/restore")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    // Still in the data (never purged, FR-LIF-001), still hidden.
    assertThat(countRows(card.id())).isEqualTo(1);
    assertThat(list(token, card.id())).isEmpty();
  }

  @Test
  void aCardPurchasesFeeRowGoesAndComesBackWithIt() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse foreign =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                today(),
                new BigDecimal("-100.00"),
                "EUR",
                "Hotel Berlin",
                null,
                null,
                null,
                new BigDecimal("0.95"),
                null,
                new BigDecimal("1.50")));
    assertThat(list(token, card.id())).hasSize(2);

    TransactionRemovalResponse removed = remove(token, card.id(), foreign.id(), null);

    assertThat(removed.affected()).hasSize(2);
    assertThat(list(token, card.id())).isEmpty();

    TransactionRemovalResponse restored = restore(token, card.id(), foreign.id());

    assertThat(restored.affected()).hasSize(2);
    assertThat(list(token, card.id())).hasSize(2);
  }

  @Test
  void aRetryWithTheKeyOfADeletedTransactionIsAConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    CreateTransactionRequest keyed =
        TransactionRequests.cash(
            PURCHASE,
            today(),
            new BigDecimal("-85.00"),
            "CHF",
            null,
            null,
            null,
            "key-1",
            null,
            null,
            null);
    TransactionResponse recorded = record(token, card.id(), keyed);
    remove(token, card.id(), recorded.id(), null);

    post(token, card.id(), keyed).expectStatus().isEqualTo(HttpStatus.CONFLICT);
    assertThat(countRows(card.id())).isEqualTo(1);
  }

  // --- T2: imported, void -----------------------------------------------------------------------

  @Test
  void anImportedTransactionIsVoidedWithAReversingEntry() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", LocalDate.now());
    TransactionResponse listed = list(token, card.id()).get(0);
    assertThat(listed.removal()).isEqualTo(VOID);
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");

    // The system decided on a void, which needs a reason.
    client(token)
        .delete()
        .uri(rowUri(card.id(), imported))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    TransactionRemovalResponse voided = remove(token, card.id(), imported, "Duplicate import");

    assertThat(voided.removal()).isEqualTo(VOID);
    assertThat(voided.affected())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.voidedAt()).isNotNull();
              assertThat(row.voidReason()).isEqualTo("Duplicate import");
              assertThat(row.amount()).isEqualByComparingTo("-85.00"); // financial fields untouched
            });
    assertThat(voided.reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.amount()).isEqualByComparingTo("85.00");
              assertThat(reversal.transactionType()).isEqualTo(PURCHASE);
              assertThat(reversal.replacesTransactionId()).isEqualTo(imported);
              assertThat(reversal.bookingDate()).isEqualTo(today());
              assertThat(reversal.categoryId()).isNull();
              assertThat(reversal.removal()).isNull();
            });
    // Both stay listed (FR-LIF-003); balances net to zero; spending leaves the pair out.
    assertThat(list(token, card.id())).hasSize(2);
    assertThat(balance(token, card.id())).isEqualByComparingTo("0");
    assertThat(spending(token)).isEqualByComparingTo("0");
    assertThat(uncategorized(token, card.id())).isEmpty();

    // Neither the voided original nor its reversal can be removed again.
    client(token)
        .delete()
        .uri(rowUri(card.id(), imported) + "?reason=again")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    client(token)
        .delete()
        .uri(rowUri(card.id(), voided.reversals().get(0).id()) + "?reason=again")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    // A reversal's category follows its original.
    client(token)
        .put()
        .uri(rowUri(card.id(), voided.reversals().get(0).id()) + "/category")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(defaultCategory("SHOPPING")))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void aVoidedTradeIsReversedWithItsQuantityNegated() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "SECURITIES", "CHF");
    UUID security = createSecurity(token);
    UUID buy = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, security_id, quantity, unit_price,"
                    + " fee_amount, source) SELECT ?, workspace_id, id, 'BUY', CURRENT_DATE,"
                    + " -1005, 'CHF', ?, 10, 100, 5, 'CSV' FROM account WHERE id = ?")) {
      statement.setObject(1, buy);
      statement.setObject(2, security);
      statement.setObject(3, depot.id());
      statement.executeUpdate();
    }

    TransactionRemovalResponse voided = remove(token, depot.id(), buy, "Wrong depot");

    assertThat(voided.reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.quantity()).isEqualByComparingTo("-10");
              assertThat(reversal.amount()).isEqualByComparingTo("1005");
              assertThat(reversal.unitPrice()).isEqualByComparingTo("100");
              assertThat(reversal.feeAmount()).isEqualByComparingTo("5");
              assertThat(reversal.securityId()).isEqualTo(security);
            });
    assertThat(
            queryDecimal("SELECT sum(quantity) FROM transaction WHERE account_id = ?", depot.id()))
        .isEqualByComparingTo("0");
  }

  @Test
  void aFutureDatedVoidIsReversedOnItsOwnDate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    LocalDate later = today().plusDays(10);
    UUID imported = insertImported(card.id(), PURCHASE, "-40.00", later);

    TransactionRemovalResponse voided = remove(token, card.id(), imported, "Cancelled");

    assertThat(voided.reversals().get(0).bookingDate()).isEqualTo(later);
  }

  // --- the append-only backstop -----------------------------------------------------------------

  @Test
  void theLedgerTriggerRejectsAFinancialUpdateWhateverTheTier() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse manual = record(token, card.id(), purchase("-85.00"));
    remove(token, card.id(), manual.id(), null);
    UUID imported = insertImported(card.id(), PURCHASE, "-40.00", LocalDate.now());
    remove(token, card.id(), imported, "Duplicate");

    for (UUID row : List.of(manual.id(), imported)) {
      assertThatThrownBy(() -> execute("UPDATE transaction SET amount = -1 WHERE id = ?", row))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("transaction_ledger_append_only");
    }
  }

  // --- linked rows -----------------------------------------------------------------------------

  @Test
  void removingAMatchedPaymentDissolvesTheMatchAndFreesTheCredit() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    TransactionResponse payment =
        record(
            token,
            checking.id(),
            TransactionRequests.cash(
                "WITHDRAWAL",
                today(),
                new BigDecimal("-300.00"),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    TransactionResponse credit =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                "SETTLEMENT",
                today(),
                new BigDecimal("300.00"),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    confirmMatch(card.id(), payment.id(), credit.id());

    TransactionRemovalResponse removed = remove(token, checking.id(), payment.id(), null);

    assertThat(removed.unmatchedTransactionIds()).containsExactly(credit.id());
    assertThat(
            queryDecimal(
                "SELECT count(*) FROM settlement_match WHERE card_account_id = ?", card.id()))
        .isEqualByComparingTo("0");
    TransactionResponse freed = list(token, card.id()).get(0);
    assertThat(freed.internalTransfer()).isFalse();
  }

  // --- review findings on PR #180 ------------------------------------------------------------

  // V36 required tax_withheld_amount >= 0 on every row; the reversal negates it (V40).
  @Test
  void aVoidedDividendWithWithholdingTaxIsReversedInFull() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "SECURITIES", "CHF");
    UUID security = createSecurity(token);
    UUID dividend = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, security_id, gross_amount,"
                    + " tax_withheld_amount, net_amount, source) SELECT ?, workspace_id, id,"
                    + " 'DIVIDEND', CURRENT_DATE, 75, 'CHF', ?, 100, 25, 75, 'CSV' FROM account"
                    + " WHERE id = ?")) {
      statement.setObject(1, dividend);
      statement.setObject(2, security);
      statement.setObject(3, depot.id());
      statement.executeUpdate();
    }

    TransactionRemovalResponse voided = remove(token, depot.id(), dividend, "Booked twice");

    assertThat(voided.reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.amount()).isEqualByComparingTo("-75");
              assertThat(reversal.grossAmount()).isEqualByComparingTo("-100");
              assertThat(reversal.taxWithheldAmount()).isEqualByComparingTo("-25");
              assertThat(reversal.netAmount()).isEqualByComparingTo("-75");
            });
    assertThat(queryDecimal("SELECT sum(amount) FROM transaction WHERE account_id = ?", depot.id()))
        .isEqualByComparingTo("0");
  }

  // A soft delete used to drop the row's rejected matches, so a restore re-confirmed the very pair
  // the member had rejected.
  @Test
  void aRejectedPairStaysRejectedThroughASoftDeleteAndRestore() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    setSettlementSource(token, card.id(), checking.id());
    TransactionResponse payment = record(token, checking.id(), cashRow("WITHDRAWAL", "-300.00"));
    TransactionResponse credit = record(token, card.id(), cashRow("SETTLEMENT", "300.00"));
    UUID match = matchIdOf(card.id());
    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + match + "/reject")
        .exchange()
        .expectStatus()
        .isOk();

    remove(token, card.id(), credit.id(), null);

    assertThat(rejectedMatches(card.id())).isEqualByComparingTo("1");
    // While its card leg is deleted the match is neither listed nor actionable - and loads fine.
    assertThat(settlementMatches(token, "REJECTED")).isEmpty();
    client(token)
        .post()
        .uri("/api/v1/accounts/" + card.id() + "/settlement-matches/run")
        .exchange()
        .expectStatus()
        .isOk();
    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + match + "/confirm")
        .exchange()
        .expectStatus()
        .isNotFound();

    restore(token, card.id(), credit.id());

    assertThat(rejectedMatches(card.id())).isEqualByComparingTo("1");
    assertThat(
            queryDecimal(
                "SELECT count(*) FROM settlement_match WHERE card_account_id = ?", card.id()))
        .isEqualByComparingTo("1");
    assertThat(list(token, checking.id()))
        .filteredOn(row -> row.id().equals(payment.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.internalTransfer()).isFalse());
    assertThat(settlementMatches(token, "REJECTED")).singleElement().isEqualTo(match);
  }

  // Voiding a +500 card credit adds a -500 SETTLEMENT reversal; it is not a payment to review.
  @Test
  void aVoidedCardCreditIsNotAnUnresolvedSettlement() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID credit = insertImported(card.id(), "SETTLEMENT", "500.00", today());

    remove(token, card.id(), credit, "Duplicate import");

    assertThat(pendingReview(token)).isEmpty();
  }

  // Removal used to lock only the cards already matched to the row. A detection run holding a
  // card's lock may be about to match this unmatched payment, so removal must wait for it.
  @Test
  void aRemovalWaitsForMatchingOnTheCardEvenBeforeTheRowIsMatched() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    setSettlementSource(token, card.id(), checking.id());
    TransactionResponse payment = record(token, checking.id(), cashRow("WITHDRAWAL", "-120.00"));
    AuthenticatedUserPrincipal admin = adminPrincipal();

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> matching =
          pool.submit(
              () -> {
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(admin, null, List.of()));
                try {
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            settlementDetectionService.lockCard(card.id());
                            locked.countDown();
                            await(release);
                          });
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
      Future<TransactionRemovalResponse> removal =
          pool.submit(() -> remove(token, checking.id(), payment.id(), null));

      assertThatThrownBy(() -> removal.get(1, TimeUnit.SECONDS))
          .isInstanceOf(TimeoutException.class);
      release.countDown();
      matching.get(30, TimeUnit.SECONDS);
      assertThat(removal.get(30, TimeUnit.SECONDS).removal()).isEqualTo(SOFT_DELETE);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  // --- access ----------------------------------------------------------------------------------

  @Test
  void removingAndRestoringNeedEditOnTheAccount() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(adminToken, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(adminToken, card.id(), purchase("-85.00"));
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    grantAccount(adminToken, memberId, card.id(), AccessLevelValues.READ);

    client(memberToken)
        .delete()
        .uri(rowUri(card.id(), typo.id()))
        .exchange()
        .expectStatus()
        .isNotFound();
    // Reading the restore list needs READ only.
    remove(adminToken, card.id(), typo.id(), null);
    assertThat(restorable(memberToken, card.id())).hasSize(1);
    client(memberToken)
        .post()
        .uri(rowUri(card.id(), typo.id()) + "/restore")
        .exchange()
        .expectStatus()
        .isNotFound();

    grantAccount(adminToken, memberId, card.id(), AccessLevelValues.EDIT);
    restore(memberToken, card.id(), typo.id());
    assertThat(list(adminToken, card.id())).hasSize(1);
  }

  @Test
  void anotherAccountsTransactionIsNotFound() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse other = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));

    client(token)
        .delete()
        .uri(rowUri(other.id(), typo.id()))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(list(token, card.id())).hasSize(1);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private static CreateTransactionRequest purchase(String amount) {
    return TransactionRequests.cash(
        PURCHASE,
        today(),
        new BigDecimal(amount),
        "CHF",
        "Shop",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static String rowUri(UUID accountId, UUID transactionId) {
    return "/api/v1/accounts/" + accountId + "/transactions/" + transactionId;
  }

  private RestTestClient.ResponseSpec post(
      String token, UUID accountId, CreateTransactionRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private TransactionResponse record(
      String token, UUID accountId, CreateTransactionRequest request) {
    return post(token, accountId, request)
        .expectStatus()
        .isCreated()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionRemovalResponse remove(
      String token, UUID accountId, UUID transactionId, String reason) {
    return client(token)
        .delete()
        .uri(rowUri(accountId, transactionId) + (reason == null ? "" : "?reason=" + reason))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TransactionRemovalResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionRemovalResponse restore(String token, UUID accountId, UUID transactionId) {
    return client(token)
        .post()
        .uri(rowUri(accountId, transactionId) + "/restore")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TransactionRemovalResponse.class)
        .returnResult()
        .getResponseBody();
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  private List<TransactionResponse> list(String token, UUID accountId) {
    return page(token, "/api/v1/accounts/" + accountId + "/transactions");
  }

  private List<TransactionResponse> uncategorized(String token, UUID accountId) {
    return page(token, "/api/v1/accounts/" + accountId + "/transactions?uncategorized=true");
  }

  private List<TransactionResponse> page(String token, String uri) {
    return client(token)
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  private List<TransactionResponse> restorable(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions/deleted")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private BigDecimal balance(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
        .returnResult()
        .getResponseBody()
        .value();
  }

  // This month's CHF spending across the workspace.
  private BigDecimal spending(String token) {
    CashFlowResponse flow =
        client(token)
            .get()
            .uri("/api/v1/cash-flow?month=" + YearMonth.from(today()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(CashFlowResponse.class)
            .returnResult()
            .getResponseBody();
    return flow.spending().stream()
        .filter(line -> "CHF".equals(line.currency()))
        .map(CashFlowResponse.CurrencyAmount::amount)
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  private static CreateTransactionRequest cashRow(String type, String amount) {
    return TransactionRequests.cash(
        type, today(), new BigDecimal(amount), "CHF", null, null, null, null, null, null, null);
  }

  private void setSettlementSource(String token, UUID cardAccountId, UUID sourceAccountId) {
    client(token)
        .put()
        .uri("/api/v1/accounts/" + cardAccountId + "/settlement-source")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetSettlementSourceRequest(sourceAccountId))
        .exchange()
        .expectStatus()
        .is2xxSuccessful();
  }

  private UUID matchIdOf(UUID cardAccountId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM settlement_match WHERE card_account_id = ?")) {
      statement.setObject(1, cardAccountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("a match for the card").isTrue();
        return (UUID) resultSet.getObject(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private BigDecimal rejectedMatches(UUID cardAccountId) {
    return queryDecimal(
        "SELECT count(*) FROM settlement_match WHERE card_account_id = ? AND status = 'REJECTED'",
        cardAccountId);
  }

  private List<UUID> settlementMatches(String token, String status) {
    return client(token)
        .get()
        .uri("/api/v1/settlement-matches?status=" + status)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<SettlementMatchResponse>>() {})
        .returnResult()
        .getResponseBody()
        .stream()
        .map(SettlementMatchResponse::id)
        .toList();
  }

  private List<CashFlowResponse.CurrencyAmount> pendingReview(String token) {
    return client(token)
        .get()
        .uri("/api/v1/cash-flow?month=" + YearMonth.from(today()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody()
        .pendingReview();
  }

  private AuthenticatedUserPrincipal adminPrincipal() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT u.id, wm.workspace_id FROM app_user u JOIN workspace_member wm ON wm.id"
                    + " = u.workspace_member_id WHERE u.email = 'admin@example.com'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return new AuthenticatedUserPrincipal(
            (UUID) resultSet.getObject(1),
            "SYSTEM_ADMINISTRATOR",
            (UUID) resultSet.getObject(2),
            UUID.randomUUID());
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // An imported row as a CSV import (EPIC 07) would leave it: source CSV, so its removal is a void.
  private UUID insertImported(UUID accountId, String type, String amount, LocalDate bookedOn) {
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, source) SELECT ?, workspace_id, id, ?, ?,"
                    + " ?, 'CHF', 'CSV' FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setString(2, type);
      statement.setObject(3, bookedOn);
      statement.setBigDecimal(4, new BigDecimal(amount));
      statement.setObject(5, accountId);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  // A confirmed LEG_PAIR match flags both legs an internal transfer (US-09-02).
  private void confirmMatch(UUID cardAccountId, UUID paymentId, UUID creditId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO settlement_match (workspace_id, card_account_id,"
                    + " payment_transaction_id, card_transaction_id, status, match_basis)"
                    + " SELECT workspace_id, id, ?, ?, 'CONFIRMED', 'LEG_PAIR' FROM account"
                    + " WHERE id = ?")) {
      statement.setObject(1, paymentId);
      statement.setObject(2, creditId);
      statement.setObject(3, cardAccountId);
      statement.executeUpdate();
    }
    execute("UPDATE transaction SET is_internal_transfer = TRUE WHERE id = ?", paymentId);
    execute("UPDATE transaction SET is_internal_transfer = TRUE WHERE id = ?", creditId);
  }

  private UUID createSecurity(String token) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                "IE00B4L5Y983",
                "iShares Core MSCI World",
                "USD",
                "ETF",
                "EQUITY",
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private AccountSummaryResponse createAccount(String token, String type, String currency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(type, type, currency).build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID defaultCategory(String code) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM category WHERE workspace_id IS NULL AND code = ?")) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return (UUID) resultSet.getObject(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private int countRows(UUID accountId) {
    return queryDecimal("SELECT count(*) FROM transaction WHERE account_id = ?", accountId)
        .intValue();
  }

  private BigDecimal queryDecimal(String sql, UUID parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getBigDecimal(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void execute(String sql, UUID parameter) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      statement.executeUpdate();
    }
  }

  private void grantAccount(String token, UUID memberId, UUID accountId, String level) {
    client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                memberId, ScopeTypeValues.ACCOUNT, accountId, null, level))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private UUID createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
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
