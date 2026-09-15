package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CustomAssetValuationResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.service.CustomAssetValuationService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-05-05: dated manual valuations for {@code CUSTOM_ASSET} accounts. The DoD's test is {@link
 * #listingValuationsReturnsThemNewestFirst} (enters two valuations at different dates, asserts
 * correct historical attribution); {@link
 * #theValuationAsOfADateIsTheLatestOneOnOrBeforeItNeverAnInterpolation} is AC #2's "no silent
 * interpolation" requirement (PR-011), verified directly against {@link
 * CustomAssetValuationService#getValuationAsOf} since no net-worth/reporting feature exists yet to
 * expose it through an endpoint (the same gap US-05-03/#68 already documented).
 *
 * <p>{@code currency} is deliberately not settable through the API ({@link
 * CreateCustomAssetValuationRequest}'s own Javadoc), so {@link
 * #recordingAValuationWithAMismatchedCurrencyOnACorrectlyTypedAccountIsRejected} and {@link
 * #theTypeGuardFiresBeforeTheCurrencyGuardWhenARowViolatesBoth} both insert directly via JDBC the
 * way the service never does, to verify V26/V27's currency guard - alone, and in combination with
 * the type guard - still rejects correctly. Neither goes through {@code GlobalExceptionHandler}: a
 * request the service itself builds can no longer trigger either guard, so the
 * currency-mismatch-to-409 translation is unit-tested only (see {@code
 * GlobalExceptionHandlerTest}'s own note on this), not exercised end-to-end any more.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomAssetValuationControllerTest {

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
  @Autowired CustomAssetValuationService customAssetValuationService;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // See InstitutionControllerTest's identical method for why this is plain per-table DELETEs,
    // not TRUNCATE ... CASCADE.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
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

  @Test
  void recordingAValuationCreatesItAndReturnsItCreated() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createCustomAssetAccount(token, "CHF");

    CustomAssetValuationResponse created =
        recordValuation(
            token,
            account.id(),
            new CreateCustomAssetValuationRequest(
                LocalDate.of(2026, 1, 15), new BigDecimal("50000.0000")));

    assertThat(created.accountId()).isEqualTo(account.id());
    assertThat(created.valuationDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(created.value()).isEqualByComparingTo("50000.0000");
    // Not client-supplied - derived from the account's own native_currency.
    assertThat(created.currency()).isEqualTo("CHF");
    assertThat(created.source()).isEqualTo("MANUAL");
  }

  @Test
  void listingValuationsReturnsThemNewestFirst() {
    // DoD: enters two valuations at different dates, asserts correct historical attribution.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createCustomAssetAccount(token, "CHF");
    recordValuation(
        token,
        account.id(),
        new CreateCustomAssetValuationRequest(
            LocalDate.of(2026, 1, 1), new BigDecimal("40000.0000")));
    recordValuation(
        token,
        account.id(),
        new CreateCustomAssetValuationRequest(
            LocalDate.of(2026, 6, 1), new BigDecimal("45000.0000")));

    List<CustomAssetValuationResponse> valuations =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + account.id() + "/valuations")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<CustomAssetValuationResponse>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(valuations).hasSize(2);
    assertThat(valuations.get(0).valuationDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(valuations.get(0).value()).isEqualByComparingTo("45000.0000");
    assertThat(valuations.get(1).valuationDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(valuations.get(1).value()).isEqualByComparingTo("40000.0000");
  }

  @Test
  void theValuationAsOfADateIsTheLatestOneOnOrBeforeItNeverAnInterpolation() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createCustomAssetAccount(token, "CHF");
    recordValuation(
        token,
        account.id(),
        new CreateCustomAssetValuationRequest(
            LocalDate.of(2026, 1, 1), new BigDecimal("40000.0000")));
    recordValuation(
        token,
        account.id(),
        new CreateCustomAssetValuationRequest(
            LocalDate.of(2026, 6, 1), new BigDecimal("45000.0000")));

    // Between the two known points: the earlier valuation carries forward, not a straight-line
    // interpolated ~42500.
    Optional<CustomAssetValuationResponse> midYear =
        customAssetValuationService.getValuationAsOf(account.id(), LocalDate.of(2026, 3, 15));
    assertThat(midYear).isPresent();
    assertThat(midYear.get().value()).isEqualByComparingTo("40000.0000");

    Optional<CustomAssetValuationResponse> afterBoth =
        customAssetValuationService.getValuationAsOf(account.id(), LocalDate.of(2026, 12, 31));
    assertThat(afterBoth).isPresent();
    assertThat(afterBoth.get().value()).isEqualByComparingTo("45000.0000");
  }

  @Test
  void anAccountWithNoValuationHasNoCurrentValueRatherThanZero() {
    // PR-011/data-quality behaviour: unknown, never silently zero.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createCustomAssetAccount(token, "CHF");

    Optional<CustomAssetValuationResponse> asOf =
        customAssetValuationService.getValuationAsOf(account.id(), LocalDate.of(2026, 1, 1));

    assertThat(asOf).isEmpty();
  }

  @Test
  void recordingASecondValuationForTheSameDateIsRejectedWithAStructuredConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createCustomAssetAccount(token, "CHF");
    recordValuation(
        token,
        account.id(),
        new CreateCustomAssetValuationRequest(
            LocalDate.of(2026, 1, 1), new BigDecimal("40000.0000")));

    client(token)
        .post()
        .uri("/api/v1/accounts/" + account.id() + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateCustomAssetValuationRequest(
                LocalDate.of(2026, 1, 1), new BigDecimal("41000.0000")))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void recordingAValuationForANonCustomAssetAccountIsRejectedWithAStructuredConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cashAccount = createCashAccount(token, "CHF");

    client(token)
        .post()
        .uri("/api/v1/accounts/" + cashAccount.id() + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateCustomAssetValuationRequest(
                LocalDate.of(2026, 1, 1), new BigDecimal("100.0000")))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void recordingAValuationForAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateCustomAssetValuationRequest(
                LocalDate.of(2026, 1, 1), new BigDecimal("100.0000")))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void listingValuationsForAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .get()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/valuations")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void recordingAValuationWithAMismatchedCurrencyOnACorrectlyTypedAccountIsRejected()
      throws Exception {
    // Isolates the currency guard alone (correct CUSTOM_ASSET type, wrong currency) - the type
    // guard never fires here, unlike the combined-violation test below - so this is the only
    // remaining check that custom_asset_valuation_guard_2_currency's own predicate/message are
    // still correct on their own, now that the service can never construct this input itself.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createCustomAssetAccount(token, "CHF");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            insertValuationStatement(connection, account.id(), LocalDate.of(2026, 1, 1), "EUR")) {
      assertThatThrownBy(statement::executeUpdate)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("custom_asset_valuation_currency_mismatch");
    }
  }

  @Test
  void theTypeGuardFiresBeforeTheCurrencyGuardWhenARowViolatesBoth() throws Exception {
    // V27: PostgreSQL fires same-timing triggers alphabetically, so the two triggers were renamed
    // to guarantee the type guard (the more fundamental check) fires first. The service can never
    // actually construct a row violating both - currency isn't client input any more - so this
    // reaches the DB directly the way a future non-service caller of this table might, to verify
    // the ordering fix itself rather than anything the API surface can still exercise.
    String token = bootstrapAdministrator();
    AccountSummaryResponse cashAccount = createCashAccount(token, "CHF");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            insertValuationStatement(
                connection, cashAccount.id(), LocalDate.of(2026, 1, 1), "EUR")) {
      assertThatThrownBy(statement::executeUpdate)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("account_extension_type_mismatch")
          .hasMessageNotContaining("custom_asset_valuation_currency_mismatch");
    }
  }

  private PreparedStatement insertValuationStatement(
      Connection connection, UUID accountId, LocalDate valuationDate, String currency)
      throws SQLException {
    PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO custom_asset_valuation (account_id, valuation_date, value, currency)"
                + " VALUES (?, ?, ?, ?)");
    statement.setObject(1, accountId);
    statement.setObject(2, valuationDate);
    statement.setBigDecimal(3, new BigDecimal("100.0000"));
    statement.setString(4, currency);
    return statement;
  }

  private CustomAssetValuationResponse recordValuation(
      String token, UUID accountId, CreateCustomAssetValuationRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(CustomAssetValuationResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createCustomAssetAccount(String token, String nativeCurrency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null,
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

  private AccountSummaryResponse createCashAccount(String token, String nativeCurrency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null,
                "Everyday Checking",
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

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SetupAdministratorRequest(
                "admin@example.com", "correct-horse-battery-staple", "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
