package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest.OwnerAllocation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-04-03, scoped to what this codebase can actually resolve today - see {@code
 * InstitutionSummaryResponse}'s own Javadoc. The DoD's "mixed asset/liability negative total"
 * scenario is proven separately, against synthetic data, by {@code
 * InstitutionSummaryServiceAggregationTest} (no liability-nature account type has an implemented
 * value source yet); this class proves the parts that are genuinely end-to-end today: aggregation
 * of resolvable ({@code CUSTOM_ASSET}) accounts, currency conversion via {@code FxRateService},
 * unresolvable accounts surfaced (not silently zeroed), the empty-container case (C7), and
 * per-account access filtering (a summary is a "show me what I can see" view, not a single
 * all-or-nothing gate - see the service's own Javadoc for why).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstitutionSummaryControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";

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
  @Autowired FxRateRepository fxRateRepository;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "sharing_grant",
              "account_ownership",
              "custom_asset_valuation",
              "account_custom_asset",
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

  // fx_rate is shared/global (no workspace_id, no RLS - unlike every table above), so it is not
  // wiped by the per-workspace cleanup loop; cleaned separately, same as FxRateServiceTest's own
  // @AfterEach, so one test's seeded rate can never collide with another's.
  @AfterEach
  void cleanFxRates() {
    fxRateRepository.deleteAll();
  }

  @Test
  void emptyInstitutionShowsAValidZeroSummaryNotAnError() {
    // C7: a container may be empty and remain valid.
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(token, "CHF");

    InstitutionSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
    assertThat(summary.totalLiabilities()).isEqualByComparingTo("0");
    assertThat(summary.netValue()).isEqualByComparingTo("0");
    assertThat(summary.hasUnresolvedValues()).isFalse();
    assertThat(summary.accounts()).isEmpty();
  }

  @Test
  void aResolvedCustomAssetInTheContainerCurrencyNeedsNoConversion() {
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(token, "CHF");
    AccountSummaryResponse account = createCustomAssetAccount(token, institution.id(), "CHF");
    recordValuation(token, account.id(), new BigDecimal("45000.00"));

    InstitutionSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.totalAssets()).isEqualByComparingTo("45000.00");
    assertThat(summary.netValue()).isEqualByComparingTo("45000.00");
    assertThat(summary.hasUnresolvedValues()).isFalse();
    assertThat(summary.accounts()).hasSize(1);
    InstitutionSummaryResponse.AccountLine line = summary.accounts().get(0);
    assertThat(line.valueResolvable()).isTrue();
    assertThat(line.nativeValue()).isEqualByComparingTo("45000.00");
    assertThat(line.convertedValue()).isEqualByComparingTo("45000.00");
    // No conversion was needed, so there is genuinely no rate to report.
    assertThat(line.fxRateUsed()).isNull();
    assertThat(line.fxRateDate()).isNull();
  }

  @Test
  void aResolvedCustomAssetInADifferentCurrencyIsConvertedAndTheRateIsInspectable() {
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(token, "CHF");
    AccountSummaryResponse account = createCustomAssetAccount(token, institution.id(), "USD");
    recordValuation(token, account.id(), new BigDecimal("1000.00"));
    LocalDate today = LocalDate.now();
    seedFxRate("USD", "CHF", today, new BigDecimal("0.9000000000"));

    InstitutionSummaryResponse summary = getSummary(token, institution.id());

    InstitutionSummaryResponse.AccountLine line = summary.accounts().get(0);
    assertThat(line.nativeValue()).isEqualByComparingTo("1000.00");
    assertThat(line.convertedValue()).isEqualByComparingTo("900.00");
    assertThat(line.fxRateUsed()).isEqualByComparingTo("0.9000000000");
    assertThat(line.fxRateDate()).isEqualTo(today);
    assertThat(line.carriedForward()).isFalse();
    assertThat(summary.totalAssets()).isEqualByComparingTo("900.00");
    assertThat(summary.hasCarriedForwardFxRate()).isFalse();
  }

  @Test
  void aCarriedForwardFxRateIsSurfacedAtTheHeadline() {
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(token, "CHF");
    AccountSummaryResponse account = createCustomAssetAccount(token, institution.id(), "USD");
    recordValuation(token, account.id(), new BigDecimal("1000.00"));
    // Only a rate from several days ago exists - the read contract carries it forward rather than
    // refusing (FR-CUR-012), and that must be visible at the headline (PR-011/FR-CON-007).
    seedFxRate("USD", "CHF", LocalDate.now().minusDays(5), new BigDecimal("0.9100000000"));

    InstitutionSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.hasCarriedForwardFxRate()).isTrue();
    assertThat(summary.accounts().get(0).carriedForward()).isTrue();
  }

  @Test
  void aResolvedAccountWithNoFxRateAtAllIsUnresolvedNotAFailedRequest() {
    // Regression: FxRateService.getConversionRate() 404s when no direct or chained rate exists at
    // all (not merely stale) - that must degrade to an unresolved line, the same as no valuation
    // ever having been recorded, never abort the whole summary request.
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(token, "CHF");
    AccountSummaryResponse account = createCustomAssetAccount(token, institution.id(), "USD");
    recordValuation(token, account.id(), new BigDecimal("1000.00"));
    // Deliberately no seedFxRate call - USD/CHF has no rate at all, not even a stale one.

    InstitutionSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.hasUnresolvedValues()).isTrue();
    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
    InstitutionSummaryResponse.AccountLine line = summary.accounts().get(0);
    assertThat(line.valueResolvable()).isFalse();
    // The native value is still known and shown - only the conversion into CHF is missing.
    assertThat(line.nativeValue()).isEqualByComparingTo("1000.00");
    assertThat(line.convertedValue()).isNull();
  }

  @Test
  void anUnresolvableAccountIsExcludedFromTotalsButStillListedForDrillDown() {
    // CASH has no implemented value source yet (no transaction ledger, EPIC 07) - it must appear
    // in the drill-down so a user can see why, not be silently treated as worth zero (PR-011).
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(token, "CHF");
    createCashAccount(token, institution.id(), "CHF");
    AccountSummaryResponse resolvedAccount =
        createCustomAssetAccount(token, institution.id(), "CHF");
    recordValuation(token, resolvedAccount.id(), new BigDecimal("500.00"));

    InstitutionSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.hasUnresolvedValues()).isTrue();
    assertThat(summary.totalAssets()).isEqualByComparingTo("500.00");
    assertThat(summary.accounts()).hasSize(2);
    assertThat(summary.accounts())
        .anySatisfy(
            line -> {
              assertThat(line.valueResolvable()).isFalse();
              assertThat(line.nativeValue()).isNull();
              assertThat(line.convertedValue()).isNull();
            });
  }

  @Test
  void aCustomAssetWithoutAnyValuationYetIsUnresolvedNotZero() {
    // Has the manualValuation capability but no custom_asset_valuation row recorded yet.
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(token, "CHF");
    createCustomAssetAccount(token, institution.id(), "CHF");

    InstitutionSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.hasUnresolvedValues()).isTrue();
    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
  }

  @Test
  void requestingAnUnknownInstitutionIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .get()
        .uri("/api/v1/institutions/" + UUID.randomUUID() + "/summary")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void aMemberWithNoAccessToAnyAccountStillGetsAValidButEmptySummary() {
    // The access-composition decision this story made explicitly: a summary always succeeds for
    // any workspace member (never the institution-level all-or-nothing 404 AccountController's
    // own endpoints use) - accounts the caller cannot see are silently dropped, the same as an
    // unresolvable value, rather than denying the whole request.
    String adminToken = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(adminToken, "CHF");
    AccountSummaryResponse account = createCustomAssetAccount(adminToken, institution.id(), "CHF");
    recordValuation(adminToken, account.id(), new BigDecimal("1000.00"));
    String bobToken = createAndLoginSecondMember(adminToken, "bob@example.com");

    InstitutionSummaryResponse summary = getSummary(bobToken, institution.id());

    assertThat(summary.accounts()).isEmpty();
    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
    assertThat(summary.hasUnresolvedValues()).isFalse();
  }

  @Test
  void aMemberWhoOwnsOneAccountSeesOnlyThatAccountInTheSummary() {
    String adminToken = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution = createInstitution(adminToken, "CHF");
    AccountSummaryResponse ownedByAdmin =
        createCustomAssetAccount(adminToken, institution.id(), "CHF");
    recordValuation(adminToken, ownedByAdmin.id(), new BigDecimal("1000.00"));
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com", PASSWORD);
    AccountSummaryResponse ownedByBob =
        createCustomAssetAccount(adminToken, institution.id(), "CHF");
    recordValuation(adminToken, ownedByBob.id(), new BigDecimal("2000.00"));
    assignOwnership(adminToken, ownedByBob.id(), bobMemberId);

    InstitutionSummaryResponse summary = getSummary(bobToken, institution.id());

    assertThat(summary.accounts()).hasSize(1);
    assertThat(summary.accounts().get(0).accountId()).isEqualTo(ownedByBob.id());
    assertThat(summary.totalAssets()).isEqualByComparingTo("2000.00");
  }

  private InstitutionSummaryResponse getSummary(String token, UUID institutionId) {
    return client(token)
        .get()
        .uri("/api/v1/institutions/" + institutionId + "/summary")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(InstitutionSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void seedFxRate(String base, String quote, LocalDate date, BigDecimal rate) {
    FxRate fxRate = new FxRate();
    fxRate.setBaseCurrency(base);
    fxRate.setQuoteCurrency(quote);
    fxRate.setRateDate(date);
    fxRate.setRate(rate);
    fxRate.setSource("MANUAL");
    fxRateRepository.save(fxRate);
  }

  private FinancialInstitutionSummaryResponse createInstitution(String token, String currency) {
    return client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                null, "Sparkasse", "DE", "BANK", null, null, currency))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(FinancialInstitutionSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createCustomAssetAccount(
      String token, UUID institutionId, String nativeCurrency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                institutionId,
                "Vintage Car",
                "CUSTOM_ASSET",
                nativeCurrency,
                null,
                null,
                null,
                null,
                null,
                "VEHICLE"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createCashAccount(
      String token, UUID institutionId, String nativeCurrency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                institutionId,
                "Current Account",
                "CASH",
                nativeCurrency,
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void recordValuation(String token, UUID accountId, BigDecimal value) {
    client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateCustomAssetValuationRequest(LocalDate.now(), value))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private void assignOwnership(String token, UUID accountId, UUID memberId) {
    client(token)
        .put()
        .uri("/api/v1/accounts/" + accountId + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberId, BigDecimal.ONE))))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private UUID createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
    return workspaceMemberIdForEmail(email);
  }

  private String createAndLoginSecondMember(String adminToken, String email) {
    createSecondMember(adminToken, email);
    return login(email, PASSWORD);
  }

  private UUID workspaceMemberIdForEmail(String email) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("app_user with email " + email).isTrue();
        return (UUID) resultSet.getObject("workspace_member_id");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String login(String email, String password) {
    LoginResponse response =
        anonymousClient()
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest(email, password))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(LoginResponse.class)
            .returnResult()
            .getResponseBody();
    return response.tokens().accessToken();
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
