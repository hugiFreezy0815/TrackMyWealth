package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.architecture.IfMatchExceptions;
import com.trackmywealth.backend.config.JwtProperties;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportRowCode;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.SortWhitelist;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EPIC-29 (#149, #175, #176): the wire conventions, end to end.
 *
 * <ul>
 *   <li>One error shape for every error class - {@code application/problem+json} with a {@code
 *       detail}, a stable {@code code} and the {@code correlationId} - with a 404 that says nothing
 *       about another workspace (US-28-03).
 *   <li>A correlation id on every response, taken from a well-formed {@code X-Correlation-Id} or
 *       generated.
 *   <li>Every decimal as a plain string with its full precision, in both directions.
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiConventionsIntegrationTest.FailureProbeController.class)
class ApiConventionsIntegrationTest {

  private static final Set<RequestMethod> MUTATING_METHODS =
      EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

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

  @Autowired ObjectMapper objectMapper;

  @Autowired JwtProperties jwtProperties;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping handlerMapping;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM transaction_categorization_log",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM category WHERE workspace_id IS NOT NULL",
              "DELETE FROM account_securities",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM authorization_denial_log",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM portfolio",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  // --- the error envelope (#149, #175) -----------------------------------------------------------

  @Test
  void aValidationFailureNamesEachRejectedField() {
    String token = bootstrapAdministrator();

    JsonNode problem =
        problem(
            client(token)
                .post()
                .uri("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"nameEn\":\"\",\"nameDe\":\"Hobby\"}")
                .exchange(),
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED");

    assertThat(problem.path("errors").findValuesAsString("field")).contains("nameEn");
  }

  @Test
  void aMissingTokenIsUnauthenticatedAndAWrongRoleForbidden() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "member@example.com");

