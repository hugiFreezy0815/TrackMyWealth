package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ReferenceDataResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
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
 * US-01-04: an administrator sees which reference-data baseline is loaded (the DoD's test is {@link
 * #anAdministratorSeesTheCurrentBaselineAndWhatItContains}), and a new workspace can use it at
 * once.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminReferenceDataControllerTest {

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

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM transaction_categorization_log",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  @Test
  void anAdministratorSeesTheCurrentBaselineAndWhatItContains() {
    String adminToken = bootstrapAdministrator();

    ReferenceDataResponse current =
        client(adminToken)
            .get()
            .uri("/api/v1/admin/reference-data")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ReferenceDataResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(current.packageVersion()).isEqualTo("1.1.0-baseline");
    assertThat(current.publicationDate()).isEqualTo(LocalDate.of(2026, 9, 28));
    assertThat(current.importedAt()).isNotNull();
    assertThat(current.importedBy()).isNull(); // shipped with the application
    // Pins the shipped content on purpose (V19 plus V37: 10 + 7 default categories, 43 source-code
    // mappings): a migration that changes the baseline must update these counts - and should ship
    // as a new baseline version, as V43 does.
    assertThat(current.contents())
        .isEqualTo(new ReferenceDataResponse.Contents(5, 17, 43, 12, "2023-03"));
    // FR-REF-011: no staleness warning for the baseline's mere age.
    assertThat(current.stalenessWarnings()).isEmpty();
  }

  @Test
  void theFirstBaselineStaysAsHistoryWithItsReleaseDate() throws Exception {
    assertThat(
            query(
                "SELECT publication_date FROM reference_package WHERE package_version = ?",
                "1.0.0-baseline"))
        .isEqualTo(Date.valueOf(LocalDate.of(2026, 9, 3)));
    assertThat(query("SELECT count(*) FROM reference_package WHERE is_current = ?", true))
        .isEqualTo(1L);
    assertThat(
            query(
                "SELECT count(*) FROM category_source_mapping"
                    + " WHERE reference_package_version <> ?",
                "1.1.0-baseline"))
        .isEqualTo(0L);
  }

  @Test
  void onlyAnAdministratorMayReadIt() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "member@example.com");

    client(login("member@example.com"))
        .get()
        .uri("/api/v1/admin/reference-data")
        .exchange()
        .expectStatus()
        .isForbidden();
    anonymousClient()
        .get()
        .uri("/api/v1/admin/reference-data")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void aNewWorkspaceCanUseTheBaselineWithoutAnySetup() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    List<InstitutionCatalogueEntrySummaryResponse> found =
        client(memberToken)
            .get()
            .uri("/api/v1/institutions/catalogue?query=Post")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(
                new ParameterizedTypeReference<
                    PageOf<InstitutionCatalogueEntrySummaryResponse>>() {})
            .returnResult()
            .getResponseBody()
            .content();
    assertThat(found)
        .extracting(InstitutionCatalogueEntrySummaryResponse::name)
        .contains("PostFinance");

    List<CategoryResponse> categories =
        client(memberToken)
            .get()
            .uri("/api/v1/categories")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<CategoryResponse>>() {})
            .returnResult()
            .getResponseBody();
    assertThat(categories)
        .extracting(CategoryResponse::code)
        .contains("GROCERIES", "UNCATEGORIZED", "DINING", "FEES");
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  private UUID createStandardUser(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
    return (UUID) query("SELECT workspace_member_id FROM app_user WHERE email = ?", email);
  }

  private Object query(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getObject(1) : null;
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Workspace A", "CHF"))
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
