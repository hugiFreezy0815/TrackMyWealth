package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportPreviewRowResponse;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportTemplateCandidateResponse;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.ImportTemplateTestResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.service.ShippedTemplateFixtures;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * US-07-15 (#275), acceptance criterion: given each shipped template's synthetic fixture, when it
 * is detected, then its shipped template is the top candidate - here the only one - and every row
 * parses to the expected canonical values. Runs against the templates V75-V79 actually ship,
 * through the detection and dry-run endpoints a member uses; the fixtures are invented
 * (src/test/resources/import, {@link ShippedTemplateFixtures}).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShippedImportTemplatesIntegrationTest {

  private static final String BASE = "/api/v1/import-templates";
  private static final UUID DKB = UUID.fromString("752d1bc3-1951-408f-9d08-fbe4a1f1d9c5");
  private static final UUID POSTFINANCE = UUID.fromString("8d1b8ef6-882f-46d9-87e1-a17278f8bfa3");
  private static final UUID SPARKASSE_CAMT =
      UUID.fromString("d902ba3d-1344-4e55-8cc2-e2b0e0fe8bf5");
  private static final UUID SPARKASSE_CARD =
      UUID.fromString("4a5b2ef3-9839-45c1-b173-fd9c33f57bd6");
  private static final UUID SPARKASSE_LOAN =
      UUID.fromString("fe678117-6fd0-4863-b271-b2a038ecb5cf");
  private static final UUID YUH = UUID.fromString("a2820365-0b08-4474-bdb9-f2c419a91a25");
  private static final UUID LBS = UUID.fromString("0ac5fd45-d7f5-4c7b-895d-cc8233d07e7b");
  private static final List<UUID> SHIPPED =
      List.of(DKB, POSTFINANCE, SPARKASSE_CAMT, SPARKASSE_CARD, SPARKASSE_LOAN, YUH, LBS);
  private static final ParameterizedTypeReference<List<ImportTemplateResponse>> TEMPLATE_LIST =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<List<ImportTemplateCandidateResponse>>
      CANDIDATES = new ParameterizedTypeReference<>() {};

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  // One administrator for the whole class: these tests only read shipped templates.
  private static String token;

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void signIn() {
    if (token == null) {
      token = bootstrapAdministrator();
    }
  }

  @Test
  void everyShippedTemplateIsAReadOnlyCurrentVersionWithItsInstitution() {
    List<ImportTemplateResponse> shipped =
        client()
            .get()
            .uri(BASE)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TEMPLATE_LIST)
            .returnResult()
            .getResponseBody()
            .stream()
            .filter(ImportTemplateResponse::systemProvided)
            .toList();

    assertThat(shipped)
        .extracting(ImportTemplateResponse::id)
        .containsExactlyInAnyOrderElementsOf(SHIPPED);
    assertThat(shipped)
        .allSatisfy(
            template -> {
              assertThat(template.familyId()).isEqualTo(template.id());
              assertThat(template.current()).isTrue();
              assertThat(template.active()).isTrue();
              assertThat(template.canEdit()).isFalse();
              assertThat(template.institutionCatalogueId()).isNotNull();
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM institution_catalogue WHERE id = (SELECT"
                    + " institution_catalogue_id FROM import_template WHERE id = ?)",
                String.class,
                LBS))
        .isEqualTo("LBS S\u00fcd");
  }

  @Test
  void theMigrationsLeaveImportTemplateUnderForcedRowLevelSecurity() {
    assertThat(
            jdbc.queryForObject(
                "SELECT relforcerowsecurity FROM pg_class WHERE oid = 'import_template'::regclass",
                Boolean.class))
        .isTrue();
  }

  @Test
  void dkbCurrentAccountCsvSkipsPendingBookingsAndReadsItsBalanceAndIban() throws IOException {
    ImportTemplateTestResponse result = detectAndTest(DKB, fixture("dkb-girokonto-synthetic.csv"));

    assertThat(result.rows())
        .extracting(ImportPreviewRowResponse::status)
        .containsExactly("PARSED", "PARSED", "SKIPPED");
    assertBooking(result.rows().get(0), LocalDate.of(2031, 1, 28), "-17.90", "EUR", "EXPENSE");
    assertThat(result.rows().get(0).canonical().valueDate()).isEqualTo(LocalDate.of(2031, 1, 29));
    assertBooking(result.rows().get(1), LocalDate.of(2031, 1, 29), "3041.00", "EUR", "INCOME");
    assertThat(result.rows().get(2).error().code()).isEqualTo(ImportRowErrorValues.ROW_FILTERED);
    assertThat(result.accountIdentifierFound()).isTrue();
    assertThat(result.statementBalanceCount()).isOne();
  }

  @Test
  void postFinanceAccountCsvReadsTheColumnsSignAndTheIbanInExcelQuotes() throws IOException {
    ImportTemplateTestResponse result =
        detectAndTest(POSTFINANCE, fixture("postfinance-account-synthetic.csv"));

    assertThat(result.rowCount()).isEqualTo(3);
    assertThat(result.parsedRowCount()).isEqualTo(3);
    assertBooking(result.rows().get(0), LocalDate.of(2031, 1, 28), "-17.90", "CHF", "EXPENSE");
    assertThat(result.rows().get(0).canonical().description())
        .isEqualTo("Einkauf Musterladen Beispielstadt");
    assertBooking(result.rows().get(1), LocalDate.of(2031, 1, 27), "2963.00", "CHF", "INCOME");
    assertBooking(result.rows().get(2), LocalDate.of(2031, 1, 26), "-1234.55", "CHF", "EXPENSE");
    assertThat(result.accountIdentifierFound()).isTrue();
    assertThat(result.statementBalanceCount()).as("the export states no balance").isNull();
  }

  @Test
  void sparkasseCamtCsvTypesUnambiguousCodesAndSkipsPendingRows() throws IOException {
    ImportTemplateTestResponse result =
        detectAndTest(SPARKASSE_CAMT, fixture("sparkasse-camt-synthetic.csv"));

    assertThat(result.rows())
        .extracting(ImportPreviewRowResponse::status)
        .containsExactly("PARSED", "PARSED", "PARSED", "SKIPPED");
    assertBooking(result.rows().get(0), LocalDate.of(2031, 1, 28), "-19.87", "EUR", "EXPENSE");
    assertThat(result.rows().get(0).canonical().counterpartyName())
        .isEqualTo("Beispiel Versicherung AG");
    assertBooking(result.rows().get(1), LocalDate.of(2031, 1, 30), "-7.45", "EUR", "FEE");
    assertThat(result.rows().get(1).canonical().description())
        .as("decoded as windows-1252")
        .isEqualTo("Entgelt f\u00fcr Kontof\u00fchrung 01/2031");
    assertBooking(result.rows().get(2), LocalDate.of(2031, 1, 31), "1234.56", "EUR", "INCOME");
    assertThat(result.accountIdentifierFound()).isTrue();
  }

  @Test
  void theEighteenColumnSparkasseExportIsOfferedTheCamtTemplateByItsMappedColumns()
      throws IOException {
    // SPK-1/SPK-2: the same export with a trailing Kategorie column.
    String camt = new String(fixture("sparkasse-camt-synthetic.csv"), WINDOWS_1252);
    StringBuilder withCategory = new StringBuilder();
    camt.lines()
        .forEach(
            line ->
                withCategory
                    .append(line)
                    .append(withCategory.isEmpty() ? ";\"Kategorie\"" : ";\"Sonstiges\"")
                    .append('\n'));

    List<ImportTemplateCandidateResponse> candidates =
        detect(withCategory.toString().getBytes(WINDOWS_1252));

    assertThat(candidates)
        .extracting(c -> c.template().id(), ImportTemplateCandidateResponse::match)
        .containsExactly(
            tuple(SPARKASSE_CAMT, ImportTemplateCandidateResponse.MAPPED_COLUMNS_PRESENT));
  }

  @Test
  void sparkasseCardCsvIdentifiesTheMaskedCard() throws IOException {
    ImportTemplateTestResponse result =
        detectAndTest(SPARKASSE_CARD, fixture("sparkasse-card-synthetic.csv"));

    assertThat(result.parsedRowCount()).isEqualTo(3);
    // Without a target account a dry run reads the rows as into an ordinary account; on a card
    // account they become purchases and a settlement (US-07-18).
    assertBooking(result.rows().get(0), LocalDate.of(2031, 1, 29), "-1.49", "EUR", "EXPENSE");
    assertThat(result.rows().get(0).canonical().notes()).isEqualTo("Beispielstadt");
    assertThat(result.rows().get(1).canonical().description()).isEqualTo("Caf\u00e9 Beispiel");
    assertBooking(result.rows().get(2), LocalDate.of(2031, 1, 31), "263.17", "EUR", "INCOME");
    assertThat(result.accountIdentifierFound()).isTrue();
  }

  @Test
  void sparkasseLoanStatementReadsSignedBookingsAndBothBalances() throws IOException {
    ImportTemplateTestResponse result =
        detectAndTest(SPARKASSE_LOAN, ShippedTemplateFixtures.sparkasseLoanStatement());

    assertThat(result.rows())
        .extracting(ImportPreviewRowResponse::status)
        .containsExactly("PARSED", "PARSED", "PARSED", "PARSED", "PARSED");
    assertBooking(result.rows().get(0), LocalDate.of(2031, 1, 29), "-123.45", "EUR", "EXPENSE");
    assertBooking(result.rows().get(1), LocalDate.of(2031, 1, 29), "456.78", "EUR", "INCOME");
    assertBooking(result.rows().get(2), LocalDate.of(2031, 2, 28), "-118.63", "EUR", "EXPENSE");
    assertThat(result.rows().get(2).canonical().description()).isEqualTo("Darlehenszins");
    assertThat(result.rows().get(2).canonical().valueDate()).isEqualTo(LocalDate.of(2031, 3, 1));
    assertBooking(result.rows().get(3), LocalDate.of(2031, 2, 28), "-2.50", "EUR", "EXPENSE");
    // #322: the blanks the statement draws over the date's first digit do not split it.
    assertBooking(result.rows().get(4), LocalDate.of(2031, 3, 31), "456.78", "EUR", "INCOME");
    assertThat(result.statementBalanceCount()).isEqualTo(2);
  }

  @Test
  void yuhStatementReadsEachCurrencySectionAndChecksTheRunningBalance() throws IOException {
    ImportTemplateTestResponse result = detectAndTest(YUH, ShippedTemplateFixtures.yuhStatement());

    assertThat(result.rowCount()).as("balance lines are no bookings").isEqualTo(4);
    assertThat(result.parsedRowCount()).isEqualTo(4);
    assertBooking(result.rows().get(0), LocalDate.of(2031, 1, 3), "1000.00", "CHF", "INCOME");
    assertThat(result.rows().get(0).canonical().description())
        .isEqualTo("Zahlung von Erika Beispiel");
    assertThat(result.rows().get(0).canonical().externalId())
        .as("REFERENZ repeats across the legs of a currency exchange")
        .isNull();
    assertBooking(result.rows().get(1), LocalDate.of(2031, 1, 5), "-17.35", "CHF", "EXPENSE");
    assertBooking(result.rows().get(2), LocalDate.of(2031, 1, 7), "2.50", "CHF", "INCOME");
    assertBooking(result.rows().get(3), LocalDate.of(2031, 1, 10), "-41.19", "EUR", "EXPENSE");
    assertThat(result.statementBalanceCount()).as("opening and closing per section").isEqualTo(4);
  }

  @Test
  void lbsStatementTypesDepositsAndInterestAndIgnoresTheContractPanel() throws IOException {
    ImportTemplateTestResponse result = detectAndTest(LBS, ShippedTemplateFixtures.lbsStatement());

    assertThat(result.rowCount()).isEqualTo(4);
    assertThat(result.parsedRowCount()).isEqualTo(4);
    assertBooking(result.rows().get(0), LocalDate.of(2031, 1, 15), "47.13", "EUR", "DEPOSIT");
    assertThat(result.rows().get(0).canonical().description()).isEqualTo("Einzahlung");
    assertBooking(result.rows().get(1), LocalDate.of(2031, 2, 15), "52.61", "EUR", "DEPOSIT");
    assertBooking(result.rows().get(2), LocalDate.of(2031, 3, 31), "-11.37", "EUR", "EXPENSE");
    assertBooking(result.rows().get(3), LocalDate.of(2031, 12, 31), "1.23", "EUR", "INTEREST");
    assertThat(result.statementBalanceCount()).isOne();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  // The acceptance criterion: detection offers exactly the expected shipped template, first; then
  // that template's dry run on the same file.
  private ImportTemplateTestResponse detectAndTest(UUID expected, byte[] content) {
    List<ImportTemplateCandidateResponse> candidates = detect(content);
    assertThat(candidates)
        .extracting(c -> c.template().id())
        .as("the only candidate is the shipped template")
        .containsExactly(expected);
    return client()
        .post()
        .uri(BASE + "/" + expected + "/test")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(file(content))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportTemplateTestResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<ImportTemplateCandidateResponse> detect(byte[] content) {
    return client()
        .post()
        .uri(BASE + "/detect")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(file(content))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CANDIDATES)
        .returnResult()
        .getResponseBody();
  }

  private static void assertBooking(
      ImportPreviewRowResponse row,
      LocalDate bookingDate,
      String amount,
      String currency,
      String type) {
    assertThat(row.status()).isEqualTo("PARSED");
    assertThat(row.canonical().bookingDate()).isEqualTo(bookingDate);
    assertThat(row.canonical().amount()).isEqualByComparingTo(new BigDecimal(amount));
    assertThat(row.canonical().currency()).isEqualTo(currency);
    assertThat(row.canonical().transactionType()).isEqualTo(type);
  }

  private static MultiValueMap<String, Object> file(byte[] content) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    headers.setContentDispositionFormData("file", "statement");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), headers));
    return body;
  }

  private static byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        ShippedImportTemplatesIntegrationTest.class.getResourceAsStream("/import/" + name)) {
      assertThat(stream).as(name).isNotNull();
      return stream.readAllBytes();
    }
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SetupAdministratorRequest(
                "admin@example.com", "correct-horse-battery-staple", "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
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