    problem(
        anonymousClient().get().uri("/api/v1/accounts").exchange(),
        HttpStatus.UNAUTHORIZED,
        "UNAUTHENTICATED");
    problem(
        client(login("member@example.com")).get().uri("/api/v1/admin/reference-data").exchange(),
        HttpStatus.FORBIDDEN,
        "FORBIDDEN");
  }

  @Test
  void aWrongIssuerBearerTokenIsUnauthenticatedInTheStandardErrorShape() {
    Instant now = Instant.now();
    String wrongIssuerToken =
        Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .issuer("another-service")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
            .claim("tokenType", "access")
            .claim("tokenVersion", 1)
            .claim("sessionId", UUID.randomUUID().toString())
            .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
            .compact();

    problem(
        client(wrongIssuerToken).get().uri("/api/v1/accounts").exchange(),
        HttpStatus.UNAUTHORIZED,
        "UNAUTHENTICATED");
  }

  @Test
  void aForeignResourceIsNotFoundExactlyLikeOneThatDoesNotExist() throws Exception {
    String token = bootstrapAdministrator();
    UUID foreign = accountInAnotherWorkspace();

    JsonNode missing =
        problem(
            client(token).get().uri("/api/v1/accounts/" + UUID.randomUUID()).exchange(),
            HttpStatus.NOT_FOUND,
            "NOT_FOUND");
    JsonNode hidden =
        problem(
            client(token).get().uri("/api/v1/accounts/" + foreign).exchange(),
            HttpStatus.NOT_FOUND,
            "NOT_FOUND");

    assertThat(hidden.path("detail").asString()).isEqualTo(missing.path("detail").asString());
  }

  @Test
  void aConflictAndABrokenRuleExplainThemselves() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");
    String category = "{\"nameEn\":\"Hobby\",\"nameDe\":\"Hobby\"}";
    client(token)
        .post()
        .uri("/api/v1/categories")
        .contentType(MediaType.APPLICATION_JSON)
        .body(category)
        .exchange()
        .expectStatus()
        .isCreated();

    JsonNode conflict =
        problem(
            client(token)
                .post()
                .uri("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(category)
                .exchange(),
            HttpStatus.CONFLICT,
            "CONFLICT");
    assertThat(conflict.path("detail").asString()).contains("already exists");

    JsonNode rule =
        problem(
            postTransaction(token, cash.id(), expense("45.00")),
            HttpStatus.UNPROCESSABLE_CONTENT,
            "UNPROCESSABLE");
    assertThat(rule.path("detail").asString()).contains("amount must be negative");
  }

  @Test
  void aClientCanTellAnArchivedAccountFromOtherConflicts() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");
    client(token)
        .post()
        .uri("/api/v1/accounts/" + cash.id() + "/archive")
        .header("If-Match", "\"" + cash.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();

    problem(
        postTransaction(token, cash.id(), expense("-45.00")),
        HttpStatus.CONFLICT,
        "ACCOUNT_ARCHIVED");
  }

  @Test
  void anUnexpectedHttpErrorUsesTheSameEnvelopeAndDoesNotLeakItsCause() {
    String token = bootstrapAdministrator();

    JsonNode problem =
        problem(
            client(token).get().uri("/api/v1/test/internal-error").exchange(),
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL");

    assertThat(problem.path("detail").asString())
        .doesNotContain("SELECT")
        .doesNotContain("password_hash")
        .doesNotContain("super-secret")
        .doesNotContain("IllegalStateException");
  }

  /** PR #267 review: a busy server says when to retry, inside the same error envelope. */
  @Test
  void aRetryableErrorSaysWhenToRetry() {
    String token = bootstrapAdministrator();

    client(token)
        .get()
        .uri("/api/v1/test/busy")
        .exchange()
        .expectHeader()
        .valueEquals(HttpHeaders.RETRY_AFTER, "7");
    problem(
        client(token).get().uri("/api/v1/test/busy").exchange(),
        HttpStatus.SERVICE_UNAVAILABLE,
        ProblemCode.IMPORT_PDF_BUSY.name());
  }

  @Test
  void aRequestTheSecurityFirewallRejectsGetsTheSameShape() {
    String token = bootstrapAdministrator();
    // Sent as given - a client would normalise these paths - so they reach the firewall as is.
    for (String path : List.of("/api/v1/accounts;x=1", "/api/v1/accounts/%2e%2e/x")) {
      URI uri = URI.create("http://localhost:" + port + path);
      problem(client(token).get().uri(uri).exchange(), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
      problem(
          anonymousClient().get().uri(uri).exchange(), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }
  }

  // --- correlation ids
  // ----------------------------------------------------------------------------

  @Test
  void aWellFormedCorrelationIdIsEchoedAndAnythingElseReplaced() {
    String token = bootstrapAdministrator();

    String echoed =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + UUID.randomUUID())
            .header("X-Correlation-Id", "mobile-7f3a9c21")
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectHeader()
            .valueEquals("X-Correlation-Id", "mobile-7f3a9c21")
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(objectMapper.readTree(echoed).path("correlationId").asString())
        .isEqualTo("mobile-7f3a9c21");

    String replaced =
        client(token)
            .get()
            .uri("/api/v1/categories")
            .header("X-Correlation-Id", "x")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst("X-Correlation-Id");
    assertThat(replaced).isNotEqualTo("x").hasSize(36); // a generated UUID
  }

  @Test
  void browserPreflightAllowsApiConventionHeadersAndExposesEtag() {
    anonymousClient()
        .options()
        .uri("/api/v1/accounts/some-id")
        .header("Origin", "http://localhost:19006")
        .header("Access-Control-Request-Method", "PUT")
        .header("Access-Control-Request-Headers", "X-Correlation-Id, If-Match")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .value(
            "Access-Control-Allow-Headers",
            value ->
                assertThat(value.toLowerCase()).contains("x-correlation-id").contains("if-match"));

    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token, "CASH");
    client(token)
        .get()
        .uri("/api/v1/accounts/" + account.id())
        .header("Origin", "http://localhost:19006")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .value(
            "Access-Control-Expose-Headers",
            value -> assertThat(value.toLowerCase()).contains("etag"));
  }

  // --- decimals as strings (#149, #176)
  // ------------------------------------------------------------

  @Test
  void everyDecimalTravelsAsAStringWithItsFullPrecision() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "SECURITIES");
    UUID security = createSecurity(token);
    // 12345678.1234567891 x 1.0000000001 = 12345678.12469135691..., statement-rounded to 4 places.
    String buy =
        """
        {"transactionType":"BUY","bookingDate":"%s","amount":"-12345678.1247","currency":"CHF",
         "securityId":"%s","quantity":"12345678.1234567891","unitPrice":"1.0000000001",
         "externalId":"exact-buy"}
        """
            .formatted(today(), security);

    String created =
        postTransaction(token, depot.id(), buy)
            .expectStatus()
            .isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    JsonNode row = objectMapper.readTree(created);
    assertThat(row.path("quantity").isString()).isTrue();
    assertThat(row.path("quantity").asString()).isEqualTo("12345678.1234567891");
    assertThat(row.path("unitPrice").asString()).isEqualTo("1.0000000001");
    assertThat(row.path("amount").asString()).isEqualTo("-12345678.1247");
    // A replay sending back exactly what it received is the same transaction, not a conflict.
    postTransaction(token, depot.id(), buy).expectStatus().isCreated();

    // #176's sell-all regression: use the exact quantity the API returned, with no conversion
    // through a JavaScript/IEEE-754 number. Every digit must survive the BUY -> response -> SELL
    // round trip so the position can be closed without precision dust.
    String sellAll =
        """
        {"transactionType":"SELL","bookingDate":"%s","amount":"12345678.1247","currency":"CHF",
         "securityId":"%s","quantity":"-%s","unitPrice":"%s","externalId":"exact-sell"}
        """
            .formatted(
                today(),
                security,
                row.path("quantity").asString(),
                row.path("unitPrice").asString());
    JsonNode sold =
        objectMapper.readTree(
            postTransaction(token, depot.id(), sellAll)
                .expectStatus()
                .isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());
    assertThat(sold.path("quantity").asString()).isEqualTo("-12345678.1234567891");
  }

  @Test
  void numbersAreStillAcceptedOnRequestsAndReadBackAsStrings() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");

    String created =
        postTransaction(
                token,
                cash.id(),
                """
                {"transactionType":"EXPENSE","bookingDate":"%s","amount":-10.5,"currency":"EUR",
                 "fxRateToAccountCurrency":0.9412345678}
                """
                    .formatted(today()))
            .expectStatus()
            .isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    JsonNode row = objectMapper.readTree(created);
    assertThat(row.path("amount").asString()).isEqualTo("-10.5");
    assertThat(row.path("fxRateToAccountCurrency").asString()).isEqualTo("0.9412345678");
  }

  @Test
  void openApiDocumentsExactDecimalsCorrelationIdsAndTheSharedProblemEnvelope() {
    String token = bootstrapAdministrator();

    JsonNode spec =
        objectMapper.readTree(
            client(token)
                .get()
                .uri("/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

    JsonNode transaction = spec.path("components").path("schemas").path("TransactionResponse");
    assertThat(transaction.path("properties").path("amount").path("type").asString())
        .isEqualTo("string");
    assertThat(transaction.path("properties").path("quantity").path("type").asString())
        .isEqualTo("string");
    assertThat(transaction.path("properties").path("unitPrice").path("format").asString())
        .isEqualTo("decimal");

    JsonNode postTransaction =
        spec.path("paths").path("/api/v1/accounts/{accountId}/transactions").path("post");
    assertThat(postTransaction.path("parameters").findValuesAsString("name"))
        .contains("X-Correlation-Id");
    assertThat(postTransaction.path("responses").path("default").path("$ref").asString())
        .isEqualTo("#/components/responses/ApiProblemResponse");

    JsonNode account = spec.path("components").path("schemas").path("AccountSummaryResponse");
    assertThat(account.path("properties").path("version").path("type").asString())
        .isEqualTo("integer");

    JsonNode updateAccount = spec.path("paths").path("/api/v1/accounts/{accountId}").path("put");
    JsonNode ifMatch =
        updateAccount.path("parameters").findParents("name").stream()
            .filter(parameter -> "If-Match".equals(parameter.path("name").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(ifMatch.path("required").asBoolean()).isTrue();
    // #374: each status is its own problem response, naming the codes it carries.
    assertThat(updateAccount.path("responses").path("412").path("description").asString())
        .contains("`VERSION_CONFLICT`");
    assertThat(updateAccount.path("responses").path("428").path("description").asString())
        .contains("`VERSION_REQUIRED`");

    JsonNode updateCategory = spec.path("paths").path("/api/v1/categories/{id}").path("put");
    assertThat(updateCategory.path("parameters").findValuesAsString("name")).contains("If-Match");

    // US-06-05: display-currency selection is part of the generated contract on every agreed
    // read surface, and the net-worth wire name is currency rather than the retired
    // reportingCurrency.
    JsonNode schemas = spec.path("components").path("schemas");

    // US-06-07: generated clients see the exact admin FX value sets, not unconstrained
    // Integer/String fields that accept values the runtime rejects.
    List<Integer> fxIntervals = new ArrayList<>();
    schemas
        .path("UpdateFxImportIntervalRequest")
        .path("properties")
        .path("intervalHours")
        .path("enum")
        .forEach(value -> fxIntervals.add(value.asInt()));
    assertThat(fxIntervals).containsExactly(1, 2, 6, 12, 24);
    List<String> fxSources = new ArrayList<>();
    schemas
        .path("FxImportSettingsResponse")
        .path("properties")
        .path("intervalSource")
        .path("enum")
        .forEach(value -> fxSources.add(value.asString()));
    assertThat(fxSources).containsExactly("ADMINISTRATOR", "ENVIRONMENT");

    assertThat(schemas.path("WorkspaceResponse").path("properties").has("currency")).isTrue();
    assertThat(schemas.path("NetWorthResponse").path("properties").has("currency")).isTrue();
    assertThat(schemas.path("NetWorthResponse").path("properties").has("reportingCurrency"))
        .isFalse();
    for (String path :
        List.of(
            "/api/v1/net-worth",
            "/api/v1/portfolios/{portfolioId}/summary",
            "/api/v1/accounts/{accountId}/balance",
            "/api/v1/cash-flow")) {
      assertThat(
              spec.path("paths")
                  .path(path)
                  .path("get")
                  .path("parameters")
                  .findValuesAsString("name"))
          .contains("currency");
    }

    // US-04-06: the workspace's containers are portfolios; "institutions" is left only for the
    // shared catalogue of real banks and brokers.
    List<String> institutionPaths = new ArrayList<>();
    spec.path("paths")
        .propertyNames()
        .forEach(
            path -> {
              if (path.startsWith("/api/v1/institutions")) {
                institutionPaths.add(path);
              }
            });
    assertThat(institutionPaths).containsExactly("/api/v1/institutions/catalogue");
    assertThat(
            spec.path("paths")
                .path("/api/v1/portfolios/{portfolioId}/view")
                .path("get")
                .path("operationId")
                .asString())
        .isEqualTo("getPortfolioView");

    // #207: the expanded rollout is in the generated contract too, not only the original
    // account/category endpoints from #206.
    assertThat(transaction.path("properties").path("version").path("type").asString())
        .isEqualTo("integer");
    assertThat(mutatingOperationsWithoutDocumentedIfMatch(spec))
        .as("mutating operations whose OpenAPI lacks a required If-Match with 412/428")
        .isEmpty();

    // PR #267 review, #374: a 503 a client may retry documents its codes and Retry-After header,
    // next to the generated success response.
    JsonNode detect = spec.path("paths").path("/api/v1/import-templates/detect").path("post");
    assertThat(detect.path("responses").path("503").path("description").asString())
        .contains("`IMPORT_PDF_BUSY`", "`IMPORT_OCR_UNAVAILABLE`");
    assertThat(detect.path("responses").path("503").path("headers").has("Retry-After")).isTrue();
    assertThat(detect.path("responses").has("200")).isTrue();

    JsonNode problem = spec.path("components").path("schemas").path("ApiProblem");
    assertThat(problem.path("properties").path("code").path("type").asString()).isEqualTo("string");
    assertThat(problem.path("properties").path("correlationId").path("type").asString())
        .isEqualTo("string");
  }

  // US-29-04 (#374): the contract names every code - all of them in ApiProblem.code, and per
  // operation the ones it can return, in x-problem-codes and one error response per status.
  @Test
  void openApiListsEveryProblemCodeAndPerOperationTheCodesItCanReturn() {
    JsonNode spec =
        objectMapper.readTree(
            client(bootstrapAdministrator())
                .get()
                .uri("/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

    // An open set (x-extensible-enum): adding a code is additive; a closed enum would not be.
    JsonNode problemCode =
        spec.path("components").path("schemas").path("ApiProblem").path("properties").path("code");
    assertThat(problemCode.has("enum")).as("ApiProblem.code is no closed enum").isFalse();
    List<String> codes = new ArrayList<>();
    problemCode.path("x-extensible-enum").forEach(code -> codes.add(code.asString()));
    assertThat(codes)
        .containsExactlyElementsOf(Arrays.stream(ProblemCode.values()).map(Enum::name).toList());

    JsonNode commit =
        spec.path("paths")
            .path("/api/v1/accounts/{accountId}/imports/{batchId}/commit")
            .path("post");
    List<String> commitCodes = new ArrayList<>();
    commit.path("x-problem-codes").forEach(code -> commitCodes.add(code.asString()));
    assertThat(commitCodes)
        .contains(
            ProblemCode.IMPORT_BATCH_STATE.name(),
            ProblemCode.ACCOUNT_ARCHIVED.name(),
            ProblemCode.NOT_FOUND.name(),
            ProblemCode.UNAUTHENTICATED.name(),
            ProblemCode.INTERNAL.name());
    assertThat(commit.path("responses").path("409").path("description").asString())
        .contains("`IMPORT_BATCH_STATE`: " + ProblemCode.IMPORT_BATCH_STATE.meaning())
        // The commit sends If-Match, so a version conflict is its 412, never a 409.
        .doesNotContain("`VERSION_CONFLICT`");
    assertThat(commit.path("responses").path("412").path("description").asString())
        .contains("`VERSION_CONFLICT`");
    // A write without If-Match answers a lost version race with 409 VERSION_CONFLICT.
    JsonNode createCategory = spec.path("paths").path("/api/v1/categories").path("post");
    assertThat(createCategory.path("responses").path("409").path("description").asString())
        .contains("`VERSION_CONFLICT`: " + ProblemCode.VERSION_CONFLICT.meaning());
    assertThat(createCategory.path("responses").has("412")).isFalse();
    assertThat(
            commit
                .path("responses")
                .path("409")
                .path("content")
                .path("application/problem+json")
                .path("schema")
                .path("$ref")
                .asString())
        .isEqualTo("#/components/schemas/ApiProblem");
    assertThat(commit.path("responses").has("200")).isTrue();

    // A public, rate-limited sign-in documents 429 but no 401 for a missing token.
    JsonNode login = spec.path("paths").path("/api/v1/auth/login").path("post");
    List<String> loginCodes = new ArrayList<>();
    login.path("x-problem-codes").forEach(code -> loginCodes.add(code.asString()));
    assertThat(loginCodes).contains(ProblemCode.RATE_LIMITED.name(), ProblemCode.LOCKED.name());
    assertThat(login.path("responses").path("429").path("headers").has("Retry-After")).isTrue();

    // Every operation lists its codes; each listed code has a response for its status.
    List<String> undocumented = new ArrayList<>();
    spec.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation -> {
                          JsonNode listed = operation.getValue().path("x-problem-codes");
                          if (listed.isEmpty()) {
                            undocumented.add(operation.getKey() + " " + path.getKey());
                          }
                          // Each listed code is named by the response of one of its statuses.
                          listed.forEach(
                              code -> {
                                ProblemCode problem = ProblemCode.valueOf(code.asString());
                                boolean named =
                                    problem.statuses().stream()
                                        .map(
                                            status ->
                                                operation
                                                    .getValue()
                                                    .path("responses")
                                                    .path(Integer.toString(status.value()))
                                                    .path("description")
                                                    .asString())
                                        .anyMatch(
                                            text -> text.contains("`" + problem.name() + "`"));
                                if (!named) {
                                  undocumented.add(
                                      operation.getKey() + " " + path.getKey() + " " + problem);
                                }
                              });
                        }));
    assertThat(undocumented).as("operations without their problem codes").isEmpty();

    JsonNode rowCode =
        spec.path("components")
            .path("schemas")
            .path("ImportRowErrorResponse")
            .path("properties")
            .path("code");
    assertThat(rowCode.has("enum")).as("ImportRowErrorResponse.code is no closed enum").isFalse();
    List<String> rowCodes = new ArrayList<>();
    rowCode.path("x-extensible-enum").forEach(code -> rowCodes.add(code.asString()));
    assertThat(rowCodes)
        .containsExactlyElementsOf(Arrays.stream(ImportRowCode.values()).map(Enum::name).toList());
  }

  // US-29-04 (#374): an error response never carries a code the registry does not list.
  @Test
  void anUnregisteredCodeNeverReachesAClient() {
    JsonNode problem =
        objectMapper.readTree(
            client(bootstrapAdministrator())
                .get()
                .uri("/api/v1/test/unregistered-code")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

    assertThat(problem.path("code").asString()).isEqualTo(ProblemCode.CONFLICT.name());
    assertThat(ProblemCode.isRegistered(problem.path("code").asString())).isTrue();
  }

  // #373: every paged list documents the one paging contract and answers with a PageResponse.
  @Test
  void openApiDocumentsEveryPagedListWithItsPageResponseAndSortWhitelist() {
    JsonNode spec =
        objectMapper.readTree(
            client(bootstrapAdministrator())
                .get()
                .uri("/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());

    List<String> pagedOperations = new ArrayList<>();
    List<String> notPageResponses = new ArrayList<>();
    spec.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation -> {
                          String name = operation.getKey() + " " + path.getKey();
                          List<String> parameters =
                              operation.getValue().path("parameters").findValuesAsString("name");
                          assertThat(parameters).as(name).doesNotContain("pageable");
                          if (!parameters.contains("page")) {
                            return;
                          }
                          pagedOperations.add(name);
                          String schema =
                              operation
                                  .getValue()
                                  .path("responses")
                                  .path("200")
                                  .path("content")
                                  .findPath("schema")
                                  .path("$ref")
                                  .asString();
                          if (!schema.startsWith("#/components/schemas/PageResponse")) {
                            notPageResponses.add(name + " -> " + schema);
                          }
                        }));
    // Six since US-07-35 (#382) added GET /api/v1/transactions.
    assertThat(pagedOperations).hasSize(6);
    assertThat(notPageResponses).as("paged operations not answering a PageResponse").isEmpty();

    JsonNode list =
        spec.path("paths").path("/api/v1/accounts/{accountId}/transactions").path("get");
    JsonNode size = parameter(list, "size").path("schema");
    assertThat(size.path("maximum").asInt()).isEqualTo(200);
    assertThat(size.path("default").asInt()).isEqualTo(50);
    List<String> sortValues = new ArrayList<>();
    parameter(list, "sort")
        .path("schema")
        .path("items")
        .path("enum")
        .forEach(value -> sortValues.add(value.asString()));
    assertThat(sortValues)
        .containsExactly(
            "bookingDate",
            "bookingDate,asc",
            "bookingDate,desc",
            "amount",
            "amount,asc",
            "amount,desc");
    assertThat(list.path("responses").path("400").path("description").asString())
        .contains("`INVALID_SORT`", "`VALIDATION_FAILED`");

    JsonNode schemas = spec.path("components").path("schemas");
    JsonNode page = schemas.path("PageResponseTransactionResponse").path("properties");
    assertThat(page.path("content").path("items").path("$ref").asString())
        .isEqualTo("#/components/schemas/TransactionResponse");
    assertThat(schemas.path("PageMetadata").path("properties").propertyNames())
        .containsExactlyInAnyOrder("number", "size", "totalElements", "totalPages");
  }

  // #373: a @Paged declaration that contradicts itself would fail only on its first request.
  @Test
  void everyPagedDeclarationIsConsistent() {
    List<String> paged = new ArrayList<>();
    handlerMapping
        .getHandlerMethods()
        .values()
        .forEach(
            handler -> {
              for (MethodParameter parameter : handler.getMethodParameters()) {
                Paged declaration = parameter.getParameterAnnotation(Paged.class);
                if (declaration != null) {
                  SortWhitelist.of(declaration);
                  paged.add(handler.getShortLogMessage());
                }
              }
            });
    assertThat(paged).hasSize(6);
  }

  private static JsonNode parameter(JsonNode operation, String name) {
    return operation.path("parameters").findParents("name").stream()
        .filter(parameter -> name.equals(parameter.path("name").asString()))
        .findFirst()
        .orElseThrow();
  }

  // --- helpers ---------------------------------------------------------------------------------

  // Asserts the one error shape and returns the body for the caller's own checks.
  private JsonNode problem(RestTestClient.ResponseSpec response, HttpStatus status, String code) {
    EntityExchangeResult<String> result =
        response
            .expectStatus()
            .isEqualTo(status)
            .expectHeader()
            .contentType(PROBLEM)
            .expectBody(String.class)
            .returnResult();
    String correlationHeader = result.getResponseHeaders().getFirst("X-Correlation-Id");
    JsonNode problem = objectMapper.readTree(result.getResponseBody());
    assertThat(problem.path("status").asInt()).isEqualTo(status.value());
    assertThat(problem.path("code").asString()).isEqualTo(code);
    assertThat(problem.path("detail").asString()).isNotBlank();
    assertThat(problem.path("correlationId").asString()).isEqualTo(correlationHeader);
    // #197: every error body names its request, whichever component wrote it.
    assertThat(problem.path("instance").asString()).startsWith("/");
    return problem;
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private static String expense(String amount) {
    return """
        {"transactionType":"EXPENSE","bookingDate":"%s","amount":"%s","currency":"CHF"}
        """
        .formatted(today(), amount);
  }

  private RestTestClient.ResponseSpec postTransaction(String token, UUID accountId, String json) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
        .exchange();
  }

  private AccountSummaryResponse createAccount(String token, String type) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, type, type, "CHF", null, null, null, null, null, null, null))
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
                "IE00B4L5Y983",
                "iShares Core MSCI World",
                "USD",
                "ETF",
                "EQUITY",
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private UUID accountInAnotherWorkspace() throws Exception {
    UUID workspace = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'B')")) {
        statement.setObject(1, workspace);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO account (id, workspace_id, portfolio_id, account_type,"
                  + " name, native_currency) SELECT ?, ?, id, 'CASH', 'B', 'CHF'"
                  + " FROM portfolio WHERE workspace_id = ?")) {
        statement.setObject(1, account);
        statement.setObject(2, workspace);
        statement.setObject(3, workspace);
        statement.executeUpdate();
      }
    }
    return account;
  }

  private void createStandardUser(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
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

  @RestController
  static class FailureProbeController {

    @GetMapping("/api/v1/test/internal-error")
    void internalError() {
      throw new IllegalStateException(
          "SELECT password_hash FROM app_user WHERE password_hash = 'super-secret'");
    }

    @GetMapping("/api/v1/test/unregistered-code")
    void unregisteredCode() {
      ResponseStatusException raw = new ResponseStatusException(HttpStatus.CONFLICT, "Raw.");
      raw.getBody().setProperty(ProblemCode.PROPERTY, "NOT_IN_THE_REGISTRY");
      throw raw;
    }

    @GetMapping("/api/v1/test/busy")
    void busy() {
      throw new ApiException(
              HttpStatus.SERVICE_UNAVAILABLE, ProblemCode.IMPORT_PDF_BUSY, "Busy; try again.")
          .withRetryAfter(7);
    }
  }

  /**
   * Every route Spring actually serves for POST/PUT/PATCH/DELETE, minus ADR 0004's reviewed
   * exceptions, must be documented with a required {@code If-Match} and its 412/428 responses - the
   * contract-side twin of {@code IfMatchCoverageTest}, so generated clients cannot miss one.
   */
  private List<String> mutatingOperationsWithoutDocumentedIfMatch(JsonNode spec) {
    List<String> undocumented = new ArrayList<>();
    handlerMapping
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              String key =
                  handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
              if (IfMatchExceptions.REVIEWED_WITHOUT_IF_MATCH.containsKey(key)) {
                return;
              }
              for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (!MUTATING_METHODS.contains(method)) {
                  continue;
                }
                for (String path : info.getPatternValues()) {
                  JsonNode operation =
                      spec.path("paths").path(path).path(method.name().toLowerCase(Locale.ROOT));
                  boolean documented =
                      operation.path("parameters").findParents("name").stream()
                              .anyMatch(
                                  parameter ->
                                      "If-Match".equals(parameter.path("name").asString())
                                          && parameter.path("required").asBoolean())
                          && operation.path("responses").has("412")
                          && operation.path("responses").has("428");
                  if (!documented) {
                    undocumented.add(method + " " + path + " (" + key + ")");
                  }
                }
              }
            });
    return undocumented;
  }
}
