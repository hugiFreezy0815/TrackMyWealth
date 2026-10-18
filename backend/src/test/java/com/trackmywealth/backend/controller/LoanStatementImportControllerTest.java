package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportIdentificationResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.service.ShippedTemplateFixtures;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Comparator;
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
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-21 against a real PostgreSQL: a Sparkasse loan statement imported onto a LOAN account is
 * recorded as the bank books it - interest (EXPENSE) and the statement fee (FEE) charged onto the
 * loan, the instalment (DEBT_REPAYMENT) paid into it - and the loan's value is the statement's
 * closing balance. The instalment the current account pays (CSV-CAMT, "EINZUG RATE/ANNUITAET") is
 * one internal transfer with the loan's, and the saving figure is the loan's net debt reduction.
 * Every value is invented; the statement is for 2019, outside every real sample's years.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanStatementImportControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  // The shipped templates' current versions (loan statement V96, CAMT V88).
  private static final UUID SPARKASSE_LOAN =
      UUID.fromString("db261cc9-5ae3-4eef-b8b4-8529a243abcf");
  private static final UUID SPARKASSE_CAMT =
      UUID.fromString("df6b6640-6a37-49ce-b5fc-feb61c677971");
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
  private static final String CAMT_HEADER =
      "\"Auftragskonto\";\"Buchungstag\";\"Valutadatum\";\"Buchungstext\";\"Verwendungszweck\";"
          + "\"Glaeubiger ID\";\"Mandatsreferenz\";\"Kundenreferenz (End-to-End)\";"
          + "\"Sammlerreferenz\";\"Lastschrift Ursprungsbetrag\";"
          + "\"Auslagenersatz Ruecklastschrift\";\"Beguenstigter/Zahlungspflichtiger\";"
          + "\"Kontonummer/IBAN\";\"BIC (SWIFT-Code)\";\"Betrag\";\"Waehrung\";\"Info\"\n";
  // The current account pays the January instalment on the day the loan statement books it.
  private static final String CAMT_INSTALMENT =
      "\"DE13 0000 0000 0000 0123 45\";\"29.01.19\";\"29.01.19\";\"EINZUG RATE/ANNUITAET\";"
          + "\"Darlehen 0000000000 Rate 01/2019\";\"\";\"\";\"\";\"\";\"\";\"\";"
          + "\"Sparkasse Musterstadt\";\"DE00 4444 4444 4444 4444 44\";\"XXXXDEXXXXX\";"
          + "\"-456,78\";\"EUR\";\"Umsatz gebucht\"\n";

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
  private UUID current;
  private UUID loan;

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
              "DELETE FROM savings_rate_methodology",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_loan",
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
    current = create(AccountRequests.account("Girokonto", "CASH", "EUR").build());
    loan =
        create(
            AccountRequests.account("Darlehen", "LOAN", "EUR")
                .originalPrincipal(new BigDecimal("60000.00"))
                .build());
  }

  // --- DoD -----------------------------------------------------------------------------------

  @Test
  void everyBookingOfTheStatementIsRecordedOnTheLoanAndItsBalanceAgrees() throws Exception {
    importLoanStatement();

    List<TransactionResponse> rows = byBookingDate(list(loan));
    assertThat(rows)
        .extracting(TransactionResponse::transactionType, row -> row.amount().toPlainString())
        .containsExactly(
            tuple("EXPENSE", "-123.4500"),
            tuple("DEBT_REPAYMENT", "456.7800"),
            tuple("EXPENSE", "-118.6300"),
            tuple("FEE", "-2.5000"));
    assertThat(rows.get(0).categoryId()).isEqualTo(shippedCategory("LOAN_INTEREST"));
    assertThat(rows.get(3).categoryId()).isEqualTo(shippedCategory("FEES"));
    // Opening balance plus the bookings is the statement's closing balance.
    BigDecimal ledger =
        rows.stream().map(TransactionResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(new BigDecimal("-50000.00").add(ledger)).isEqualByComparingTo("-49787.80");
    // The loan is worth what the statement says it owes, not original principal less repayments.
    AccountValuation owed = balance(loan);
    assertThat(owed.valueKnown()).isTrue();
    assertThat(owed.value()).isEqualByComparingTo("49787.80");
    assertThat(owed.valueBasis()).isEqualTo(ValueBasisValues.LEDGER_FROM_STATEMENT_BALANCE);
  }

  @Test
  void theInstalmentOnBothAccountsIsOneInternalTransferAndSavingIsTheNetDebtReduction()
      throws Exception {
    importLoanStatement();
    // Until the payment from the current account is known, the instalment awaits a decision.
    CashFlowResponse before = cashFlow("2019-01");
    assertThat(eur(before.pendingReview())).isEqualByComparingTo("456.78");
    assertThat(eur(before.spending())).isEqualByComparingTo("123.45");

    importCurrentAccount(CAMT_HEADER + CAMT_INSTALMENT);

    TransactionResponse payment = list(current).get(0);
    assertThat(payment.transactionType()).isEqualTo("TRANSFER");
    assertThat(payment.internalTransfer()).isTrue();
    assertThat(payment.counterpartyAccountId()).isEqualTo(loan);
    TransactionResponse instalment =
        list(loan).stream()
            .filter(row -> "DEBT_REPAYMENT".equals(row.transactionType()))
            .findFirst()
            .orElseThrow();
    assertThat(instalment.internalTransfer()).isTrue();
    assertThat(instalment.counterpartyAccountId()).isEqualTo(current);

    CashFlowResponse january = cashFlow("2019-01");
    assertThat(january.income()).isEmpty();
    assertThat(eur(january.spending())).as("the interest only").isEqualByComparingTo("123.45");
    // 456.78 paid into the loan less 123.45 interest charged onto it.
    assertThat(eur(january.saving())).isEqualByComparingTo("333.33");
    assertThat(january.pendingReview()).isEmpty();
    assertThat(january.complete()).isTrue();
    CashFlowResponse february = cashFlow("2019-02");
    assertThat(eur(february.spending())).isEqualByComparingTo("121.13");
    assertThat(eur(february.saving())).isEqualByComparingTo("-121.13");
  }

  @Test
  void withoutMortgagePrincipalTheLoanMovesNoSaving() throws Exception {
    execute(
        "INSERT INTO savings_rate_methodology (workspace_id, include_mortgage_principal)"
            + " SELECT workspace_id, FALSE FROM account WHERE id = ?",
        loan);
    importLoanStatement();
    importCurrentAccount(CAMT_HEADER + CAMT_INSTALMENT);

    CashFlowResponse january = cashFlow("2019-01");
    assertThat(eur(january.spending())).isEqualByComparingTo("123.45");
    assertThat(january.saving()).isEmpty();
  }

  @Test
  void anInstalmentPaidFromAnUntrackedAccountCanBeConfirmedAsSuch() throws Exception {
    importLoanStatement();
    TransactionResponse instalment =
        list(loan).stream()
            .filter(row -> "DEBT_REPAYMENT".equals(row.transactionType()))
            .findFirst()
            .orElseThrow();

    client()
        .post()
        .uri(
            "/api/v1/accounts/" + loan + "/transactions/" + instalment.id() + "/untracked-transfer")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", instalment.id()))
        .exchange()
        .expectStatus()
        .isOk();

    CashFlowResponse january = cashFlow("2019-01");
    assertThat(january.pendingReview()).isEmpty();
    assertThat(eur(january.saving())).isEqualByComparingTo("333.33");
  }

  @Test
  void byHandALoanStillTakesNoneOfTheStatementsTypes() {
    for (String type : List.of("EXPENSE", "FEE", "DEBT_REPAYMENT")) {
      BigDecimal amount = new BigDecimal("DEBT_REPAYMENT".equals(type) ? "456.78" : "-12.34");
      client()
          .post()
          .uri("/api/v1/accounts/" + loan + "/transactions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              TransactionRequests.cash(
                  type,
                  LocalDate.of(2019, 1, 29),
                  amount,
                  "EUR",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null))
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
  }

  // US-07-20 (#323), acceptance criterion 1: the statement names the loan by the IBAN after its
  // number, so a loan registered with that IBAN is found without the member confirming it.
  @Test
  void theStatementFindsTheLoanRegisteredWithItsIban() throws Exception {
    client()
        .put()
        .uri("/api/v1/accounts/" + loan + "/identifier")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(loan) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AccountIdentifierRequest("DE89 3704 0044 0532 0130 00"))
        .exchange()
        .expectStatus()
        .isOk();

    ImportBatchResponse batch =
        upload(
            loan,
            ShippedTemplateFixtures.sparkasseLoanStatement(),
            "statement.pdf",
            "application/pdf",
            SPARKASSE_LOAN);

    assertThat(batch.accountIdentification())
        .isEqualTo(
            new ImportIdentificationResponse(
                ImportBatchValues.IDENTIFIED_MATCHED, null, false, false));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void importLoanStatement() throws IOException {
    ImportBatchResponse batch =
        upload(
            loan,
            ShippedTemplateFixtures.sparkasseLoanStatement2019(),
            "statement.pdf",
            "application/pdf",
            SPARKASSE_LOAN);
    assertThat(batch.counts().errors()).isZero();
    // #323: the 2019 fixture's text names no valid IBAN, so the statement imports into the loan
    // the member picked, as with a USER_SELECTED template, without asking for a confirmation.
    assertThat(batch.accountIdentification()).isNull();
    commit(loan, batch);
  }

  // The file names an IBAN the account does not carry, so the member confirms the account first
  // (US-07-10).
  private void importCurrentAccount(String csv) {
    ImportBatchResponse batch =
        upload(current, csv.getBytes(WINDOWS_1252), "umsaetze.csv", "text/csv", SPARKASSE_CAMT);
    assertThat(batch.counts().errors()).isZero();
    ImportBatchResponse confirmed =
        client()
            .post()
            .uri("/api/v1/accounts/" + current + "/imports/" + batch.id() + "/confirm-account")
            .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportBatchResponse.class)
            .returnResult()
            .getResponseBody();
    commit(current, confirmed);
  }

  private ImportBatchResponse upload(
      UUID account, byte[] content, String name, String mediaType, UUID templateId) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType(mediaType));
    fileHeaders.setContentDispositionFormData("file", name);
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    body.add("templateId", templateId.toString());
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

  private void commit(UUID account, ImportBatchResponse batch) {
    client()
        .post()
        .uri("/api/v1/accounts/" + account + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private static List<TransactionResponse> byBookingDate(List<TransactionResponse> rows) {
    return rows.stream()
        .sorted(
            Comparator.comparing(TransactionResponse::bookingDate)
                .thenComparing(TransactionResponse::amount))
        .toList();
  }

  private List<TransactionResponse> list(UUID accountId) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  private CashFlowResponse cashFlow(String month) {
    return client()
        .get()
        .uri("/api/v1/cash-flow?month=" + month)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountValuation balance(UUID accountId) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
        .returnResult()
        .getResponseBody();
  }

  private static BigDecimal eur(List<CashFlowResponse.CurrencyAmount> amounts) {
    return amounts.stream()
        .filter(line -> "EUR".equals(line.currency()))
        .map(CashFlowResponse.CurrencyAmount::amount)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no EUR line in " + amounts));
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

  private UUID shippedCategory(String code) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM category WHERE workspace_id IS NULL AND code = ?")) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return (UUID) resultSet.getObject(1);
      }
    }
  }

  private long accountVersion(UUID account) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT version FROM account WHERE id = ?")) {
      statement.setObject(1, account);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private void execute(String sql, Object... parameters) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
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
