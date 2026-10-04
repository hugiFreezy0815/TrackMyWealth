package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.ReconciliationAdjustmentValues;
import com.trackmywealth.backend.dto.ReconciliationDecisionRequest;
import com.trackmywealth.backend.dto.ReconciliationResultResponse;
import com.trackmywealth.backend.dto.ReconciliationStatusValues;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetTransactionCategoryRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * US-25-03 guided resolution, end to end against PostgreSQL: every fixture opens the same 45.67
 * difference (opening 10000.00, income 2300.00, snapshot 12345.67), which a member then accepts,
 * dismisses or reopens.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationDecisionControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final LocalDate OPENING_DATE = LocalDate.of(2026, 1, 1);
  private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 9, 30);
  private static final String NOTE = "Bank statement is authoritative";
  private static final String RESULTS = "reconciliation_result";

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

  private String token;
  private AccountSummaryResponse cash;

  @BeforeEach
  void cleanDatabaseAndOpenADifference() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              // An accepted result and its adjusting entry point at each other (V63).
              "UPDATE reconciliation_result SET resolution_transaction_id = NULL",
              "DELETE FROM settlement_match",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction WHERE replaces_transaction_id IS NOT NULL",
              "DELETE FROM transaction",
              "DELETE FROM reconciliation_result",
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
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
    token = bootstrapAdministrator();
    cash = createCashAccount();
    recordOpeningBalance();
    recordTransaction(SNAPSHOT_DATE.minusDays(1), "2300.00", "INCOME");
  }

  @Test
  void acceptingBooksAVisibleAdjustmentThatReconcilesWithoutTouchingCashFlow() {
    AccountSnapshotResponse snapshot = recordSnapshot(SNAPSHOT_DATE, "12345.67");
    CashFlowResponse before = cashFlow();
    ReconciliationResultResponse open = onlyResult();

    ReconciliationResultResponse accepted =
        CurrentVersion.storedEtag(
            decide(open, "accept", new ReconciliationDecisionRequest("  " + NOTE + " "))
                .expectStatus()
                .isOk()
                .expectBody(ReconciliationResultResponse.class)
                .returnResult(),
            dataSource,
            RESULTS,
            open.id());

    assertThat(accepted.status()).isEqualTo("ACCEPTED");
    assertThat(accepted.resolutionNote()).isEqualTo(NOTE);
    assertThat(accepted.resolvedAt()).isNotNull();
    assertThat(accepted.snapshotId()).isEqualTo(snapshot.id());
    assertThat(accepted.differenceAmount()).isEqualByComparingTo("45.67");

    TransactionResponse adjustment =
        ledger().stream()
            .filter(row -> row.id().equals(accepted.resolutionTransactionId()))
            .findFirst()
            .orElseThrow();
    assertThat(adjustment.transactionType()).isEqualTo("VALUATION_ADJUSTMENT");
    assertThat(adjustment.amount()).isEqualByComparingTo("45.67");
    assertThat(adjustment.currency()).isEqualTo("CHF");
    assertThat(adjustment.bookingDate()).isEqualTo(SNAPSHOT_DATE);
    assertThat(adjustment.source()).isEqualTo("MANUAL");
    assertThat(adjustment.notes()).isEqualTo(NOTE);
    assertThat(adjustment.categoryId()).isNull();
    // Only a reopen of its result takes it back.
    assertThat(adjustment.removal()).isNull();
    assertThat(adjustment.reconciliationAdjustment())
        .isEqualTo(ReconciliationAdjustmentValues.REOPENABLE);
    assertThat(accepted.finalized()).isFalse();

    AccountSummaryResponse account = account();
    assertThat(account.reconciliation().status()).isEqualTo(ReconciliationStatusValues.RECONCILED);
    assertThat(account.warnings())
        .doesNotContain(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
    // A balance correction, not income: every cash-flow figure is what it was.
    assertThat(cashFlow()).isEqualTo(before);
  }

  @Test
  void dismissingDocumentsTheDifferenceInsteadOfWarning() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse open = onlyResult();

    ReconciliationResultResponse dismissed =
        decide(open, "dismiss", new ReconciliationDecisionRequest(NOTE))
            .expectStatus()
            .isOk()
            .expectBody(ReconciliationResultResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(dismissed.status()).isEqualTo("DISMISSED");
    assertThat(dismissed.resolutionNote()).isEqualTo(NOTE);
    assertThat(dismissed.resolutionTransactionId()).isNull();
    AccountSummaryResponse account = account();
    assertThat(account.reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.DISMISSED_DIFFERENCE);
    assertThat(account.reconciliation().openDifference()).isEqualByComparingTo("45.67");
    assertThat(account.warnings())
        .doesNotContain(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
    assertThat(count("SELECT count(*) FROM transaction WHERE account_id = ?", cash.id()))
        .isEqualTo(1);
  }

  @Test
  void reopeningADismissalBringsTheOpenWarningBack() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse dismissed =
        decide(onlyResult(), "dismiss", new ReconciliationDecisionRequest(NOTE))
            .expectBody(ReconciliationResultResponse.class)
            .returnResult()
            .getResponseBody();

    ReconciliationResultResponse reopened = reopen(dismissed);

    assertThat(reopened.status()).isEqualTo("OPEN");
    assertThat(reopened.differenceAmount()).isEqualByComparingTo("45.67");
    assertThat(reopened.resolvedAt()).isNull();
    // The earlier decision's reason stays as history.
    assertThat(reopened.resolutionNote()).isEqualTo(NOTE);
    AccountSummaryResponse account = account();
    assertThat(account.reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(account.warnings())
        .contains(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
  }

  @Test
  void reopeningAnAcceptanceRemovesItsAdjustingEntry() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse accepted = accept(onlyResult());

    ReconciliationResultResponse reopened = reopen(accepted);

    assertThat(reopened.status()).isEqualTo("OPEN");
    assertThat(reopened.differenceAmount()).isEqualByComparingTo("45.67");
    assertThat(reopened.resolutionTransactionId()).isNull();
    assertThat(ledger()).extracting(TransactionResponse::id).doesNotContain(adjustmentOf(accepted));
    assertThat(
            stringValue(
                "SELECT deleted_at IS NOT NULL FROM transaction WHERE id = ?",
                adjustmentOf(accepted)))
        .isEqualTo("t");
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(restorable())
        .extracting(TransactionResponse::id)
        .doesNotContain(adjustmentOf(accepted));
  }

  @Test
  void bookingTheMissingRowAfterAnAcceptanceWithdrawsTheAdjustmentAndReconciles() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse accepted = accept(onlyResult());

    recordTransaction(SNAPSHOT_DATE, "45.67", "INCOME");

    ReconciliationResultResponse result = onlyResult();
    assertThat(result.status()).isEqualTo("RESOLVED");
    assertThat(result.resolutionTransactionId()).isNull();
    assertThat(ledger()).extracting(TransactionResponse::id).doesNotContain(adjustmentOf(accepted));
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void aChangedAmountReopensADismissal() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    decide(onlyResult(), "dismiss", new ReconciliationDecisionRequest(NOTE)).expectStatus().isOk();

    recordTransaction(SNAPSHOT_DATE, "5.67", "INCOME");

    ReconciliationResultResponse result = onlyResult();
    assertThat(result.status()).isEqualTo("OPEN");
    assertThat(result.differenceAmount()).isEqualByComparingTo("40.00");
    assertThat(account().warnings())
        .contains(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
  }

  @Test
  void theAdjustingEntryCannotBeCorrectedRemovedRestoredOrCategorizedOnItsOwn() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    UUID adjustment = adjustmentOf(accept(onlyResult()));
    TransactionResponse row =
        ledger().stream().filter(r -> r.id().equals(adjustment)).findFirst().orElseThrow();
    String uri = "/api/v1/accounts/" + cash.id() + "/transactions/" + adjustment;

    client()
        .delete()
        .uri(uri)
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", adjustment))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_ADJUSTMENT_LOCKED");
    client()
        .put()
        .uri(uri)
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", adjustment))
        .contentType(MediaType.APPLICATION_JSON)
        .body(amountCorrection(row, "40.00"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_ADJUSTMENT_LOCKED");
    // A balance correction is neither income nor spending: it takes no category of its own.
    UUID category =
        UUID.fromString(
            stringValueByText(
                "SELECT id::text FROM category WHERE workspace_id IS NULL AND is_active"
                    + " AND code <> ? ORDER BY code LIMIT 1",
                "UNCATEGORIZED"));
    client()
        .put()
        .uri(uri + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", adjustment))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(category))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_ADJUSTMENT_LOCKED");
    client()
        .delete()
        .uri(uri + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", adjustment))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_ADJUSTMENT_LOCKED");
    assertThat(stringValue("SELECT category_id::text FROM transaction WHERE id = ?", adjustment))
        .isNull();

    // Withdrawn by a reopen, it cannot come back through the transaction restore either.
    reopen(onlyResult());
    client()
        .post()
        .uri(uri + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", adjustment))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_ADJUSTMENT_LOCKED");
  }

  @Test
  void aNewerSnapshotFinalizesAnAcceptanceAndCorrectionsGoIntoTheNewestComparison() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse accepted = accept(onlyResult());
    UUID adjustment = adjustmentOf(accepted);

    // The next statement agrees with the adjusted ledger: it was compared against the adjustment.
    recordSnapshot(SNAPSHOT_DATE.plusDays(1), "12345.67");

    ReconciliationResultResponse finalized = result(accepted.id());
    assertThat(finalized.status()).isEqualTo("ACCEPTED");
    assertThat(finalized.finalized()).isTrue();
    assertThat(finalized.resolutionTransactionId()).isEqualTo(adjustment);
    assertThat(adjustmentRow(adjustment).reconciliationAdjustment())
        .isEqualTo(ReconciliationAdjustmentValues.FINALIZED);
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);

    // History: the decision is not reopened and its row neither withdrawn nor removed.
    client()
        .post()
        .uri(resultUri(finalized) + "/reopen")
        .header("If-Match", "\"" + finalized.version() + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_FINALIZED")
        .jsonPath("$.status")
        .isEqualTo("ACCEPTED");
    client()
        .delete()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions/" + adjustment)
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", adjustment))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_ADJUSTMENT_LOCKED")
        .jsonPath("$.reconciliationAdjustment")
        .isEqualTo(ReconciliationAdjustmentValues.FINALIZED)
        .jsonPath("$.detail")
        .value(detail -> assertThat(detail.toString()).contains("latest reconciliation"));
    assertThat(result(accepted.id()).status()).isEqualTo("ACCEPTED");
    assertThat(adjustmentRow(adjustment).reconciliationAdjustment())
        .isEqualTo(ReconciliationAdjustmentValues.FINALIZED);

    // The missing row turns up after all: the newest comparison shows the overlap, and the member
    // corrects it there with a new, visible counter-adjustment.
    recordTransaction(SNAPSHOT_DATE.minusDays(2), "45.67", "INCOME");
    ReconciliationResultResponse newest = onlyResult();
    assertThat(newest.id()).isNotEqualTo(accepted.id());
    assertThat(newest.status()).isEqualTo("OPEN");
    assertThat(newest.differenceAmount()).isEqualByComparingTo("-45.67");
    assertThat(result(accepted.id()).status()).isEqualTo("ACCEPTED");

    ReconciliationResultResponse corrected = accept(newest);
    assertThat(adjustmentRow(adjustmentOf(corrected)).amount()).isEqualByComparingTo("-45.67");
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void aNewerSnapshotFinalizesADismissal() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse dismissed =
        decide(onlyResult(), "dismiss", new ReconciliationDecisionRequest(NOTE))
            .expectStatus()
            .isOk()
            .expectBody(ReconciliationResultResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(dismissed.finalized()).isFalse();

    recordSnapshot(SNAPSHOT_DATE.plusDays(1), "12400.00");

    ReconciliationResultResponse finalized = result(dismissed.id());
    assertThat(finalized.status()).isEqualTo("DISMISSED");
    assertThat(finalized.finalized()).isTrue();
    decide(finalized, "accept", new ReconciliationDecisionRequest(NOTE))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_FINALIZED");
    client()
        .post()
        .uri(resultUri(finalized) + "/reopen")
        .header("If-Match", "\"" + finalized.version() + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_FINALIZED")
        .jsonPath("$.status")
        .isEqualTo("DISMISSED");
    // The newest comparison is its own open difference, not the old dismissal.
    assertThat(onlyResult().status()).isEqualTo("OPEN");
  }

  @Test
  void aMemberCannotRecordAValuationAdjustmentDirectly() {
    client()
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(cashRequest(SNAPSHOT_DATE, "45.67", "VALUATION_ADJUSTMENT"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aDecisionNeedsANote() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse open = onlyResult();

    for (String action : List.of("accept", "dismiss")) {
      decide(open, action, new ReconciliationDecisionRequest(" "))
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("VALIDATION_FAILED");
      decide(open, action, new ReconciliationDecisionRequest("x".repeat(501)))
          .expectStatus()
          .isBadRequest();
    }
    assertThat(onlyResult().status()).isEqualTo("OPEN");
  }

  @Test
  void aDecisionNeedsTheCurrentVersion() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse open = onlyResult();
    String uri = resultUri(open) + "/accept";

    client()
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReconciliationDecisionRequest(NOTE))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    client()
        .post()
        .uri(uri)
        .header("If-Match", "\"" + (open.version() + 1) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReconciliationDecisionRequest(NOTE))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    assertThat(onlyResult().status()).isEqualTo("OPEN");
  }

  @Test
  void aSupersededResultCannotBeDecidedOn() {
    recordSnapshot(SNAPSHOT_DATE.minusDays(10), "12345.67");
    ReconciliationResultResponse older = onlyResult();
    recordSnapshot(SNAPSHOT_DATE, "12350.00");
    ReconciliationResultResponse superseded = result(older.id());
    assertThat(superseded.status()).isEqualTo("SUPERSEDED");

    decide(superseded, "accept", new ReconciliationDecisionRequest(NOTE))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_STALE")
        .jsonPath("$.status")
        .isEqualTo("SUPERSEDED");
    assertThat(
            count(
                "SELECT count(*) FROM transaction WHERE transaction_type = 'VALUATION_ADJUSTMENT'"
                    + " AND account_id = ?",
                cash.id()))
        .isZero();
  }

  @Test
  void anAlreadyDecidedResultMustBeReopenedFirst() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse dismissed =
        decide(onlyResult(), "dismiss", new ReconciliationDecisionRequest(NOTE))
            .expectBody(ReconciliationResultResponse.class)
            .returnResult()
            .getResponseBody();

    decide(dismissed, "accept", new ReconciliationDecisionRequest(NOTE))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_STALE")
        .jsonPath("$.status")
        .isEqualTo("DISMISSED")
        .jsonPath("$.differenceAmount")
        .isEqualTo("45.6700");
    ReconciliationResultResponse open = reopen(dismissed);
    client()
        .post()
        .uri(resultUri(open) + "/reopen")
        .header("If-Match", "\"" + open.version() + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_STALE");
  }

  @Test
  void anOpeningDateProviderSnapshotCanBeAcceptedAndReallyReconciles() {
    // A provider observation may legally share the opening-balance date because its source differs
    // from the MANUAL opening row. The reconciliation adjustment is deliberately dated to that
    // observation and must therefore be counted even though ordinary opening-date rows are not.
    UUID snapshotId = UUID.randomUUID();
    UUID resultId = UUID.randomUUID();
    execute(
        "INSERT INTO account_snapshot"
            + " (id, workspace_id, account_id, snapshot_date, balance, currency, source,"
            + " is_opening_balance, created_by)"
            + " SELECT ?, a.workspace_id, a.id, ?, 10045.67, 'CHF', 'AGGREGATOR', false, u.id"
            + " FROM account a CROSS JOIN app_user u"
            + " WHERE a.id = ? AND u.email = 'admin@example.com'",
        snapshotId,
        OPENING_DATE,
        cash.id());
    execute(
        "INSERT INTO reconciliation_result"
            + " (id, workspace_id, account_id, snapshot_id, difference_amount, probable_cause,"
            + " status)"
            + " SELECT ?, a.workspace_id, a.id, ?, 45.67, 'UNKNOWN', 'OPEN'"
            + " FROM account a WHERE a.id = ?",
        resultId,
        snapshotId,
        cash.id());

    ReconciliationResultResponse open = result(resultId);
    ReconciliationResultResponse accepted = accept(open);

    assertThat(accepted.status()).isEqualTo("ACCEPTED");
    assertThat(
            stringValue(
                "SELECT amount::text FROM transaction WHERE id = ?", adjustmentOf(accepted)))
        .startsWith("45.67");

    // Force the engine to recompute the same opening-date snapshot. The ordinary row is already
    // represented by the opening balance and stays excluded; the later reconciliation adjustment
    // is the deliberate exception and must keep the result accepted/reconciled.
    recordTransaction(OPENING_DATE, "1.00", "INCOME");
    assertThat(onlyResult().status()).isEqualTo("ACCEPTED");
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void anAcceptRacingALedgerWriteDoesNotDeadlock() throws Exception {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse open = onlyResult();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> acceptStatus =
          pool.submit(
              () -> {
                start.await();
                return decide(open, "accept", new ReconciliationDecisionRequest(NOTE))
                    .returnResult(String.class)
                    .getStatus()
                    .value();
              });
      Future<Integer> writeStatus =
          pool.submit(
              () -> {
                start.await();
                return client()
                    .post()
                    .uri("/api/v1/accounts/" + cash.id() + "/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cashRequest(SNAPSHOT_DATE, "1.00", "INCOME"))
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
              });

      start.countDown();
      assertThat(acceptStatus.get(30, TimeUnit.SECONDS)).isIn(200, 409, 412);
      assertThat(writeStatus.get(30, TimeUnit.SECONDS)).isEqualTo(201);
    } finally {
      pool.shutdownNow();
    }

    // Whichever ran first, the account ends in one consistent state. Write first: the accept's
    // version is stale and nothing is booked. Accept first: the write overtakes the acceptance and
    // the engine withdraws its entry. Either way the real difference is open, with no live
    // adjustment left behind.
    ReconciliationResultResponse settled = onlyResult();
    assertThat(settled.status()).isEqualTo("OPEN");
    assertThat(settled.differenceAmount()).isEqualByComparingTo("44.67");
    assertThat(settled.resolutionTransactionId()).isNull();
    assertThat(
            count(
                "SELECT count(*) FROM transaction WHERE account_id = ?"
                    + " AND transaction_type = 'VALUATION_ADJUSTMENT' AND deleted_at IS NULL",
                cash.id()))
        .isZero();
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
  }

  @Test
  void aStaleDifferenceCommitsItsRefreshSoTheRetrySucceeds() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    // A result stored before the engine's synchronous hooks existed: its figure is out of date.
    execute(
        "UPDATE reconciliation_result SET difference_amount = 40.00 WHERE id = ?",
        onlyResult().id());
    ReconciliationResultResponse drifted = onlyResult();
    assertThat(drifted.differenceAmount()).isEqualByComparingTo("40.00");

    decide(drifted, "accept", new ReconciliationDecisionRequest(NOTE))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RECONCILIATION_STALE")
        .jsonPath("$.differenceAmount")
        .value(
            amount -> assertThat(new BigDecimal(amount.toString())).isEqualByComparingTo("45.67"));

    // The reload shows the figure the 409 named, under a new version, and deciding on it works.
    ReconciliationResultResponse refreshed = onlyResult();
    assertThat(refreshed.differenceAmount()).isEqualByComparingTo("45.67");
    assertThat(refreshed.version()).isGreaterThan(drifted.version());
    assertThat(
            count(
                "SELECT count(*) FROM transaction WHERE transaction_type = 'VALUATION_ADJUSTMENT'"
                    + " AND account_id = ?",
                cash.id()))
        .isZero();
    assertThat(accept(refreshed).status()).isEqualTo("ACCEPTED");
  }

  @Test
  void deletingTheOpeningBalanceRetiresAnAcceptanceAndWithdrawsItsEntry() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse accepted = accept(onlyResult());

    deleteOpeningBalance();

    ReconciliationResultResponse retired = result(accepted.id());
    assertThat(retired.status()).isEqualTo("SUPERSEDED");
    assertThat(retired.resolutionTransactionId()).isNull();
    assertThat(retired.resolutionNote()).isEqualTo(NOTE);
    assertThat(ledger()).extracting(TransactionResponse::id).doesNotContain(adjustmentOf(accepted));
    assertThat(
            stringValue(
                "SELECT u.email FROM transaction t JOIN app_user u ON u.id = t.deleted_by"
                    + " WHERE t.id = ?",
                adjustmentOf(accepted)))
        .isEqualTo("admin@example.com");
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.NOT_RECONCILABLE);

    // With the basis back, the difference is open again for a new decision.
    recordOpeningBalance();
    ReconciliationResultResponse reopened = result(accepted.id());
    assertThat(reopened.status()).isEqualTo("OPEN");
    assertThat(reopened.differenceAmount()).isEqualByComparingTo("45.67");
  }

  @Test
  void deletingTheOpeningBalanceRetiresADismissal() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse dismissed =
        decide(onlyResult(), "dismiss", new ReconciliationDecisionRequest(NOTE))
            .expectStatus()
            .isOk()
            .expectBody(ReconciliationResultResponse.class)
            .returnResult()
            .getResponseBody();

    deleteOpeningBalance();

    assertThat(result(dismissed.id()).status()).isEqualTo("SUPERSEDED");
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.NOT_RECONCILABLE);
  }

  @Test
  void twoConcurrentAcceptsBookOneAdjustment() throws Exception {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse open = onlyResult();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Integer> statuses;
    try {
      Callable<Integer> accept =
          () -> {
            start.await();
            return decide(open, "accept", new ReconciliationDecisionRequest(NOTE))
                .returnResult(String.class)
                .getStatus()
                .value();
          };
      Future<Integer> first = pool.submit(accept);
      Future<Integer> second = pool.submit(accept);
      start.countDown();
      statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    // Both sent the same version: one wins, the other's precondition is stale.
    assertThat(statuses).containsExactlyInAnyOrder(200, 412);
    ReconciliationResultResponse accepted = onlyResult();
    assertThat(accepted.status()).isEqualTo("ACCEPTED");
    assertThat(
            count(
                "SELECT count(*) FROM transaction WHERE account_id = ?"
                    + " AND reconciliation_result_id IS NOT NULL",
                cash.id()))
        .isEqualTo(1);
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.RECONCILED);
  }

  @Test
  void correctingTheSnapshotAmountReopensAnAcceptanceWithTheRealDifference() {
    AccountSnapshotResponse snapshot = recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse accepted = accept(onlyResult());

    client()
        .put()
        .uri("/api/v1/accounts/" + cash.id() + "/snapshots/" + snapshot.id())
        .headers(CurrentVersion.ifMatch(dataSource, "account_snapshot", snapshot.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReplaceAccountSnapshotRequest(new BigDecimal("12350.67"), List.of()))
        .exchange()
        .expectStatus()
        .isOk();

    // The acceptance covered 45.67; the provider's corrected figure leaves 50.67 unexplained.
    ReconciliationResultResponse reopened = result(accepted.id());
    assertThat(reopened.status()).isEqualTo("OPEN");
    assertThat(reopened.differenceAmount()).isEqualByComparingTo("50.67");
    assertThat(reopened.resolutionTransactionId()).isNull();
    assertThat(reopened.resolutionNote()).isEqualTo(NOTE);
    assertThat(ledger()).extracting(TransactionResponse::id).doesNotContain(adjustmentOf(accepted));
    assertThat(account().reconciliation().status())
        .isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
  }

  @Test
  void theAdjustingEntryNamesItsOwnerForGood() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse accepted = accept(onlyResult());
    UUID adjustment = adjustmentOf(accepted);
    reopen(accepted);

    // Withdrawn, it still names the result that booked it (V63).
    assertThat(
            stringValue(
                "SELECT reconciliation_result_id::text FROM transaction WHERE id = ?", adjustment))
        .isEqualTo(accepted.id().toString());
    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE transaction SET reconciliation_result_id = NULL WHERE id = ?",
                    adjustment))
        .hasMessageContaining("transaction_ledger_append_only");
    // Only a manual VALUATION_ADJUSTMENT can belong to a reconciliation result.
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                        + " booking_date, amount, currency, source, reconciliation_result_id)"
                        + " SELECT workspace_id, account_id, 'EXPENSE', booking_date, amount,"
                        + " currency, 'MANUAL', reconciliation_result_id FROM transaction"
                        + " WHERE id = ?",
                    adjustment))
        .hasMessageContaining("transaction_reconciliation_adjustment_shape");
  }

  @Test
  void aReadOnlyMemberCannotDecide() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse open = onlyResult();
    UUID memberId = createSecondMember("reader@example.com");
    grantOnAccount(memberId, AccessLevelValues.READ);
    String readerToken = login("reader@example.com");

    client(readerToken)
        .get()
        .uri(resultUri(open))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"" + open.version() + "\"");
    client(readerToken)
        .post()
        .uri(resultUri(open) + "/dismiss")
        .header("If-Match", "\"" + open.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReconciliationDecisionRequest(NOTE))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(onlyResult().status()).isEqualTo("OPEN");
  }

  @Test
  void anotherAccountsResultIsNotFound() {
    recordSnapshot(SNAPSHOT_DATE, "12345.67");
    ReconciliationResultResponse open = onlyResult();
    AccountSummaryResponse other = createCashAccount();

    client()
        .post()
        .uri("/api/v1/accounts/" + other.id() + "/reconciliations/" + open.id() + "/dismiss")
        .header("If-Match", "\"" + open.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReconciliationDecisionRequest(NOTE))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  private ReconciliationResultResponse accept(ReconciliationResultResponse open) {
    return decide(open, "accept", new ReconciliationDecisionRequest(NOTE))
        .expectStatus()
        .isOk()
        .expectBody(ReconciliationResultResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ReconciliationResultResponse reopen(ReconciliationResultResponse decided) {
    EntityExchangeResult<ReconciliationResultResponse> result =
        client()
            .post()
            .uri(resultUri(decided) + "/reopen")
            .header("If-Match", "\"" + decided.version() + "\"")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ReconciliationResultResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(result, dataSource, RESULTS, decided.id());
  }

  private RestTestClient.ResponseSpec decide(
      ReconciliationResultResponse result, String action, ReconciliationDecisionRequest request) {
    return client()
        .post()
        .uri(resultUri(result) + "/" + action)
        .header("If-Match", "\"" + result.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private TransactionResponse adjustmentRow(UUID adjustment) {
    return ledger().stream().filter(row -> row.id().equals(adjustment)).findFirst().orElseThrow();
  }

  private static UUID adjustmentOf(ReconciliationResultResponse accepted) {
    assertThat(accepted.resolutionTransactionId()).isNotNull();
    return accepted.resolutionTransactionId();
  }

  private String resultUri(ReconciliationResultResponse result) {
    return "/api/v1/accounts/" + cash.id() + "/reconciliations/" + result.id();
  }

  private ReconciliationResultResponse onlyResult() {
    return result(
        UUID.fromString(
            stringValue(
                "SELECT id::text FROM reconciliation_result WHERE account_id = ?"
                    + " ORDER BY created_at DESC LIMIT 1",
                cash.id())));
  }

  private ReconciliationResultResponse result(UUID id) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/reconciliations/" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ReconciliationResultResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<TransactionResponse> restorable() {
    TransactionResponse[] body =
        client()
            .get()
            .uri("/api/v1/accounts/" + cash.id() + "/transactions/deleted")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionResponse[].class)
            .returnResult()
            .getResponseBody();
    return body == null ? List.of() : List.of(body);
  }

  private List<TransactionResponse> ledger() {
    return client()
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions?size=200")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TransactionPage.class)
        .returnResult()
        .getResponseBody()
        .content();
  }

  private record TransactionPage(List<TransactionResponse> content) {}

  private CashFlowResponse cashFlow() {
    return client()
        .get()
        .uri("/api/v1/cash-flow?month=" + YearMonth.from(SNAPSHOT_DATE))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // The row as read, with only its amount changed: a financial correction (US-07-03).
  private static CorrectTransactionRequest amountCorrection(
      TransactionResponse row, String amount) {
    return new CorrectTransactionRequest(
        null,
        row.transactionType(),
        row.bookingDate(),
        new BigDecimal(amount),
        row.currency(),
        row.merchantDescription(),
        row.mcc(),
        row.notes(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "Try to change the adjustment");
  }

  private AccountSummaryResponse createCashAccount() {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account("Current Account", "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void recordOpeningBalance() {
    client()
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OpeningBalanceRequest(OPENING_DATE, new BigDecimal("10000.00"), "CHF", null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private void deleteOpeningBalance() {
    UUID opening =
        UUID.fromString(
            stringValue(
                "SELECT id::text FROM account_snapshot WHERE account_id = ? AND is_opening_balance",
                cash.id()));
    client()
        .delete()
        .uri("/api/v1/accounts/" + cash.id() + "/opening-balance")
        .headers(CurrentVersion.ifMatch(dataSource, "account_snapshot", opening))
        .exchange()
        .expectStatus()
        .isNoContent();
  }

  private AccountSnapshotResponse recordSnapshot(LocalDate date, String amount) {
    return client()
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RecordAccountSnapshotRequest(date, new BigDecimal(amount), List.of()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSnapshotResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void recordTransaction(LocalDate date, String amount, String type) {
    client()
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(cashRequest(date, amount, type))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private static CreateTransactionRequest cashRequest(LocalDate date, String amount, String type) {
    return TransactionRequests.cash(
        type,
        date,
        new BigDecimal(amount),
        "CHF",
        type + " reconciliation fixture",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private AccountSummaryResponse account() {
    return client()
        .get()
        .uri("/api/v1/accounts/" + cash.id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void grantOnAccount(UUID memberId, String level) {
    client()
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                memberId, ScopeTypeValues.ACCOUNT, cash.id(), null, level))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private UUID createSecondMember(String email) {
    client()
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
    return UUID.fromString(
        stringValueByText("SELECT workspace_member_id::text FROM app_user WHERE email = ?", email));
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

  private long count(String sql, UUID id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void execute(String sql, Object... values) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) {
        statement.setObject(i + 1, values[i]);
      }
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String stringValue(String sql, UUID id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      return single(statement);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String stringValueByText(String sql, String value) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, value);
      return single(statement);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String single(PreparedStatement statement) throws Exception {
    try (ResultSet result = statement.executeQuery()) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private RestTestClient client() {
    return client(token);
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
