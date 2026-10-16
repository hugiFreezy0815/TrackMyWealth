package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportRollbackRequest;
import com.trackmywealth.backend.dto.ImportRollbackResponse;
import com.trackmywealth.backend.dto.ImportStatementBalanceResponse;
import com.trackmywealth.backend.dto.ImportStatementBalanceRule;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.ReconciliationDecisionRequest;
import com.trackmywealth.backend.dto.ReconciliationResultResponse;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-12 against a real PostgreSQL: the balance a statement states becomes a {@code DOCUMENT}
 * snapshot when its import is committed, is never recorded twice, and goes with a rollback - or
 * stays as withdrawn history when an accepted reconciliation's adjusting entry points at it. The
 * statement is a synthetic, DKB-like CSV export: a preamble line {@code Kontostand vom <date>:}
 * with the balance, then a header and three bookings. Every value is invented.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportStatementBalanceControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final LocalDate STATEMENT_DATE = LocalDate.of(2019, 1, 31);
  private static final String REASON = "Imported the wrong statement";
  private static final String BALANCE_PATTERN =
      "^Kontostand vom (?P<date>[0-9.]+): (?P<amount>-?[0-9.]+,[0-9]{2}) €$";
  // The bookings add up to 2,407.60; with an opening balance of 100.00 the ledger agrees with a
  // statement balance of 2,507.60.
  private static final String BOOKINGS =
      """
      Datum;Betrag;Waehrung;Text;Referenz
      05.01.19;-12,40;CHF;Bakery;R1
      06.01.19;-80,00;CHF;Groceries;R2
      07.01.19;2.500,00;CHF;Salary;R3
      """;

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
              "DELETE FROM settlement_match",
              "DELETE FROM transfer_detection_fx_pending",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction_category_split",
              "DELETE FROM import_row_raw",
              // An accepted difference's adjusting entry points at its result: the ledger first.
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM reconciliation_result",
              "DELETE FROM snapshot_holding",
              // An imported snapshot points at its batch (V74): before the batches.
              "DELETE FROM account_snapshot",
              "DELETE FROM import_file",
              "DELETE FROM import_batch",
              "DELETE FROM import_template",
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
    account = createAccount();
    template = createTemplate().id();
  }

  // --- acceptance criterion 1: the committed balance is a DOCUMENT snapshot of the batch -------

  @Test
  void theStatementsBalanceIsPreviewedAndBecomesADocumentSnapshotOfTheBatch() {
    ImportBatchResponse preview = upload(statement("2.507,60"));

    assertThat(preview.statementBalance())
        .isEqualTo(
            new ImportStatementBalanceResponse(
                ImportBatchValues.BALANCE_FOUND,
                STATEMENT_DATE,
                new BigDecimal("2507.60"),
                "CHF",
                null,
                null,
                ImportBatchValues.SNAPSHOT_NEW));
    assertThat(count("SELECT count(*) FROM account_snapshot")).isZero();

    ImportBatchResponse committed = commit(preview);

    assertThat(committed.statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_RECORDED);
    assertThat(
            rowsOf(
                "SELECT source, snapshot_date, balance, currency, import_batch_id,"
                    + " is_opening_balance FROM account_snapshot WHERE account_id = ?",
                account))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("source")).isEqualTo("DOCUMENT");
              assertThat(row.get("snapshot_date")).isEqualTo(Date.valueOf(STATEMENT_DATE));
              assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("2507.60");
              assertThat(row.get("currency")).isEqualTo("CHF");
              assertThat(row.get("import_batch_id")).isEqualTo(preview.id());
              assertThat(row.get("is_opening_balance")).isEqualTo(false);
            });
    assertThat(snapshots())
        .singleElement()
        .satisfies(snapshot -> assertThat(snapshot.source()).isEqualTo("DOCUMENT"));
    // The batch's summary keeps saying what the commit did.
    assertThat(get(preview.id()).statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_RECORDED);
  }

  /** A balance the account's dates do not admit is shown; the rows still go in, without it. */
  @Test
  void aBalanceBeforeTheAccountWasOpenedNeverBlocksTheRowsCommit() {
    ImportBatchResponse preview = upload(statement("2.507,60"));
    assertThat(preview.statementBalance().outcome()).isEqualTo(ImportBatchValues.BALANCE_FOUND);
    execute("UPDATE account SET opened_at = ? WHERE id = ?", STATEMENT_DATE.plusDays(1), account);

    assertThat(get(preview.id()).statementBalance().outcome())
        .isEqualTo(ImportBatchValues.BALANCE_OUT_OF_RANGE);
    ImportBatchResponse committed = commit(get(preview.id()));

    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.counts().imported()).isPositive();
    assertThat(committed.statementBalance().outcome())
        .isEqualTo(ImportBatchValues.BALANCE_OUT_OF_RANGE);
    assertThat(committed.statementBalance().snapshot()).isNull();
    assertThat(count("SELECT count(*) FROM account_snapshot")).isZero();
  }

  @Test
  void aCommittedBalanceIsReconciledAgainstTheLedger() {
    openingBalance("100.00");

    commit(upload(statement("2.500,00")));

    assertThat(results())
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.status()).isEqualTo(ReconciliationResultValues.OPEN);
              assertThat(result.asOf()).isEqualTo(STATEMENT_DATE);
              assertThat(result.differenceAmount().abs()).isEqualByComparingTo("7.60");
            });
  }

  @Test
  void aTemplateWithoutTheRuleReportsNoStatementBalance() {
    UUID plain = createTemplate("Without balance", null).id();

    ImportBatchResponse committed = commit(upload(statement("2.507,60"), plain));

    assertThat(committed.statementBalance()).isNull();
    assertThat(count("SELECT count(*) FROM account_snapshot")).isZero();
  }

  @Test
  void aStatementWithoutTheBalanceLineIsNotFoundAndRecordsNothing() {
    ImportBatchResponse committed =
        commit(upload(("Girokonto;CH00\n" + BOOKINGS).getBytes(StandardCharsets.UTF_8)));

    assertThat(committed.statementBalance().outcome())
        .isEqualTo(ImportBatchValues.BALANCE_NOT_FOUND);
    assertThat(committed.statementBalance().snapshot()).isNull();
    assertThat(count("SELECT count(*) FROM account_snapshot")).isZero();
  }

  // --- acceptance criterion 2: the same statement twice -----------------------------------------

  @Test
  void theSameStatementTwiceIsRecordedOnceAndThePreviewSaysSo() {
    commit(upload(statement("2.507,60")));

    ImportBatchResponse again = upload(statement("2.507,60"));
    assertThat(again.statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_ALREADY_RECORDED);
    ImportBatchResponse committedAgain = commit(again);

    assertThat(committedAgain.statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_ALREADY_RECORDED);
    assertThat(count("SELECT count(*) FROM account_snapshot WHERE source = 'DOCUMENT'"))
        .isEqualTo(1);
  }

  @Test
  void anotherBalanceForAnAlreadyImportedDateConflictsAndIsNotRecorded() {
    ImportBatchResponse first = commit(upload(statement("2.507,60")));

    ImportBatchResponse other = upload(statement("1.000,00"));
    assertThat(other.statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_CONFLICTING);
    assertThat(commit(other).statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_CONFLICTING);

    assertThat(
            rowsOf(
                "SELECT import_batch_id, balance FROM account_snapshot WHERE account_id = ?",
                account))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("import_batch_id")).isEqualTo(first.id());
              assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("2507.60");
            });
  }

  // --- acceptance criterion 3: rollback ---------------------------------------------------------

  @Test
  void rollingBackTheBatchDeletesItsSnapshotAndItsUndecidedReconciliation() {
    openingBalance("100.00");
    ImportBatchResponse batch = commit(upload(statement("2.500,00")));
    assertThat(results()).hasSize(1);

    ImportRollbackResponse rollback = rollback(batch);

    assertThat(rollback.batch().statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_DELETED);
    assertThat(count("SELECT count(*) FROM account_snapshot WHERE source = 'DOCUMENT'")).isZero();
    assertThat(count("SELECT count(*) FROM reconciliation_result")).isZero();
    // The statement imports as new again.
    assertThat(upload(statement("2.500,00")).statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_NEW);
  }

  @Test
  void aDismissedReconciliationBlocksTheRollbackUntilItIsReopened() {
    openingBalance("100.00");
    ImportBatchResponse batch = commit(upload(statement("2.500,00")));
    ReconciliationResultResponse open = results().get(0);
    ReconciliationResultResponse dismissed = decide(open, "dismiss");
    ImportBatchResponse current = get(batch.id());

    client()
        .post()
        .uri(imports() + "/" + batch.id() + "/rollback")
        .header(HttpHeaders.IF_MATCH, etag(current.version()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRollbackRequest(REASON))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_ROLLBACK_RECONCILIATION_DECIDED)
        .jsonPath("$.reconciliationResultId")
        .isEqualTo(open.id().toString());
    // Nothing was changed: the batch, its bookings and its snapshot are all still there.
    assertThat(get(batch.id()).status()).isEqualTo(ImportBatchValues.COMMITTED);
    assertThat(count("SELECT count(*) FROM transaction WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(3);
    assertThat(count("SELECT count(*) FROM account_snapshot WHERE source = 'DOCUMENT'"))
        .isEqualTo(1);

    reopen(dismissed);
    ImportRollbackResponse rollback = rollback(get(batch.id()));

    assertThat(rollback.batch().statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_DELETED);
    assertThat(count("SELECT count(*) FROM account_snapshot WHERE source = 'DOCUMENT'")).isZero();
  }

  @Test
  void anOnceAcceptedReconciliationKeepsTheSnapshotAsWithdrawnHistory() {
    openingBalance("100.00");
    ImportBatchResponse batch = commit(upload(statement("2.500,00")));
    ReconciliationResultResponse accepted = decide(results().get(0), "accept");
    assertThat(accepted.resolutionTransactionId()).isNotNull();
    reopen(accepted);

    ImportRollbackResponse rollback = rollback(get(batch.id()));

    assertThat(rollback.batch().statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_WITHDRAWN);
    assertThat(
            rowsOf(
                "SELECT withdrawn_at, withdrawn_by FROM account_snapshot"
                    + " WHERE import_batch_id = ?",
                batch.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("withdrawn_at")).isNotNull();
              assertThat(row.get("withdrawn_by")).isNotNull();
            });
    // History only: no longer listed as an observation, and its result is superseded, yet the
    // reconciliation history still shows it.
    assertThat(snapshots()).noneMatch(snapshot -> "DOCUMENT".equals(snapshot.source()));
    assertThat(results())
        .singleElement()
        .satisfies(
            result -> assertThat(result.status()).isEqualTo(ReconciliationResultValues.SUPERSEDED));

    // The withdrawn snapshot does not block importing the statement again.
    ImportBatchResponse again = upload(statement("2.500,00"));
    assertThat(again.statementBalance().snapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_NEW);
    assertThat(commit(again).statementBalance().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_RECORDED);
    assertThat(
            count(
                "SELECT count(*) FROM account_snapshot"
                    + " WHERE source = 'DOCUMENT' AND withdrawn_at IS NULL"))
        .isEqualTo(1);
  }

  // --- the template rule ------------------------------------------------------------------------

  @Test
  void aRuleWithoutTheAmountGroupIsRefused() {
    client()
        .post()
        .uri("/api/v1/import-templates")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            templateRequest(
                "Broken", new ImportStatementBalanceRule("^Kontostand vom (?P<date>\\S+)", null)))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID)
        .jsonPath("$.field")
        .isEqualTo("statementBalance.pattern");
  }

  @Test
  void theRuleIsPartOfTheTemplatesVersionedDefinition() {
    ImportTemplateResponse saved =
        client()
            .get()
            .uri("/api/v1/import-templates/" + template)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportTemplateResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(saved.statementBalance())
        .isEqualTo(new ImportStatementBalanceRule(BALANCE_PATTERN, "dd.MM.yyyy"));
  }

  // #339: the rule's optional sign group and statement date travel through the API whole - saved,
  // answered and read back as sent.
  @Test
  void aRuleWithASignAndAStatementDateIsSavedAndReadBackWhole() {
    ImportStatementBalanceRule rule =
        new ImportStatementBalanceRule(
            "^Neuer Saldo (?P<amount>[0-9.]*[0-9],[0-9]{2}) (?P<sign>[+-])$",
            null, "Abrechnung vom \\S+ bis (?P<date>[0-9.]+)", "dd.MM.yyyy");

    ImportTemplateResponse created =
        client()
            .post()
            .uri("/api/v1/import-templates")
            .contentType(MediaType.APPLICATION_JSON)
            .body(templateRequest("Card-like", rule))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(ImportTemplateResponse.class)
            .returnResult()
            .getResponseBody();
    ImportTemplateResponse read =
        client()
            .get()
            .uri("/api/v1/import-templates/" + created.id())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportTemplateResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.statementBalance()).isEqualTo(rule);
    assertThat(read.statementBalance()).isEqualTo(rule);
  }

  @Test
  void aStatementDatePatternOverTheLimitIsA400NamingTheField() {
    client()
        .post()
        .uri("/api/v1/import-templates")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            templateRequest(
                "Too long",
                new ImportStatementBalanceRule(
                    BALANCE_PATTERN,
                    null,
                    "(?P<date>x)" + "x".repeat(ImportPdfLayout.MAX_PATTERN_LENGTH),
                    null)))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_FAILED")
        .jsonPath("$.errors[0].field")
        .isEqualTo("statementBalance.statementDatePattern");
  }

  // --- helpers ----------------------------------------------------------------------------------

  // A synthetic statement whose preamble states balance (German notation) on STATEMENT_DATE.
  private static byte[] statement(String balance) {
    return ("\"Kontostand vom 31.01.2019:\";\"" + balance + " €\"\n" + BOOKINGS)
        .getBytes(StandardCharsets.UTF_8);
  }

  private ImportBatchResponse upload(byte[] content) {
    return upload(content, template);
  }

  private ImportBatchResponse upload(byte[] content, UUID templateId) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    body.add("templateId", templateId.toString());
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

  private ImportBatchResponse get(UUID batchId) {
    return client()
        .get()
        .uri(imports() + "/" + batchId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportRollbackResponse rollback(ImportBatchResponse batch) {
    return client()
        .post()
        .uri(imports() + "/" + batch.id() + "/rollback")
        .header(HttpHeaders.IF_MATCH, etag(batch.version()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRollbackRequest(REASON))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportRollbackResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<AccountSnapshotResponse> snapshots() {
    return client()
        .get()
        .uri("/api/v1/accounts/" + account + "/snapshots")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<AccountSnapshotResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private List<ReconciliationResultResponse> results() {
    return client()
        .get()
        .uri(reconciliations())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<ReconciliationResultResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  private ReconciliationResultResponse decide(ReconciliationResultResponse result, String action) {
    return client()
        .post()
        .uri(reconciliations() + "/" + result.id() + "/" + action)
        .header(HttpHeaders.IF_MATCH, etag(result.version()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReconciliationDecisionRequest("Checked against the paper statement"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ReconciliationResultResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void reopen(ReconciliationResultResponse result) {
    client()
        .post()
        .uri(reconciliations() + "/" + result.id() + "/reopen")
        .header(HttpHeaders.IF_MATCH, etag(result.version()))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private void openingBalance(String balance) {
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new OpeningBalanceRequest(
                LocalDate.of(2019, 1, 1), new BigDecimal(balance), "CHF", null))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private ImportTemplateResponse createTemplate() {
    return createTemplate(
        "DKB-like", new ImportStatementBalanceRule(BALANCE_PATTERN, "dd.MM.yyyy"));
  }

  private ImportTemplateResponse createTemplate(String name, ImportStatementBalanceRule rule) {
    return client()
        .post()
        .uri("/api/v1/import-templates")
        .contentType(MediaType.APPLICATION_JSON)
        .body(templateRequest(name, rule))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // Bookings dated dd.MM.yy in German notation after one preamble line; the balance's own date is
  // written dd.MM.yyyy.
  private static ImportTemplateRequest templateRequest(
      String name, ImportStatementBalanceRule rule) {
    return new ImportTemplateRequest(
        name,
        null,
        null,
        ";",
        "UTF-8",
        ",",
        ".",
        "dd.MM.yy",
        0,
        1,
        0,
        "SINGLE_SIGNED_COLUMN",
        "PER_ROW",
        null,
        new ImportColumnMapping(
            "Datum",
            null,
            "Betrag",
            null,
            null,
            "Waehrung",
            "Text",
            null,
            "Referenz",
            null,
            null,
            null,
            null),
        Map.of(),
        null,
        List.of("Datum", "Betrag", "Waehrung", "Text", "Referenz"),
        null,
        null,
        Map.of(),
        null,
        null,
        rule);
  }

  private UUID createAccount() {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account("Girokonto", "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
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

  private String reconciliations() {
    return "/api/v1/accounts/" + account + "/reconciliations";
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + token)
        .build();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private static String etag(Object version) {
    return "\"" + version + "\"";
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  // --- database helpers -------------------------------------------------------------------------

  private void execute(String sql, Object... parameters) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private long count(String sql, Object... parameters) {
    List<Map<String, Object>> rows = rowsOf(sql, parameters);
    assertThat(rows).hasSize(1);
    return ((Number) rows.get(0).values().iterator().next()).longValue();
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
