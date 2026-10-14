package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportParseRequest;
import com.trackmywealth.backend.dto.ImportRowInclusionRequest;
import com.trackmywealth.backend.dto.ImportRowResponse;
import com.trackmywealth.backend.dto.ImportTemplateCandidateResponse;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.ImportTemplateTestResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
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
 * US-07-04 against a real PostgreSQL: upload, preview with duplicate detection, row inclusion,
 * errors.csv, commit and discard, with the golden cases V-18 (same file twice), V-19 (Swiss and
 * German formats), V-20 (separate debit/credit columns, trailing summary row) and V-21 (template
 * version change). The duplicate rules' corner cases are pinned without a database in {@code
 * ImportDuplicateServiceTest}. Every fixture is synthetic.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportBatchControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String SWISS = "swiss-cash-iso-8859-1.csv";
  private static final String GERMAN = "german-cash-utf8-bom.csv";
  private static final String SIMPLE_HEADER = "Date;Amount;Currency;Text;Reference;MCC";
  private static final List<String> SIMPLE_COLUMNS =
      List.of("Date", "Amount", "Currency", "Text", "Reference", "MCC");
  private static final LocalDate DAY = LocalDate.of(2019, 1, 5);
  // Lower than the default, so the limit is reached quickly; no other test needs more.
  private static final int MAX_OPEN_BATCHES = 4;
  private static final Logger LOG = LoggerFactory.getLogger(ImportBatchControllerTest.class);

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
    registry.add("app.import.max-open-batches-per-account", () -> MAX_OPEN_BATCHES);
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  private String token;
  private UUID account;

  @BeforeEach
  void cleanDatabaseAndSignIn() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM reconciliation_result",
              "DELETE FROM settlement_match",
              "DELETE FROM transfer_detection_fx_pending",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM import_row_raw",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM import_file",
              "DELETE FROM import_batch",
              "DELETE FROM import_template",
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
    account = createAccount("Privatkonto", "CHF");
  }

  // --- upload and preview
  // -------------------------------------------------------------------------

  @Test
  void anUploadWithATemplateStoresTheFileAndPreviewsEveryRow() throws Exception {
    // V-19, Swiss format: ISO-8859-1, apostrophe thousands separator, a quoted delimiter.
    ImportTemplateResponse swiss = createTemplate(swissTemplate());
    byte[] file = fixture(SWISS);

    EntityExchangeResult<ImportBatchResponse> result =
        upload(token, account, file, swiss.id())
            .expectStatus()
            .isCreated()
            .expectBody(ImportBatchResponse.class)
            .returnResult();
    ImportBatchResponse batch =
        CurrentVersion.storedEtag(
            result, dataSource, "import_batch", result.getResponseBody().id());

    assertThat(result.getResponseHeaders().getLocation())
        .hasPath("/api/v1/accounts/" + account + "/imports/" + batch.id());
    assertThat(batch.status()).isEqualTo("PARSED");
    assertThat(batch.sourceKind()).isEqualTo("CSV");
    assertThat(batch.sourceFileName()).isEqualTo("statement.csv");
    assertThat(batch.templateId()).isEqualTo(swiss.id());
    assertThat(batch.templateVersion()).isEqualTo("1");
    assertThat(batch.templateCandidates()).isEmpty();
    assertThat(batch.counts().total()).isEqualTo(5);
    assertThat(batch.counts().newRows()).isEqualTo(5);
    assertThat(batch.counts().included()).isEqualTo(5);
    assertThat(batch.sameFileImportedIn()).isNull();

    // The original bytes are kept, with their SHA-256 on the batch and the file.
    String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
    assertThat(single("SELECT file_sha256 FROM import_batch WHERE id = ?", batch.id()))
        .isEqualTo(sha256);
    assertThat(single("SELECT sha256 FROM import_file WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(sha256);
    assertThat(
            (byte[])
                single("SELECT content FROM import_file WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(file);
    assertThat(count("SELECT count(*) FROM import_row_raw WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(5);

    List<ImportRowResponse> rows = rows(batch.id(), "").content();
    assertThat(rows).extracting(ImportRowResponse::rowNumber).containsExactly(1, 2, 3, 4, 5);
    assertThat(rows).allSatisfy(row -> assertThat(row.status()).isEqualTo("PARSED"));
    assertThat(rows.get(2).canonical().amount()).isEqualByComparingTo("-1850.00");
    assertThat(rows.get(2).canonical().description()).isEqualTo("Miete Januar; Wohnung 3.OG");
    assertThat(rows.get(2).rawData()).containsEntry("Betrag", "-1'850.00");
    // Nothing reaches the ledger before the commit.
    assertThat(count("SELECT count(*) FROM transaction")).isZero();
  }

  @Test
  void aFileWhoseHeaderMatchesExactlyOneTemplateIsParsedWithoutChoosingIt() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));

    ImportBatchResponse batch = upload(csv("2019-01-05;-12.40;CHF;Bakery;R1;"));

    assertThat(batch.status()).isEqualTo("PARSED");
    assertThat(batch.templateId()).isEqualTo(simple.id());
    assertThat(batch.counts().newRows()).isEqualTo(1);
  }

  @Test
  void withoutAnExactMatchTheBatchWaitsForAChosenTemplate() {
    // Built from a sample with one more column: the file holds every mapped column, but its header
    // is not the template's.
    List<String> wider = new ArrayList<>(SIMPLE_COLUMNS);
    wider.add("Balance");
    ImportTemplateResponse loose = createTemplate(simpleTemplate("Loose", wider));

    ImportBatchResponse uploaded = upload(csv("2019-01-05;-12.40;CHF;Bakery;R1;"));

    assertThat(uploaded.status()).isEqualTo("UPLOADED");
    assertThat(uploaded.templateId()).isNull();
    assertThat(uploaded.counts().total()).isZero();
    assertThat(uploaded.templateCandidates())
        .extracting(c -> c.template().id(), ImportTemplateCandidateResponse::match)
        .containsExactly(tuple(loose.id(), ImportTemplateCandidateResponse.MAPPED_COLUMNS_PRESENT));
    assertThat(get(uploaded.id()).templateCandidates()).isEmpty();

    ImportBatchResponse parsed = parse(uploaded.id(), uploaded.version(), loose.id());

    assertThat(parsed.status()).isEqualTo("PARSED");
    assertThat(parsed.templateId()).isEqualTo(loose.id());
    assertThat(parsed.counts().newRows()).isEqualTo(1);
    assertThat(parsed.version()).isGreaterThan(uploaded.version());
  }

  @Test
  void parsingAgainReplacesTheRowsAndNeedsTheCurrentVersion() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    ImportBatchResponse batch =
        upload(csv("2019-01-05;-12.40;CHF;Bakery;R1;", "2019-01-06;-3.50;CHF;Coffee;R2;"));

    client()
        .post()
        .uri(imports() + "/" + batch.id() + "/parse")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportParseRequest(simple.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    ImportBatchResponse again = parse(batch.id(), batch.version(), simple.id());
    client()
        .post()
        .uri(imports() + "/" + batch.id() + "/parse")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportParseRequest(simple.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);

    assertThat(again.counts().total()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM import_row_raw WHERE import_batch_id = ?", batch.id()))
        .isEqualTo(2);
  }

  // --- golden cases
  // ---------------------------------------------------------------------------------

  @Test
  void aGermanFileWithDebitAndCreditColumnsAndASummaryRowImportsItsBookings() throws Exception {
    // V-19/V-20: UTF-8 with BOM, a preamble, Soll/Haben columns, a trailing balance row.
    UUID euro = createAccount("Girokonto", "EUR");
    ImportTemplateResponse german = createTemplate(germanTemplate());

    ImportBatchResponse batch =
        upload(token, euro, fixture(GERMAN), german.id())
            .expectStatus()
            .isCreated()
            .expectBody(ImportBatchResponse.class)
            .returnResult()
            .getResponseBody();
    ImportBatchResponse committed = commit(euro, batch.id(), batch.version());

    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.counts().imported()).isEqualTo(5);
    List<Map<String, Object>> ledger =
        rowsOf(
            "SELECT transaction_type, amount, source, import_batch_id FROM transaction"
                + " WHERE account_id = ? ORDER BY booking_date, amount",
            euro);
    assertThat(ledger)
        .extracting(
            r -> r.get("transaction_type"), r -> ((BigDecimal) r.get("amount")).toPlainString())
        .containsExactly(
            tuple("EXPENSE", "-85.0000"),
            tuple("INCOME", "3150.0000"),
            tuple("EXPENSE", "-1204.5600"),
            tuple("FEE", "-4.9000"),
            tuple("INTEREST", "0.1200"));
    assertThat(ledger).allSatisfy(r -> assertThat(r.get("source")).isEqualTo("CSV"));
    assertThat(ledger).allSatisfy(r -> assertThat(r.get("import_batch_id")).isEqualTo(batch.id()));
  }

  @Test
  void eachBatchRecordsTheTemplateVersionItWasParsedWith() {
    // V-21: the bank changes its date format between two exports.
    ImportTemplateResponse v1 = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    ImportBatchResponse first = upload(token, v1.id(), csv("2019-01-05;-12.40;CHF;Bakery;R1;"));

    ImportTemplateResponse v2 =
        client()
            .put()
            .uri("/api/v1/import-templates/" + v1.id())
            .header(HttpHeaders.IF_MATCH, "\"" + v1.version() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(withDateFormat(simpleTemplate("Simple", SIMPLE_COLUMNS), "dd.MM.yyyy"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportTemplateResponse.class)
            .returnResult()
            .getResponseBody();
    ImportBatchResponse second = upload(token, v2.id(), csv("06.01.2019;-3.50;CHF;Coffee;R2;"));

    assertThat(v2.id()).isNotEqualTo(v1.id());
    assertThat(first.templateId()).isEqualTo(v1.id());
    assertThat(first.templateVersion()).isEqualTo("1");
    assertThat(second.templateId()).isEqualTo(v2.id());
    assertThat(second.templateVersion()).isEqualTo("2");
    assertThat(second.counts().newRows()).isEqualTo(1);
    assertThat(get(first.id()).templateVersion()).isEqualTo("1");
  }

  @Test
  void theSameFileTwiceIsAllDuplicatesAndItsCommitAddsNothing() {
    // V-18.
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    byte[] file = csv("2019-01-05;-12.40;CHF;Bakery;R1;", "2019-01-06;-3.50;CHF;Coffee;;");
    ImportBatchResponse first = upload(token, simple.id(), file);
    ImportBatchResponse committed = commit(account, first.id(), first.version());

    ImportBatchResponse second = upload(token, simple.id(), file);

    assertThat(second.counts().duplicates()).isEqualTo(2);
    assertThat(second.counts().included()).isZero();
    assertThat(second.sameFileImportedIn()).isNotNull();
    assertThat(second.sameFileImportedIn().batchId()).isEqualTo(first.id());
    assertThat(second.sameFileImportedIn().committedAt())
        .isCloseTo(committed.committedAt(), within(1, ChronoUnit.MILLIS));
    ImportBatchResponse again = commit(account, second.id(), second.version());
    assertThat(again.status()).isEqualTo("COMMITTED");
    assertThat(again.counts().imported()).isZero();
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(2);
  }

  // --- duplicates
  // ------------------------------------------------------------------------------------

  @Test
  void rowsTheLedgerHoldsAreExcludedDuplicatesAndOneCanBeForcedIn() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    // By reference: an earlier import of R1. By the exact fallback: a manual entry.
    ImportBatchResponse earlier =
        upload(token, simple.id(), csv("2019-01-05;-12.40;CHF;Bakery;R1;"));
    commit(account, earlier.id(), earlier.version());
    TransactionResponse manual = recordManual(account, "EXPENSE", "-20.00", "CHF", "Pharmacy");

    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv(
                "2019-01-05;-12.40;CHF;Bakery;R1;",
                "2019-01-05;-20.00;CHF;  PHARMACY ;R9;",
                "2019-01-07;-7.00;CHF;Kiosk;R3;"));

    assertThat(batch.counts().duplicates()).isEqualTo(2);
    assertThat(batch.counts().newRows()).isEqualTo(1);
    List<ImportRowResponse> duplicates = rows(batch.id(), "?status=DUPLICATE").content();
    assertThat(duplicates).extracting(ImportRowResponse::rowNumber).containsExactly(1, 2);
    assertThat(duplicates).allSatisfy(row -> assertThat(row.included()).isFalse());
    assertThat(duplicates.get(1).duplicateOfTransactionId()).isEqualTo(manual.id());
    assertThat(duplicates.get(0).duplicateOfTransactionId())
        .isEqualTo(single("SELECT id FROM transaction WHERE external_id = 'R1'"));

    // Forcing both in: the reference one goes in without its taken reference.
    ImportBatchResponse forced = include(batch.id(), batch.version(), 1, true);
    forced = include(batch.id(), forced.version(), 2, true);
    assertThat(forced.counts().included()).isEqualTo(3);
    assertThat(forced.version()).isGreaterThan(batch.version());
    // The commit confirms the rows as they are now: the old version is stale.
    commitStatus(account, batch.id(), "\"" + batch.version() + "\"")
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    commit(account, batch.id(), forced.version());

    assertThat(count("SELECT count(*) FROM transaction WHERE amount = -12.40")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM transaction WHERE external_id = 'R1'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM transaction WHERE external_id = 'R9'")).isEqualTo(1);
  }

  @Test
  void twoIdenticalRowsAgainstOneIdenticalLedgerRowGiveExactlyOneDuplicate() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    recordManual(account, "EXPENSE", "-3.50", "CHF", "Coffee");

    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv("2019-01-05;-3.50;CHF;Coffee;;", "2019-01-05;-3.50;CHF;Coffee;;"));

    assertThat(batch.counts().duplicates()).isEqualTo(1);
    assertThat(batch.counts().newRows()).isEqualTo(1);
    commit(account, batch.id(), batch.version());
    assertThat(count("SELECT count(*) FROM transaction WHERE amount = -3.50")).isEqualTo(2);
  }

  @Test
  void aRepeatedReferenceInOneFileIsAnErrorFromItsSecondRowOn() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));

    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv("2019-01-05;-3.50;CHF;Coffee;R1;", "2019-01-06;-4.50;CHF;Tea;R1;"));

    ImportRowResponse second = rows(batch.id(), "?status=ERROR").content().get(0);
    assertThat(second.rowNumber()).isEqualTo(2);
    assertThat(second.error().code()).isEqualTo("IMPORT_ROW_EXTERNAL_ID_REPEATED");
    assertThat(second.error().message())
        .isEqualTo("Row 1 already has the bank reference \"R1\"; only that row is imported.");
    assertThat(second.canonical()).isNotNull();
  }

  @Test
  void creditCardStatementImportsPurchasesSettlementsRefundsAndIssuerFx() throws Exception {
    UUID card =
        client()
            .post()
            .uri("/api/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AccountRequests.account("Synthetic card", "CREDIT_CARD", "CHF").build())
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody()
            .id();
    client()
        .put()
        .uri("/api/v1/accounts/" + card + "/settlement-source")
        .headers(CurrentVersion.ifMatchForCard(dataSource, card))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetSettlementSourceRequest(account))
        .exchange()
        .expectStatus()
        .isOk();

    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                "WITHDRAWAL",
                LocalDate.of(2031, 5, 2),
                new BigDecimal("-30.00"),
                "CHF",
                "Synthetic card payment",
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isCreated();

    ImportTemplateResponse template = createTemplate(creditCardTemplate());
    byte[] file =
        ("Date;Amount;Currency;Text;Reference;OriginalAmount;OriginalCurrency;Rate\n"
                + "2031-05-01;-55,00;CHF;Overseas purchase;CARD-A;-50,00;EUR;0,909091\n"
                + "2031-05-02;30,00;CHF;Monthly card payment;CARD-B;;;\n"
                + "2031-05-03;8,00;CHF;SHOP rEfUnD;CARD-C;;;\n"
                + "2031-05-04;4,00;CHF;Other credit;CARD-D;;;\n")
            .getBytes(StandardCharsets.UTF_8);
    // The template's dry run for the card types rows as the import into it will.
    assertThat(templatePreview(template.id(), card, file).rows())
        .extracting(row -> row.canonical().transactionType())
        .containsExactly("CREDIT_CARD_PURCHASE", "SETTLEMENT", "REFUND", "SETTLEMENT");
    assertThat(templatePreview(template.id(), null, file).rows())
        .extracting(row -> row.canonical().transactionType())
        .containsExactly("EXPENSE", "INCOME", "REFUND", "INCOME");
    ImportBatchResponse batch =
        upload(token, card, file, template.id())
            .expectStatus()
            .isCreated()
            .expectBody(ImportBatchResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(batch.counts().total()).isEqualTo(4);
    assertThat(batch.counts().newRows()).isEqualTo(4);
    assertThat(batch.counts().errors()).isZero();
    List<ImportRowResponse> preview =
        client()
            .get()
            .uri("/api/v1/accounts/" + card + "/imports/" + batch.id() + "/rows")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<PageOf<ImportRowResponse>>() {})
            .returnResult()
            .getResponseBody()
            .content();
    assertThat(preview)
        .extracting(row -> row.canonical().transactionType())
        .containsExactly("CREDIT_CARD_PURCHASE", "SETTLEMENT", "REFUND", "SETTLEMENT");
    assertThat(preview.get(0).canonical().originalCurrency()).isEqualTo("EUR");
    assertThat(preview.get(3).warnings())
        .extracting(warning -> warning.code())
        .contains("IMPORT_ROW_CARD_SETTLEMENT_UNMATCHED");
    assertThat(preview.get(1).warnings())
        .extracting(warning -> warning.code())
        .doesNotContain("IMPORT_ROW_CARD_SETTLEMENT_UNMATCHED");

    ImportBatchResponse committed = commit(card, batch.id(), batch.version());
    assertThat(committed.counts().imported()).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM transaction WHERE account_id = ?", card)).isEqualTo(4);
    assertThat(
            single(
                "SELECT currency FROM transaction WHERE account_id = ? AND external_id = ?",
                card,
                "CARD-A"))
        .isEqualTo("EUR");
    assertThat(
            (BigDecimal)
                single(
                    "SELECT amount FROM transaction WHERE account_id = ? AND external_id = ?",
                    card,
                    "CARD-A"))
        .isEqualByComparingTo("-50.00");
    assertThat(
            (BigDecimal)
                single(
                    "SELECT fx_rate_to_account_currency FROM transaction WHERE account_id = ? AND external_id = ?",
                    card,
                    "CARD-A"))
        .isEqualByComparingTo("1.100000");
    assertThat(
            single(
                "SELECT fx_rate_estimated FROM transaction WHERE account_id = ? AND external_id = ?",
                card,
                "CARD-A"))
        .isEqualTo(false);
    assertThat(count("SELECT count(*) FROM settlement_match WHERE status = 'CONFIRMED'"))
        .isEqualTo(1);

    client()
        .post()
        .uri("/api/v1/accounts/" + card + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                "REFUND",
                LocalDate.of(2031, 5, 5),
                new BigDecimal("3.00"),
                "CHF",
                "Manual card refund",
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isCreated();

    // Fallback duplicate detection must compare EUR -50.00, not the booked CHF -55.00.
    // No external bank reference is provided on either import.
    byte[] unreferenced =
        ("Date;Amount;Currency;Text;Reference;OriginalAmount;OriginalCurrency;Rate\\n"
                + "2031-05-06;-55,00;CHF;Reference-free hotel;;-50,00;EUR;0,909091\\n")
            .getBytes(StandardCharsets.UTF_8);
    ImportBatchResponse first =
        upload(token, card, unreferenced, template.id())
            .expectStatus()
            .isCreated()
            .expectBody(ImportBatchResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(commit(card, first.id(), first.version()).counts().imported()).isEqualTo(1);

    ImportBatchResponse repeated =
        upload(token, card, unreferenced, template.id())
            .expectStatus()
            .isCreated()
            .expectBody(ImportBatchResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(repeated.counts().duplicates()).isEqualTo(1);
    assertThat(repeated.counts().included()).isZero();
  }

  // --- errors and warnings
  // -----------------------------------------------------------------------------

  @Test
  void zeroAmountRowsAreVisibleSkippedLocalizedAndNeverCommittedOrExported() throws Exception {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Zero amount", SIMPLE_COLUMNS));
    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv(
                "2031-05-02;0.00;CHF;Waived information;Z1;",
                "2031-05-03;-12.40;CHF;Coffee;Z2;",
                "2031-05-04;5.00;CHF;Cashback;Z3;",
                "bad-date;-3.00;CHF;Invalid;Z4;"));

    assertThat(batch.counts().total()).isEqualTo(4);
    assertThat(batch.counts().skipped()).isEqualTo(1);
    assertThat(batch.counts().newRows()).isEqualTo(2);
    assertThat(batch.counts().errors()).isEqualTo(1);
    assertThat(batch.counts().included()).isEqualTo(2);
    ImportRowResponse skipped = rows(batch.id(), "?status=SKIPPED").content().get(0);
    assertThat(skipped.rowNumber()).isEqualTo(1);
    assertThat(skipped.included()).isFalse();
    assertThat(skipped.canonical().amount()).isEqualByComparingTo("0.00");
    assertThat(skipped.rawData()).containsEntry("Text", "Waived information");
    assertThat(skipped.error().code()).isEqualTo("IMPORT_ROW_ZERO_AMOUNT");
    assertThat(skipped.error().message())
        .isEqualTo(
            "This row has a zero amount and moves no money. It is shown but will not be imported.");

    client()
        .patch()
        .uri(imports() + "/" + batch.id() + "/rows/1")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRowInclusionRequest(true))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_ROW_NOT_INCLUDABLE)
        .jsonPath("$.detail")
        .isEqualTo("Row 1 moves no money and is never imported.");

    EntityExchangeResult<byte[]> export =
        client()
            .get()
            .uri(imports() + "/" + batch.id() + "/errors.csv")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(byte[].class)
            .returnResult();
    String errorCsv = new String(export.getResponseBody(), StandardCharsets.UTF_8);
    assertThat(errorCsv).contains("bad-date").doesNotContain("Waived information");

    execute("UPDATE app_user SET language = ?", "DE");
    token = login("admin@example.com");
    ImportRowResponse german = rows(batch.id(), "?status=SKIPPED").content().get(0);
    assertThat(german.error().message())
        .isEqualTo(
            "Diese Zeile hat den Betrag null und ver\u00e4ndert keinen Kontostand. Sie wird"
                + " angezeigt, aber nicht importiert.");

    ImportBatchResponse committed = commit(account, batch.id(), batch.version());
    assertThat(committed.counts().skipped()).isEqualTo(1);
    assertThat(committed.counts().imported()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM transaction WHERE amount = 0")).isZero();
  }

  @Test
  void separateDebitCreditExplicitZerosAreSkippedWithoutHidingMissingAmounts() throws Exception {
    ImportTemplateResponse template = createTemplate(germanTemplate());
    byte[] content =
        String.join(
                "\n",
                "Synthetic bank export",
                "Account: Example",
                "Period: May 2031",
                "Bookings follow",
                "Buchungstag;Wertstellung;Buchungstext;Auftraggeber / Begünstigter;Verwendungszweck;Soll;Haben;Währung",
                "02.05.2031;;Lastschrift;;Waived debit;0,00;;CHF",
                "03.05.2031;;Gutschrift;;Waived credit;;0,00;CHF",
                "04.05.2031;;Lastschrift;;Both zero;0,00;0,00;CHF",
                "05.05.2031;;Lastschrift;;Negative zero;-0,0000;;CHF",
                "06.05.2031;;Lastschrift;;Coffee;12,40;;CHF",
                "07.05.2031;;Gutschrift;;Cashback;;5,00;CHF",
                "08.05.2031;;Lastschrift;;Both blank;;;CHF",
                "Summary;;;;;;;")
            .concat("\n")
            .getBytes(StandardCharsets.UTF_8);
    ImportBatchResponse batch = upload(token, template.id(), content);

    assertThat(batch.counts().total()).isEqualTo(7);
    assertThat(batch.counts().skipped()).isEqualTo(4);
    assertThat(batch.counts().newRows()).isEqualTo(2);
    assertThat(batch.counts().errors()).isEqualTo(1);
    assertThat(batch.counts().included()).isEqualTo(2);
    List<ImportRowResponse> skipped = rows(batch.id(), "?status=SKIPPED").content();
    assertThat(skipped).extracting(ImportRowResponse::rowNumber).containsExactly(1, 2, 3, 4);
    assertThat(skipped)
        .allSatisfy(
            row -> {
              assertThat(row.included()).isFalse();
              assertThat(row.error().code()).isEqualTo("IMPORT_ROW_ZERO_AMOUNT");
              assertThat(row.canonical().amount()).isEqualByComparingTo("0");
            });
    assertThat(rows(batch.id(), "?status=ERROR").content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.rowNumber()).isEqualTo(7);
              assertThat(row.error().code()).isEqualTo("IMPORT_ROW_VALUE_MISSING");
            });

    EntityExchangeResult<byte[]> exported =
        client()
            .get()
            .uri(imports() + "/" + batch.id() + "/errors.csv")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(byte[].class)
            .returnResult();
    String errorCsv = new String(exported.getResponseBody(), StandardCharsets.UTF_8);
    assertThat(errorCsv).contains("Both blank").doesNotContain("Waived debit", "Both zero");

    ImportBatchResponse committed = commit(account, batch.id(), batch.version());
    assertThat(committed.counts().imported()).isEqualTo(2);
    assertThat(committed.counts().skipped()).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM transaction WHERE amount = 0")).isZero();
  }

  @Test
  void aFileOfOnlyZeroAmountsHasNothingToImportAndItsCommitAddsNothing() throws Exception {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Only zeros", SIMPLE_COLUMNS));
    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv("2031-05-02;0.00;CHF;Information;Z1;", "2031-05-03;-0.00;CHF;Waived fee;Z2;"));

    assertThat(batch.counts().total()).isEqualTo(2);
    assertThat(batch.counts().skipped()).isEqualTo(2);
    assertThat(batch.counts().newRows()).isZero();
    assertThat(batch.counts().errors()).isZero();
    assertThat(batch.counts().included()).isZero();

    ImportBatchResponse committed = commit(account, batch.id(), batch.version());
    assertThat(committed.counts().skipped()).isEqualTo(2);
    assertThat(committed.counts().imported()).isZero();
    assertThat(count("SELECT count(*) FROM transaction")).isZero();
  }

  /** The API refuses first (above); the database must refuse too, whoever writes the row. */
  @Test
  void theDatabaseRefusesToIncludeASkippedOrErrorRow() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Guarded", SIMPLE_COLUMNS));
    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv("2031-05-02;0.00;CHF;Information;Z1;", "bad-date;-3.00;CHF;Invalid;Z2;"));

    for (String status : List.of("SKIPPED", "ERROR")) {
      assertThatThrownBy(
              () ->
                  execute(
                      "UPDATE import_row_raw SET included = TRUE"
                          + " WHERE import_batch_id = ? AND parse_status = ?",
                      batch.id(),
                      status))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"))
          .hasMessageContaining("chk_import_row_raw_not_includable_excluded");
    }
    assertThat(
            count(
                "SELECT count(*) FROM import_row_raw WHERE import_batch_id = ? AND included",
                batch.id()))
        .isZero();
  }

  @Test
  void errorRowsAreShownExportableAndNeverImportedWhileTheRestCommits() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    LocalDate future = LocalDate.now().plusDays(30);

    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv(
                "2019-01-05;-12.40;CHF;Bakery;R1;",
                "31.02.2019;-1.00;CHF;=cmd;R2;",
                "2019-01-06;-9.00;USD;Duty free;R3;",
                future + ";-2.00;CHF;Preorder;R4;"));

    assertThat(batch.counts().errors()).isEqualTo(2);
    assertThat(batch.counts().newRows()).isEqualTo(2);
    assertThat(batch.counts().warnings()).isEqualTo(1);
    List<ImportRowResponse> errors = rows(batch.id(), "?status=ERROR").content();
    assertThat(errors)
        .extracting(row -> row.error().code())
        .containsExactly("IMPORT_ROW_DATE_UNPARSEABLE", "IMPORT_ROW_FX_RATE_UNAVAILABLE");
    // No rate is stored and the FX import is off in tests: the provider is not asked either.
    assertThat(errors.get(1).error().message())
        .isEqualTo("No exchange rate from USD to CHF is available for 2019-01-06.");
    ImportRowResponse warned = rows(batch.id(), "?status=PARSED").content().get(1);
    assertThat(warned.warnings())
        .extracting(w -> w.code(), w -> w.message())
        .containsExactly(
            tuple("IMPORT_ROW_FUTURE_DATE", "The booking date " + future + " is in the future."));
    assertThat(warned.included()).isTrue();

    client()
        .patch()
        .uri(imports() + "/" + batch.id() + "/rows/2")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRowInclusionRequest(true))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_ROW_NOT_INCLUDABLE)
        .jsonPath("$.detail")
        .isEqualTo("Row 2 could not be read and cannot be imported.");

    EntityExchangeResult<byte[]> export =
        client()
            .get()
            .uri(imports() + "/" + batch.id() + "/errors.csv")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(byte[].class)
            .returnResult();
    assertThat(export.getResponseHeaders().getContentType().toString())
        .isEqualTo("text/csv;charset=UTF-8");
    assertThat(export.getResponseHeaders().getContentDisposition().getFilename())
        .isEqualTo("import-" + batch.id() + "-errors.csv");
    String[] lines = new String(export.getResponseBody(), StandardCharsets.UTF_8).split("\r\n");
    assertThat(lines[0]).isEqualTo(SIMPLE_HEADER + ";error_code;error_message");
    assertThat(lines[1]).startsWith("31.02.2019;-1.00;CHF;'=cmd;R2;;IMPORT_ROW_DATE_UNPARSEABLE;");
    assertThat(lines[2])
        .startsWith("2019-01-06;-9.00;USD;Duty free;R3;;IMPORT_ROW_FX_RATE_UNAVAILABLE;");
    assertThat(lines).hasSize(3);

    ImportBatchResponse committed = commit(account, batch.id(), batch.version());
    assertThat(committed.counts().imported()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(2);
  }

  @Test
  void theErrorExportKeepsTheSourceFilesDelimiterAndEncoding() throws Exception {
    ImportTemplateResponse swiss = createTemplate(swissTemplate());
    byte[] file =
        ("Buchungsdatum;Valuta;Avisierungstext;Betrag;W\u00e4hrung;Saldo\r\n"
                + "02.01.2019;02.01.2019;B\u00e4ckerei;zw\u00f6lf;CHF;1.00\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    ImportBatchResponse batch = upload(token, swiss.id(), file);

    EntityExchangeResult<byte[]> export =
        client()
            .get()
            .uri(imports() + "/" + batch.id() + "/errors.csv")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(byte[].class)
            .returnResult();

    assertThat(export.getResponseHeaders().getContentType().toString())
        .isEqualTo("text/csv;charset=ISO-8859-1");
    String csv = new String(export.getResponseBody(), StandardCharsets.ISO_8859_1);
    assertThat(csv)
        .startsWith("Buchungsdatum;Valuta;Avisierungstext;Betrag;W\u00e4hrung;Saldo;error_code;")
        .contains(
            "02.01.2019;02.01.2019;B\u00e4ckerei;zw\u00f6lf;CHF;1.00;IMPORT_ROW_AMOUNT_UNPARSEABLE;");
  }

  // --- commit
  // ---------------------------------------------------------------------------------------

  @Test
  void aCommittedRowIsTaggedCategorizedLikeAManualOneAndJoinsTransferDetection() throws Exception {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    UUID savings = createAccount("Sparkonto", "CHF");
    TransactionResponse incoming = recordManual(savings, "DEPOSIT", "500.00", "CHF", "Transfer in");
    // The same MCC entered by hand: whatever category that gives, the import must give too.
    TransactionResponse manualLunch =
        recordManual(account, "EXPENSE", "-18.00", "CHF", "Lunch", "5812");

    ImportBatchResponse batch =
        upload(
            token,
            simple.id(),
            csv("2019-01-05;-500.00;CHF;To savings;T1;", "2019-01-06;-21.00;CHF;Dinner;D1;5812"));
    ImportBatchResponse committed = commit(account, batch.id(), batch.version());

    assertThat(committed.counts().imported()).isEqualTo(2);
    Map<String, Object> dinner =
        rowsOf("SELECT * FROM transaction WHERE external_id = 'D1'").get(0);
    assertThat(dinner.get("source")).isEqualTo("CSV");
    assertThat(dinner.get("import_batch_id")).isEqualTo(batch.id());
    assertThat(dinner.get("merchant_description")).isEqualTo("Dinner");
    assertThat(dinner.get("raw_source_data").toString())
        .contains("\"mcc\": \"5812\"", "\"cells\"", "\"Text\": \"Dinner\"");
    assertThat(dinner.get("category_id"))
        .isNotNull()
        .isEqualTo(single("SELECT category_id FROM transaction WHERE id = ?", manualLunch.id()));
    // Each committed row is linked to the transaction it became.
    assertThat(
            count(
                "SELECT count(*) FROM import_row_raw r JOIN transaction t"
                    + " ON t.id = r.resulting_transaction_id WHERE r.import_batch_id = ?",
                batch.id()))
        .isEqualTo(2);

    // The imported debit and the savings account's credit are proposed as one transfer.
    UUID outgoing = (UUID) single("SELECT id FROM transaction WHERE external_id = 'T1'");
    assertThat(
            count(
                "SELECT count(*) FROM settlement_match WHERE match_kind = 'TRANSFER'"
                    + " AND payment_transaction_id = ? AND card_transaction_id = ?",
                outgoing,
                incoming.id()))
        .isEqualTo(1);
  }

  @Test
  void aCommitNeedsTheCurrentVersionAndARetryIsAConflictNotASecondImport() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    ImportBatchResponse batch = upload(token, simple.id(), csv("2019-01-05;-12.40;CHF;Bakery;R1;"));

    commitStatus(account, batch.id(), null).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    commitStatus(account, batch.id(), "\"" + (batch.version() + 1) + "\"")
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    ImportBatchResponse committed = commit(account, batch.id(), batch.version());
    client()
        .post()
        .uri(imports() + "/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + committed.version() + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_BATCH_STATE)
        .jsonPath("$.status")
        .isEqualTo("COMMITTED");

    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(1);
  }

  @Test
  void twoBatchesOfTheSameFileCommittedAtOnceInsertItsRowsOnce() throws Exception {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    // One row with a reference, two without: the unique index backs only the first.
    byte[] file =
        csv(
            "2019-01-05;-12.40;CHF;Bakery;R1;",
            "2019-01-06;-3.50;CHF;Coffee;;",
            "2019-01-07;-8.00;CHF;Kiosk;;");
    ImportBatchResponse first = upload(token, simple.id(), file);
    ImportBatchResponse second = upload(token, simple.id(), file);
    assertThat(second.counts().newRows()).isEqualTo(3);

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<ImportBatchResponse>> commits = new ArrayList<>();
      for (ImportBatchResponse batch : List.of(first, second)) {
        Callable<ImportBatchResponse> commit =
            () -> {
              start.await();
              return commit(account, batch.id(), batch.version());
            };
        commits.add(pool.submit(commit));
      }
      start.countDown();
      List<Integer> imported = new ArrayList<>();
      for (Future<ImportBatchResponse> commit : commits) {
        imported.add(commit.get(60, TimeUnit.SECONDS).counts().imported());
      }
      assertThat(imported).containsExactlyInAnyOrder(3, 0);
    } finally {
      pool.shutdownNow();
    }

    assertThat(count("SELECT count(*) FROM transaction")).isEqualTo(3);
    // The loser's rows say why they were not imported.
    assertThat(
            count(
                "SELECT count(*) FROM import_row_raw WHERE parse_status = 'DUPLICATE'"
                    + " AND NOT included AND duplicate_of_transaction_id IS NOT NULL"))
        .isEqualTo(3);
  }

  @Test
  void committingTheMissingBookingResolvesTheReconciliationThatPointedAtItsImport() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OpeningBalanceRequest(DAY.minusDays(5), new BigDecimal("1000.00"), "CHF", null))
        .exchange()
        .expectStatus()
        .isCreated();
    byte[] file = csv("2019-01-05;-50.00;CHF;Electricity;E1;");
    // An import that held the booking, but was discarded.
    ImportBatchResponse discarded = upload(token, simple.id(), file);
    discard(discarded.id(), discarded.version());
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new RecordAccountSnapshotRequest(DAY.plusDays(5), new BigDecimal("950.00"), List.of()))
        .exchange()
        .expectStatus()
        .isCreated();
    assertThat(rowsOf("SELECT status, probable_cause FROM reconciliation_result"))
        .containsExactly(Map.of("status", "OPEN", "probable_cause", "MISSING_TRANSACTION"));

    ImportBatchResponse batch = upload(token, simple.id(), file);
    commit(account, batch.id(), batch.version());

    assertThat(single("SELECT status FROM reconciliation_result")).isEqualTo("RESOLVED");
  }

  @Test
  void anImportWhoseBookingReachedTheLedgerAnywayIsNoEvidenceOfAMissingTransaction() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OpeningBalanceRequest(DAY.minusDays(5), new BigDecimal("1000.00"), "CHF", null))
        .exchange()
        .expectStatus()
        .isCreated();
    byte[] file = csv("2019-01-05;-50.00;CHF;Electricity;E1;");
    // The file was discarded once, then uploaded again and committed: its booking is in the ledger.
    ImportBatchResponse discarded = upload(token, simple.id(), file);
    discard(discarded.id(), discarded.version());
    ImportBatchResponse batch = upload(token, simple.id(), file);
    commit(account, batch.id(), batch.version());

    // The bank says 900: another 50.00 is missing, but not the one the discarded import held.
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new RecordAccountSnapshotRequest(DAY.plusDays(5), new BigDecimal("900.00"), List.of()))
        .exchange()
        .expectStatus()
        .isCreated();

    assertThat(single("SELECT status FROM reconciliation_result")).isEqualTo("OPEN");
    assertThat(single("SELECT probable_cause FROM reconciliation_result"))
        .isNotEqualTo("MISSING_TRANSACTION");
  }

  // --- discard, limits, authorization
  // ---------------------------------------------------------------

  @Test
  void aDiscardedBatchLosesItsFileAndCanNeitherBeParsedNorCommitted() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    ImportBatchResponse batch = upload(token, simple.id(), csv("2019-01-05;-12.40;CHF;Bakery;R1;"));

    ImportBatchResponse discarded = discard(batch.id(), batch.version());

    assertThat(discarded.status()).isEqualTo("DISCARDED");
    assertThat(count("SELECT count(*) FROM import_file")).isZero();
    // Its rows stay: a reconciliation can still point at what it held.
    assertThat(count("SELECT count(*) FROM import_row_raw")).isEqualTo(1);
    client()
        .post()
        .uri(imports() + "/" + batch.id() + "/parse")
        .header(HttpHeaders.IF_MATCH, "\"" + discarded.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportParseRequest(simple.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_BATCH_STATE);
    commitStatus(account, batch.id(), "\"" + discarded.version() + "\"")
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(count("SELECT count(*) FROM transaction")).isZero();
  }

  @Test
  void aFileOverFiveMegabytesIsA413AndOneOverTwentyThousandRowsA422() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
    Arrays.fill(tooLarge, (byte) 'x');
    upload(token, account, tooLarge, simple.id())
        .expectStatus()
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_FILE_TOO_LARGE);

    String[] rows = new String[20_001];
    for (int i = 0; i < rows.length; i++) {
      rows[i] = "2019-01-05;-1.00;CHF;Row " + i + ";;";
    }
    upload(token, account, csv(rows), simple.id())
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS);
    assertThat(count("SELECT count(*) FROM import_batch")).isZero();
  }

  @Test
  void anAccountTheCallerCannotWriteToIsTheAuditedNotFound() throws Exception {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    ImportBatchResponse batch = upload(token, simple.id(), csv("2019-01-05;-12.40;CHF;Bakery;R1;"));
    UUID memberId = createSecondMember("member@example.com");
    String memberToken = login("member@example.com");
    grantOnAccount(memberId, account, AccessLevelValues.BALANCE_ONLY);

    upload(memberToken, account, csv("2019-01-05;-1.00;CHF;x;;"), simple.id())
        .expectStatus()
        .isNotFound();
    client(memberToken)
        .get()
        .uri(imports() + "/" + batch.id())
        .exchange()
        .expectStatus()
        .isNotFound();
    // READ is not enough either: the rows are raw bank data, and an import is a write.
    UUID readerId = createSecondMember("reader@example.com");
    String readerToken = login("reader@example.com");
    grantOnAccount(readerId, account, AccessLevelValues.READ);
    client(readerToken)
        .get()
        .uri(imports() + "/" + batch.id() + "/rows")
        .exchange()
        .expectStatus()
        .isNotFound();
    upload(readerToken, account, csv("2019-01-05;-1.00;CHF;x;;"), simple.id())
        .expectStatus()
        .isNotFound();
    // A batch id under another account of the caller's own, and an unknown account.
    UUID other = createAccount("Other", "CHF");
    client()
        .get()
        .uri("/api/v1/accounts/" + other + "/imports/" + batch.id())
        .exchange()
        .expectStatus()
        .isNotFound();
    client()
        .get()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/imports")
        .exchange()
        .expectStatus()
        .isNotFound();

    awaitDenials("Account", account, 4);
    awaitDenials("ImportBatch", batch.id(), 1);
  }

  @Test
  void anArchivedAccountTakesNoImport() throws Exception {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    execute("UPDATE account SET status = 'ARCHIVED', archived_at = now() WHERE id = ?", account);

    upload(token, account, csv("2019-01-05;-12.40;CHF;Bakery;R1;"), simple.id())
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.ACCOUNT_ARCHIVED);
  }

  @Test
  void theAccountsBatchesAreListedNewestFirst() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    ImportBatchResponse older = upload(token, simple.id(), csv("2019-01-05;-1.00;CHF;a;;"));
    commit(account, older.id(), older.version());
    ImportBatchResponse newer = upload(token, simple.id(), csv("2019-01-05;-2.00;CHF;b;;"));
    ImportBatchResponse again = upload(token, simple.id(), csv("2019-01-05;-1.00;CHF;a;;"));

    PageOf<ImportBatchResponse> page =
        client()
            .get()
            .uri(imports() + "?size=10")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<PageOf<ImportBatchResponse>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(page.content())
        .extracting(ImportBatchResponse::id)
        .containsExactly(again.id(), newer.id(), older.id());
    // Each batch's own counts and same-file warning, though the page reads them in one query each.
    assertThat(page.content().get(0).counts().duplicates()).isEqualTo(1);
    assertThat(page.content().get(0).sameFileImportedIn().batchId()).isEqualTo(older.id());
    assertThat(page.content().get(1).counts().newRows()).isEqualTo(1);
    assertThat(page.content().get(1).sameFileImportedIn()).isNull();
    assertThat(page.content().get(2).counts().imported()).isEqualTo(1);
    assertThat(page.content().get(2).sameFileImportedIn()).isNull();
  }

  @Test
  void anAccountHoldsABoundedNumberOfOpenBatches() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    List<ImportBatchResponse> open = new ArrayList<>();
    for (int i = 1; i <= MAX_OPEN_BATCHES; i++) {
      open.add(upload(token, simple.id(), csv("2019-01-05;-" + i + ".00;CHF;Row;;")));
    }

    upload(token, account, csv("2019-01-05;-9.00;CHF;Row;;"), simple.id())
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_TOO_MANY_OPEN_BATCHES)
        .jsonPath("$.maxOpenBatches")
        .isEqualTo(MAX_OPEN_BATCHES);
    assertThat(count("SELECT count(*) FROM import_batch")).isEqualTo(MAX_OPEN_BATCHES);

    // A committed and a discarded batch make room again; another account has room of its own.
    commit(account, open.get(0).id(), open.get(0).version());
    discard(open.get(1).id(), open.get(1).version());
    upload(token, simple.id(), csv("2019-01-05;-9.00;CHF;Row;;"));
    upload(token, simple.id(), csv("2019-01-05;-10.00;CHF;Row;;"));
    UUID other = createAccount("Other", "CHF");
    upload(token, other, csv("2019-01-05;-11.00;CHF;Row;;"), simple.id())
        .expectStatus()
        .isCreated();
  }

  @Test
  void anUnknownRowStatusIsA400AndAnUnknownRowA404() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    ImportBatchResponse batch = upload(token, simple.id(), csv("2019-01-05;-12.40;CHF;Bakery;R1;"));

    client()
        .get()
        .uri(imports() + "/" + batch.id() + "/rows?status=NEW")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    client()
        .patch()
        .uri(imports() + "/" + batch.id() + "/rows/99")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRowInclusionRequest(false))
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  // --- performance -----------------------------------------------------------------------------

  @Test
  void fiveThousandRowsArePreviewedAndCommittedWithinTheStoryTargets() {
    ImportTemplateResponse simple = createTemplate(simpleTemplate("Simple", SIMPLE_COLUMNS));
    // A year of a busy account: 250 merchant texts that recur, as on real statements, each row
    // with its own reference. (5,000 mutually similar texts are the fuzzy match's worst case; see
    // the PR.)
    List<String> brands =
        List.of(
            "Migros",
            "Coop",
            "Denner",
            "Aldi",
            "Lidl",
            "Spar",
            "Volg",
            "Manor",
            "Globus",
            "Interdiscount",
            "Galaxus",
            "Digitec",
            "Ikea",
            "Jumbo",
            "Hornbach",
            "Ochsner",
            "Zalando",
            "Starbucks",
            "Avec",
            "Kiosk",
            "Apotheke",
            "Swisscom",
            "Salt",
            "Sunrise",
            "Helsana");
    List<String> places =
        List.of(
            "Zuerich",
            "Bern",
            "Basel",
            "Luzern",
            "Winterthur",
            "Thun",
            "Aarau",
            "Olten",
            "Zug",
            "Chur");
    String[] rows = new String[5_000];
    for (int i = 0; i < rows.length; i++) {
      String merchant = brands.get(i % brands.size()) + " " + places.get(i / 25 % places.size());
      rows[i] =
          LocalDate.of(2018, 1, 1).plusDays(i % 365)
              + ";-"
              + (i % 97 + 1)
              + ".25;CHF;"
              + merchant
              + ";P"
              + i
              + ";";
    }
    byte[] file = csv(rows);

    Instant started = Instant.now();
    ImportBatchResponse batch = upload(token, simple.id(), file);
    Duration preview = Duration.between(started, Instant.now());
    started = Instant.now();
    ImportBatchResponse committed = commit(account, batch.id(), batch.version());
    Duration commit = Duration.between(started, Instant.now());

    assertThat(committed.counts().imported()).isEqualTo(5_000);
    LOG.info(
        "5,000 import rows: preview {} ms, commit {} ms", preview.toMillis(), commit.toMillis());
    // US-07-04's targets on a developer laptop: parse and preview < 5 s, commit < 30 s (logged
    // above; measured 0.2 s and 8.6 s). Asserted with room for a slower CI runner: the check is
    // for a regression in kind - the first commit took 11 minutes - not for the runner's speed.
    assertThat(preview).isLessThan(Duration.ofSeconds(5).multipliedBy(3));
    assertThat(commit).isLessThan(Duration.ofSeconds(30).multipliedBy(3));
  }

  // --- requests --------------------------------------------------------------------------------

  private ImportBatchResponse upload(byte[] content) {
    return upload(token, account, content, null)
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportBatchResponse upload(String bearer, UUID templateId, byte[] content) {
    return upload(bearer, account, content, templateId)
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec upload(
      String bearer, UUID accountId, byte[] content, UUID templateId) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    if (templateId != null) {
      body.add("templateId", templateId.toString());
    }
    return client(bearer)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/imports")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange();
  }

  private ImportTemplateTestResponse templatePreview(
      UUID templateId, UUID accountId, byte[] content) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    return client()
        .post()
        .uri(
            "/api/v1/import-templates/"
                + templateId
                + "/test"
                + (accountId == null ? "" : "?accountId=" + accountId))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportTemplateTestResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportBatchResponse parse(UUID batchId, int version, UUID templateId) {
    return client()
        .post()
        .uri(imports() + "/" + batchId + "/parse")
        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportParseRequest(templateId))
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

  private PageOf<ImportRowResponse> rows(UUID batchId, String query) {
    return client()
        .get()
        .uri(imports() + "/" + batchId + "/rows" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<ImportRowResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private ImportBatchResponse include(UUID batchId, int version, int rowNumber, boolean included) {
    EntityExchangeResult<ImportBatchResponse> result =
        client()
            .patch()
            .uri(imports() + "/" + batchId + "/rows/" + rowNumber)
            .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ImportRowInclusionRequest(included))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportBatchResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(result, dataSource, "import_batch", batchId);
  }

  private ImportBatchResponse commit(UUID accountId, UUID batchId, int version) {
    EntityExchangeResult<ImportBatchResponse> result =
        client()
            .post()
            .uri("/api/v1/accounts/" + accountId + "/imports/" + batchId + "/commit")
            .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportBatchResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(result, dataSource, "import_batch", batchId);
  }

  private StatusAssertions commitStatus(UUID accountId, UUID batchId, String ifMatch) {
    RestTestClient.RequestBodySpec request =
        client().post().uri("/api/v1/accounts/" + accountId + "/imports/" + batchId + "/commit");
    if (ifMatch != null) {
      request = request.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    return request.exchange().expectStatus();
  }

  private ImportBatchResponse discard(UUID batchId, int version) {
    return client()
        .post()
        .uri(imports() + "/" + batchId + "/discard")
        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionResponse recordManual(
      UUID accountId, String type, String amount, String currency, String description) {
    return recordManual(accountId, type, amount, currency, description, null);
  }

  private TransactionResponse recordManual(
      UUID accountId, String type, String amount, String currency, String description, String mcc) {
    CreateTransactionRequest request =
        TransactionRequests.cash(
            type,
            DAY,
            new BigDecimal(amount),
            currency,
            description,
            mcc,
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

  private ImportTemplateResponse createTemplate(ImportTemplateRequest request) {
    return client()
        .post()
        .uri("/api/v1/import-templates")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createAccount(String name, String currency) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, "CASH", currency).build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private UUID createSecondMember(String email) throws SQLException {
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

  // Only the two members of Spring Data's page JSON these tests read; the rest is ignored.
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PageOf<T>(List<T> content, long totalElements) {}

  // --- templates and files -----------------------------------------------------------------------

  private static byte[] csv(String... rows) {
    return (SIMPLE_HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        ImportBatchControllerTest.class.getResourceAsStream("/import/" + name)) {
      return stream.readAllBytes();
    }
  }

  private static ImportTemplateRequest creditCardTemplate() {
    List<String> headers =
        List.of(
            "Date",
            "Amount",
            "Currency",
            "Text",
            "Reference",
            "OriginalAmount",
            "OriginalCurrency",
            "Rate");
    return new ImportTemplateRequest(
        "Synthetic credit-card statement",
        null,
        "CASH_TRANSACTIONS",
        ";",
        "UTF-8",
        ",",
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
            null,
            "OriginalAmount",
            "OriginalCurrency",
            "Rate"),
        Map.of(),
        null,
        headers,
        null,
        null,
        Map.of("REFUND", "refund"));
  }

  private static ImportTemplateRequest simpleTemplate(String name, List<String> headerColumns) {
    return new ImportTemplateRequest(
        name,
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
            "MCC",
            null,
            null),
        Map.of(),
        null,
        headerColumns,
        null,
        null);
  }

  private static ImportTemplateRequest withDateFormat(ImportTemplateRequest r, String dateFormat) {
    return new ImportTemplateRequest(
        r.name(),
        r.institutionCatalogueId(),
        r.templateClass(),
        r.delimiter(),
        r.encoding(),
        r.decimalSeparator(),
        r.thousandsSeparator(),
        dateFormat,
        r.headerRowIndex(),
        r.preambleRowCount(),
        r.trailingSummaryRowCount(),
        r.amountRepresentation(),
        r.currencyMode(),
        r.fixedCurrency(),
        r.columnMapping(),
        r.typeMapping(),
        r.accountIdentificationStrategy(),
        r.headerColumns(),
        r.fileFormat(),
        r.pdfLayout());
  }

  private static ImportTemplateRequest swissTemplate() {
    return new ImportTemplateRequest(
        "Swiss bank",
        null,
        null,
        ";",
        "iso-8859-1",
        ".",
        "'",
        "dd.MM.yyyy",
        0,
        0,
        0,
        "SINGLE_SIGNED_COLUMN",
        "PER_ROW",
        null,
        new ImportColumnMapping(
            "Buchungsdatum",
            "Valuta",
            "Betrag",
            null,
            null,
            "W\u00e4hrung",
            "Avisierungstext",
            null,
            null,
            null,
            null,
            null,
            null),
        Map.of(),
        null,
        List.of("Buchungsdatum", "Valuta", "Avisierungstext", "Betrag", "W\u00e4hrung", "Saldo"),
        null,
        null);
  }

  private static ImportTemplateRequest germanTemplate() {
    return new ImportTemplateRequest(
        "German bank",
        null,
        "CASH_TRANSACTIONS",
        ";",
        "UTF-8",
        ",",
        ".",
        "dd.MM.yyyy",
        0,
        4,
        1,
        "SEPARATE_DEBIT_CREDIT",
        "PER_ROW",
        null,
        new ImportColumnMapping(
            "Buchungstag",
            "Wertstellung",
            null,
            "Soll",
            "Haben",
            "W\u00e4hrung",
            "Verwendungszweck",
            "Auftraggeber / Beg\u00fcnstigter",
            null,
            "Buchungstext",
            null,
            null,
            null),
        Map.of(
            "Lastschrift",
            "EXPENSE",
            "Gutschrift",
            "INCOME",
            "Entgelt",
            "FEE",
            "Zinsen",
            "INTEREST"),
        "USER_SELECTED",
        List.of(
            "Buchungstag",
            "Wertstellung",
            "Buchungstext",
            "Auftraggeber / Beg\u00fcnstigter",
            "Verwendungszweck",
            "Soll",
            "Haben",
            "W\u00e4hrung"),
        null,
        null);
  }

  // --- database helpers ------------------------------------------------------------------------

  private void awaitDenials(String entityType, UUID entityId, int expected) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    long found;
    do {
      found =
          count(
              "SELECT count(*) FROM authorization_denial_log WHERE requested_entity_type = ?"
                  + " AND requested_entity_id = ?",
              entityType,
              entityId);
      if (found >= expected) {
        break;
      }
      Thread.sleep(100);
    } while (Instant.now().isBefore(deadline));
    assertThat(found).isEqualTo(expected);
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
