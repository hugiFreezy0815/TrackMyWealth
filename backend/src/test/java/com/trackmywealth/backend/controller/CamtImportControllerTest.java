package com.trackmywealth.backend.controller;

import static com.trackmywealth.backend.testsupport.CamtFixtures.IBAN;
import static com.trackmywealth.backend.testsupport.CamtFixtures.OTHER_IBAN;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt052;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt053;
import static com.trackmywealth.backend.testsupport.CamtFixtures.detail;
import static com.trackmywealth.backend.testsupport.CamtFixtures.entry;
import static com.trackmywealth.backend.testsupport.CamtFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.ReconciliationStatusResponse;
import com.trackmywealth.backend.dto.ReconciliationStatusValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
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
import org.springframework.http.HttpStatus;
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
 * #350 against a real PostgreSQL: an ISO 20022 CAMT statement imported without a template through
 * the existing upload, preview, account identification, duplicate check, commit and statement
 * balance. Every statement is synthetic ({@code CamtFixtures}); no real bank file is used.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CamtImportControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final UUID DKB = UUID.fromString("752d1bc3-1951-408f-9d08-fbe4a1f1d9c5");

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

  @BeforeEach
  void cleanDatabaseAndSetUp() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM settlement_match",
              "DELETE FROM transfer_detection_fx_pending",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction_category_split",
              "DELETE FROM import_row_raw",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM reconciliation_result",
              "DELETE FROM account_snapshot",
              "DELETE FROM import_file",
              "DELETE FROM import_batch",
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
    account = create(AccountRequests.account("Girokonto", "CASH", "EUR").build());
  }

  // The statement names the account by its IBAN, its booked entries become rows - a collective
  // entry one per detail - and its closing balance the snapshot the ledger is reconciled against.
  @Test
  void aStatementImportsItsBookedEntriesAndReconcilesItsClosingBalance() throws Exception {
    registerIban(account, IBAN);
    openingBalance(LocalDate.of(2026, 8, 31), "0.00");
    byte[] statement = september();

    ImportBatchResponse batch = upload(account, statement, "auszug.xml", "application/xml");

    assertThat(batch.status()).isEqualTo(ImportBatchValues.PARSED);
    assertThat(batch.sourceKind()).isEqualTo(ImportBatchValues.SOURCE_DOCUMENT);
    assertThat(batch.templateId()).isNull();
    assertThat(batch.counts().total()).isEqualTo(5);
    assertThat(batch.counts().newRows()).isEqualTo(4);
    assertThat(batch.counts().skipped()).isOne();
    assertThat(batch.counts().errors()).isZero();
    assertThat(batch.accountIdentification().outcome())
        .isEqualTo(ImportBatchValues.IDENTIFIED_MATCHED);
    assertThat(batch.accountIdentification().confirmationRequired()).isFalse();
    assertThat(batch.statementBalance().date()).isEqualTo(LocalDate.of(2026, 9, 30));
    assertThat(batch.statementBalance().balance()).isEqualByComparingTo("2384.90");

    commit(account, batch);

    assertThat(sortedTransactions(account))
        .extracting(
            TransactionResponse::bookingDate,
            TransactionResponse::transactionType,
            row -> row.amount().toPlainString())
        .containsExactly(
            tuple(LocalDate.of(2026, 9, 1), "INCOME", "2500.0000"),
            tuple(LocalDate.of(2026, 9, 2), "EXPENSE", "-45.1000"),
            tuple(LocalDate.of(2026, 9, 3), "EXPENSE", "-50.0000"),
            tuple(LocalDate.of(2026, 9, 3), "EXPENSE", "-20.0000"));
    ReconciliationStatusResponse reconciliation = summary(account).reconciliation();
    assertThat(reconciliation.status()).isEqualTo(ReconciliationStatusValues.RECONCILED);
    assertThat(reconciliation.asOf()).isEqualTo(LocalDate.of(2026, 9, 30));
    assertThat(documentSnapshots(account))
        .containsExactly(Map.entry(LocalDate.of(2026, 9, 30), "2384.9000"));

    // The same file again: every booking is in the ledger, by its reference or date and amount.
    ImportBatchResponse again = upload(account, statement, "auszug.xml", "application/xml");
    assertThat(again.counts().duplicates()).isEqualTo(4);
    assertThat(again.counts().newRows()).isZero();
    assertThat(again.sameFileImportedIn()).isNotNull();
  }

  // A ZIP archive of two intraday reports: both are read; an intraday balance is no statement
  // balance, so none is recorded and no DOCUMENT snapshot written (product owner, 2026-10-18).
  @Test
  void aZipArchiveOfTwoReportsImportsBothAndStatesNoBalance() {
    registerIban(account, IBAN);
    byte[] archive =
        zip(
            camt052("001.08")
                .statement(
                    IBAN,
                    s ->
                        s.balance("CLBD", "999.00", "CRDT", "2026-09-01")
                            .entry(entry("12.00", "DBIT", "2026-09-01").reference("R-1")))
                .bytes(),
            camt052("001.04")
                .statement(
                    IBAN, s -> s.entry(entry("30.00", "CRDT", "2026-09-02").reference("R-2")))
                .bytes());

    ImportBatchResponse batch = upload(account, archive, "umsaetze.zip", "application/zip");

    assertThat(batch.counts().newRows()).isEqualTo(2);
    assertThat(batch.statementBalance() == null || batch.statementBalance().outcome() == null)
        .isTrue();
    commit(account, batch);
    assertThat(sortedTransactions(account))
        .extracting(row -> row.amount().toPlainString())
        .containsExactly("-12.0000", "30.0000");
    assertThat(documentSnapshots(account)).isEmpty();
  }

  // Sparkasse (.001.02): every entry of a daily report carries the report's one AcctSvcrRef. It
  // names no booking: two equal coffees on one day are two bookings, and a reimport finds both.
  @Test
  void aReferenceSharedByAReportsEntriesNamesNoBookingAndEqualBookingsAreAllImported() {
    registerIban(account, IBAN);
    byte[] report =
        camt052("001.02")
            .statement(
                IBAN,
                s ->
                    s.entry(entry("2.50", "DBIT", "2026-09-04").reference("2026090400001"))
                        .entry(entry("2.50", "DBIT", "2026-09-04").reference("2026090400001"))
                        .entry(entry("19.99", "DBIT", "2026-09-04").reference("2026090400001"))
                        .entry(
                            entry("4.00", "DBIT", "2026-09-04")
                                .status("PDNG")
                                .reference("2026090400001")))
            .bytes();

    ImportBatchResponse batch = upload(account, report, "umsaetze.xml", "application/xml");

    assertThat(batch.counts().errors()).isZero();
    assertThat(batch.counts().newRows()).isEqualTo(3);
    assertThat(batch.counts().skipped()).isOne();
    commit(account, batch);
    assertThat(sortedTransactions(account))
        .extracting(row -> row.amount().toPlainString())
        .containsExactly("-19.9900", "-2.5000", "-2.5000");

    ImportBatchResponse again = upload(account, report, "umsaetze.xml", "application/xml");
    assertThat(again.counts().duplicates()).isEqualTo(3);
    assertThat(again.counts().newRows()).isZero();
  }

  // The CSV export first, then the CAMT statement of the same bookings: only the new one is new.
  @Test
  void aCamtStatementAfterTheCsvExportOfTheSameBookingsAddsOnlyItsNewOnes() throws IOException {
    commit(
        account,
        confirmAccount(account, upload(account, fixture("dkb-girokonto-synthetic.csv"), DKB)));
    assertThat(sortedTransactions(account)).hasSize(2);

    ImportBatchResponse camt =
        confirmAccount(
            account,
            upload(
                account,
                camt053("001.08")
                    .statement(
                        IBAN,
                        s ->
                            s.entry(entry("17.90", "DBIT", "2031-01-28").reference("DKB-1"))
                                .entry(entry("3041.00", "CRDT", "2031-01-29").reference("DKB-2"))
                                .entry(entry("8.15", "DBIT", "2031-01-30").reference("DKB-3")))
                    .bytes(),
                "auszug.xml",
                "text/xml"));

    assertThat(camt.counts().duplicates()).isEqualTo(2);
    assertThat(camt.counts().newRows()).isOne();
    commit(account, camt);
    assertThat(sortedTransactions(account)).hasSize(3);
  }

  // The file names another account the member can import into: never posted without asking.
  @Test
  void aStatementOfAnotherAccountNeedsTheMembersConfirmation() {
    registerIban(account, IBAN);
    UUID other = create(AccountRequests.account("Sparkonto", "SAVINGS", "EUR").build());
    registerIban(other, OTHER_IBAN);

    ImportBatchResponse batch =
        upload(
            account,
            camt053("001.08")
                .statement(OTHER_IBAN, s -> s.entry(entry("5.00", "CRDT", "2026-09-01")))
                .bytes(),
            "auszug.xml",
            "application/xml");

    assertThat(batch.accountIdentification().outcome())
        .isEqualTo(ImportBatchValues.IDENTIFIED_OTHER_ACCOUNT);
    assertThat(batch.accountIdentification().proposedAccountId()).isEqualTo(other);
    assertThat(batch.accountIdentification().confirmationRequired()).isTrue();
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.IMPORT_ACCOUNT_UNCONFIRMED);
    assertThat(sortedTransactions(account)).isEmpty();
  }

  // A CAMT batch parses again without a template; any other file still needs one.
  @Test
  void aCamtBatchParsesAgainWithoutATemplateAndACsvBatchDoesNot() throws IOException {
    registerIban(account, IBAN);
    ImportBatchResponse camt = upload(account, september(), "auszug.xml", "application/xml");

    ImportBatchResponse parsed =
        client()
            .post()
            .uri("/api/v1/accounts/" + account + "/imports/" + camt.id() + "/parse")
            .header(HttpHeaders.IF_MATCH, "\"" + camt.version() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportBatchResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(parsed.status()).isEqualTo(ImportBatchValues.PARSED);
    assertThat(parsed.templateId()).isNull();
    assertThat(parsed.counts().newRows()).isEqualTo(4);

    ImportBatchResponse csv = upload(account, fixture("dkb-girokonto-synthetic.csv"), DKB);
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/imports/" + csv.id() + "/parse")
        .header(HttpHeaders.IF_MATCH, "\"" + csv.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  // Hostile, unsupported, oversized and ambiguous files are refused, and nothing of them stored.
  @Test
  void refusesFilesItCannotSafelyImportAndStoresNothingOfThem() throws Exception {
    byte[] bomb = new byte[6 * 1024 * 1024];
    Arrays.fill(bomb, (byte) ' ');
    bomb[0] = '<';
    Map<byte[], String> refused =
        Map.of(
            ("<?xml version=\"1.0\"?><!DOCTYPE Document [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                    + "<Document>&xxe;</Document>")
                .getBytes(StandardCharsets.UTF_8),
            ApiErrorCode.IMPORT_FILE_MALFORMED,
            "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.054.001.08\"/>"
                .getBytes(StandardCharsets.UTF_8),
            ApiErrorCode.IMPORT_CAMT_UNSUPPORTED,
            zip(bomb),
            ApiErrorCode.IMPORT_CAMT_LIMIT_EXCEEDED,
            camt053("001.08")
                .statement(IBAN, s -> s.entry(entry("1.00", "CRDT", "2026-09-01")))
                .statement(OTHER_IBAN, s -> s.entry(entry("1.00", "CRDT", "2026-09-01")))
                .bytes(),
            ApiErrorCode.IMPORT_CAMT_MULTIPLE_ACCOUNTS);

    refused.forEach(
        (file, code) ->
            client()
                .post()
                .uri("/api/v1/accounts/" + account + "/imports")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(file, "auszug.xml", "application/xml", null))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo(code)
                .jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).doesNotContain("passwd")));
    assertThat(count("SELECT count(*) FROM import_batch")).isZero();
    assertThat(count("SELECT count(*) FROM import_file")).isZero();
  }

  // #341 rule 3: a foreign card purchase is the euro booking already in the ledger.
  @Test
  void aForeignCardPurchaseIsTheDuplicateOfItsBookingInTheCardsCurrency() {
    UUID card = create(AccountRequests.account("Kreditkarte", "CREDIT_CARD", "EUR").build());
    client()
        .post()
        .uri("/api/v1/accounts/" + card + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                "CREDIT_CARD_PURCHASE",
                LocalDate.of(2026, 9, 5),
                new BigDecimal("-1.49"),
                "EUR",
                "Muster Online Shop",
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isCreated();

    ImportBatchResponse batch =
        upload(
            card,
            camt053("001.08")
                .statement(
                    IBAN,
                    s ->
                        s.entry(
                            entry("1.49", "DBIT", "2026-09-05")
                                .detail(
                                    detail("1.49", "DBIT")
                                        .creditor("Muster Online Shop")
                                        .instructed("37.00", "CZK", "0.0403"))))
                .bytes(),
            "karte.xml",
            "application/xml");

    assertThat(batch.counts().duplicates()).isOne();
    assertThat(batch.counts().newRows()).isZero();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private byte[] september() {
    return camt053("001.08")
        .statement(
            IBAN,
            s ->
                s.balance("OPBD", "0.00", "CRDT", "2026-08-31")
                    .balance("CLBD", "2384.90", "CRDT", "2026-09-30")
                    .entry(entry("2500.00", "CRDT", "2026-09-01").reference("SEP-1"))
                    .entry(entry("45.10", "DBIT", "2026-09-02").reference("SEP-2"))
                    .entry(entry("10.00", "DBIT", "2026-09-02").status("PDNG"))
                    .entry(
                        entry("70.00", "DBIT", "2026-09-03")
                            .reference("SEP-3")
                            .detail(detail("50.00", "DBIT").creditor("Vermieter AG"))
                            .detail(detail("20.00", "DBIT").creditor("Stadtwerke Muster"))))
        .bytes();
  }

  private void registerIban(UUID id, String iban) {
    client()
        .put()
        .uri("/api/v1/accounts/" + id + "/identifier")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(id) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AccountIdentifierRequest(iban))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private void openingBalance(LocalDate date, String balance) {
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OpeningBalanceRequest(date, new BigDecimal(balance), "EUR", null))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private AccountSummaryResponse summary(UUID id) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private long accountVersion(UUID id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT version FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private long count(String sql) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<LocalDate, String> documentSnapshots(UUID id) {
    Map<LocalDate, String> snapshots = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT snapshot_date, balance FROM account_snapshot"
                    + " WHERE account_id = ? AND source = 'DOCUMENT' ORDER BY snapshot_date")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          snapshots.put(
              resultSet.getObject(1, LocalDate.class), resultSet.getBigDecimal(2).toPlainString());
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return snapshots;
  }

  private static byte[] fixture(String name) throws IOException {
    try (InputStream in = CamtImportControllerTest.class.getResourceAsStream("/import/" + name)) {
      return in.readAllBytes();
    }
  }

  private ImportBatchResponse upload(UUID id, byte[] content, UUID templateId) {
    return upload(id, multipart(content, "umsaetze.csv", "text/csv", templateId));
  }

  private ImportBatchResponse upload(UUID id, byte[] content, String name, String mediaType) {
    return upload(id, multipart(content, name, mediaType, null));
  }

  private ImportBatchResponse upload(UUID id, MultiValueMap<String, Object> body) {
    return client()
        .post()
        .uri("/api/v1/accounts/" + id + "/imports")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static MultiValueMap<String, Object> multipart(
      byte[] content, String name, String mediaType, UUID templateId) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType(mediaType));
    fileHeaders.setContentDispositionFormData("file", name);
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    if (templateId != null) {
      body.add("templateId", templateId.toString());
    }
    return body;
  }

  // A file whose IBAN the account does not carry: the member confirms it (US-07-10).
  private ImportBatchResponse confirmAccount(UUID id, ImportBatchResponse batch) {
    if (batch.accountIdentification() == null
        || !batch.accountIdentification().confirmationRequired()) {
      return batch;
    }
    return client()
        .post()
        .uri("/api/v1/accounts/" + id + "/imports/" + batch.id() + "/confirm-account")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void commit(UUID id, ImportBatchResponse batch) {
    client()
        .post()
        .uri("/api/v1/accounts/" + id + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private List<TransactionResponse> sortedTransactions(UUID id) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + id + "/transactions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageResponse<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content()
        .stream()
        .sorted(
            Comparator.comparing(TransactionResponse::bookingDate)
                .thenComparing(row -> row.amount().abs(), Comparator.reverseOrder()))
        .toList();
  }

  private UUID create(Object request) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "EUR"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + token)
        .build();
  }
}
