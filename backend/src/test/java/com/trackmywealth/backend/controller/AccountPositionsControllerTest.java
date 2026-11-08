package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountPositionResponse;
import com.trackmywealth.backend.dto.AccountPositionsResponse;
import com.trackmywealth.backend.dto.AccountPositionsSource;
import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.SnapshotHoldingRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.testsupport.AccountRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
 * US-15-05 (#478): a depot's positions from its newest holdings snapshot. One test per acceptance
 * criterion of the issue, plus the authorization rules (#389, FR-TEN-007).
 *
 * <p>The manual snapshot API takes no reported value, price or currency (those come from a holdings
 * import), so the tests set them on the stored rows directly.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountPositionsControllerTest {

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
              "fx_rate",
              "snapshot_holding",
              "account_snapshot",
              "security_field_provenance",
              "security_asset_class_weight",
              "security_identifier",
              "security",
              "account_securities",
              "account",
              "admin_audit_log",
              "user_session",
              "refresh_token",
              "authorization_denial_log",
              "app_user",
              "workspace_member",
              "portfolio",
              "workspace")) {
        statement.execute("DELETE FROM " + table);
      }
    }
  }

  // AC 1 and 2: three holdings, largest first, from the 30 Sep snapshot behind a newer
  // balance-only one. AC 3: a holding without a cost basis.
  @Test
  void positionsComeFromLatestHoldingsSnapshotNotNewerBalanceOnlySnapshot() throws SQLException {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");
    SecurityResponse first = createSecurity(token, "US9220427424", "Vanguard Total World");
    SecurityResponse second = createSecurity(token, "CH0038863350", "Nestle");
    SecurityResponse third = createSecurity(token, "IE00B4L5Y983", "iShares Core MSCI World");
    LocalDate holdingsDate = today().minusDays(2);
    AccountSnapshotResponse snapshot =
        recordOk(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(
                holdingsDate,
                null,
                List.of(
                    holding(first.id(), "5", "100"),
                    holding(second.id(), "2", null),
                    holding(third.id(), "3", "210"))));
    execute(
        "UPDATE snapshot_holding SET reported_value = CASE security_id"
            + " WHEN ? THEN 120 WHEN ? THEN 30 WHEN ? THEN 240 END,"
            + " reported_price = 10, reported_currency = 'CHF'"
            + " WHERE snapshot_id = ?",
        first.id(),
        second.id(),
        third.id(),
        snapshot.id());
    recordOk(token, depot.id(), balanceOnly(today(), "390"));

    AccountPositionsResponse positions = positions(token, depot.id());

    assertThat(positions.accountId()).isEqualTo(depot.id());
    assertThat(positions.currency()).isEqualTo("CHF");
    assertThat(positions.asOf()).isEqualTo(holdingsDate);
    assertThat(positions.source()).isEqualTo(AccountPositionsSource.HOLDINGS_SNAPSHOT);
    assertThat(positions.complete()).isTrue();
    assertThat(positions.positions())
        .extracting(AccountPositionResponse::securityId)
        .containsExactly(third.id(), first.id(), second.id());
    AccountPositionResponse largest = positions.positions().get(0);
    assertThat(largest.displayName()).isEqualTo("iShares Core MSCI World");
    assertThat(largest.isin()).isEqualTo("IE00B4L5Y983");
    assertThat(largest.quantity()).isEqualByComparingTo("3");
    assertThat(largest.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(largest.unrealisedGain()).isEqualByComparingTo("30");
    assertThat(largest.unrealisedGainPercent()).isEqualByComparingTo("14.29");
    AccountPositionResponse withoutCostBasis = positions.positions().get(2);
    assertThat(withoutCostBasis.value()).isEqualByComparingTo("30");
    assertThat(withoutCostBasis.valueInAccountCurrency()).isEqualByComparingTo("30");
    assertThat(withoutCostBasis.costBasis()).isNull();
    assertThat(withoutCostBasis.unrealisedGain()).isNull();
    assertThat(withoutCostBasis.unrealisedGainPercent()).isNull();
  }

  // AC 4, plus a holding without a reported value.
  @Test
  void estimatedCostBasisIsMarkedEstimatedAndMissingReportedValuesAreUnknown() throws SQLException {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");
    SecurityResponse first = createSecurity(token, "US9220427424", "Vanguard Total World");
    SecurityResponse second = createSecurity(token, "CH0038863350", "Nestle");
    AccountSnapshotResponse snapshot =
        recordOk(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(
                today(),
                null,
                List.of(
                    new SnapshotHoldingRequest(
                        first.id(), BigDecimal.ONE, new BigDecimal("50"), true),
                    holding(second.id(), "1", null))));
    execute(
        "UPDATE snapshot_holding SET reported_value = 55, reported_currency = 'CHF'"
            + " WHERE snapshot_id = ? AND security_id = ?",
        snapshot.id(),
        first.id());

    AccountPositionsResponse result = positions(token, depot.id());

    assertThat(result.complete()).isFalse();
    assertThat(result.positions()).hasSize(2);
    AccountPositionResponse estimated = result.positions().get(0);
    assertThat(estimated.valueStatus()).isEqualTo(ValueStatus.ESTIMATED);
    assertThat(estimated.costBasisEstimated()).isTrue();
    assertThat(estimated.unrealisedGain()).isEqualByComparingTo("5");
    AccountPositionResponse unknown = result.positions().get(1);
    assertThat(unknown.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(unknown.value()).isNull();
    assertThat(unknown.valueInAccountCurrency()).isNull();
  }

  // AC 5. The cost basis has no currency of its own, so a USD holding in a CHF depot gets no
  // gain rather than one that mixes currencies (FR-PERF-018).
  @Test
  void foreignCurrencyHoldingUsesSnapshotDateFxRateAndGetsNoGain() throws SQLException {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "CHF Depot", "SECURITIES");
    SecurityResponse security = createSecurity(token, "US9220427424", "Vanguard Total World");
    LocalDate asOf = today().minusDays(2);
    AccountSnapshotResponse snapshot =
        recordOk(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(
                asOf, null, List.of(holding(security.id(), "1", "75"))));
    execute(
        "UPDATE snapshot_holding SET reported_value = 100, reported_price = 100,"
            + " reported_currency = 'USD' WHERE snapshot_id = ?",
        snapshot.id());
    saveFxRate(asOf, "0.90");
    saveFxRate(today(), "0.50");

    AccountPositionResponse position = positions(token, depot.id()).positions().get(0);

    assertThat(position.priceCurrency()).isEqualTo("USD");
    assertThat(position.price()).isEqualByComparingTo("100");
    assertThat(position.value()).isEqualByComparingTo("100");
    assertThat(position.valueInAccountCurrency()).isEqualByComparingTo("90");
    assertThat(position.valueStatus()).isEqualTo(ValueStatus.CURRENT);
    assertThat(position.costBasis()).isEqualByComparingTo("75");
    assertThat(position.unrealisedGain()).isNull();
    assertThat(position.unrealisedGainPercent()).isNull();
  }

  @Test
  void missingFxRateMarksAReportedHoldingUnknown() throws SQLException {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "CHF Depot", "SECURITIES");
    SecurityResponse security = createSecurity(token, "US9220427424", "Vanguard Total World");
    AccountSnapshotResponse snapshot =
        recordOk(
            token,
            depot.id(),
            new RecordAccountSnapshotRequest(
                today(), null, List.of(holding(security.id(), "1", null))));
    execute(
        "UPDATE snapshot_holding SET reported_value = 100, reported_currency = 'USD'"
            + " WHERE snapshot_id = ?",
        snapshot.id());

    AccountPositionsResponse result = positions(token, depot.id());

    assertThat(result.complete()).isFalse();
    assertThat(result.positions().get(0).value()).isEqualByComparingTo("100");
    assertThat(result.positions().get(0).valueInAccountCurrency()).isNull();
    assertThat(result.positions().get(0).valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
  }

  @Test
  void aDepotWithoutHoldingsSnapshotHasAnEmptyList() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "Depot", "SECURITIES");
    recordOk(token, depot.id(), balanceOnly(today(), "100"));

    AccountPositionsResponse empty = positions(token, depot.id());

    assertThat(empty.asOf()).isNull();
    assertThat(empty.positions()).isEmpty();
    assertThat(empty.complete()).isTrue();
  }

  // AC 6, first half.
  @Test
  void aCurrentAccountCannotHoldPositions() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "Cash", "CASH");

    client(token)
        .get()
        .uri("/api/v1/accounts/" + cash.id() + "/positions")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("POSITIONS_NOT_APPLICABLE");
  }

  // AC 6, second half: another workspace's depot is the audited 404 of an unknown one, and its
  // holdings are never read.
  @Test
  void anotherWorkspacesDepotIsNotFoundLikeAnUnknownOne() throws SQLException {
    String token = bootstrapAdministrator();
    UUID foreign = foreignDepot();

    for (UUID id : List.of(foreign, UUID.randomUUID())) {
      client(token)
          .get()
          .uri("/api/v1/accounts/" + id + "/positions")
          .exchange()
          .expectStatus()
          .isNotFound();
    }
    assertThat(deniedAccountLookups(foreign)).isPositive();
  }

  // FR-TEN-007: once a Standard User joins, the administrator no longer sees financial data.
  @Test
  void theAdministratorCannotReadAMembersPositions() {
    String adminToken = bootstrapAdministrator();
    createSecondMember(adminToken, "first@example.com");
    String memberToken = login("first@example.com");
    AccountSummaryResponse depot = createAccount(memberToken, "Depot", "SECURITIES");

    assertThat(positions(memberToken, depot.id()).positions()).isEmpty();
    client(adminToken)
        .get()
        .uri("/api/v1/accounts/" + depot.id() + "/positions")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  // --- Helpers ------------------------------------------------------------------------------

  private AccountPositionsResponse positions(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/positions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountPositionsResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static RecordAccountSnapshotRequest balanceOnly(LocalDate date, String balance) {
    return new RecordAccountSnapshotRequest(date, new BigDecimal(balance), List.of());
  }

  private static SnapshotHoldingRequest holding(
      UUID securityId, String quantity, String costBasis) {
    return new SnapshotHoldingRequest(
        securityId,
        new BigDecimal(quantity),
        costBasis == null ? null : new BigDecimal(costBasis),
        null);
  }

  private void saveFxRate(LocalDate date, String usdToChf) {
    FxRate rate = new FxRate();
    rate.setBaseCurrency("USD");
    rate.setQuoteCurrency("CHF");
    rate.setRateDate(date);
    rate.setRate(new BigDecimal(usdToChf));
    rate.setSource("ECB"); // app.fx.default-source
    fxRateRepository.saveAndFlush(rate);
  }

  private AccountSnapshotResponse recordOk(
      String token, UUID accountId, RecordAccountSnapshotRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSnapshotResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createAccount(String token, String name, String accountType) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, accountType, "CHF").build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private SecurityResponse createSecurity(String token, String isin, String displayName) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                isin, displayName, "CHF", "EQUITY", "EQUITY", null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
  }

  // A depot in a second workspace, written past the API (and RLS) as another tenant's data.
  private UUID foreignDepot() throws SQLException {
    UUID workspace = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Other')", workspace);
    execute(
        "INSERT INTO account (id, workspace_id, portfolio_id, account_type, name, native_currency)"
            + " SELECT ?, ?, id, 'SECURITIES', 'Other depot', 'CHF' FROM portfolio"
            + " WHERE workspace_id = ? AND is_personal_assets_default",
        account,
        workspace,
        workspace);
    return account;
  }

  private long deniedAccountLookups(UUID accountId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM authorization_denial_log"
                    + " WHERE requested_entity_type = 'Account' AND requested_entity_id = ?")) {
      statement.setObject(1, accountId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  // The zone app.business-zone defaults to, as in TransactionControllerTest.
  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
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
