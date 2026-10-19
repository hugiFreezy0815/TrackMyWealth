package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRollbackRequest;
import com.trackmywealth.backend.dto.ImportRollbackResponse;
import com.trackmywealth.backend.dto.ImportRowErrorResponse;
import com.trackmywealth.backend.dto.ImportRowResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.service.ShippedTemplateFixtures;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
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
 * US-07-22 (#325) against a real PostgreSQL: a Yuh statement imported with the shipped template
 * (V101) keeps its currency exchange and its savings pocket moves out of income and spending. The
 * statement is the template's synthetic fixture ({@link ShippedTemplateFixtures#yuhStatement});
 * every value is invented. In January 2031 it books, on a CHF account: 1000.00 CHF in, 17.35 CHF
 * into the savings pocket, a 2.50 CHF dividend, an exchange of 23.17 CHF into 24.53 EUR, and a
 * payment of 41.19 EUR.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class YuhStatementImportControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final UUID YUH_TEMPLATE = UUID.fromString("2548b28a-0884-4aa9-b544-557446e0963c");
  // The IBAN the synthetic statement names: registered, the statement imports without asking.
  private static final String YUH_IBAN = "CH21 0076 2000 0000 0000 0";
  private static final String JANUARY = "2031-01";

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
  private UUID yuh;

  @BeforeEach
  void cleanDatabaseAndSetUp() {
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
    // The EUR rows are converted into the CHF account's currency at their booking dates.
    for (LocalDate date : List.of(LocalDate.of(2031, 1, 8), LocalDate.of(2031, 1, 10))) {
      jdbc.update(
          "INSERT INTO fx_rate (base_currency, quote_currency, rate_date, rate, source)"
              + " VALUES ('EUR', 'CHF', ?, 0.9437, 'ECB') ON CONFLICT DO NOTHING",
          date);
    }
    token = bootstrapAdministrator();
    yuh = createAccount("Yuh", "CASH", "CHF");
    client()
        .put()
        .uri("/api/v1/accounts/" + yuh + "/identifier")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(yuh) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AccountIdentifierRequest(YUH_IBAN))
        .exchange()
        .expectStatus()
        .isOk();
  }

  // Acceptance criteria 1 and 2: neither leg of the exchange counts as income or spending, and the
  // pocket move is saving, booked on a pocket account the first commit creates.
  @Test
  void theExchangeIsOneLinkedPairAndThePocketMoveIsSavingOnANewPocketAccount() throws IOException {
    ImportBatchResponse batch = upload();
    assertThat(batch.counts().errors()).isZero();
    assertThat(warningsOf(batch, "-17.35"))
        .as("the preview says the commit creates the pocket")
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_CREATED);

    commit(batch);

    UUID pocket = account(yuh).savingsPocketAccountId();
    assertThat(pocket).isNotNull();
    AccountSummaryResponse pocketAccount = account(pocket);
    assertThat(pocketAccount.name()).isEqualTo("Yuh \u2013 Sparkonto");
    assertThat(pocketAccount.accountType()).isEqualTo("SAVINGS");
    assertThat(pocketAccount.nativeCurrency()).isEqualTo("CHF");
    assertThat(pocketAccount.countsAsSaving()).isTrue();
    assertThat(pocketAccount.financialInstitutionId())
        .isEqualTo(account(yuh).financialInstitutionId());
    assertThat(
            jdbc.queryForList(
                "SELECT workspace_member_id::TEXT || ':' || ownership_share FROM account_ownership"
                    + " WHERE account_id = ? AND effective_to IS NULL ORDER BY 1",
                String.class,
                pocket))
        .as("the pocket has the Yuh account's owners and shares")
        .isEqualTo(
            jdbc.queryForList(
                "SELECT workspace_member_id::TEXT || ':' || ownership_share FROM account_ownership"
                    + " WHERE account_id = ? AND effective_to IS NULL ORDER BY 1",
                String.class,
                yuh));

    // The pocket's leg: the opposite amount, a row of the same batch, linked to the move.
    Map<String, Object> leg =
        jdbc.queryForMap(
            "SELECT amount, currency, transaction_type, is_internal_transfer,"
                + " counterparty_account_id, import_batch_id, related_transaction_id"
                + " FROM transaction WHERE account_id = ?",
            pocket);
    UUID move = moveId("-17.35");
    assertThat((BigDecimal) leg.get("amount")).isEqualByComparingTo("17.35");
    assertThat(leg)
        .containsEntry("currency", "CHF")
        .containsEntry("transaction_type", "TRANSFER")
        .containsEntry("is_internal_transfer", true)
        .containsEntry("counterparty_account_id", yuh)
        .containsEntry("import_batch_id", batch.id())
        .containsEntry("related_transaction_id", move);
    assertThat(
            jdbc.queryForMap(
                "SELECT is_internal_transfer, counterparty_account_id FROM transaction"
                    + " WHERE id = ?",
                move))
        .containsEntry("is_internal_transfer", true)
        .containsEntry("counterparty_account_id", pocket);

    // The exchange: one pair the system decided, the CHF leg sending, the EUR leg receiving.
    Map<String, Object> exchange =
        jdbc.queryForMap(
            "SELECT payment_transaction_id, card_transaction_id, status, match_kind, decided_by"
                + " FROM settlement_match");
    assertThat(exchange)
        .containsEntry("payment_transaction_id", moveId("-23.17"))
        .containsEntry("card_transaction_id", moveId("24.53"))
        .containsEntry("status", "CONFIRMED")
        .containsEntry("match_kind", "TRANSFER")
        .containsEntry("decided_by", null);

    CashFlowResponse january = cashFlow();
    assertThat(sum(january.income(), "CHF"))
        .as("salary and dividend")
        .isEqualByComparingTo("1002.50");
    assertThat(sum(january.income(), "EUR")).as("not the exchange").isNull();
    assertThat(sum(january.spending(), "EUR")).as("the payment").isEqualByComparingTo("41.19");
    assertThat(sum(january.spending(), "CHF")).as("neither exchange nor pocket").isNull();
    assertThat(sum(january.saving(), "CHF")).as("into the pocket").isEqualByComparingTo("17.35");
    assertThat(january.pendingReview()).isEmpty();
  }

  // A rollback undoes everything the commit linked, keeps the pocket, and the next commit of the
  // statement books onto that same pocket.
  @Test
  void aRollbackRemovesBothLegsAndTheExchangeAndTheNextCommitReusesThePocket() throws IOException {
    ImportBatchResponse committed = commit(upload());
    UUID pocket = account(yuh).savingsPocketAccountId();

    ImportRollbackResponse rollback =
        client()
            .post()
            .uri("/api/v1/accounts/" + yuh + "/imports/" + committed.id() + "/rollback")
            .header(HttpHeaders.IF_MATCH, "\"" + committed.version() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ImportRollbackRequest("Wrong statement"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportRollbackResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(rollback.rollback()).as("nobody worked on the rows").isEqualTo("HARD_DELETE");
    assertThat(rollback.deletedTransactionCount()).as("six rows and the pocket's leg").isEqualTo(7);
    assertThat(count("SELECT count(*) FROM transaction")).isZero();
    assertThat(count("SELECT count(*) FROM settlement_match")).isZero();
    assertThat(account(yuh).savingsPocketAccountId()).as("the pocket stays").isEqualTo(pocket);

    ImportBatchResponse again = upload();
    assertThat(warningsOf(again, "-17.35"))
        .as("the pocket exists and can take the move")
        .doesNotContain(
            ImportBatchValues.WARNING_SAVINGS_POCKET_CREATED,
            ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    commit(again);

    assertThat(count("SELECT count(*) FROM account")).as("no second pocket").isEqualTo(2);
    assertThat(count("SELECT count(*) FROM transaction WHERE account_id = '" + pocket + "'"))
        .isEqualTo(1);
  }

  // A pocket that can no longer take a move leaves it a one-sided transfer for the member to
  // review; no second pocket is created.
  @Test
  void anArchivedPocketLeavesTheMoveAOneSidedTransferPendingReview() throws IOException {
    ImportBatchResponse committed = commit(upload());
    UUID pocket = account(yuh).savingsPocketAccountId();
    client()
        .post()
        .uri("/api/v1/accounts/" + yuh + "/imports/" + committed.id() + "/rollback")
        .header(HttpHeaders.IF_MATCH, "\"" + committed.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRollbackRequest("Archive the pocket first"))
        .exchange()
        .expectStatus()
        .isOk();
    client()
        .post()
        .uri("/api/v1/accounts/" + pocket + "/archive")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(pocket) + "\"")
        .exchange()
        .expectStatus()
        .isOk();

    ImportBatchResponse batch = upload();
    assertThat(warningsOf(batch, "-17.35"))
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    commit(batch);

    assertThat(
            jdbc.queryForMap(
                "SELECT transaction_type, is_internal_transfer, counterparty_account_id"
                    + " FROM transaction WHERE id = ?",
                moveId("-17.35")))
        .containsEntry("transaction_type", "TRANSFER")
        .containsEntry("is_internal_transfer", false)
        .containsEntry("counterparty_account_id", null);
    assertThat(count("SELECT count(*) FROM account")).isEqualTo(2);
    CashFlowResponse january = cashFlow();
    assertThat(sum(january.pendingReview(), "CHF")).isEqualByComparingTo("17.35");
    assertThat(sum(january.spending(), "CHF")).isNull();
  }

  // V99: a pocket is an account of the same workspace, and never the account itself.
  @Test
  void theDatabaseRefusesAPocketOfAnotherWorkspaceOrTheAccountItself() {
    UUID workspace = UUID.randomUUID();
    jdbc.update("INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", workspace);
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO account (id, workspace_id, financial_institution_id, account_type, name,"
            + " native_currency) SELECT ?, ?, id, 'SAVINGS', 'B pocket', 'CHF'"
            + " FROM financial_institution WHERE workspace_id = ?",
        foreign,
        workspace,
        workspace);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE account SET savings_pocket_account_id = ? WHERE id = ?", foreign, yuh))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("account_savings_pocket_scope");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE account SET savings_pocket_account_id = ? WHERE id = ?", yuh, yuh))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_account_savings_pocket_not_self");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private ImportBatchResponse upload() throws IOException {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);
    fileHeaders.setContentDispositionFormData("file", "statement.pdf");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add(
        "file",
        new HttpEntity<>(
            new ByteArrayResource(ShippedTemplateFixtures.yuhStatement()), fileHeaders));
    body.add("templateId", YUH_TEMPLATE.toString());
    return client()
        .post()
        .uri("/api/v1/accounts/" + yuh + "/imports")
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
        .uri("/api/v1/accounts/" + yuh + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // The warning codes of the batch's row with this amount.
  private List<String> warningsOf(ImportBatchResponse batch, String amount) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + yuh + "/imports/" + batch.id() + "/rows?size=50")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<ImportRowResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content()
        .stream()
        .filter(row -> row.canonical().amount().compareTo(new BigDecimal(amount)) == 0)
        .findFirst()
        .orElseThrow()
        .warnings()
        .stream()
        .map(ImportRowErrorResponse::code)
        .toList();
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  // The Yuh account's live row with this amount.
  private UUID moveId(String amount) {
    return jdbc.queryForObject(
        "SELECT id FROM transaction WHERE account_id = ? AND amount = ? AND voided_at IS NULL",
        UUID.class,
        yuh,
        new BigDecimal(amount));
  }

  private CashFlowResponse cashFlow() {
    return client()
        .get()
        .uri("/api/v1/cash-flow?month=" + JANUARY)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // The figure's amount in currency, or null without one.
  private static BigDecimal sum(List<CashFlowResponse.CurrencyAmount> amounts, String currency) {
    return amounts.stream()
        .filter(line -> currency.equals(line.currency()))
        .map(CashFlowResponse.CurrencyAmount::amount)
        .filter(amount -> amount.signum() != 0)
        .findFirst()
        .orElse(null);
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private AccountSummaryResponse account(UUID id) {
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

  private long accountVersion(UUID account) {
    return jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, account);
  }

  private UUID createAccount(String name, String type, String currency) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, type, currency).build())
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
