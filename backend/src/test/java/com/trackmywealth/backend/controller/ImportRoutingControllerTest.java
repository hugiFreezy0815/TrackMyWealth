package com.trackmywealth.backend.controller;

import static com.trackmywealth.backend.testsupport.CamtFixtures.IBAN;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt053;
import static com.trackmywealth.backend.testsupport.CamtFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.ImportAccountCandidateResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRoutingResponse;
import com.trackmywealth.backend.dto.ImportRoutingValues;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.service.IdempotencyService;
import com.trackmywealth.backend.service.ShippedTemplateFixtures;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-34 (#365) against a real PostgreSQL: a statement uploaded without an account ({@code POST
 * /api/v1/imports}) is routed to the account it names for certain, or stores nothing and lists what
 * the member may choose. CSV, PDF and CAMT files; every statement is synthetic.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportRoutingControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final UUID DKB = UUID.fromString("752d1bc3-1951-408f-9d08-fbe4a1f1d9c5");
  private static final UUID YUH = UUID.fromString("2548b28a-0884-4aa9-b544-557446e0963c");
  private static final UUID SPARKASSE_CARD_PDF =
      UUID.fromString("8f7e1b44-deb6-4e30-81d8-9a96d63d417c");
  private static final String DKB_IBAN = "DE97 9999 9999 0000 0123 45";
  private static final String YUH_IBAN = "CH21 0076 2000 0000 0000 0";
  private static final String CARD = "1234 56** **** 7890";

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
              "DELETE FROM idempotency_key",
              "DELETE FROM settlement_match",
              "DELETE FROM transfer_detection_fx_pending",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction_category_split",
              "DELETE FROM import_row_raw",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM reconciliation_result",
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM import_file",
              "DELETE FROM import_batch",
              "DELETE FROM account_credit_card",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM authorization_denial_log",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM portfolio",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
    token = bootstrapAdministrator();
    account = create(AccountRequests.account("Girokonto", "CASH", "EUR").build());
  }

  // AC 1 (CAMT): the IBAN names one account for certain - the batch is created there, with no
  // confirmation needed to commit.
  @Test
  void aCamtStatementNamingARegisteredIbanIsImportedIntoThatAccount() {
    registerIdentifier(account, IBAN);

    EntityExchangeResult<ImportRoutingResponse> result =
        exchange(multipart(camt(), "auszug.xml", "application/xml", null), null);

    assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);
    ImportRoutingResponse routed = result.getResponseBody();
    assertThat(routed.outcome()).isEqualTo(ImportRoutingValues.BATCH_CREATED);
    assertThat(routed.accountCandidates()).isEmpty();
    assertThat(routed.templateCandidates()).isEmpty();
    assertThat(routed.accountProposal()).isNull();
    ImportBatchResponse batch = routed.batch();
    assertThat(batch.accountId()).isEqualTo(account);
    assertThat(batch.status()).isEqualTo(ImportBatchValues.PARSED);
    assertThat(batch.accountIdentification().outcome())
        .isEqualTo(ImportBatchValues.IDENTIFIED_MATCHED);
    assertThat(batch.accountIdentification().confirmationRequired()).isFalse();
    assertThat(result.getResponseHeaders().getLocation().getPath())
        .isEqualTo("/api/v1/accounts/" + account + "/imports/" + batch.id());
    // AC 7: nothing is committed until the member commits.
    assertThat(transactions(account)).isEmpty();

    commit(batch);
    assertThat(transactions(account)).hasSize(2);
  }

  // AC 1 (CSV): the format is detected by the header (FR-IMP-022) and the account by the IBAN
  // in the preamble.
  @Test
  void aDkbCsvIsDetectedAndImportedIntoTheAccountItsIbanNames() throws IOException {
    registerIdentifier(account, DKB_IBAN);

    ImportRoutingResponse routed =
        route(fixture("dkb-girokonto-synthetic.csv"), "umsaetze.csv", "text/csv", null);

    assertThat(routed.outcome()).isEqualTo(ImportRoutingValues.BATCH_CREATED);
    assertThat(routed.batch().accountId()).isEqualTo(account);
    assertThat(routed.batch().templateId()).isEqualTo(DKB);
    assertThat(routed.batch().accountIdentification().confirmationRequired()).isFalse();
  }

  // AC 1 (PDF with a text layer): the Yuh statement names its account by its IBAN.
  @Test
  void aYuhPdfIsImportedIntoTheAccountItsIbanNames() throws IOException {
    UUID yuh = create(AccountRequests.account("Yuh", "CASH", "CHF").build());
    registerIdentifier(yuh, YUH_IBAN);

    ImportRoutingResponse routed =
        route(ShippedTemplateFixtures.yuhStatement(), "yuh.pdf", "application/pdf", YUH);

    assertThat(routed.outcome()).isEqualTo(ImportRoutingValues.BATCH_CREATED);
    assertThat(routed.batch().accountId()).isEqualTo(yuh);
    assertThat(routed.batch().accountIdentification().outcome())
        .isEqualTo(ImportBatchValues.IDENTIFIED_MATCHED);
  }

  // AC 5 (PO rule: nothing is imported twice): the same file through /imports again previews
  // every row as a duplicate and its commit adds nothing.
  @Test
  void theSameFileUploadedTwiceThroughImportsIsImportedOnce() {
    registerIdentifier(account, IBAN);
    commit(route(camt(), "auszug.xml", "application/xml", null).batch());

    ImportBatchResponse again = route(camt(), "auszug.xml", "application/xml", null).batch();

    assertThat(again.counts().duplicates()).isEqualTo(2);
    assertThat(again.counts().newRows()).isZero();
    assertThat(again.sameFileImportedIn()).isNotNull();
    commit(again);
    assertThat(transactions(account)).hasSize(2);
  }

  // AC 2: a masked card number never routes on its own; the card it names comes first, then the
  // bank's other cards - not its current account, not a card at another bank. Nothing stored.
  @Test
  void aMaskedCardNumberListsTheCardsInsteadOfImporting() throws IOException {
    UUID sparkasse = portfolioAt(catalogueInstitutionOf(SPARKASSE_CARD_PDF));
    UUID named =
        create(
            AccountRequests.account("Karte", "CREDIT_CARD", "EUR").portfolioId(sparkasse).build());
    registerIdentifier(named, CARD);
    UUID second =
        create(
            AccountRequests.account("Zweitkarte", "CREDIT_CARD", "EUR")
                .portfolioId(sparkasse)
                .build());
    create(AccountRequests.account("Giro", "CASH", "EUR").portfolioId(sparkasse).build());
    create(AccountRequests.account("Andere Karte", "CREDIT_CARD", "EUR").build());

    EntityExchangeResult<ImportRoutingResponse> result =
        exchange(
            multipart(
                ShippedTemplateFixtures.sparkasseCardStatement2019(),
                "abrechnung.pdf",
                "application/pdf",
                SPARKASSE_CARD_PDF),
            null);

    assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(result.getResponseHeaders().getLocation()).isNull();
    ImportRoutingResponse routed = result.getResponseBody();
    assertThat(routed.outcome()).isEqualTo(ImportRoutingValues.CHOOSE_ACCOUNT);
    assertThat(routed.batch()).isNull();
    assertThat(routed.accountProposal()).isNull();
    assertThat(routed.accountCandidates())
        .extracting(
            ImportAccountCandidateResponse::accountId,
            ImportAccountCandidateResponse::accountType,
            ImportAccountCandidateResponse::reason)
        .containsExactly(
            tuple(named, "CREDIT_CARD", ImportRoutingValues.REASON_IDENTIFIER),
            tuple(second, "CREDIT_CARD", ImportRoutingValues.REASON_INSTITUTION));
    assertThat(routed.accountCandidates().get(0).maskedIdentifier()).isNotNull();
    assertThat(count("SELECT count(*) FROM import_batch")).isZero();
    assertThat(count("SELECT count(*) FROM import_file")).isZero();
  }

  // No account is named: the proposal for a new one, and no account is created.
  @Test
  void anIbanNoAccountCarriesProposesANewAccount() throws IOException {
    long accounts = count("SELECT count(*) FROM account");

    ImportRoutingResponse routed =
        route(fixture("dkb-girokonto-synthetic.csv"), "umsaetze.csv", "text/csv", null);

    assertThat(routed.outcome()).isEqualTo(ImportRoutingValues.NO_ACCOUNT);
    assertThat(routed.accountCandidates()).isEmpty();
    assertThat(routed.accountProposal().templateId()).isEqualTo(DKB);
    assertThat(routed.accountProposal().suggestedAccountType()).isEqualTo("CASH");
    assertThat(routed.accountProposal().suggestedCurrency()).isEqualTo("EUR");
    assertThat(routed.accountProposal().maskedIdentifier()).endsWith("2345");
    assertThat(count("SELECT count(*) FROM account")).isEqualTo(accounts);
    assertThat(count("SELECT count(*) FROM import_batch")).isZero();
  }

  // Not named for certain, but the bank's accounts are candidates - and the proposal stays, as
  // the file names none of them.
  @Test
  void theBanksAccountsAreCandidatesBesideTheProposal() throws IOException {
    UUID dkb = portfolioAt(catalogueInstitutionOf(DKB));
    UUID atDkb =
        create(AccountRequests.account("DKB Giro", "CASH", "EUR").portfolioId(dkb).build());

    ImportRoutingResponse routed =
        route(fixture("dkb-girokonto-synthetic.csv"), "umsaetze.csv", "text/csv", null);

    assertThat(routed.outcome()).isEqualTo(ImportRoutingValues.CHOOSE_ACCOUNT);
    assertThat(routed.accountCandidates())
        .extracting(
            ImportAccountCandidateResponse::accountId, ImportAccountCandidateResponse::reason)
        .containsExactly(tuple(atDkb, ImportRoutingValues.REASON_INSTITUTION));
    assertThat(routed.accountProposal()).isNotNull();
  }

  // AC 3: an account the member may not import into (here archived) is answered exactly as no
  // account at all - nothing about it is revealed.
  @Test
  void anAccountTheMemberMayNotImportIntoIsAnsweredAsNoAccount() throws IOException {
    byte[] csv = fixture("dkb-girokonto-synthetic.csv");
    ImportRoutingResponse unknown = route(csv, "umsaetze.csv", "text/csv", null);
    registerIdentifier(account, DKB_IBAN);
    archive(account);

    ImportRoutingResponse hidden = route(csv, "umsaetze.csv", "text/csv", null);

    assertThat(hidden).isEqualTo(unknown);
    assertThat(hidden.outcome()).isEqualTo(ImportRoutingValues.NO_ACCOUNT);
  }

  // AC 4: a file no template reads creates no batch and says so.
  @Test
  void aFileNoTemplateReadsStoresNothing() {
    ImportRoutingResponse routed =
        route(
            "just some notes\nnothing like a statement\n".getBytes(StandardCharsets.UTF_8),
            "notes.txt",
            "text/plain",
            null);

    assertThat(routed.outcome()).isEqualTo(ImportRoutingValues.NO_TEMPLATE);
    assertThat(routed.templateCandidates()).isEmpty();
    assertThat(routed.batch()).isNull();
    assertThat(count("SELECT count(*) FROM import_batch")).isZero();
  }

  @Test
  void anEmptyFileIsRefused() {
    client()
        .post()
        .uri("/api/v1/imports")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(multipart(new byte[0], "leer.csv", "text/csv", null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_FILE_EMPTY");
  }

  // ADR 0006: a retry with the same Idempotency-Key answers the first 201 and stores no second
  // batch.
  @Test
  void aRetryWithTheSameIdempotencyKeyCreatesNoSecondBatch() {
    registerIdentifier(account, IBAN);
    String key = UUID.randomUUID().toString();

    EntityExchangeResult<ImportRoutingResponse> first =
        exchange(multipart(camt(), "auszug.xml", "application/xml", null), key);
    EntityExchangeResult<ImportRoutingResponse> retry =
        exchange(multipart(camt(), "auszug.xml", "application/xml", null), key);

    assertThat(retry.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getResponseBody().outcome()).isEqualTo(ImportRoutingValues.BATCH_CREATED);
    assertThat(retry.getResponseBody().batch().id())
        .isEqualTo(first.getResponseBody().batch().id());
    assertThat(retry.getResponseHeaders().getLocation())
        .isEqualTo(first.getResponseHeaders().getLocation());
    assertThat(count("SELECT count(*) FROM import_batch")).isOne();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static byte[] camt() {
    return camt053("001.08")
        .statement(
            IBAN,
            s ->
                s.entry(entry("2500.00", "CRDT", "2030-09-01").reference("R-1"))
                    .entry(entry("45.10", "DBIT", "2030-09-02").reference("R-2")))
        .bytes();
  }

  private ImportRoutingResponse route(
      byte[] content, String name, String mediaType, UUID templateId) {
    return exchange(multipart(content, name, mediaType, templateId), null).getResponseBody();
  }

  private EntityExchangeResult<ImportRoutingResponse> exchange(
      MultiValueMap<String, Object> body, String idempotencyKey) {
    RestTestClient.RequestBodySpec request =
        client().post().uri("/api/v1/imports").contentType(MediaType.MULTIPART_FORM_DATA);
    if (idempotencyKey != null) {
      request = request.header(IdempotencyService.HEADER, idempotencyKey);
    }
    return request
        .body(body)
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(ImportRoutingResponse.class)
        .returnResult();
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

  private static byte[] fixture(String name) throws IOException {
    try (InputStream in =
        ImportRoutingControllerTest.class.getResourceAsStream("/import/" + name)) {
      return in.readAllBytes();
    }
  }

  private void commit(ImportBatchResponse batch) {
    client()
        .post()
        .uri("/api/v1/accounts/" + batch.accountId() + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private List<TransactionResponse> transactions(UUID id) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + id + "/transactions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageResponse<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  private UUID catalogueInstitutionOf(UUID templateId) {
    return client()
        .get()
        .uri("/api/v1/import-templates/" + templateId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody()
        .institutionCatalogueId();
  }

  private UUID portfolioAt(UUID catalogueInstitutionId) {
    return client()
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreatePortfolioRequest(catalogueInstitutionId, null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(PortfolioResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private void registerIdentifier(UUID id, String identifier) {
    client()
        .put()
        .uri("/api/v1/accounts/" + id + "/identifier")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(id) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AccountIdentifierRequest(identifier))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private void archive(UUID id) {
    client()
        .post()
        .uri("/api/v1/accounts/" + id + "/archive")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(id) + "\"")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private Integer accountVersion(UUID id) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .version();
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
