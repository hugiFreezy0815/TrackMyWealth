package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PageResponse.PageMetadata;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.error.ApiErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #373 (ADR 0006): the one paging contract, end to end over HTTP - the {@code PageResponse}
 * envelope, the size cap, the sort whitelist's 400 {@code INVALID_SORT} on every paged list, and
 * pages that neither overlap nor skip rows when the sorted values tie.
 *
 * <p>The data is created once for the class (the administrator bootstrap works only once per
 * database) and only read by the tests.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PagingIntegrationTest {

  private static final int LEDGER_ROWS = 120;
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final ParameterizedTypeReference<PageResponse<TransactionResponse>> PAGE =
      new ParameterizedTypeReference<>() {};

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

  // Created by the first test to run; the tests only read them.
  private static String token;
  private static UUID ledger;
  private static UUID other;

  @LocalServerPort int port;

  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void seedOnce() {
    if (token != null) {
      return;
    }
    token = bootstrapAdministrator();
    ledger = createAccount("Ledger");
    other = createAccount("Other");
    // Every row on the same booking date with the same amount: only the tie-breaker orders them.
    for (int i = 0; i < LEDGER_ROWS; i++) {
      postIncome(ledger, yesterday(), "10.00");
    }
    postIncome(other, yesterday().minusDays(2), "30.00");
    postIncome(other, yesterday().minusDays(1), "5.00");
    postIncome(other, yesterday(), "20.00");
  }

  @Test
  void aMiddlePageHoldsItsRowsAndTheTotals() {
    List<UUID> all = ids(page(ledger, "?size=200"));

    PageResponse<TransactionResponse> second = page(ledger, "?page=1&size=50");

    assertThat(second.page()).isEqualTo(new PageMetadata(1, 50, LEDGER_ROWS, 3));
    assertThat(ids(second)).containsExactlyElementsOf(all.subList(50, 100));
    // Another account's rows are neither listed nor counted.
    assertThat(all).hasSize(LEDGER_ROWS);
  }

  @Test
  void aSizeAboveTheCapIsCappedNotRefused() {
    PageResponse<TransactionResponse> page = page(ledger, "?size=1000");

    assertThat(page.page().size()).isEqualTo(200);
    assertThat(page.content()).hasSizeLessThanOrEqualTo(200);
    assertThat(page.page().totalPages()).isEqualTo(1);
  }

  @Test
  void withoutParametersItIsTheFirstPageOfFifty() {
    assertThat(page(ledger, "").page()).isEqualTo(new PageMetadata(0, 50, LEDGER_ROWS, 3));
  }

  @Test
  void aPageBeyondTheEndIsEmptyWithTheSameTotals() {
    PageResponse<TransactionResponse> beyond = page(ledger, "?page=9&size=50");

    assertThat(beyond.content()).isEmpty();
    assertThat(beyond.page()).isEqualTo(new PageMetadata(9, 50, LEDGER_ROWS, 3));
  }

  @ParameterizedTest
  @CsvSource({"''", "?sort=bookingDate,desc", "?sort=amount", "?sort=amount,desc"})
  void pagesOfTiedRowsNeitherOverlapNorSkipAny(String sort) {
    String separator = sort.isEmpty() ? "?" : "&";
    List<UUID> paged = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      paged.addAll(ids(page(ledger, sort + separator + "size=50&page=" + page)));
    }

    assertThat(paged).hasSize(LEDGER_ROWS).doesNotHaveDuplicates();
    assertThat(new HashSet<>(paged)).isEqualTo(Set.copyOf(ids(page(ledger, "?size=200"))));
  }

  @Test
  void aWhitelistedSortOrdersTheRows() {
    assertThat(amounts(page(other, ""))).containsExactly("20.0000", "5.0000", "30.0000");
    assertThat(amounts(page(other, "?sort=amount,asc")))
        .containsExactly("5.0000", "20.0000", "30.0000");
    assertThat(amounts(page(other, "?sort=bookingDate,asc")))
        .containsExactly("30.0000", "5.0000", "20.0000");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "/api/v1/accounts/{ledger}/transactions | bookingDate, amount",
        "/api/v1/accounts/{ledger}/imports | uploadedAt",
        "/api/v1/accounts/{ledger}/reconciliations | createdAt",
        "/api/v1/institutions/catalogue | name"
      })
  void anUnknownSortFieldIsRefusedOnEveryPagedList(String path, String allowed) {
    JsonNode problem =
        problem(path.replace("{ledger}", ledger.toString()) + "?sort=unknownField,asc");

    assertThat(problem.path("code").asString()).isEqualTo(ApiErrorCode.INVALID_SORT);
    assertThat(problem.path("detail").asString())
        .contains("'unknownField'")
        .contains("Sort by one of: " + allowed + ".");
  }

  @Test
  void anEntityPropertyOutsideTheWhitelistIsRefusedToo() {
    JsonNode problem = problem("/api/v1/accounts/" + ledger + "/transactions?sort=workspaceId,asc");

    assertThat(problem.path("code").asString()).isEqualTo(ApiErrorCode.INVALID_SORT);
  }

  @Test
  void aMalformedPageIsRefused() {
    JsonNode problem = problem("/api/v1/accounts/" + ledger + "/transactions?page=-1");

    assertThat(problem.path("code").asString()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    assertThat(problem.path("detail").asString()).contains("page must be a whole number");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private PageResponse<TransactionResponse> page(UUID accountId, String query) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(PAGE)
        .returnResult()
        .getResponseBody();
  }

  private JsonNode problem(String uri) {
    String body =
        client()
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.BAD_REQUEST)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return objectMapper.readTree(body);
  }

  private static List<UUID> ids(PageResponse<TransactionResponse> page) {
    return page.content().stream().map(TransactionResponse::id).toList();
  }

  private static List<String> amounts(PageResponse<TransactionResponse> page) {
    return page.content().stream()
        .map(TransactionResponse::amount)
        .map(BigDecimal::toPlainString)
        .toList();
  }

  private static LocalDate yesterday() {
    return LocalDate.now(ZoneId.of("Europe/Zurich")).minusDays(1);
  }

  private void postIncome(UUID accountId, LocalDate bookingDate, String amount) {
    client()
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"transactionType":"INCOME","bookingDate":"%s","amount":"%s","currency":"CHF"}
            """
                .formatted(bookingDate, amount))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private UUID createAccount(String name) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, name, "CASH", "CHF", null, null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
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

  private RestTestClient client() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + token)
        .build();
  }
}
