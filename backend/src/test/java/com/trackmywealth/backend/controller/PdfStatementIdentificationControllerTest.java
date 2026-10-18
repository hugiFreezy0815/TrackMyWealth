package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountIdentifierFromImportRequest;
import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountIdentifierResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportIdentificationResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.service.ShippedTemplateFixtures;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-20 (#323) against a real PostgreSQL: a statement PDF uploaded with a shipped template (V96)
 * finds its own account by what its text names - the Yuh statement by its IBAN, the LBS statement
 * by its contract number - and warns when it names another account. The statements are the
 * templates' synthetic fixtures; every value is invented. The Sparkasse loan statement is covered
 * by {@link LoanStatementImportControllerTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PdfStatementIdentificationControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final UUID YUH = UUID.fromString("a82dbfd8-abeb-4fad-af4e-0f9070b3111e");
  private static final UUID LBS = UUID.fromString("d2971d6e-e091-48c5-96dd-d59c45c8dbd4");
  // The identifiers the synthetic fixtures name (ShippedTemplateFixtures).
  private static final String YUH_IBAN = "CH21 0076 2000 0000 0000 0";
  private static final String LBS_CONTRACT = "4 268 135 009";

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
  }

  // Acceptance criterion 1: the Yuh statement names the registered account by its IBAN, so the
  // member does not pick or confirm it.
  @Test
  void aYuhStatementFindsTheAccountRegisteredWithItsIban() throws Exception {
    UUID yuh = create("Yuh", "CHF");
    register(yuh, new AccountIdentifierRequest(YUH_IBAN));

    ImportBatchResponse batch = upload(yuh, ShippedTemplateFixtures.yuhStatement(), YUH);

    assertThat(batch.accountIdentification())
        .isEqualTo(
            new ImportIdentificationResponse(
                ImportBatchValues.IDENTIFIED_MATCHED, null, false, false));
  }

  // Acceptance criterion 2: uploaded into another account, the statement warns and proposes the
  // account it names, as a CSV file does (US-07-10).
  @Test
  void anLbsStatementFindsItsContractAndWarnsWhenUploadedIntoAnotherAccount() throws Exception {
    UUID lbs = create("Bausparvertrag", "EUR");
    register(lbs, new AccountIdentifierRequest(LBS_CONTRACT, "CONTRACT_NUMBER"));
    UUID other = create("Tagesgeld", "EUR");

    ImportBatchResponse matched = upload(lbs, ShippedTemplateFixtures.lbsStatement(), LBS);
    ImportBatchResponse elsewhere = upload(other, ShippedTemplateFixtures.lbsStatement(), LBS);

    assertThat(matched.accountIdentification())
        .isEqualTo(
            new ImportIdentificationResponse(
                ImportBatchValues.IDENTIFIED_MATCHED, null, false, false));
    assertThat(elsewhere.accountIdentification())
        .isEqualTo(
            new ImportIdentificationResponse(
                ImportBatchValues.IDENTIFIED_OTHER_ACCOUNT, lbs, true, false));
  }

  // US-07-11 for a contract number: an LBS statement that matched no account registers its
  // contract number on the account, beside the IBAN it already has, without the member typing it.
  @Test
  void theContractNumberAStatementNamesIsRegisteredFromTheImport() throws Exception {
    UUID lbs = create("Bausparvertrag", "EUR");
    register(lbs, new AccountIdentifierRequest("DE89 3704 0044 0532 0130 00"));
    ImportBatchResponse batch = upload(lbs, ShippedTemplateFixtures.lbsStatement(), LBS);
    assertThat(batch.accountIdentification().outcome())
        .isEqualTo(ImportBatchValues.IDENTIFIED_NO_MATCH);

    AccountIdentifierResponse registered =
        client()
            .put()
            .uri("/api/v1/accounts/" + lbs + "/identifier/from-import")
            .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(lbs) + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new AccountIdentifierFromImportRequest(lbs, batch.id()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountIdentifierResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(registered.identifierMasked()).isEqualTo("\u2022\u2022\u2022\u20223000");
    assertThat(registered.contractIdentifierMasked()).isEqualTo("\u2022\u2022\u2022\u20225009");
    assertThat(
            upload(lbs, ShippedTemplateFixtures.lbsStatement(), LBS)
                .accountIdentification()
                .outcome())
        .isEqualTo(ImportBatchValues.IDENTIFIED_MATCHED);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void register(UUID account, AccountIdentifierRequest request) throws Exception {
    client()
        .put()
        .uri("/api/v1/accounts/" + account + "/identifier")
        .header(HttpHeaders.IF_MATCH, "\"" + accountVersion(account) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isOk();
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

  private ImportBatchResponse upload(UUID account, byte[] content, UUID templateId)
      throws IOException {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);
    fileHeaders.setContentDispositionFormData("file", "statement.pdf");
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

  private UUID create(String name, String currency) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, "SAVINGS", currency).build())
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
