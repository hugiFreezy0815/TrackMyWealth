package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AmortisationScheduleEntryRequest;
import com.trackmywealth.backend.dto.AmortisationScheduleEntryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.testsupport.AccountRequests;
import java.math.BigDecimal;
import java.sql.Connection;
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
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-10-02: members maintain a loan's amortisation schedule - create, list, replace and delete
 * entries under {@code If-Match} (ADR 0004), only on a loan or mortgage, one entry per due date.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AmortisationScheduleControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String TABLE = "amortisation_schedule_entry";
  private static final LocalDate MARCH = LocalDate.of(2026, 3, 31);
  private static final LocalDate APRIL = LocalDate.of(2026, 4, 30);

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
              "DELETE FROM amortisation_schedule_entry",
              "DELETE FROM account_ownership",
              "DELETE FROM account_mortgage",
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
  }

  @Test
  void entriesAreCreatedListedInDueDateOrderReplacedAndDeleted() {
    String token = bootstrapAdministrator();
    UUID mortgage = createMortgage(token);

    EntityExchangeResult<AmortisationScheduleEntryResponse> created =
        post(token, mortgage, entry(APRIL, "798.00", "1202.00", "497598.00"))
            .expectStatus()
            .isCreated()
            .expectBody(AmortisationScheduleEntryResponse.class)
            .returnResult();
    AmortisationScheduleEntryResponse april =
        CurrentVersion.storedEtag(created, dataSource, TABLE, created.getResponseBody().id());
    post(token, mortgage, entry(MARCH, "800.00", "1200.00", "498800.00"))
        .expectStatus()
        .isCreated();

    assertThat(list(token, mortgage))
        .extracting(AmortisationScheduleEntryResponse::dueDate)
        .containsExactly(MARCH, APRIL);
    assertThat(april.currency()).isEqualTo("CHF");

    AmortisationScheduleEntryResponse replaced =
        client(token)
            .put()
            .uri(entryUri(mortgage, april.id()))
            .header("If-Match", "\"" + april.version() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(entry(APRIL, "797.50", "1202.50", "497597.50"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AmortisationScheduleEntryResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(replaced.interestComponent()).isEqualByComparingTo("797.50");
    assertThat(replaced.version()).isGreaterThan(april.version());

    client(token)
        .delete()
        .uri(entryUri(mortgage, april.id()))
        .headers(CurrentVersion.ifMatch(dataSource, TABLE, april.id()))
        .exchange()
        .expectStatus()
        .isNoContent();
    assertThat(list(token, mortgage))
        .extracting(AmortisationScheduleEntryResponse::dueDate)
        .containsExactly(MARCH);
  }

  @Test
  void aReplaceNeedsTheCurrentVersion() {
    String token = bootstrapAdministrator();
    UUID mortgage = createMortgage(token);
    AmortisationScheduleEntryResponse march =
        post(token, mortgage, entry(MARCH, "800.00", "1200.00", "498800.00"))
            .expectStatus()
            .isCreated()
            .expectBody(AmortisationScheduleEntryResponse.class)
            .returnResult()
            .getResponseBody();

    client(token)
        .put()
        .uri(entryUri(mortgage, march.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(entry(MARCH, "1.00", "1.00", "1.00"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    client(token)
        .put()
        .uri(entryUri(mortgage, march.id()))
        .header("If-Match", "\"" + (march.version() + 1) + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(entry(MARCH, "1.00", "1.00", "1.00"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
  }

  @Test
  void aDeleteNeedsTheCurrentVersion() {
    String token = bootstrapAdministrator();
    UUID mortgage = createMortgage(token);
    AmortisationScheduleEntryResponse march =
        post(token, mortgage, entry(MARCH, "800.00", "1200.00", "498800.00"))
            .expectStatus()
            .isCreated()
            .expectBody(AmortisationScheduleEntryResponse.class)
            .returnResult()
            .getResponseBody();

    client(token)
        .delete()
        .uri(entryUri(mortgage, march.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    client(token)
        .delete()
        .uri(entryUri(mortgage, march.id()))
        .header("If-Match", "\"" + (march.version() + 1) + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    assertThat(list(token, mortgage)).singleElement().extracting("id").isEqualTo(march.id());
  }

  @Test
  void anArchivedLoansScheduleCanBeReadButNotChanged() {
    String token = bootstrapAdministrator();
    UUID mortgage = createMortgage(token);
    AmortisationScheduleEntryResponse march =
        post(token, mortgage, entry(MARCH, "800.00", "1200.00", "498800.00"))
            .expectStatus()
            .isCreated()
            .expectBody(AmortisationScheduleEntryResponse.class)
            .returnResult()
            .getResponseBody();
    client(token)
        .post()
        .uri("/api/v1/accounts/" + mortgage + "/archive")
        .headers(CurrentVersion.ifMatch(dataSource, "account", mortgage))
        .exchange()
        .expectStatus()
        .isOk();

    post(token, mortgage, entry(APRIL, "790.00", "1210.00", "497590.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("ACCOUNT_ARCHIVED");
    client(token)
        .put()
        .uri(entryUri(mortgage, march.id()))
        .header("If-Match", "\"" + march.version() + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(entry(MARCH, "1.00", "1.00", "1.00"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    client(token)
        .delete()
        .uri(entryUri(mortgage, march.id()))
        .header("If-Match", "\"" + march.version() + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(list(token, mortgage)).singleElement().extracting("id").isEqualTo(march.id());
  }

  @Test
  void aSecondEntryForTheSameDueDateIsAConflictNamingTheFirst() {
    String token = bootstrapAdministrator();
    UUID mortgage = createMortgage(token);
    UUID first =
        post(token, mortgage, entry(MARCH, "800.00", "1200.00", "498800.00"))
            .expectStatus()
            .isCreated()
            .expectBody(AmortisationScheduleEntryResponse.class)
            .returnResult()
            .getResponseBody()
            .id();

    post(token, mortgage, entry(MARCH, "700.00", "1300.00", "498700.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.existingEntryId")
        .isEqualTo(first.toString());
  }

  @Test
  void onlyALoanTakesAScheduleAndItsAmountsAreNeverNegativeOrZero() {
    String token = bootstrapAdministrator();
    UUID mortgage = createMortgage(token);
    UUID current = create(token, AccountRequests.account("Current", "CASH", "CHF").build());

    post(token, current, entry(MARCH, "800.00", "1200.00", "498800.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    post(token, mortgage, entry(MARCH, "-1.00", "1200.00", "498800.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    post(token, mortgage, entry(MARCH, "0", "0", "498800.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(list(token, mortgage)).isEmpty();
  }

  @Test
  void anEntryOfAnotherLoanIsNotFound() {
    String token = bootstrapAdministrator();
    UUID mortgage = createMortgage(token);
    UUID other = createMortgage(token);
    UUID entry =
        post(token, mortgage, entry(MARCH, "800.00", "1200.00", "498800.00"))
            .expectStatus()
            .isCreated()
            .expectBody(AmortisationScheduleEntryResponse.class)
            .returnResult()
            .getResponseBody()
            .id();

    client(token)
        .delete()
        .uri(entryUri(other, entry))
        .header("If-Match", "\"0\"")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  private static AmortisationScheduleEntryRequest entry(
      LocalDate dueDate, String interest, String principal, String remaining) {
    return new AmortisationScheduleEntryRequest(
        dueDate, new BigDecimal(interest), new BigDecimal(principal), new BigDecimal(remaining));
  }

  private static String entryUri(UUID accountId, UUID entryId) {
    return "/api/v1/accounts/" + accountId + "/amortisation-schedule/" + entryId;
  }

  private RestTestClient.ResponseSpec post(
      String token, UUID accountId, AmortisationScheduleEntryRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/amortisation-schedule")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private List<AmortisationScheduleEntryResponse> list(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/amortisation-schedule")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<AmortisationScheduleEntryResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private UUID createMortgage(String token) {
    return create(
        token,
        AccountRequests.account("Home Mortgage", "MORTGAGE", "CHF")
            .originalPrincipal(new BigDecimal("500000.00"))
            .interestRatePercent(new BigDecimal("2.0"))
            .build());
  }

  private UUID create(String token, Object request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
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
