package com.trackmywealth.backend.controller;

import static com.trackmywealth.backend.controller.HardenedRuntimeAuthenticationScenario.SUPERUSER;
import static com.trackmywealth.backend.controller.HardenedRuntimeAuthenticationScenario.SUPERUSER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.client.EcbFxRateProvider;
import com.trackmywealth.backend.client.FxRateProvider;
import com.trackmywealth.backend.client.FxRateProviderDefinition;
import com.trackmywealth.backend.client.ProvidedFxRate;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.service.FxRateImportService;
import com.trackmywealth.backend.service.TransferRecheckService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * US-28-05 (#331): the work beyond authentication on the role split, started strict, so all of it
 * runs as the RLS-bound runtime role - the role every new installation now uses. The rest of the
 * suite runs as the superuser, which would hide a missing grant or a query that only works while
 * RLS is bypassed:
 *
 * <ul>
 *   <li>an import committed over HTTP: template, upload, commit, categorization and transfer
 *       detection, with the ledger's triggers (append-only, FX history requirement, currency in
 *       use);
 *   <li>the FX import, which has no workspace: it writes the shared, partitioned {@code fx_rate}
 *       and derives cross rates from the trigger-kept currencies in use (V52, V54);
 *   <li>the transfer re-check, which reads its queue outside RLS and re-runs detection inside each
 *       workspace ({@code SystemWorkspaceContext}).
 * </ul>
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.fx.import.enabled=true",
      // The FX job is registered with the scheduler but run directly here, never on its own.
      "spring.quartz.auto-startup=false"
    })
class RoleSplitRuntimeWorkHttpTest {

  private static final String EMAIL = "runtime-work@example.invalid";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final LocalDate DAY = LocalDate.of(2019, 1, 5);
  private static final String HEADER = "Date;Amount;Currency;Text;Reference;MCC";

  @Container
  static PostgreSQLContainer<?> postgres = HardenedRuntimeAuthenticationScenario.newContainer();

  @BeforeAll
  static void splitBeforeTheFirstMigration() throws IOException, SQLException {
    HardenedRuntimeAuthenticationScenario.splitRoles(postgres);
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    HardenedRuntimeAuthenticationScenario.registerSplitDatasource(registry, postgres);
  }

  @LocalServerPort int port;

  @Autowired FxRateImportService fxRateImportService;
  @Autowired TransferRecheckService transferRecheckService;

  @Test
  void importsFxRatesAndTransferRechecksWorkAsTheRuntimeRole() throws Exception {
    String token = setUpAdministrator();
    UUID current = createAccount(token, "Privatkonto");
    UUID savings = createAccount(token, "Sparkonto");
    TransactionResponse incoming = recordDeposit(token, savings);

    // An import, committed: its debit and the savings account's credit are one transfer.
    ImportTemplateResponse template = createTemplate(token);
    ImportBatchResponse batch =
        upload(
            token,
            current,
            template.id(),
            HEADER
                + "\n2019-01-05;-500.00;CHF;To savings;T1;"
                + "\n2019-01-06;-21.00;CHF;Dinner;D1;5812\n");
    ImportBatchResponse committed = commit(token, current, batch);
    assertThat(committed.counts().imported()).isEqualTo(2);
    UUID outgoing = (UUID) singleValue("SELECT id FROM transaction WHERE external_id = ?", "T1");
    assertThat(
            singleValue(
                "SELECT count(*) FROM settlement_match WHERE match_kind = 'TRANSFER'"
                    + " AND payment_transaction_id = ? AND card_transaction_id = ?",
                outgoing,
                incoming.id()))
        .isEqualTo(1L);

    // The FX import: no workspace context at all, shared rates and their cross rates.
    assertThat(fxRateImportService.importLatest()).as("published rates stored").isPositive();
    LocalDate latestRate =
        LocalDate.parse((String) singleValue("SELECT max(rate_date)::TEXT FROM fx_rate"));

    // A detection waiting for that day's rates is re-run inside its workspace and dequeued.
    UUID workspace = (UUID) singleValue("SELECT workspace_id FROM account WHERE id = ?", current);
    asSuperuser(
        "INSERT INTO transfer_detection_fx_pending (workspace_id, booking_date) VALUES (?, ?)",
        workspace,
        latestRate);
    assertThat(transferRecheckService.recheckPending()).isEqualTo(1);
    assertThat(singleValue("SELECT count(*) FROM transfer_detection_fx_pending")).isEqualTo(0L);
  }

  private String setUpAdministrator() {
    return client(null)
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest(EMAIL, PASSWORD, "Runtime Work", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private UUID createAccount(String token, String name) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private TransactionResponse recordDeposit(String token, UUID account) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + account + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                "DEPOSIT",
                DAY,
                new BigDecimal("500.00"),
                "CHF",
                "Transfer in",
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportTemplateResponse createTemplate(String token) {
    ImportTemplateRequest request =
        new ImportTemplateRequest(
            "Runtime work",
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
            List.of(HEADER.split(";")),
            null,
            null);
    return client(token)
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

  private ImportBatchResponse upload(String token, UUID account, UUID template, String csv) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add(
        "file",
        new HttpEntity<>(new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)), fileHeaders));
    body.add("templateId", template.toString());
    return client(token)
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

  private ImportBatchResponse commit(String token, UUID account, ImportBatchResponse batch) {
    return client(token)
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

  // Checked as the superuser, outside the application, so RLS hides nothing from the assertion.
  private Object singleValue(String sql, Object... parameters) throws SQLException {
    try (Connection superuser = superuserConnection();
        PreparedStatement query = superuser.prepareStatement(sql)) {
      bind(query, parameters);
      try (ResultSet result = query.executeQuery()) {
        assertThat(result.next()).as(sql).isTrue();
        return result.getObject(1);
      }
    }
  }

  private void asSuperuser(String sql, Object... parameters) throws SQLException {
    try (Connection superuser = superuserConnection();
        PreparedStatement update = superuser.prepareStatement(sql)) {
      bind(update, parameters);
      assertThat(update.executeUpdate()).as(sql).isOne();
    }
  }

  private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int i = 0; i < parameters.length; i++) {
      statement.setObject(i + 1, parameters[i]);
    }
  }

  private Connection superuserConnection() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), SUPERUSER, SUPERUSER_PASSWORD);
  }

  private RestTestClient client(String token) {
    var builder = RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port));
    if (token != null) {
      builder.defaultHeader("Authorization", "Bearer " + token);
    }
    return builder.build();
  }

  @TestConfiguration
  static class StubProviderConfig {

    @Bean
    @Primary
    FxRateProvider stubFxRateProvider() {
      return new WeekdayFxRateProvider();
    }
  }

  /** Publishes EUR/CHF and EUR/USD at a fixed rate on every weekday asked for. */
  static final class WeekdayFxRateProvider implements FxRateProvider {

    @Override
    public FxRateProviderDefinition definition() {
      return EcbFxRateProvider.ECB_DEFINITION;
    }

    @Override
    public List<ProvidedFxRate> fetch(LocalDate from, LocalDate to) {
      List<ProvidedFxRate> rates = new ArrayList<>();
      from.datesUntil(to.plusDays(1))
          .filter(
              date ->
                  date.getDayOfWeek() != DayOfWeek.SATURDAY
                      && date.getDayOfWeek() != DayOfWeek.SUNDAY)
          .forEach(
              date -> {
                rates.add(new ProvidedFxRate("EUR", "CHF", date, new BigDecimal("0.94")));
                rates.add(new ProvidedFxRate("EUR", "USD", date, new BigDecimal("1.08")));
              });
      return rates;
    }
  }
}
