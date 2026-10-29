package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SnapshotHoldingRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-03-06 (#389), every acceptance criterion with real members: A and B share one workspace, C has
 * a workspace of their own. Membership is the only access grant (FR-TEN-008), so whatever A creates
 * or imports is there for B at once, B may change it, and C sees none of it.
 *
 * <p>The members are written directly: the API only ever creates a member together with its user,
 * and the MVP allows one member per workspace through it (FR-HOU-010), while the model and the
 * access checks treat membership as a set (FR-HOU-001). Every member still has its login, so each
 * one authenticates through {@code /auth/login} like any user.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceMembershipAccessIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String HEADER = "Date;Amount;Currency;Text;Reference";

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
  @Autowired PasswordEncoder passwordEncoder;

  private UUID workspace;
  private UUID memberB;
  private String a;
  private String b;
  private String c;

  @BeforeEach
  void twoMembersOfOneWorkspaceAndAStranger() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "transaction_category_split",
              "transaction_categorization_log",
              "transaction",
              "import_row_raw",
              "import_batch",
              "import_template",
              "snapshot_holding",
              "account_snapshot",
              "security_field_provenance",
              "security_asset_class_weight",
              "security_identifier",
              "security",
              "account_securities",
              "account",
              "user_session",
              "refresh_token",
              "authorization_denial_log",
              "app_user",
              "workspace_member",
              "financial_institution",
              "workspace")) {
        // V68 refuses a plain DELETE of transactions outside an import rollback.
        statement.execute(
            "transaction".equals(table)
                ? LedgerCleanup.DELETE_ALL_TRANSACTIONS
                : "DELETE FROM " + table);
      }
    }
    workspace = newWorkspace("Shared");
    newMember(workspace, "a@example.com");
    memberB = newMember(workspace, "b@example.com");
    newMember(newWorkspace("Elsewhere"), "c@example.com");
    a = login("a@example.com");
    b = login("b@example.com");
    c = login("c@example.com");
  }

  // AC 1: what A creates and imports, B sees in every list, detail and report without a step.
  @Test
  void whatOneMemberCreatesAndImportsTheOtherSeesEverywhere() {
    UUID portfolio = createInstitution(a, "Synthetic Bank");
    AccountSummaryResponse account = createAccount(a, portfolio, "Everyday account");
    openingBalance(a, account.id(), "0.00");
    importRows(a, account.id(), "2026-01-05;-12.40;CHF;Bakery;R1", "2026-01-06;100.00;CHF;Pay;R2");

    assertThat(get(b, "/api/v1/accounts/" + account.id(), AccountSummaryResponse.class).name())
        .isEqualTo("Everyday account");
    assertThat(transactions(b, account.id()))
        .extracting(TransactionResponse::merchantDescription)
        .containsExactlyInAnyOrder("Bakery", "Pay");
    assertThat(
            get(b, "/api/v1/accounts/" + account.id() + "/balance", AccountValuation.class).value())
        .isEqualByComparingTo("87.60");
    InstitutionSummaryResponse summary =
        get(b, "/api/v1/institutions/" + portfolio + "/summary", InstitutionSummaryResponse.class);
    assertThat(summary.netValue()).isEqualByComparingTo("87.60");
    assertThat(get(b, "/api/v1/net-worth", NetWorthResponse.class).netWorth())
        .isEqualByComparingTo("87.60");
    client(b)
        .get()
        .uri("/api/v1/accounts/" + account.id() + "/imports")
        .exchange()
        .expectStatus()
        .isOk();

    // Positions: A records a depot snapshot with a holding; B sees the holding.
    AccountSummaryResponse depot = createAccount(a, portfolio, "Depot", "SECURITIES");
    UUID security = createSecurity(a);
    client(a)
        .post()
        .uri("/api/v1/accounts/" + depot.id() + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new RecordAccountSnapshotRequest(
                LocalDate.of(2026, 1, 31),
                null,
                List.of(new SnapshotHoldingRequest(security, new BigDecimal("3"), null, null))))
        .exchange()
        .expectStatus()
        .isCreated();
    List<AccountSnapshotResponse> snapshots =
        client(b)
            .get()
            .uri("/api/v1/accounts/" + depot.id() + "/snapshots")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<AccountSnapshotResponse>>() {})
            .returnResult()
            .getResponseBody();
    assertThat(snapshots)
        .singleElement()
        .satisfies(
            snapshot ->
                assertThat(snapshot.holdings())
                    .singleElement()
                    .satisfies(
                        holding -> {
                          assertThat(holding.securityId()).isEqualTo(security);
                          assertThat(holding.quantity()).isEqualByComparingTo("3");
                        }));
  }

  // AC 2: B edits and deletes what A created, subject only to the usual version check.
  @Test
  void theOtherMemberChangesAndDeletesWhatTheFirstCreated() {
    UUID portfolio = createInstitution(a, "Synthetic Bank");
    AccountSummaryResponse account = createAccount(a, portfolio, "Everyday account");
    importRows(a, account.id(), "2026-01-05;-12.40;CHF;Bakery;R1");
    TransactionResponse imported = transactions(a, account.id()).get(0);

    client(b)
        .put()
        .uri("/api/v1/accounts/" + account.id())
        .header(HttpHeaders.IF_MATCH, "\"" + account.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateAccountRequest("Renamed by B", "CASH", "CHF", null, null, null))
        .exchange()
        .expectStatus()
        .isOk();
    client(b)
        .put()
        .uri("/api/v1/accounts/" + account.id())
        .header(HttpHeaders.IF_MATCH, "\"" + account.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateAccountRequest("Stale", "CASH", "CHF", null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    client(b)
        .delete()
        .uri(
            "/api/v1/accounts/" + account.id() + "/transactions/" + imported.id() + "?reason=Wrong")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported.id()))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(get(a, "/api/v1/accounts/" + account.id(), AccountSummaryResponse.class).name())
        .isEqualTo("Renamed by B");
    // An imported row is voided, not deleted (FR-LIF-002): A sees B's void on A's own import.
    assertThat(transactions(a, account.id()))
        .filteredOn(row -> row.id().equals(imported.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.voidReason()).isEqualTo("Wrong"));
  }

  // AC 3: another workspace's ids answer exactly like unknown ones.
  @Test
  void aMemberOfAnotherWorkspaceGetsTheNotFoundOfAnUnknownId() {
    UUID portfolio = createInstitution(a, "Synthetic Bank");
    AccountSummaryResponse account = createAccount(a, portfolio, "Everyday account");

    for (String uri :
        List.of(
            "/api/v1/accounts/" + account.id(),
            "/api/v1/accounts/" + account.id() + "/transactions",
            "/api/v1/accounts/" + account.id() + "/balance",
            "/api/v1/institutions/" + portfolio + "/summary",
            "/api/v1/accounts/" + UUID.randomUUID())) {
      client(c).get().uri(uri).exchange().expectStatus().isNotFound();
    }
    assertThat(get(c, "/api/v1/net-worth", NetWorthResponse.class).accounts()).isEmpty();
  }

  // AC 4: an account is created without an owner and none is returned.
  @Test
  void anAccountHasNoOwner() throws Exception {
    UUID portfolio = createInstitution(a, "Synthetic Bank");
    UUID account = createAccount(a, portfolio, "Everyday account").id();

    Map<String, Object> json =
        client(a)
            .get()
            .uri("/api/v1/accounts/" + account)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertThat(json.keySet()).noneMatch(key -> key.toLowerCase().contains("owner"));
    assertThat(tableExists("account_ownership")).isFalse();
  }

  // AC 5: a deactivated member is refused on the next request; what they created stays.
  @Test
  void aDeactivatedMemberIsRefusedAndTheirDataStaysInTheWorkspace() {
    AccountSummaryResponse bs = createAccount(b, createInstitution(b, "B's Bank"), "Created by B");

    client(b)
        .post()
        .uri("/api/v1/workspace-members/" + memberB + "/deactivate")
        .headers(CurrentVersion.ifMatch(dataSource, "workspace_member", memberB))
        .exchange()
        .expectStatus()
        .isOk();

    client(b).get().uri("/api/v1/accounts/" + bs.id()).exchange().expectStatus().isUnauthorized();
    assertThat(get(a, "/api/v1/accounts/" + bs.id(), AccountSummaryResponse.class).name())
        .isEqualTo("Created by B");
  }

  // AC 6: no ownership or sharing-grant endpoint is left.
  @Test
  void noOwnershipOrGrantEndpointExists() {
    UUID account = createAccount(a, createInstitution(a, "Synthetic Bank"), "Everyday").id();

    client(a).get().uri("/api/v1/sharing-grants").exchange().expectStatus().isNotFound();
    client(a)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of())
        .exchange()
        .expectStatus()
        .isNotFound();
    client(a)
        .get()
        .uri("/api/v1/accounts/" + account + "/ownership")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  // AC 7: a member without a login is refused (V110), so every member is a user.
  @Test
  void aMemberWithoutALoginIsRefused() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO workspace_member (workspace_id, display_name) VALUES (?, 'Nobody')")) {
      insert.setObject(1, workspace);
      assertThatThrownBy(insert::executeUpdate)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("workspace_member_requires_login");
    }
    assertThat(countMembers()).isEqualTo(2);
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private UUID newWorkspace(String name) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement("INSERT INTO workspace (name) VALUES (?) RETURNING id")) {
      insert.setString(1, name);
      try (var result = insert.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  // A member and its login in one statement, so V110's check at commit sees both.
  private UUID newMember(UUID workspaceId, String email) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "WITH member AS (INSERT INTO workspace_member (workspace_id, display_name)"
                    + " VALUES (?, ?) RETURNING id)"
                    + " INSERT INTO app_user (email, password_hash, workspace_member_id)"
                    + " SELECT ?, ?, id FROM member RETURNING workspace_member_id")) {
      insert.setObject(1, workspaceId);
      insert.setString(2, email);
      insert.setString(3, email);
      insert.setString(4, passwordEncoder.encode(PASSWORD));
      try (var result = insert.executeQuery()) {
        result.next();
        return result.getObject(1, UUID.class);
      }
    }
  }

  private long countMembers() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT count(*) FROM workspace_member WHERE workspace_id = ?")) {
      query.setObject(1, workspace);
      try (var result = query.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private boolean tableExists(String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      query.setString(1, "public." + table);
      try (var result = query.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private UUID createInstitution(String token, String name) {
    return client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateFinancialInstitutionRequest(null, name, "CH", "BANK", null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(FinancialInstitutionSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private AccountSummaryResponse createAccount(String token, UUID institution, String name) {
    return createAccount(token, institution, name, "CASH");
  }

  private AccountSummaryResponse createAccount(
      String token, UUID institution, String name, String accountType) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account(name, accountType, "CHF")
                .financialInstitutionId(institution)
                .build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createSecurity(String token) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                "CH0038863350", "Nestle", "CHF", "EQUITY", "EQUITY", null, null, null, null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  // Before the imported rows, so the balance is known (an account without one has no value).
  private void openingBalance(String token, UUID account, String amount) {
    client(token)
        .post()
        .uri("/api/v1/accounts/" + account + "/opening-balance")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new OpeningBalanceRequest(
                LocalDate.of(2026, 1, 1), new BigDecimal(amount), "CHF", null))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private void importRows(String token, UUID account, String... rows) {
    byte[] file = (HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(file), fileHeaders));
    body.add("templateId", template(token).id().toString());
    ImportBatchResponse batch =
        client(token)
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
    client(token)
        .post()
        .uri("/api/v1/accounts/" + account + "/imports/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, "\"" + batch.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private ImportTemplateResponse template(String token) {
    return client(token)
        .post()
        .uri("/api/v1/import-templates")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new ImportTemplateRequest(
                "Synthetic CSV " + UUID.randomUUID(),
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
                    null,
                    null,
                    null),
                Map.of(),
                null,
                List.of("Date", "Amount", "Currency", "Text", "Reference"),
                null,
                null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<TransactionResponse> transactions(String token, UUID account) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + account + "/transactions?size=100")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageResponse<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  private <T> T get(String token, String uri, Class<T> type) {
    return client(token)
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(type)
        .returnResult()
        .getResponseBody();
  }

  private String login(String email) {
    return anonymousClient()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, PASSWORD))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens()
        .accessToken();
  }

  private RestTestClient client(String bearer) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + bearer)
        .build();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }
}
