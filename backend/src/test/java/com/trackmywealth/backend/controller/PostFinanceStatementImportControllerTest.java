package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.service.ShippedTemplateFixtures;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-23 (#326) against a real PostgreSQL: a PostFinance account statement PDF imported with the
 * shipped template (V105) books every booking by its value date in the statement's currency, and
 * the same bookings imported from the statement and from the CSV export (V76) are in the ledger
 * once, whichever comes first (PO rule: nothing is imported twice). The statement is the template's
 * synthetic fixture ({@link ShippedTemplateFixtures#postFinanceStatement}); the CSV is built here
 * with the same invented bookings, each dated as the export dates it: by value date.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostFinanceStatementImportControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final UUID STATEMENT = UUID.fromString("35cd132b-78f1-4480-93a7-e59e0fd18f9f");
  private static final UUID CSV_EXPORT = UUID.fromString("8d1b8ef6-882f-46d9-87e1-a17278f8bfa3");
  // The standard example IBAN the synthetic statement names.
  private static final String IBAN = "CH93 0076 2011 6238 5295 7";

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

  @Autowired JdbcTemplate jdbc;

  private String token;

  @BeforeEach
  void cleanDatabaseAndSignIn() {
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
            "DELETE FROM account",
            "DELETE FROM admin_audit_log",
            "DELETE FROM user_session",
            "DELETE FROM refresh_token",
            "DELETE FROM authorization_denial_log",
            "DELETE FROM app_user",
            "DELETE FROM workspace_member",
            "DELETE FROM financial_institution",
            "DELETE FROM workspace")) {
      jdbc.execute(sql);
    }
    token = bootstrapAdministrator();
  }

  // Acceptance criterion: a EUR account's statement imports in EUR, each booking by its value date.
  @Test
  void aEurStatementImportsEveryBookingInEurByItsValueDate() throws IOException {
    UUID account = accountWithIban("PostFinance EUR", "EUR");

    ImportBatchResponse batch =
        upload(account, ShippedTemplateFixtures.postFinanceStatement("EUR"), STATEMENT, "pdf");
    assertThat(batch.accountIdentification().outcome())
        .isEqualTo(ImportBatchValues.IDENTIFIED_MATCHED);
    assertThat(batch.counts().newRows()).isEqualTo(5);
    assertThat(batch.counts().errors()).isZero();
    assertThat(commit(account, batch).counts().imported()).isEqualTo(5);

    assertThat(
            jdbc.query(
                "SELECT booking_date, amount, currency FROM transaction WHERE account_id = ?"
                    + " ORDER BY booking_date",
                (row, i) ->
                    tuple(
                        row.getObject("booking_date", LocalDate.class),
                        row.getBigDecimal("amount"),
                        row.getString("currency")),
                account))
        .containsExactly(
            tuple(LocalDate.of(2031, 3, 3), new BigDecimal("2487.1300"), "EUR"),
            tuple(LocalDate.of(2031, 3, 4), new BigDecimal("-41.1900"), "EUR"),
            tuple(LocalDate.of(2031, 3, 5), new BigDecimal("-8.5300"), "EUR"),
            tuple(LocalDate.of(2031, 3, 6), new BigDecimal("-1213.7700"), "EUR"),
            tuple(LocalDate.of(2031, 3, 20), new BigDecimal("17.4100"), "EUR"));
    assertThat(
            jdbc.queryForObject(
                "SELECT merchant_description FROM transaction WHERE account_id = ? AND amount = ?",
                String.class,
                account,
                new BigDecimal("-41.19")))
        .isEqualTo("Zahlung an Max Muster Beispielweg 12 Rechnung");
  }

  @Test
  void theSameStatementCommittedTwiceAddsNothing() throws IOException {
    UUID account = accountWithIban("PostFinance EUR", "EUR");
    commit(
        account, upload(account, ShippedTemplateFixtures.postFinanceStatement(), STATEMENT, "pdf"));

    ImportBatchResponse again =
        upload(account, ShippedTemplateFixtures.postFinanceStatement(), STATEMENT, "pdf");

    assertThat(again.counts().duplicates()).isEqualTo(5);
    assertThat(again.counts().newRows()).isZero();
    assertThat(commit(account, again).counts().imported()).isZero();
    assertThat(count(account)).isEqualTo(5);
  }

  // PO rule: the statement after the CSV export adds nothing the export holds - also the booking
  // the statement dates a day after its value date - and the export after the statement neither.
  @Test
  void theStatementAndTheCsvExportOfTheSameBookingsAreInTheLedgerOnce() throws IOException {
    UUID statementFirst = accountWithIban("PostFinance CHF", "CHF");
    commit(statementFirst, upload(statementFirst, chfStatement(), STATEMENT, "pdf"));
    ImportBatchResponse csvAfter = upload(statementFirst, csvExport(), CSV_EXPORT, "csv");
    assertThat(csvAfter.counts().duplicates()).as("the statement's five bookings").isEqualTo(5);
    assertThat(csvAfter.counts().newRows()).as("the one booking after the statement").isOne();
    commit(statementFirst, csvAfter);
    assertThat(count(statementFirst)).isEqualTo(6);

    jdbc.update("UPDATE account SET identifier_hash = NULL, identifier_masked = NULL");
    UUID csvFirst = accountWithIban("PostFinance CHF 2", "CHF");
    commit(csvFirst, upload(csvFirst, csvExport(), CSV_EXPORT, "csv"));
    ImportBatchResponse statementAfter = upload(csvFirst, chfStatement(), STATEMENT, "pdf");
    assertThat(statementAfter.counts().duplicates()).isEqualTo(5);
    assertThat(statementAfter.counts().newRows()).isZero();
    commit(csvFirst, statementAfter);
    assertThat(count(csvFirst)).isEqualTo(6);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static byte[] chfStatement() throws IOException {
    return ShippedTemplateFixtures.postFinanceStatement("CHF");
  }

  // The PF-1 export of the statement's bookings and one booked after it, dated by value date.
  private static byte[] csvExport() {
    String csv =
        "\uFEFFDatum von:;=\"01.03.2031\"\r\n"
            + "Datum bis:;=\"31.03.2031\"\r\n"
            + "Kategorie:;=\"Alle\"\r\n"
            + "Konto:;=\"CH9300762011623852957\"\r\n"
            + "W\u00e4hrung:;=\"CHF\"\r\n"
            + "\r\n"
            + "Datum;Bewegungstyp;Avisierungstext;Gutschrift in CHF;Lastschrift in CHF;Label;"
            + "Kategorie\r\n"
            + "\r\n"
            + "27.03.2031;Buchung;\"Einkauf Musterladen\";;-23.61;;Lebensmittel\r\n"
            + "20.03.2031;Buchung;\"Gutschrift Erika Beispiel\";17.41;;;Einkommen\r\n"
            + "06.03.2031;Buchung;\"Dauerauftrag Miete\";;-1213.77;;Wohnen\r\n"
            + "05.03.2031;Buchung;\"TWINT Kauf Backstube\";;-8.53;;Lebensmittel\r\n"
            + "04.03.2031;Buchung;\"Zahlung an Max Muster\";;-41.19;;Zahlungen\r\n"
            + "03.03.2031;Buchung;\"Gutschrift Muster AG Lohn Maerz\";2487.13;;;Einkommen\r\n"
            + "\r\n"
            + "Disclaimer:\r\n"
            + "\"Dieser Auszug dient nur zur Information.\"\r\n";
    return csv.getBytes(StandardCharsets.UTF_8);
  }

  private UUID accountWithIban(String name, String currency) {
    UUID account =
        client()
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
    client()
        .put()
        .uri("/api/v1/accounts/" + account + "/identifier")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(account) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AccountIdentifierRequest(IBAN))
        .exchange()
        .expectStatus()
        .isOk();
    return account;
  }

  private ImportBatchResponse upload(UUID account, byte[] content, UUID template, String kind) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(
        "pdf".equals(kind) ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement." + kind);
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    body.add("templateId", template.toString());
    return client()
        .post()
        .uri("/api/v1/accounts/" + account + "/imports")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportBatchResponse commit(UUID account, ImportBatchResponse batch) {
    return client()
        .post()
        .uri("/api/v1/accounts/" + account + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private long count(UUID account) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM transaction WHERE account_id = ?", Long.class, account);
  }

  private long accountVersion(UUID account) {
    return jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, account);
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
