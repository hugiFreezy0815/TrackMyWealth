package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
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
 * US-04-01: search the shared institution catalogue and create a workspace's own financial
 * institution, both from a catalogue entry and as a custom one. {@code institution_catalogue} is
 * shared, non-tenant-scoped seed data (V19) - never truncated between tests, unlike every
 * workspace-scoped table below.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstitutionControllerTest {

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

  @Autowired FinancialInstitutionRepository financialInstitutionRepository;
  @Autowired DataSource dataSource;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // Deliberately plain DELETEs, not TRUNCATE ... CASCADE (the pattern AdminUserControllerTest
    // uses): CASCADE here would also reach institution_catalogue - app_user is referenced by
    // reference_package.imported_by (V18), and institution_catalogue is itself a child of
    // reference_package (V18's fk_institution_catalogue_reference_package) - silently wiping the
    // shared, non-tenant-scoped seed catalogue (V19) this test depends on staying seeded across
    // every test method in this class. DELETE respects the actual (always-NULL here) row data
    // instead of TRUNCATE's blanket structural table-level check, so it never needs to touch
    // reference_package/institution_catalogue at all. Order matches FK dependency order (children
    // before parents) - no CASCADE needed.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
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
  void searchingCatalogueByNameSuggestsTheMatchingSeedInstitution() {
    String token = bootstrapAdministrator();

    List<InstitutionCatalogueEntrySummaryResponse> results =
        searchCatalogue(token, "Post").content();

    assertThat(results)
        .anySatisfy(
            entry -> {
              assertThat(entry.name()).isEqualTo("PostFinance");
              assertThat(entry.country()).isEqualTo("CH");
            });
  }

  @Test
  void searchingCatalogueWithNoQueryReturnsEveryActiveEntry() {
    String token = bootstrapAdministrator();

    // The V19 seed carries PostFinance, Yuh, VIAC, DKB, Sparkasse - five active entries.
    assertThat(searchCatalogue(token, null).totalElements()).isEqualTo(5);
  }

  @Test
  void creatingFromACatalogueEntryDefaultsCurrencyFromCountryAndLinksTheCatalogueId() {
    String token = bootstrapAdministrator();
    var postFinance = searchCatalogue(token, "Post").content().get(0);

    FinancialInstitutionSummaryResponse created =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                postFinance.id(), null, null, null, null, null, null));

    assertThat(created.catalogueInstitutionId()).isEqualTo(postFinance.id());
    assertThat(created.name()).isEqualTo("PostFinance");
    assertThat(created.country()).isEqualTo("CH");
    // FR-INS-004: CH's default per the country->currency map (US-04-01), not user-supplied.
    assertThat(created.containerCurrency()).isEqualTo("CHF");
    assertThat(created.personalAssetsDefault()).isFalse();
    assertThat(created.status()).isEqualTo("ACTIVE");
  }

  @Test
  void creatingFromACatalogueEntryHonorsAnExplicitCurrencyOverride() {
    String token = bootstrapAdministrator();
    var postFinance = searchCatalogue(token, "Post").content().get(0);

    FinancialInstitutionSummaryResponse created =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                postFinance.id(), null, null, null, null, null, "USD"));

    assertThat(created.containerCurrency()).isEqualTo("USD");
  }

  @Test
  void creatingACustomInstitutionWithNoCatalogueLinkSucceeds() {
    String token = bootstrapAdministrator();

    FinancialInstitutionSummaryResponse created =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                null, "My Local Credit Union", "US", "BANK", null, null, "USD"));

    assertThat(created.catalogueInstitutionId()).isNull();
    assertThat(created.name()).isEqualTo("My Local Credit Union");
    assertThat(created.institutionType()).isEqualTo("BANK");
    assertThat(created.containerCurrency()).isEqualTo("USD");
  }

  @Test
  void customInstitutionDefaultsInstitutionTypeToOtherWhenNotChosen() {
    String token = bootstrapAdministrator();

    FinancialInstitutionSummaryResponse created =
        createInstitution(
            token,
            new CreateFinancialInstitutionRequest(
                null, "Unlabelled Provider", null, null, null, null, "CHF"));

    assertThat(created.institutionType()).isEqualTo("OTHER");
  }

  @Test
  void customInstitutionWithoutANameIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateFinancialInstitutionRequest(null, null, null, null, null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void customInstitutionWithoutACurrencyIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                null, "No Currency Bank", null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void invalidCurrencyCodeIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                null, "Bad Currency Bank", null, null, null, null, "ZZZ"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(financialInstitutionRepository.findAll())
        .noneMatch(institution -> "Bad Currency Bank".equals(institution.getName()));
  }

  @Test
  void personalAssetsIsNotASelectableInstitutionTypeForANewInstitution() {
    // Reserved for the one system-created default container per workspace (V19's trigger) - a
    // user must never be able to create a second one via this endpoint.
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                null, "Sneaky Personal Assets", null, "PERSONAL_ASSETS", null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void creatingAnInstitutionFromAnUnknownCatalogueIdIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                java.util.UUID.randomUUID(), null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void anonymousRequestIsUnauthorized() {
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .get()
        .uri("/api/v1/institutions/catalogue")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aDeactivatedWorkspaceMemberCannotAuthenticateAtAll() throws Exception {
    // No code path deactivates a workspace_member yet (US-03-04 is later in this sprint) - this
    // simulates that future state directly at the DB level to prove JwtAuthenticationFilter's
    // workspace_member.status check (added alongside this story - see its class Javadoc) actually
    // holds, ahead of US-03-04 landing for real. This is enforced globally (401, the token simply
    // no longer authenticates - the same treatment a disabled AppUser already gets), not as a
    // per-endpoint 403, so every workspace-scoped endpoint is covered automatically, not just
    // this one.
    String token = bootstrapAdministrator();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("UPDATE workspace_member SET status = 'INACTIVE'");
    }

    client(token)
        .get()
        .uri("/api/v1/institutions/catalogue")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                null, "Should Not Be Created", null, null, null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void blankOptionalFieldsAreTreatedAsOmittedWhenLinkingACatalogueEntry() {
    // A client that reuses one form model for both create flows and defaults unset fields to ""
    // rather than null must not have that fail validation for fields the catalogue path ignores.
    String token = bootstrapAdministrator();
    var postFinance = searchCatalogue(token, "Post").content().get(0);

    FinancialInstitutionSummaryResponse created =
        createInstitution(
            token, new CreateFinancialInstitutionRequest(postFinance.id(), "", "", "", "", "", ""));

    assertThat(created.name()).isEqualTo("PostFinance");
    // containerCurrency="" normalizes to null too, so the country-derived default still applies.
    assertThat(created.containerCurrency()).isEqualTo("CHF");
  }

  @Test
  void malformedCountryCodeIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/institutions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateFinancialInstitutionRequest(
                null, "Bad Country Bank", "USA", null, null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(financialInstitutionRepository.findAll())
        .noneMatch(institution -> "Bad Country Bank".equals(institution.getName()));
  }

  @SuppressWarnings("unchecked")
  private PageResult<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      String token, String query) {
    String uri =
        query != null
            ? "/api/v1/institutions/catalogue?query=" + query
            : "/api/v1/institutions/catalogue";
    Map<String, Object> body =
        client(token)
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
    List<InstitutionCatalogueEntrySummaryResponse> entries =
        content.stream()
            .map(
                entry ->
                    new InstitutionCatalogueEntrySummaryResponse(
                        java.util.UUID.fromString((String) entry.get("id")),
                        (String) entry.get("name"),
                        (String) entry.get("country"),
                        (String) entry.get("institutionType"),
                        (String) entry.get("identifier"),
                        (String) entry.get("logoUrl")))
            .toList();
    long totalElements = ((Number) body.get("totalElements")).longValue();
    return new PageResult<>(entries, totalElements);
  }

  private record PageResult<T>(List<T> content, long totalElements) {}

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
