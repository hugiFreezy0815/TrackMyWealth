package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
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
 * US-07-24 against a real PostgreSQL: the shipped Sparkasse credit card template, version 2 (V91),
 * records a foreign purchase in its original amount and currency with the billed EUR amount - the
 * ledger derives the rate - while a purchase in euros (0,00 and no original currency) and a
 * settlement import as before. The statement is the template's synthetic fixture; every value is
 * invented.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SparkasseCardImportControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final UUID SPARKASSE_CARD =
      UUID.fromString("4fccd74c-48e2-4e96-aab3-37478b3e05be");

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
  private UUID card;

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
    card = create(AccountRequests.account("Kreditkarte", "CREDIT_CARD", "EUR").build());
  }

  @Test
  void aForeignPurchaseIsRecordedInItsOriginalCurrencyAndAPurchaseInEurosAsBefore()
      throws IOException {
    ImportBatchResponse batch = upload(fixture("sparkasse-card-synthetic.csv"));
    assertThat(batch.counts().errors()).isZero();
    commit(confirmAccount(batch));

    List<TransactionResponse> rows =
        list().stream().sorted(Comparator.comparing(TransactionResponse::bookingDate)).toList();
    assertThat(rows)
        .extracting(
            TransactionResponse::transactionType,
            row -> row.amount().toPlainString(),
            TransactionResponse::currency)
        .containsExactly(
            tuple("CREDIT_CARD_PURCHASE", "-37.0000", "CZK"),
            tuple("CREDIT_CARD_PURCHASE", "-23.7500", "EUR"),
            tuple("SETTLEMENT", "263.1700", "EUR"));
    // The billed 1.49 EUR for 37.00 CZK: the rate the ledger derives, not the bank's rounded one.
    assertThat(rows.get(0).fxRateToAccountCurrency()).isEqualByComparingTo("0.0402702703");
    assertThat(rows.get(0).fxRateEstimated()).isFalse();
    assertThat(rows.get(1).fxRateToAccountCurrency()).isNull();
  }

  // #341: the foreign purchase already in the ledger in euros - as template version 1 booked it, or
  // a member by hand - is a duplicate of the statement's row booked in its original currency.
  @Test
  void aForeignPurchaseAlreadyBookedInEurosIsADuplicate() throws IOException {
    client()
        .post()
        .uri("/api/v1/accounts/" + card + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                "CREDIT_CARD_PURCHASE",
                LocalDate.of(2031, 1, 29),
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

    ImportBatchResponse batch = upload(fixture("sparkasse-card-synthetic.csv"));

    assertThat(batch.counts().duplicates()).isOne();
    assertThat(batch.counts().newRows()).isEqualTo(2);
    commit(confirmAccount(batch));
    assertThat(list())
        .extracting(TransactionResponse::currency)
        .containsExactlyInAnyOrder("EUR", "EUR", "EUR");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static byte[] fixture(String name) throws IOException {
    try (InputStream in =
        SparkasseCardImportControllerTest.class.getResourceAsStream("/import/" + name)) {
      return in.readAllBytes();
    }
  }

  private ImportBatchResponse upload(byte[] content) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "karte.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    body.add("templateId", SPARKASSE_CARD.toString());
    return client()
        .post()
        .uri("/api/v1/accounts/" + card + "/imports")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // The file names a masked card the account does not carry: the member confirms it (US-07-10).
  private ImportBatchResponse confirmAccount(ImportBatchResponse batch) {
    if (batch.accountIdentification() == null
        || !batch.accountIdentification().confirmationRequired()) {
      return batch;
    }
    return client()
        .post()
        .uri("/api/v1/accounts/" + card + "/imports/" + batch.id() + "/confirm-account")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void commit(ImportBatchResponse batch) {
    client()
        .post()
        .uri("/api/v1/accounts/" + card + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private List<TransactionResponse> list() {
    return client()
        .get()
        .uri("/api/v1/accounts/" + card + "/transactions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  record PageOf<T>(List<T> content, long totalElements) {}

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
