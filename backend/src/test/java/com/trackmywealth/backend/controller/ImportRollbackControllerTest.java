package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateCategorizationRuleRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportRollbackModifiedResponse;
import com.trackmywealth.backend.dto.ImportRollbackRequest;
import com.trackmywealth.backend.dto.ImportRollbackResponse;
import com.trackmywealth.backend.dto.ImportRollbackValues;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.ReconciliationStatusValues;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetTransactionCategoryRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.StatusAssertions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-05 against a real PostgreSQL: rolling back a committed import batch. An untouched batch is
 * hard-deleted, one somebody worked on is voided (each "modified" criterion, parameterised), all or
 * nothing (a failure injected halfway through by a test trigger), and the database refuses any
 * other hard delete of a transaction (V68). Every fixture is synthetic.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportRollbackControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String HEADER = "Date;Amount;Currency;Text;Reference";
  private static final LocalDate DAY = LocalDate.of(2019, 1, 5);
  private static final String REASON = "Imported into the wrong account";
  // Three rows, distinct enough that no detection pairs them with each other.
  private static final byte[] THREE_ROWS =
      csv(
          "2019-01-05;-12.40;CHF;Bakery;R1",
          "2019-01-06;-80.00;CHF;Groceries;R2",
          "2019-01-07;2500.00;CHF;Salary;R3");

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
  private UUID account;
  private UUID template;

  @BeforeEach
  void cleanDatabaseAndSignIn() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DROP TRIGGER IF EXISTS zz_test_failure ON transaction",
              "DROP FUNCTION IF EXISTS test_fail_on_row()",
              "DELETE FROM reconciliation_result",
              "DELETE FROM settlement_match",
              "DELETE FROM transfer_detection_fx_pending",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction_category_split",
              "DELETE FROM categorization_rule",
              "DELETE FROM import_row_raw",
              "DELETE FROM tax_lot",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM import_file",
              "DELETE FROM import_batch",
              "DELETE FROM import_template",
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
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
    account = createAccount("Privatkonto");
    template = createTemplate().id();
  }

  // --- unmodified: hard delete ------------------------------------------------------------------

  @Test
  void anUntouchedBatchIsDeletedAndItsFileImportsAsNewAgain() {
    // A rule categorizes the bakery row automatically, so the rows have categorization log rows.
    client()
        .post()
        .uri("/api/v1/categorization-rules")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateCategorizationRuleRequest("MERCHANT", "Bakery", shopping(), null))
        .exchange()
        .expectStatus()
        .isCreated();
    ImportBatchResponse batch = importFile(THREE_ROWS);
    assertThat(count("SELECT count(*) FROM transaction_categorization_log")).isPositive();

    EntityExchangeResult<ImportRollbackResponse> result = rollbackResult(batch, REASON);
    ImportRollbackResponse rollback = result.getResponseBody();

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.HARD_DELETE);
    assertThat(rollback.deletedTransactionCount()).isEqualTo(3);
    assertThat(rollback.modified()).isEmpty();
    assertThat(rollback.voidedTransactionIds()).isEmpty();
    assertThat(rollback.reversalTransactionIds()).isEmpty();
    assertThat(rollback.unmatchedTransactionIds()).isEmpty();
    ImportBatchResponse rolledBack =
        CurrentVersion.storedEtag(result, dataSource, "import_batch", batch.id()).batch();
    assertThat(rolledBack.status()).isEqualTo("ROLLED_BACK");
    assertThat(rolledBack.rolledBackAt()).isNotNull();
    assertThat(rolledBack.rollbackReason()).isEqualTo(REASON);
    assertThat(single("SELECT rolled_back_by FROM import_batch WHERE id = ?", batch.id()))
        .isEqualTo(single("SELECT id FROM app_user WHERE email = 'admin@example.com'"));

    // The ledger is as before the import; the batch, its rows and its file stay as evidence.
    assertThat(count("SELECT count(*) FROM transaction")).isZero();
    assertThat(count("SELECT count(*) FROM transaction_categorization_log")).isZero();
    assertThat(count("SELECT count(*) FROM import_row_raw WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(3);
    assertThat(
            count("SELECT count(*) FROM import_row_raw WHERE resulting_transaction_id IS NOT NULL"))
        .isZero();
    assertThat(count("SELECT count(*) FROM import_file WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(1);

    // The same file again is an ordinary import: nothing is a duplicate, no same-file warning.
    ImportBatchResponse again = upload(THREE_ROWS);
    assertThat(again.counts().newRows()).isEqualTo(3);
    assertThat(again.counts().duplicates()).isZero();
    assertThat(again.sameFileImportedIn()).isNull();
  }

  @Test
  void aVoidedBatchCanBeReimportedWithoutForcingRows() {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    overrideCategory(batchRows(batch.id()).get(0));
    assertThat(rollback(batch, REASON).batch().status()).isEqualTo("VOIDED");
    ImportBatchResponse again = upload(THREE_ROWS);
    assertThat(again.counts().duplicates()).isZero();
    assertThat(again.counts().newRows()).isEqualTo(3);
    assertThat(again.sameFileImportedIn()).isNull();
    ImportBatchResponse reimported = commit(again);
    assertThat(batchRows(reimported.id())).hasSize(3);
    // Original references stay on the voided rows as audit evidence, and on the new live rows.
    assertThat(
            count(
                "SELECT count(*) FROM transaction WHERE import_batch_id = ?"
                    + " AND voided_at IS NOT NULL AND external_id IN ('R1', 'R2', 'R3')",
                batch.id()))
        .isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM transaction WHERE import_batch_id = ?"
                    + " AND voided_at IS NULL AND external_id IN ('R1', 'R2', 'R3')",
                reimported.id()))
        .isEqualTo(3);
    // The new live rows still prevent an accidental second import.
    assertThat(upload(THREE_ROWS).counts().duplicates()).isEqualTo(3);
  }

  @Test
  void aBatchWhoseRowsWereAllDuplicatesIsRolledBackWithNothingToDelete() {
    importFile(THREE_ROWS);
    ImportBatchResponse onlyDuplicates = upload(THREE_ROWS);
    for (int row = 1; row <= 3; row++) {
      assertThat(rowStatus(onlyDuplicates.id(), row)).isEqualTo("DUPLICATE");
    }
    ImportBatchResponse committed = commit(onlyDuplicates);

    ImportRollbackResponse rollback = rollback(committed, REASON);

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.HARD_DELETE);
    assertThat(rollback.deletedTransactionCount()).isZero();
    assertThat(rollback.batch().status()).isEqualTo("ROLLED_BACK");
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(3);
  }

  @Test
  void aMatchTheSystemProposedOrConfirmedIsDissolvedWithTheDeletedRows() throws Exception {
    UUID savings = createAccount("Sparkonto");
    TransactionResponse credit = recordManual(savings, "DEPOSIT", "100.00", "From checking");
    ImportBatchResponse batch = importFile(csv("2019-01-05;-100.00;CHF;To savings;T1"));
    UUID debit = batchRows(batch.id()).get(0);
    assertThat(single("SELECT status FROM settlement_match")).isEqualTo("PROPOSED");
    // As if the system had applied it (an unambiguous TRANSFER pair): decided by nobody.
    execute(
        "UPDATE settlement_match SET status = 'CONFIRMED', decided_at = now(), decided_by = NULL");
    execute(
        "UPDATE transaction SET is_internal_transfer = TRUE, counterparty_account_id = ?"
            + " WHERE id = ?",
        account,
        credit.id());

    ImportRollbackResponse rollback = rollback(batch, REASON);

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.HARD_DELETE);
    assertThat(rollback.unmatchedTransactionIds()).containsExactly(credit.id());
    assertThat(count("SELECT count(*) FROM settlement_match")).isZero();
    assertThat(count("SELECT count(*) FROM transaction WHERE id = ?", debit)).isZero();
    assertThat(
            rowsOf(
                "SELECT is_internal_transfer, counterparty_account_id FROM transaction"
                    + " WHERE id = ?",
                credit.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("is_internal_transfer")).isEqualTo(false);
              assertThat(row.get("counterparty_account_id")).isNull();
            });
  }

  @Test
  void aRolledBackBookingIsWhatTheReconciliationNowMisses() {
    openingBalance("1000.00");
    ImportBatchResponse batch = importFile(csv("2019-01-05;-50.00;CHF;Electricity;E1"));
    snapshot("950.00");
    assertThat(reconciliationStatus()).isEqualTo(ReconciliationStatusValues.RECONCILED);

    rollback(batch, REASON);

    assertThat(reconciliationStatus()).isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(single("SELECT difference_amount FROM reconciliation_result WHERE status = 'OPEN'"))
        .satisfies(amount -> assertThat((BigDecimal) amount).isEqualByComparingTo("-50.00"));
    // The rolled-back import still holds the booking the bank's balance includes.
    assertThat(single("SELECT probable_cause FROM reconciliation_result WHERE status = 'OPEN'"))
        .isEqualTo("MISSING_TRANSACTION");
  }

  // --- modified: void
  // -----------------------------------------------------------------------------

  /** One way of working on the batch's first row, and the criterion it must be reported with. */
  enum Modification {
    CORRECTED(ImportRollbackValues.CORRECTED),
    REMOVED(ImportRollbackValues.REMOVED),
    USER_CATEGORY_OVERRIDE(ImportRollbackValues.USER_CATEGORY_OVERRIDE),
    RESET_CATEGORY_OVERRIDE(ImportRollbackValues.USER_CATEGORY_OVERRIDE),
    CATEGORY_SPLIT(ImportRollbackValues.CATEGORY_SPLIT),
    RECONCILIATION_RESOLUTION(ImportRollbackValues.RECONCILIATION_RESOLUTION),
    RESTORED(ImportRollbackValues.RESTORED),
    DUPLICATE_IN_ANOTHER_BATCH(ImportRollbackValues.REFERENCED),
    TAX_LOT(ImportRollbackValues.REFERENCED);

    final String criterion;

    Modification(String criterion) {
      this.criterion = criterion;
    }
  }

  @ParameterizedTest
  @EnumSource(Modification.class)
  void aBatchSomebodyWorkedOnIsVoidedAndNamesTheModifiedRow(Modification modification)
      throws Exception {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    List<UUID> rows = batchRows(batch.id());
    UUID touched = rows.get(0);
    modify(modification, touched);
    // Rows still in effect before the rollback: a removed or corrected row is not voided again.
    List<UUID> active =
        uuids(
            "SELECT id FROM transaction WHERE import_batch_id = ? AND voided_at IS NULL"
                + " AND deleted_at IS NULL ORDER BY id",
            batch.id());

    EntityExchangeResult<ImportRollbackResponse> result = rollbackResult(batch, REASON);
    ImportRollbackResponse rollback = result.getResponseBody();

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.VOID);
    assertThat(rollback.deletedTransactionCount()).isZero();
    assertThat(rollback.modified())
        .filteredOn(modified -> modified.transactionId().equals(touched))
        .singleElement()
        .extracting(ImportRollbackModifiedResponse::reasons)
        .satisfies(reasons -> assertThat(reasons).contains(modification.criterion));
    assertThat(rollback.voidedTransactionIds()).containsExactlyElementsOf(active);
    for (UUID id : active) {
      assertThat(single("SELECT void_reason FROM transaction WHERE id = ?", id)).isEqualTo(REASON);
    }
    assertThat(rollback.reversalTransactionIds())
        .map(id -> single("SELECT replaces_transaction_id FROM transaction WHERE id = ?", id))
        .containsExactlyElementsOf(active);
    ImportBatchResponse voided =
        CurrentVersion.storedEtag(result, dataSource, "import_batch", batch.id()).batch();
    assertThat(voided.status()).isEqualTo("VOIDED");
    assertThat(voided.rollbackReason()).isEqualTo(REASON);
    assertThat(
            single("SELECT contains_modified_records FROM import_batch WHERE id = ?", batch.id()))
        .isEqualTo(true);

    // Nothing of the batch is in effect any more, and nothing was deleted.
    assertThat(
            count(
                "SELECT count(*) FROM transaction WHERE import_batch_id = ? AND voided_at IS NULL"
                    + " AND deleted_at IS NULL",
                batch.id()))
        .isZero();
    assertThat(count("SELECT count(*) FROM transaction WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM import_row_raw WHERE import_batch_id = ?"
                    + " AND resulting_transaction_id IS NULL",
                batch.id()))
        .isZero();
  }

  @Test
  void aTransferMatchAMemberConfirmedForcesTheVoidAndFreesTheOtherLeg() {
    UUID savings = createAccount("Sparkonto");
    TransactionResponse credit = recordManual(savings, "DEPOSIT", "100.00", "From checking");
    ImportBatchResponse batch = importFile(csv("2019-01-05;-100.00;CHF;To savings;T1"));
    UUID debit = batchRows(batch.id()).get(0);
    UUID match = (UUID) single("SELECT id FROM settlement_match WHERE status = 'PROPOSED'");
    client()
        .post()
        .uri("/api/v1/settlement-matches/" + match + "/confirm")
        .header(HttpHeaders.IF_MATCH, etag(single("SELECT version FROM settlement_match")))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(single("SELECT is_internal_transfer FROM transaction WHERE id = ?", credit.id()))
        .isEqualTo(true);

    ImportRollbackResponse rollback = rollback(batch, REASON);

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.VOID);
    assertThat(rollback.modified())
        .containsExactly(
            new ImportRollbackModifiedResponse(debit, List.of(ImportRollbackValues.MATCH_DECIDED)));
    assertThat(rollback.unmatchedTransactionIds()).containsExactly(credit.id());
    assertThat(count("SELECT count(*) FROM settlement_match")).isZero();
    assertThat(
            rowsOf(
                "SELECT is_internal_transfer, counterparty_account_id FROM transaction"
                    + " WHERE id = ?",
                credit.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("is_internal_transfer")).isEqualTo(false);
              assertThat(row.get("counterparty_account_id")).isNull();
            });
  }

  @Test
  void aTransferMatchAMemberRejectedForcesTheVoidToo() {
    UUID savings = createAccount("Sparkonto");
    TransactionResponse credit = recordManual(savings, "DEPOSIT", "100.00", "From checking");
    ImportBatchResponse batch = importFile(csv("2019-01-05;-100.00;CHF;To savings;T1"));
    UUID debit = batchRows(batch.id()).get(0);
    UUID match = (UUID) single("SELECT id FROM settlement_match WHERE status = 'PROPOSED'");
    client()
        .post()
        .uri("/api/v1/settlement-matches/" + match + "/reject")
        .header(HttpHeaders.IF_MATCH, etag(single("SELECT version FROM settlement_match")))
        .exchange()
        .expectStatus()
        .isOk();

    ImportRollbackResponse rollback = rollback(batch, REASON);

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.VOID);
    assertThat(rollback.modified())
        .containsExactly(
            new ImportRollbackModifiedResponse(debit, List.of(ImportRollbackValues.MATCH_DECIDED)));
    assertThat(rollback.voidedTransactionIds()).containsExactly(debit);
    assertThat(single("SELECT voided_at IS NULL FROM transaction WHERE id = ?", credit.id()))
        .isEqualTo(true);
  }

  @Test
  void aRowOutsideTheBatchPointingAtOneOfItsRowsForcesTheVoid() {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    UUID purchase = batchRows(batch.id()).get(0);
    // A fee recorded outside the batch, linked to the imported row (US-09-04's related row).
    UUID fee =
        (UUID)
            single(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, related_transaction_id)"
                    + " SELECT workspace_id, account_id, 'FEE', booking_date, -1.00, currency, id"
                    + " FROM transaction WHERE id = ? RETURNING id",
                purchase);

    ImportRollbackResponse rollback = rollback(batch, REASON);

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.VOID);
    assertThat(rollback.modified())
        .containsExactly(
            new ImportRollbackModifiedResponse(purchase, List.of(ImportRollbackValues.REFERENCED)));
    // The fee goes with its purchase, as in a single removal.
    assertThat(rollback.voidedTransactionIds()).contains(purchase, fee);
    assertThat(count("SELECT count(*) FROM transaction WHERE id = ?", fee)).isEqualTo(1);
  }

  @Test
  void aDiscardedPreviewOfTheSameFileDoesNotKeepTheBatch() {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    ImportBatchResponse preview = upload(THREE_ROWS);
    assertThat(preview.counts().duplicates()).isEqualTo(3);
    client()
        .post()
        .uri(imports() + "/" + preview.id() + "/discard")
        .header(HttpHeaders.IF_MATCH, etag(preview.version()))
        .exchange()
        .expectStatus()
        .isOk();

    ImportRollbackResponse rollback = rollback(batch, REASON);

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.HARD_DELETE);
    assertThat(rollback.deletedTransactionCount()).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM transaction")).isZero();
    // The discarded preview keeps its rows, no longer pointing at the deleted ones.
    assertThat(
            count(
                "SELECT count(*) FROM import_row_raw WHERE import_batch_id = ?"
                    + " AND duplicate_of_transaction_id IS NULL",
                preview.id()))
        .isEqualTo(3);
  }

  @Test
  void aVoidingRollbackReopensTheReconciliationItHadClosed() {
    openingBalance("1000.00");
    ImportBatchResponse batch = importFile(csv("2019-01-05;-50.00;CHF;Electricity;E1"));
    snapshot("950.00");
    assertThat(reconciliationStatus()).isEqualTo(ReconciliationStatusValues.RECONCILED);
    modify(Modification.USER_CATEGORY_OVERRIDE, batchRows(batch.id()).get(0));

    ImportRollbackResponse rollback = rollback(batch, REASON);

    assertThat(rollback.rollback()).isEqualTo(ImportRollbackValues.VOID);
    assertThat(reconciliationStatus()).isEqualTo(ReconciliationStatusValues.OPEN_DIFFERENCE);
    assertThat(single("SELECT difference_amount FROM reconciliation_result WHERE status = 'OPEN'"))
        .satisfies(amount -> assertThat((BigDecimal) amount).isEqualByComparingTo("-50.00"));
  }

  @Test
  void removingOneImportedRowIsStillASingleVoid() {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    UUID row = batchRows(batch.id()).get(0);

    TransactionRemovalResponse removal = remove(row);

    assertThat(removal.removal()).isEqualTo("VOID");
    assertThat(removal.affected()).extracting(TransactionResponse::id).containsExactly(row);
    assertThat(single("SELECT status FROM import_batch WHERE id = ?", batch.id()))
        .isEqualTo("COMMITTED");
  }

  // --- atomicity and the database guard
  // -----------------------------------------------------------

  @Test
  void aFailureHalfwayThroughTheDeleteLeavesEverythingAsItWas() throws Exception {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    List<UUID> rows = batchRows(batch.id());
    failOn("BEFORE DELETE", "OLD.id = '" + rows.get(1) + "'");

    rollbackStatus(batch.id(), etag(batch.version()), REASON).is5xxServerError();

    assertUnchanged(batch, rows);
  }

  @Test
  void aFailureHalfwayThroughTheVoidLeavesEverythingAsItWas() throws Exception {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    List<UUID> rows = batchRows(batch.id());
    modify(Modification.CATEGORY_SPLIT, rows.get(0));
    // The second row's reversal: the first row is voided and reversed by then.
    failOn("BEFORE INSERT", "NEW.replaces_transaction_id = '" + rows.get(1) + "'");

    rollbackStatus(batch.id(), etag(batch.version()), REASON).is5xxServerError();

    assertUnchanged(batch, rows);
  }

  @Test
  void theDatabaseRefusesAnyOtherHardDeleteOfATransaction() {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    UUID imported = batchRows(batch.id()).get(0);
    UUID manual = recordManual(account, "EXPENSE", "-5.00", "Coffee").id();

    assertThatThrownBy(() -> execute("DELETE FROM transaction WHERE id = ?", imported))
        .hasMessageContaining("transaction_no_hard_delete");
    assertThatThrownBy(() -> execute("DELETE FROM transaction WHERE id = ?", manual))
        .hasMessageContaining("transaction_no_hard_delete");
    // The permit names one batch: it opens neither another batch's rows nor a manual row.
    assertThatThrownBy(
            () ->
                execute(
                    "WITH permit AS (SELECT set_config('app.import_rollback_batch_id', ?, TRUE))"
                        + " DELETE FROM transaction WHERE id = ? AND EXISTS (SELECT 1 FROM permit)",
                    UUID.randomUUID().toString(),
                    imported))
        .hasMessageContaining("transaction_no_hard_delete");
    // TRUNCATE fires no row trigger; its own guard refuses it, also as a workspace's CASCADE.
    assertThatThrownBy(() -> execute("TRUNCATE transaction CASCADE"))
        .hasMessageContaining("transaction_no_hard_delete");
    assertThatThrownBy(() -> execute("TRUNCATE workspace CASCADE"))
        .hasMessageContaining("transaction_no_hard_delete");
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(4);
  }

  // --- preconditions, concurrency, access
  // ---------------------------------------------------------

  @Test
  void onlyACommittedBatchCanBeRolledBackAndOnlyOnce() {
    ImportBatchResponse parsed = upload(THREE_ROWS);
    rollbackStatus(parsed.id(), etag(parsed.version()), REASON)
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_BATCH_STATE);

    ImportBatchResponse committed = commit(parsed);
    ImportRollbackResponse rollback = rollback(committed, REASON);

    // A retry, with the version it was sent with or the new one, learns that it happened.
    rollbackStatus(parsed.id(), etag(committed.version()), REASON)
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_BATCH_STATE);
    rollbackStatus(parsed.id(), etag(rollback.batch().version()), REASON)
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void theRollbackNeedsTheCurrentVersionAndAReason() {
    ImportBatchResponse batch = importFile(THREE_ROWS);

    rollbackStatus(batch.id(), etag(batch.version() - 1), REASON)
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    rollbackStatus(batch.id(), null, REASON).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    rollbackStatus(batch.id(), etag(batch.version()), null).isEqualTo(HttpStatus.BAD_REQUEST);
    rollbackStatus(batch.id(), etag(batch.version()), "   ").isEqualTo(HttpStatus.BAD_REQUEST);
    // Unicode whitespace alone is blank too: the reason is stripped before it is stored.
    rollbackStatus(batch.id(), etag(batch.version()), "\u2003\u3000")
        .isEqualTo(HttpStatus.BAD_REQUEST);
    rollbackStatus(batch.id(), etag(batch.version()), "x".repeat(501))
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(single("SELECT status FROM import_batch WHERE id = ?", batch.id()))
        .isEqualTo("COMMITTED");
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(3);
  }

  @Test
  void twoConcurrentRollbacksOfOneBatchRollItBackOnce() throws Exception {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    CountDownLatch start = new CountDownLatch(1);
    Callable<HttpStatus> attempt =
        () -> {
          start.await();
          return HttpStatus.valueOf(
              client()
                  .post()
                  .uri(imports() + "/" + batch.id() + "/rollback")
                  .header(HttpHeaders.IF_MATCH, etag(batch.version()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(new ImportRollbackRequest(REASON))
                  .exchange()
                  .returnResult(String.class)
                  .getStatus()
                  .value());
        };
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<HttpStatus> first = executor.submit(attempt);
      Future<HttpStatus> second = executor.submit(attempt);
      start.countDown();
      List<HttpStatus> statuses =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(statuses).containsOnlyOnce(HttpStatus.OK);
      assertThat(statuses)
          .filteredOn(status -> status != HttpStatus.OK)
          .singleElement()
          .isIn(HttpStatus.CONFLICT, HttpStatus.PRECONDITION_FAILED);
    } finally {
      executor.shutdownNow();
    }
    assertThat(single("SELECT status FROM import_batch WHERE id = ?", batch.id()))
        .isEqualTo("ROLLED_BACK");
    assertThat(count("SELECT count(*) FROM transaction")).isZero();
  }

  @Test
  void aMemberWhoMayOnlyReadTheAccountGetsTheAuditedNotFound() throws Exception {
    ImportBatchResponse batch = importFile(THREE_ROWS);
    UUID member = createSecondMember("reader@example.com");
    grantOnAccount(member, account, AccessLevelValues.READ);
    String reader = login("reader@example.com");

    client(reader)
        .post()
        .uri(imports() + "/" + batch.id() + "/rollback")
        .header(HttpHeaders.IF_MATCH, etag(batch.version()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRollbackRequest(REASON))
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(single("SELECT status FROM import_batch WHERE id = ?", batch.id()))
        .isEqualTo("COMMITTED");
  }

  // --- modifications
  // ------------------------------------------------------------------------------

  private void modify(Modification modification, UUID row) {
    switch (modification) {
      case CORRECTED -> correct(row);
      case REMOVED -> remove(row);
      case USER_CATEGORY_OVERRIDE -> overrideCategory(row);
      case RESET_CATEGORY_OVERRIDE -> {
        overrideCategory(row);
        client()
            .delete()
            .uri(transactions() + "/" + row + "/category")
            .header(HttpHeaders.IF_MATCH, etag(versionOf(row)))
            .exchange()
            .expectStatus()
            .isOk();
      }
      case CATEGORY_SPLIT ->
          execute(
              "INSERT INTO transaction_category_split (transaction_id, category_id, amount)"
                  + " SELECT ?, id, -6.20 FROM category WHERE workspace_id IS NULL"
                  + " AND code = 'SHOPPING'",
              row);
      case RECONCILIATION_RESOLUTION -> {
        openingBalance("1000.00");
        snapshot("1.00");
        execute("UPDATE reconciliation_result SET resolution_transaction_id = ?", row);
      }
      case RESTORED -> {
        remove(row);
        client()
            .post()
            .uri(transactions() + "/" + row + "/restore")
            .header(HttpHeaders.IF_MATCH, etag(versionOf(row)))
            .exchange()
            .expectStatus()
            .isOk();
      }
      case DUPLICATE_IN_ANOTHER_BATCH -> {
        // The same file again, previewed but not committed: its rows are duplicates of these.
        ImportBatchResponse preview = upload(THREE_ROWS);
        assertThat(preview.counts().duplicates()).isEqualTo(3);
      }
      case TAX_LOT -> {
        UUID security = UUID.randomUUID();
        execute(
            "INSERT INTO security (id, synthetic_key, legal_name, display_name,"
                + " denomination_currency) VALUES (?, ?, 'Test ETF', 'Test ETF', 'CHF')",
            security,
            "MANUAL-" + security);
        execute(
            "INSERT INTO tax_lot (account_id, security_id, acquisition_transaction_id,"
                + " acquisition_date, original_quantity, remaining_quantity, unit_cost, currency,"
                + " cost_basis_method) SELECT account_id, ?, id, booking_date, 1, 1, 10, 'CHF',"
                + " 'FIFO' FROM transaction WHERE id = ?",
            security,
            row);
      }
    }
  }

  private void correct(UUID row) {
    Map<String, Object> original =
        rowsOf(
                "SELECT transaction_type, booking_date, amount, currency FROM transaction"
                    + " WHERE id = ?",
                row)
            .get(0);
    BigDecimal amount = (BigDecimal) original.get("amount");
    client()
        .put()
        .uri(transactions() + "/" + row)
        .header(HttpHeaders.IF_MATCH, etag(versionOf(row)))
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CorrectTransactionRequest(
                null,
                (String) original.get("transaction_type"),
                ((java.sql.Date) original.get("booking_date")).toLocalDate(),
                // One more in the row's own direction: an income stays positive, a spending
                // negative.
                amount.add(BigDecimal.valueOf(amount.signum())),
                ((String) original.get("currency")).strip(),
                "Bakery",
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
                null,
                null,
                "The bank booked the wrong amount"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private TransactionRemovalResponse remove(UUID row) {
    return client()
        .delete()
        .uri(transactions() + "/" + row + "?reason=Booked twice")
        .header(HttpHeaders.IF_MATCH, etag(versionOf(row)))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TransactionRemovalResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void overrideCategory(UUID row) {
    client()
        .put()
        .uri(transactions() + "/" + row + "/category")
        .header(HttpHeaders.IF_MATCH, etag(versionOf(row)))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(shopping()))
        .exchange()
        .expectStatus()
        .isOk();
  }

  // A trigger that fails the statement on the row the condition names, after every row before it
  // was written: the failure injected halfway through a rollback. cleanDatabaseAndSignIn drops it.
  private void failOn(String timing, String condition) throws SQLException {
    execute(
        "CREATE FUNCTION test_fail_on_row() RETURNS TRIGGER AS $$ BEGIN"
            + " RAISE EXCEPTION 'injected test failure'; END; $$ LANGUAGE plpgsql");
    execute(
        "CREATE TRIGGER zz_test_failure "
            + timing
            + " ON transaction FOR EACH ROW WHEN ("
            + condition
            + ") EXECUTE FUNCTION test_fail_on_row()");
  }

  private void assertUnchanged(ImportBatchResponse batch, List<UUID> rows) {
    assertThat(rowsOf("SELECT status, version FROM import_batch WHERE id = ?", batch.id()).get(0))
        .containsEntry("status", "COMMITTED")
        .containsEntry("version", batch.version());
    assertThat(
            uuids(
                "SELECT id FROM transaction WHERE import_batch_id = ? AND voided_at IS NULL"
                    + " ORDER BY id",
                batch.id()))
        .containsExactlyElementsOf(rows);
    assertThat(count("SELECT count(*) FROM transaction WHERE replaces_transaction_id IS NOT NULL"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM import_row_raw WHERE import_batch_id = ?"
                    + " AND resulting_transaction_id IS NOT NULL",
                batch.id()))
        .isEqualTo(rows.size());
  }

  // --- HTTP helpers
  // -------------------------------------------------------------------------------

  private ImportBatchResponse importFile(byte[] file) {
    return commit(upload(file));
  }

  private ImportBatchResponse upload(byte[] content) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    body.add("templateId", template.toString());
    return client()
        .post()
        .uri(imports())
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportBatchResponse commit(ImportBatchResponse batch) {
    return client()
        .post()
        .uri(imports() + "/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, etag(batch.version()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportRollbackResponse rollback(ImportBatchResponse batch, String reason) {
    return rollbackResult(batch, reason).getResponseBody();
  }

  private EntityExchangeResult<ImportRollbackResponse> rollbackResult(
      ImportBatchResponse batch, String reason) {
    return client()
        .post()
        .uri(imports() + "/" + batch.id() + "/rollback")
        .header(HttpHeaders.IF_MATCH, etag(batch.version()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRollbackRequest(reason))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportRollbackResponse.class)
        .returnResult();
  }

  private StatusAssertions rollbackStatus(UUID batchId, String ifMatch, String reason) {
    RestTestClient.RequestBodySpec request =
        client()
            .post()
            .uri(imports() + "/" + batchId + "/rollback")
            .contentType(MediaType.APPLICATION_JSON);
    if (ifMatch != null) {
      request = request.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    return request.body(new ImportRollbackRequest(reason)).exchange().expectStatus();
  }

  private TransactionResponse recordManual(
      UUID accountId, String type, String amount, String description) {
    CreateTransactionRequest request =
        TransactionRequests.cash(
            type,
            DAY,
            new BigDecimal(amount),
            "CHF",
            description,
            null,
            null,
            null,
            null,
            null,
            null);
    return client()
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void openingBalance(String balance) {
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OpeningBalanceRequest(DAY.minusDays(5), new BigDecimal(balance), "CHF", null))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private void snapshot(String balance) {
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RecordAccountSnapshotRequest(DAY.plusDays(5), new BigDecimal(balance), List.of()))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private String reconciliationStatus() {
    return client()
        .get()
        .uri("/api/v1/accounts/" + account)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .reconciliation()
        .status();
  }

  private String rowStatus(UUID batchId, int rowNumber) {
    return (String)
        single(
            "SELECT parse_status FROM import_row_raw WHERE import_batch_id = ? AND row_number = ?",
            batchId,
            rowNumber);
  }

  private ImportTemplateResponse createTemplate() {
    return client()
        .post()
        .uri("/api/v1/import-templates")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new ImportTemplateRequest(
                "Simple",
                null,
                null,
                ";",
                "UTF-8",
                ".",
                null,
                "yyyy-MM-dd",
                0,
                0,
                0,
                "SINGLE_SIGNED_COLUMN",
                "PER_ROW",
                null,
                new ImportColumnMapping(
                    "Date",
                    null,
                    "Amount",
                    null,
                    null,
                    "Currency",
                    "Text",
                    null,
                    "Reference",
                    null,
                    null,
                    null,
                    null),
                Map.of(),
                null,
                List.of("Date", "Amount", "Currency", "Text", "Reference"),
                null,
                null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createAccount(String name) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private UUID createSecondMember(String email) {
    client()
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(UserSummaryResponse.class);
    return (UUID) single("SELECT workspace_member_id FROM app_user WHERE email = ?", email);
  }

  private void grantOnAccount(UUID memberId, UUID accountId, String level) {
    client()
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
        .isCreated()
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private String imports() {
    return "/api/v1/accounts/" + account + "/imports";
  }

  private String transactions() {
    return "/api/v1/accounts/" + account + "/transactions";
  }

  private RestTestClient client() {
    return client(token);
  }

  private RestTestClient client(String bearer) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + bearer)
        .build();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private static String etag(Object version) {
    return "\"" + version + "\"";
  }

  private static byte[] csv(String... rows) {
    return (HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  // --- database helpers
  // ---------------------------------------------------------------------------

  // The batch's transactions in id order, the order a rollback locks and voids them in.
  private List<UUID> batchRows(UUID batchId) {
    return uuids("SELECT id FROM transaction WHERE import_batch_id = ? ORDER BY id", batchId);
  }

  private UUID shopping() {
    return (UUID)
        single("SELECT id FROM category WHERE workspace_id IS NULL AND code = 'SHOPPING'");
  }

  private int versionOf(UUID transactionId) {
    return ((Number) single("SELECT version FROM transaction WHERE id = ?", transactionId))
        .intValue();
  }

  private List<UUID> uuids(String sql, Object... parameters) {
    return rowsOf(sql, parameters).stream()
        .map(row -> (UUID) row.values().iterator().next())
        .toList();
  }

  private void execute(String sql, Object... parameters) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.execute();
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  private long count(String sql, Object... parameters) {
    return ((Number) single(sql, parameters)).longValue();
  }

  private Object single(String sql, Object... parameters) {
    List<Map<String, Object>> rows = rowsOf(sql, parameters);
    assertThat(rows).hasSize(1);
    return rows.get(0).values().iterator().next();
  }

  private List<Map<String, Object>> rowsOf(String sql, Object... parameters) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int columns = resultSet.getMetaData().getColumnCount();
        while (resultSet.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= columns; i++) {
            row.put(resultSet.getMetaData().getColumnName(i), resultSet.getObject(i));
          }
          rows.add(row);
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
