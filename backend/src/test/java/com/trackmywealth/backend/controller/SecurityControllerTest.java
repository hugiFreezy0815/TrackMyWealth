package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SecurityCreation;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SecurityService;
import com.trackmywealth.backend.validation.IsinValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-12-01: the security master is created lazily on first reference and shared by every workspace.
 * The DoD's test is {@link #twoWorkspacesCreatingTheSameIsinAtOnceShareOneRow}; the rest pin the
 * acceptance criteria and the shared-data rules (NFR-LIC-007).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  // The story's own example (an iShares ETF) and a well-known equity, both with valid check digits.
  private static final String ETF_ISIN = "IE00B4L5Y983";
  private static final String EQUITY_ISIN = "US0378331005";

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

  @Autowired SecurityService securityService;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM fx_rate");
      statement.execute("DELETE FROM transaction_category_split");
      statement.execute("DELETE FROM transaction");
      statement.execute("DELETE FROM security_field_provenance");
      statement.execute("DELETE FROM security_asset_class_weight");
      statement.execute("DELETE FROM security_identifier");
      statement.execute("DELETE FROM security");
      for (String table :
          List.of(
              "sharing_grant",
              "account_ownership",
              "custom_asset_valuation",
              "account_custom_asset",
              "account_credit_card",
              "account_mortgage",
              "account",
              "admin_audit_log",
              "user_session",
              "refresh_token",
              "app_user",
              "workspace_member",
              "financial_institution",
              "workspace")) {
        statement.execute("DELETE FROM " + table);
      }
    }
  }

  // --- Acceptance criteria -----------------------------------------------------------------

  @Test
  void aSecurityNotYetInTheMasterIsCreatedAndDescribed() {
    String token = adminWithAccount();

    SecurityResponse created =
        post(token, etf(ETF_ISIN, "iShares Core MSCI World"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.id()).isNotNull();
    assertThat(created.isin()).isEqualTo(ETF_ISIN);
    assertThat(created.syntheticKey()).isNull();
    assertThat(created.displayName()).isEqualTo("iShares Core MSCI World");
    assertThat(created.legalName()).isEqualTo("iShares Core MSCI World");
    assertThat(created.denominationCurrency()).isEqualTo("USD");
    assertThat(created.instrumentType()).isEqualTo("ETF");
    assertThat(created.assetClass()).isEqualTo("EQUITY");
    assertThat(created.state()).isEqualTo("ACTIVE");
    assertThat(count("security")).isEqualTo(1);
  }

  @Test
  void aSecondCallForTheSameIsinReturnsTheExistingRecordUnchanged() {
    String token = adminWithAccount();
    SecurityResponse first =
        post(token, etf(ETF_ISIN, "iShares Core MSCI World"))
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();

    // Different name, currency and type: shared reference data is never edited by a later caller.
    SecurityResponse second =
        post(
                token,
                new CreateSecurityRequest(
                    ETF_ISIN.toLowerCase(),
                    "Something else",
                    "CHF",
                    "BOND",
                    "FIXED_INCOME",
                    "CH",
                    "CH"))
            .expectStatus()
            .isOk()
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(second).isEqualTo(first);
    assertThat(second.displayName()).isEqualTo("iShares Core MSCI World");
    assertThat(second.denominationCurrency()).isEqualTo("USD");
    assertThat(count("security")).isEqualTo(1);
    assertThat(count("security_asset_class_weight")).isEqualTo(1);
  }

  @Test
  void lookingAnIsinUpNeverWritesARecord() {
    String token = adminWithAccount();

    get(token, ETF_ISIN).expectStatus().isNotFound();
    assertThat(count("security")).isZero();

    post(token, etf(ETF_ISIN, "iShares Core MSCI World")).expectStatus().isCreated();
    get(token, ETF_ISIN.toLowerCase())
        .expectStatus()
        .isOk()
        .expectBody(SecurityResponse.class)
        .value(found -> assertThat(found.isin()).isEqualTo(ETF_ISIN));
    assertThat(count("security")).isEqualTo(1);
  }

  @Test
  void twoWorkspacesCreatingTheSameIsinAtOnceShareOneRow() throws Exception {
    // Definition of Done. Workspace A comes from the setup endpoint; there is no API for a second
    // workspace (setup is a one-time bootstrap), so B is seeded directly, the way
    // CrossTenantIsolationTest does. Each thread runs under its own workspace's principal, so the
    // transaction listener sets that workspace's app.current_workspace_id.
    adminWithAccount();
    AuthenticatedUserPrincipal a = principalOfExistingWorkspace();
    AuthenticatedUserPrincipal b = seedSecondWorkspaceWithAccount();
    CreateSecurityRequest request = etf(ETF_ISIN, "iShares Core MSCI World");

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<SecurityCreation> fromA = pool.submit(() -> createAs(a, request, start));
      Future<SecurityCreation> fromB = pool.submit(() -> createAs(b, request, start));
      start.countDown();
      SecurityCreation resultA = fromA.get(30, TimeUnit.SECONDS);
      SecurityCreation resultB = fromB.get(30, TimeUnit.SECONDS);

      assertThat(resultA.security().id()).isEqualTo(resultB.security().id());
      assertThat(List.of(resultA.created(), resultB.created()))
          .as("exactly one caller created the row")
          .containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }

    assertThat(count("security")).isEqualTo(1);
    assertThat(count("security_asset_class_weight")).isEqualTo(1);
    assertThat(count("security_field_provenance"))
        .isEqualTo(7); // isin + the six other supplied fields
  }

  // --- Manual record, provenance, completeness --------------------------------------------

  @Test
  void aManualRecordWithoutAnIsinGetsAGeneratedSyntheticKey() {
    String token = adminWithAccount();
    CreateSecurityRequest request =
        new CreateSecurityRequest(
            null, "Private Loan Note", "CHF", "OTHER", "ALTERNATIVES", null, null);

    SecurityResponse first =
        post(token, request)
            .expectStatus()
            .isCreated()
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();
    SecurityResponse second =
        post(token, request)
            .expectStatus()
            .isCreated()
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(first.isin()).isNull();
    assertThat(first.syntheticKey()).startsWith("MANUAL-");
    assertThat(second.syntheticKey()).isNotEqualTo(first.syntheticKey()); // no ISIN = no identity
    assertThat(count("security")).isEqualTo(2);
  }

  @Test
  void aManualRecordStoresOneEstimatedFullWeightAssetClassAndMarksEveryFieldManual()
      throws Exception {
    String token = adminWithAccount();
    SecurityResponse created =
        post(token, etf(ETF_ISIN, "iShares Core MSCI World"))
            .expectStatus()
            .isCreated()
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement weights =
            connection.prepareStatement(
                "SELECT asset_class, weight, is_estimated, source FROM"
                    + " security_asset_class_weight WHERE security_id = ?");
        PreparedStatement provenance =
            connection.prepareStatement(
                "SELECT DISTINCT source FROM security_field_provenance WHERE security_id = ?")) {
      weights.setObject(1, created.id());
      try (ResultSet rs = weights.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("asset_class")).isEqualTo("EQUITY");
        assertThat(rs.getBigDecimal("weight")).isEqualByComparingTo("1");
        assertThat(rs.getBoolean("is_estimated")).isTrue();
        assertThat(rs.next()).isFalse();
      }
      provenance.setObject(1, created.id());
      try (ResultSet rs = provenance.executeQuery()) {
        // Only MANUAL - never a user or workspace id (NFR-LIC-007).
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("MANUAL");
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void theCompletenessIndicatorNamesTheGapsInsteadOfHidingThem() {
    String token = adminWithAccount();

    // An equity with no countries and (always, for now) no GICS code.
    SecurityResponse equity =
        post(
                token,
                new CreateSecurityRequest(
                    EQUITY_ISIN, "Apple", "USD", "EQUITY", "EQUITY", null, null))
            .expectStatus()
            .isCreated()
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(equity.completeness().complete()).isFalse();
    assertThat(equity.completeness().missingFields())
        .containsExactlyInAnyOrder("securityCountry", "issuerCountry", "gicsSubIndustry");

    // GICS is for equity-nature instruments only (FR-GICS-003): an ETF with both countries has
    // nothing missing.
    SecurityResponse etf =
        post(
                token,
                new CreateSecurityRequest(ETF_ISIN, "iShares", "USD", "ETF", "EQUITY", "IE", "IE"))
            .expectStatus()
            .isCreated()
            .expectBody(SecurityResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(etf.completeness().complete()).isTrue();
    assertThat(etf.completeness().missingFields()).isEmpty();
  }

  // --- Validation ---------------------------------------------------------------------------

  @Test
  void anIsinWithABadCheckDigitOrFormatIsRejected() {
    String token = adminWithAccount();

    for (String bad : List.of("IE00B4L5Y984", "IE00B4L5Y98", "1E00B4L5Y983", "IE00B4L5Y98-")) {
      post(token, etf(bad, "Bad")).expectStatus().isBadRequest();
    }
    assertThat(count("security")).isZero();
  }

  @Test
  void requiredFieldsAndKnownValuesAreEnforced() {
    String token = adminWithAccount();

    post(token, new CreateSecurityRequest(ETF_ISIN, " ", "USD", "ETF", "EQUITY", null, null))
        .expectStatus()
        .isBadRequest();
    post(token, new CreateSecurityRequest(ETF_ISIN, "X", "ZZZ", "ETF", "EQUITY", null, null))
        .expectStatus()
        .isBadRequest();
    post(token, new CreateSecurityRequest(ETF_ISIN, "X", "USD", "WIDGET", "EQUITY", null, null))
        .expectStatus()
        .isBadRequest();
    post(token, new CreateSecurityRequest(ETF_ISIN, "X", "USD", "ETF", "STAMPS", null, null))
        .expectStatus()
        .isBadRequest();
    post(token, new CreateSecurityRequest(ETF_ISIN, "X", "USD", "ETF", "EQUITY", "ch", null))
        .expectStatus()
        .isBadRequest();
    assertThat(count("security")).isZero();
  }

  @Test
  void isinCheckDigitsFollowIso6166() {
    assertThat(IsinValidator.isValidIsin("IE00B4L5Y983")).isTrue();
    assertThat(IsinValidator.isValidIsin("US0378331005")).isTrue();
    assertThat(IsinValidator.isValidIsin("CH0012221716")).isTrue(); // ABB
    assertThat(IsinValidator.isValidIsin("US0378331006")).isFalse();
    assertThat(IsinValidator.isValidIsin("ie00b4l5y983")).isFalse(); // callers normalise first
    assertThat(IsinValidator.isValidIsin(null)).isFalse();
  }

  // --- Authorization ------------------------------------------------------------------------

  @Test
  void theEndpointsRequireAuthentication() {
    anonymousClient()
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(etf(ETF_ISIN, "iShares"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    anonymousClient()
        .get()
        .uri("/api/v1/securities?isin=" + ETF_ISIN)
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void aMemberWhoCannotEditAnyAccountMayLookUpButNotCreate() {
    String adminToken = adminWithAccount();
    AccountSummaryResponse cash = firstAccount(adminToken);
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.READ);

    post(memberToken, etf(ETF_ISIN, "iShares")).expectStatus().isNotFound();
    assertThat(count("security")).isZero();

    post(adminToken, etf(ETF_ISIN, "iShares")).expectStatus().isCreated();
    get(memberToken, ETF_ISIN).expectStatus().isOk();

    grantOnAccount(adminToken, memberId, cash.id(), AccessLevelValues.EDIT);
    post(memberToken, etf(EQUITY_ISIN, "Apple")).expectStatus().isCreated();
  }

  // --- helpers ------------------------------------------------------------------------------

  private SecurityCreation createAs(
      AuthenticatedUserPrincipal principal, CreateSecurityRequest request, CountDownLatch start)
      throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    try {
      start.await();
      return securityService.findOrCreate(request, principal);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static CreateSecurityRequest etf(String isin, String name) {
    return new CreateSecurityRequest(isin, name, "USD", "ETF", "EQUITY", "IE", "IE");
  }

  private int count(String table) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getInt(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID jdbcUuid(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as(sql).isTrue();
        return (UUID) resultSet.getObject(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private AuthenticatedUserPrincipal principalOfExistingWorkspace() {
    UUID userId = jdbcUuid("SELECT id FROM app_user WHERE email = ?", "admin@example.com");
    UUID workspaceId =
        jdbcUuid(
            "SELECT wm.workspace_id FROM workspace_member wm JOIN app_user u ON u.workspace_member_id"
                + " = wm.id WHERE u.id = ?",
            userId);
    return new AuthenticatedUserPrincipal(
        userId, "SYSTEM_ADMINISTRATOR", workspaceId, UUID.randomUUID());
  }

  // A second, fully independent workspace: its own member, user and (EDIT-able, as sole member)
  // account. The Personal Assets container comes from V19's trigger on workspace.
  private AuthenticatedUserPrincipal seedSecondWorkspaceWithAccount() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection, "INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", workspaceId);
      execute(
          connection,
          "INSERT INTO workspace_member (id, workspace_id, display_name) VALUES (?, ?, 'B')",
          memberId,
          workspaceId);
      execute(
          connection,
          "INSERT INTO app_user (id, email, password_hash, workspace_member_id) VALUES (?,"
              + " 'b@example.com', 'x', ?)",
          userId,
          memberId);
      UUID institutionId =
          jdbcUuid("SELECT id FROM financial_institution WHERE workspace_id = ?", workspaceId);
      execute(
          connection,
          "INSERT INTO account (id, workspace_id, financial_institution_id, account_type, name,"
              + " native_currency) VALUES (?, ?, ?, 'CASH', 'B cash', 'CHF')",
          UUID.randomUUID(),
          workspaceId,
          institutionId);
    }
    return new AuthenticatedUserPrincipal(userId, "STANDARD_USER", workspaceId, UUID.randomUUID());
  }

  private static void execute(Connection connection, String sql, Object... parameters)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private String adminWithAccount() {
    String token = bootstrapAdministrator();
    client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    return token;
  }

  private AccountSummaryResponse firstAccount(String token) {
    // The one cash account adminWithAccount created; looked up rather than threaded through.
    UUID id = jdbcUuid("SELECT id FROM account WHERE name = ?", "Everyday Checking");
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec post(String token, CreateSecurityRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private RestTestClient.ResponseSpec get(String token, String isin) {
    return client(token).get().uri("/api/v1/securities?isin=" + isin).exchange();
  }

  private void grantOnAccount(String token, UUID memberId, UUID accountId, String level) {
    client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                memberId, ScopeTypeValues.ACCOUNT, accountId, null, level))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private UUID createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    return jdbcUuid("SELECT workspace_member_id FROM app_user WHERE email = ?", email);
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

  private String bootstrapAdministrator() {
    return anonymousClient()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
