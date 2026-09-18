package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.ReassignAccountInstitutionRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * US-05-01: create an account of every supported type under a container, applying the correct
 * capability defaults and (where the type has one) the matching extension table row - the DoD's
 * parameterized test is {@link #createsCorrectCapabilitiesAndExtensionRowForEveryAccountType}.
 *
 * <p>US-05-02: {@code account_type} and {@code native_currency} are immutable after creation - the
 * DoD's tests are {@link #changingAccountTypeIsRejectedWithAStructuredConflict} and {@link
 * #changingNativeCurrencyIsRejectedWithAStructuredConflict}.
 *
 * <p>US-05-03: archive and restore an account - the DoD's test is {@link
 * #archivingThenRestoringWithinTheWindowReturnsTheAccountToActive}; {@link
 * #restoringAfterTheWindowIsRejectedWithAStructuredConflict} covers FR-LIF-006's 30-day cutoff.
 *
 * <p>US-04-02: {@code institution_type} never restricts which {@code account_type} may be added
 * under it - the DoD's test is {@link #everyAccountTypeCanBeAddedUnderAPensionProviderInstitution}.
 * The architecture-test half of the same story ({@code
 * only_institution_service_reads_institution_type}) lives in {@code ArchitectureTest}, not here,
 * matching where every other such rule in this codebase lives.
 *
 * <p>US-04-04: reassign an account to a different institution without losing history - the DoD's
 * test is {@link #reassigningInstitutionMovesTheAccountAndBothSummariesRecomputeCorrectly}; {@link
 * #reassigningADeletedAccountIsRejectedWithAStructuredConflict} covers FR-STA-001's terminal
 * status. The "requires EDIT on the destination institution" half of the story's
 * Authorization/privacy line lives in {@code SharingGrantControllerTest} instead, alongside every
 * other {@code AccessControlService}-gated endpoint that needs a second workspace member to test.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerTest {

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

  @BeforeEach
  void cleanDatabase() throws Exception {
    // See InstitutionControllerTest's identical method for why this is plain per-table DELETEs,
    // not TRUNCATE ... CASCADE - the latter transitively wipes the shared institution_catalogue
    // seed data via app_user -> reference_package -> institution_catalogue.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "account_securities",
              "account_credit_card",
              "account_mortgage",
              "account_loan",
              "account_pension",
              "account_vested_benefits",
              "account_custom_asset",
              "account_ownership",
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

  private record ExpectedCapabilities(
      boolean holdsPositions,
      boolean hasTransactions,
      boolean hasStatementCycle,
      boolean hasAmortisation,
      boolean hasContributionLimit,
      boolean discretionary,
      boolean manualValuation,
      String nature,
      String extensionTable) {}

  static Stream<Arguments> accountTypeCases() {
    return Stream.of(
        Arguments.of(
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null),
            new ExpectedCapabilities(
                false, true, false, false, false, false, false, "ASSET", null)),
        Arguments.of(
            new CreateAccountRequest(
                null, "Savings", "SAVINGS", "CHF", null, null, null, null, null, null),
            new ExpectedCapabilities(
                false, true, false, false, false, false, false, "ASSET", null)),
        Arguments.of(
            new CreateAccountRequest(
                null, "Depot", "SECURITIES", "CHF", null, null, null, null, null, null),
            new ExpectedCapabilities(
                true, true, false, false, false, false, false, "ASSET", "account_securities")),
        Arguments.of(
            new CreateAccountRequest(
                null,
                "Discretionary Mandate",
                "MANAGED_MANDATE",
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null),
            new ExpectedCapabilities(
                true, true, false, false, false, true, false, "ASSET", "account_securities")),
        Arguments.of(
            new CreateAccountRequest(
                null, "Pillar 3a", "PENSION", "CHF", null, null, null, null, "CH_PILLAR_3A", null),
            new ExpectedCapabilities(
                false, true, false, false, true, false, false, "ASSET", "account_pension")),
        Arguments.of(
            new CreateAccountRequest(
                null,
                "Vested Benefits",
                "VESTED_BENEFITS",
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null),
            new ExpectedCapabilities(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                "ASSET",
                "account_vested_benefits")),
        Arguments.of(
            new CreateAccountRequest(
                null, "Visa Card", "CREDIT_CARD", "CHF", null, null, null, null, null, null),
            new ExpectedCapabilities(
                false, true, true, false, false, false, false, "LIABILITY", "account_credit_card")),
        Arguments.of(
            new CreateAccountRequest(
                null,
                "Home Mortgage",
                "MORTGAGE",
                "CHF",
                null,
                null,
                BigDecimal.valueOf(500000),
                BigDecimal.valueOf(1.5),
                null,
                null),
            new ExpectedCapabilities(
                false, true, false, true, false, false, false, "LIABILITY", "account_mortgage")),
        Arguments.of(
            new CreateAccountRequest(
                null,
                "Personal Loan",
                "LOAN",
                "CHF",
                null,
                null,
                BigDecimal.valueOf(10000),
                null,
                null,
                null),
            new ExpectedCapabilities(
                false, true, false, true, false, false, false, "LIABILITY", "account_loan")),
        Arguments.of(
            new CreateAccountRequest(
                null, "Crypto Wallet", "CRYPTO", "CHF", null, null, null, null, null, null),
            new ExpectedCapabilities(true, true, false, false, false, false, false, "ASSET", null)),
        Arguments.of(
            new CreateAccountRequest(
                null,
                "Family Home",
                "CUSTOM_ASSET",
                "CHF",
                null,
                null,
                null,
                null,
                null,
                "REAL_ESTATE"),
            new ExpectedCapabilities(
                false, false, false, false, false, false, true, "ASSET", "account_custom_asset")));
  }

  @ParameterizedTest
  @MethodSource("accountTypeCases")
  void createsCorrectCapabilitiesAndExtensionRowForEveryAccountType(
      CreateAccountRequest request, ExpectedCapabilities expected) throws Exception {
    String token = bootstrapAdministrator();

    AccountSummaryResponse created = createAccount(token, request);

    assertThat(created.accountType()).isEqualTo(request.accountType());
    assertThat(created.nature()).isEqualTo(expected.nature());
    assertThat(created.holdsPositions()).isEqualTo(expected.holdsPositions());
    assertThat(created.hasTransactions()).isEqualTo(expected.hasTransactions());
    assertThat(created.hasStatementCycle()).isEqualTo(expected.hasStatementCycle());
    assertThat(created.hasAmortisation()).isEqualTo(expected.hasAmortisation());
    assertThat(created.hasContributionLimit()).isEqualTo(expected.hasContributionLimit());
    assertThat(created.discretionary()).isEqualTo(expected.discretionary());
    assertThat(created.manualValuation()).isEqualTo(expected.manualValuation());

    for (String table :
        List.of(
            "account_securities",
            "account_credit_card",
            "account_mortgage",
            "account_loan",
            "account_pension",
            "account_vested_benefits",
            "account_custom_asset")) {
      boolean exists = extensionRowExists(table, created.id());
      if (table.equals(expected.extensionTable())) {
        assertThat(exists)
            .as("expected a %s row for %s".formatted(table, request.accountType()))
            .isTrue();
      } else {
        assertThat(exists)
            .as("unexpected %s row for %s".formatted(table, request.accountType()))
            .isFalse();
      }
    }
  }

  @Test
  void pensionHoldsPositionsOverrideIsHonored() {
    // US-05-04's own example: a VIAC-style Pillar 3a holds positions, unlike PostFinance's - not
    // a fixed type default, so this must be settable per account.
    String token = bootstrapAdministrator();

    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null,
                "VIAC Pillar 3a",
                "PENSION",
                "CHF",
                true,
                null,
                null,
                null,
                "CH_PILLAR_3A",
                null));

    assertThat(created.holdsPositions()).isTrue();
  }

  @Test
  void occupationalPensionSchemesAreMarkedOccupationalAndOthersAreNot() throws Exception {
    // FR-ACC-030: is_occupational is inherent to which scheme is chosen (CH Pillar 2 / DE bAV are
    // occupational-by-definition), not a per-account override like holdsPositions above.
    String token = bootstrapAdministrator();

    AccountSummaryResponse occupational =
        createAccount(
            token,
            new CreateAccountRequest(
                null,
                "Company Pension",
                "PENSION",
                "CHF",
                null,
                null,
                null,
                null,
                "CH_PILLAR_2_VESTED_BENEFITS",
                null));
    AccountSummaryResponse nonOccupational =
        createAccount(
            token,
            new CreateAccountRequest(
                null,
                "Private Pillar 3a",
                "PENSION",
                "CHF",
                null,
                null,
                null,
                null,
                "CH_PILLAR_3A",
                null));

    assertThat(isOccupational(occupational.id())).isTrue();
    assertThat(isOccupational(nonOccupational.id())).isFalse();
  }

  @Test
  void creditCardBillingCurrencyDefaultsToNativeCurrencyWhenOmitted() throws Exception {
    String token = bootstrapAdministrator();

    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Amex", "CREDIT_CARD", "USD", null, null, null, null, null, null));

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT billing_currency FROM account_credit_card WHERE account_id = ?")) {
      statement.setObject(1, created.id());
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("billing_currency")).isEqualTo("USD");
      }
    }
  }

  @Test
  void mortgageWithoutOriginalPrincipalIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null,
                "Underspecified Mortgage",
                "MORTGAGE",
                "CHF",
                null,
                null,
                null,
                BigDecimal.valueOf(1.5),
                null,
                null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void pensionWithoutSchemeIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, "Nameless Pension", "PENSION", "CHF", null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void omittingFinancialInstitutionIdDefaultsToThePersonalAssetsContainer() throws Exception {
    String token = bootstrapAdministrator();

    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Unassigned Cash", "CASH", "CHF", null, null, null, null, null, null));

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT fi.is_personal_assets_default FROM account a "
                    + "JOIN financial_institution fi ON fi.id = a.financial_institution_id "
                    + "WHERE a.id = ?")) {
      statement.setObject(1, created.id());
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBoolean("is_personal_assets_default")).isTrue();
      }
    }
  }

  @Test
  void extensionTypeGuardRejectsAMismatchedExtensionRowRegardlessOfApplicationLogic()
      throws Exception {
    // AC #2: a defensive/negative test proving trg_extension_type_guard (V5) rejects this at the
    // database level, independent of what AccountService does - a CREDIT_CARD account must never
    // be able to acquire an account_mortgage row.
    String token = bootstrapAdministrator();
    AccountSummaryResponse creditCard =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Visa Card", "CREDIT_CARD", "CHF", null, null, null, null, null, null));

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO account_mortgage (account_id, original_principal,"
                    + " interest_rate_percent) VALUES (?, 100000, 1.0)")) {
      statement.setObject(1, creditCard.id());
      org.assertj.core.api.Assertions.assertThatThrownBy(statement::executeUpdate)
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("account_extension_type_mismatch");
    }
  }

  @Test
  void anonymousRequestIsUnauthorized() {
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, "Nope", "CASH", "CHF", null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void creatingAnAccountUnderAnUnknownInstitutionIdIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                UUID.randomUUID(), "Orphan", "CASH", "CHF", null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void changingAccountTypeIsRejectedWithAStructuredConflict() {
    // AC #1 / DoD: the DB's trg_account_type_immutable (V4) rejection must surface as a clean
    // 409, referencing FR-ACC-005, never a raw 500.
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));

    client(token)
        .put()
        .uri("/api/v1/accounts/" + created.id())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new UpdateAccountRequest("Everyday Checking", "SAVINGS", "CHF", null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .isEqualTo(
            "An account's type cannot be changed after creation (FR-ACC-005/G5). To convert this"
                + " account, archive it and create a new one with the correct type.");
  }

  @Test
  void changingNativeCurrencyIsRejectedWithAStructuredConflict() {
    // Same immutability guarantee extended to native_currency (V24) - not explicit in FR-ACC-002
    // itself, but the same historical-reinterpretation risk as account_type (US-05-02).
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));

    client(token)
        .put()
        .uri("/api/v1/accounts/" + created.id())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateAccountRequest("Everyday Checking", "CASH", "EUR", null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .isEqualTo(
            "An account's currency cannot be changed after creation (FR-ACC-002). To convert this"
                + " account, archive it and create a new one with the correct currency.");
  }

  @Test
  void updateAppliesMutableFieldChanges() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));

    AccountSummaryResponse updated =
        client(token)
            .put()
            .uri("/api/v1/accounts/" + created.id())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new UpdateAccountRequest(
                    "Renamed Checking",
                    "CASH",
                    "CHF",
                    "**** 1234",
                    "CH",
                    LocalDate.of(2020, 1, 1),
                    null))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(updated.name()).isEqualTo("Renamed Checking");
    assertThat(updated.accountType()).isEqualTo("CASH");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT identifier_masked, jurisdiction, opened_at FROM account WHERE id = ?")) {
      statement.setObject(1, created.id());
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("identifier_masked")).isEqualTo("**** 1234");
        assertThat(rs.getString("jurisdiction")).isEqualTo("CH");
        assertThat(rs.getObject("opened_at", LocalDate.class)).isEqualTo(LocalDate.of(2020, 1, 1));
      }
    }
  }

  @Test
  void updatingAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .put()
        .uri("/api/v1/accounts/" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateAccountRequest("Name", "CASH", "CHF", null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void archivingThenRestoringWithinTheWindowReturnsTheAccountToActive() {
    // DoD: archives, verifies status/archivedAt, then restores within the window.
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));

    AccountSummaryResponse archived =
        client(token)
            .post()
            .uri("/api/v1/accounts/" + created.id() + "/archive")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(archived.status()).isEqualTo("ARCHIVED");
    assertThat(archived.archivedAt()).isNotNull();

    AccountSummaryResponse restored =
        client(token)
            .post()
            .uri("/api/v1/accounts/" + created.id() + "/restore")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(restored.status()).isEqualTo("ACTIVE");
    assertThat(restored.archivedAt()).isNull();
  }

  @Test
  void restoringAfterTheWindowIsRejectedWithAStructuredConflict() throws Exception {
    // FR-LIF-006: the 30-day restore window is enforced by the API itself, not left to the UI to
    // stop offering the button. Backdates archived_at directly (there's no way to fast-forward 30
    // real days), mirroring how this test class already reaches into the DB for state the API
    // itself has no way to set up (e.g. isOccupational below).
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));
    client(token).post().uri("/api/v1/accounts/" + created.id() + "/archive").exchange();
    backdateArchivedAt(created.id(), 31);

    client(token)
        .post()
        .uri("/api/v1/accounts/" + created.id() + "/restore")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void archivingAnAlreadyArchivedAccountIsRejectedWithAStructuredConflict() {
    // FR-STA-001 lists ACTIVE -> ARCHIVED, not ARCHIVED -> ARCHIVED - transitions not listed are
    // prohibited.
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));
    client(token).post().uri("/api/v1/accounts/" + created.id() + "/archive").exchange();

    client(token)
        .post()
        .uri("/api/v1/accounts/" + created.id() + "/archive")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void archivingADeletedAccountIsRejectedWithAStructuredConflict() throws Exception {
    // FR-STA-001: DELETED is terminal - reachable from ACTIVE only, reachable from nowhere once
    // there. There's no delete-account endpoint yet (that's its own future story), so this sets
    // status directly via SQL to exercise the guard against whatever eventually produces a
    // DELETED account - archiveAccount must reject "not ACTIVE", not just "not already ARCHIVED".
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));
    setStatusDirectly(created.id(), "DELETED");

    client(token)
        .post()
        .uri("/api/v1/accounts/" + created.id() + "/archive")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void restoringAnActiveAccountIsRejectedWithAStructuredConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));

    client(token)
        .post()
        .uri("/api/v1/accounts/" + created.id() + "/restore")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void archivingAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/archive")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void restoringAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/restore")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void reassigningInstitutionMovesTheAccountAndBothSummariesRecomputeCorrectly() {
    // DoD: reassigns an account with a known value (a MORTGAGE's original_principal is the
    // simplest existing value source that needs no extra setup call - see InstitutionService's
    // resolveNativeValue) and confirms both institutions' summaries are correct before and after.
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institutionA =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                null, "Institution A", "CH", "BANK", null, null, "CHF"));
    FinancialInstitutionSummaryResponse institutionB =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                null, "Institution B", "CH", "BANK", null, null, "CHF"));
    AccountSummaryResponse account =
        createAccount(
            token,
            new CreateAccountRequest(
                institutionA.id(),
                "Home Mortgage",
                "MORTGAGE",
                "CHF",
                null,
                null,
                BigDecimal.valueOf(500000),
                BigDecimal.valueOf(1.5),
                null,
                null));

    InstitutionSummaryResponse beforeA = getInstitutionSummary(token, institutionA.id());
    InstitutionSummaryResponse beforeB = getInstitutionSummary(token, institutionB.id());
    assertThat(beforeA.totalLiabilities()).isEqualByComparingTo(BigDecimal.valueOf(500000));
    assertThat(beforeB.totalLiabilities()).isEqualByComparingTo(BigDecimal.ZERO);

    AccountSummaryResponse reassigned =
        client(token)
            .post()
            .uri("/api/v1/accounts/" + account.id() + "/reassign-institution")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ReassignAccountInstitutionRequest(institutionB.id()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(reassigned.financialInstitutionId()).isEqualTo(institutionB.id());

    InstitutionSummaryResponse afterA = getInstitutionSummary(token, institutionA.id());
    InstitutionSummaryResponse afterB = getInstitutionSummary(token, institutionB.id());
    assertThat(afterA.totalLiabilities()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(afterB.totalLiabilities()).isEqualByComparingTo(BigDecimal.valueOf(500000));
  }

  @Test
  void reassigningToAnUnknownInstitutionIsNotFound() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));

    client(token)
        .post()
        .uri("/api/v1/accounts/" + created.id() + "/reassign-institution")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReassignAccountInstitutionRequest(UUID.randomUUID()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void reassigningAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse institution =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                null, "Institution A", "CH", "BANK", null, null, "CHF"));

    client(token)
        .post()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/reassign-institution")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReassignAccountInstitutionRequest(institution.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void reassigningADeletedAccountIsRejectedWithAStructuredConflict() throws Exception {
    // FR-STA-001: DELETED is terminal - reachable from ACTIVE only, reachable from nowhere once
    // there, the same principle archivingADeletedAccountIsRejectedWithAStructuredConflict already
    // covers for archiveAccount. There's no delete-account endpoint yet, so status is set directly
    // via SQL, same as that test.
    String token = bootstrapAdministrator();
    AccountSummaryResponse created =
        createAccount(
            token,
            new CreateAccountRequest(
                null, "Everyday Checking", "CASH", "CHF", null, null, null, null, null, null));
    FinancialInstitutionSummaryResponse destination =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                null, "Institution A", "CH", "BANK", null, null, "CHF"));
    setStatusDirectly(created.id(), "DELETED");

    client(token)
        .post()
        .uri("/api/v1/accounts/" + created.id() + "/reassign-institution")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ReassignAccountInstitutionRequest(destination.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void everyAccountTypeCanBeAddedUnderAPensionProviderInstitution() {
    // DoD/FR-INS-008/RULE-020: institution_type never restricts account_type - a pension provider
    // (VIAC-like) can hold a plain cash balance, a securities depot, and more than one pension
    // account side by side, with no validation error referencing institution type for any of them.
    String token = bootstrapAdministrator();
    FinancialInstitutionSummaryResponse pensionProvider =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                null, "VIAC", "CH", "PENSION_PROVIDER", null, null, "CHF"));

    AccountSummaryResponse cash =
        createAccount(
            token,
            new CreateAccountRequest(
                pensionProvider.id(),
                "Cash Sleeve",
                "CASH",
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null));
    AccountSummaryResponse securities =
        createAccount(
            token,
            new CreateAccountRequest(
                pensionProvider.id(),
                "Fund Depot",
                "SECURITIES",
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null));
    AccountSummaryResponse pillar3aFirst =
        createAccount(
            token,
            new CreateAccountRequest(
                pensionProvider.id(),
                "Pillar 3a - Account 1",
                "PENSION",
                "CHF",
                null,
                null,
                null,
                null,
                "CH_PILLAR_3A",
                null));
    AccountSummaryResponse pillar3aSecond =
        createAccount(
            token,
            new CreateAccountRequest(
                pensionProvider.id(),
                "Pillar 3a - Account 2",
                "PENSION",
                "CHF",
                null,
                null,
                null,
                null,
                "CH_PILLAR_3A",
                null));

    assertThat(List.of(cash, securities, pillar3aFirst, pillar3aSecond))
        .extracting(AccountSummaryResponse::accountType)
        .containsExactly("CASH", "SECURITIES", "PENSION", "PENSION");
    // Distinct user-defined names for both otherwise-identical pension accounts (AC #2) - checked
    // on name(), not id(): two independently-created resources always get distinct server-
    // generated ids regardless of whether their names collide, so an id-based check here would be
    // tautological and would not actually verify this AC.
    assertThat(List.of(pillar3aFirst.name(), pillar3aSecond.name()))
        .containsExactly("Pillar 3a - Account 1", "Pillar 3a - Account 2");
  }

  private void setStatusDirectly(UUID accountId, String status) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE account SET status = ? WHERE id = ?")) {
      statement.setString(1, status);
      statement.setObject(2, accountId);
      statement.executeUpdate();
    }
  }

  private void backdateArchivedAt(UUID accountId, int daysAgo) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE account SET archived_at = archived_at - (? * INTERVAL '1 day') WHERE id ="
                    + " ?")) {
      statement.setInt(1, daysAgo);
      statement.setObject(2, accountId);
      statement.executeUpdate();
    }
  }

  private boolean isOccupational(UUID accountId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT is_occupational FROM account_pension WHERE account_id = ?")) {
      statement.setObject(1, accountId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getBoolean("is_occupational");
      }
    }
  }

  private boolean extensionRowExists(String table, UUID accountId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT 1 FROM " + table + " WHERE account_id = ?")) {
      statement.setObject(1, accountId);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private FinancialInstitutionSummaryResponse createInstitution(
      String token, CreateFinancialInstitutionRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(FinancialInstitutionSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private InstitutionSummaryResponse getInstitutionSummary(String token, UUID institutionId) {
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

  private AccountSummaryResponse createAccount(String token, CreateAccountRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
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
