package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
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
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #327 against a real PostgreSQL: the shipped Sparkasse credit-card CSV template, version 2 (V91),
 * records a foreign purchase in its original currency and amount, with the issuer's rate derived
 * from the billed EUR amount (US-09-04); domestic rows, which the export writes as {@code 0,00}
 * with no original currency, import in EUR as before. Every value is invented.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SparkasseCardImportControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  // The shipped template's current version (V91).
  private static final UUID SPARKASSE_CARD =
      UUID.fromString("394a7a1d-1b0a-4cf1-a63a-b0e92cd1d0a0");

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
              "DELETE FROM savings_rate_methodology",
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
    card = create(AccountRequests.account("Kreditkarte", "CREDIT_CARD", "EUR").build());
  }

  @Test
  void aForeignPurchaseKeepsItsOriginalAmountAndDomesticRowsImportInEuro() throws IOException {
    ImportBatchResponse batch = upload(fixture("sparkasse-card-synthetic.csv"));
    assertThat(batch.counts().errors()).isZero();
    commit(confirmAccount(batch));

    List<TransactionResponse> recorded = list();
    assertThat(recorded).hasSize(3);
    TransactionResponse foreign =
        recorded.stream().filter(row -> "CZK".equals(row.currency())).findFirst().orElseThrow();
    assertThat(foreign.amount()).isEqualByComparingTo("-37.00");
    // The issuer's rate, derived from the billed amount: 1,49 EUR / 37,00 CZK, to the ledger's ten
    // decimal places.
    assertThat(foreign.fxRateToAccountCurrency()).isEqualByComparingTo("0.0402702703");
    assertThat(foreign.fxRateEstimated()).isFalse();
    assertThat(recorded)
        .filteredOn(row -> row != foreign)
        .extracting(TransactionResponse::currency)
        .containsOnly("EUR");
  }

  private static byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        SparkasseCardImportControllerTest.class.getResourceAsStream("/import/" + name)) {
      return stream.readAllBytes();
    }
  }

  private ImportBatchResponse upload(byte[] content) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "umsaetze.csv");
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

  // The file names a masked card the account does not carry, so the member confirms the account
  // first (US-07-10).
  private ImportBatchResponse confirmAccount(ImportBatchResponse batch) {
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
