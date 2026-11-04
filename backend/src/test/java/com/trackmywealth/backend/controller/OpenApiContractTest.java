package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.SpringDocCustomizers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.Ordered;
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
 * US-29-02 (#372): the API contract is the committed {@code api/openapi.yaml}, and the build keeps
 * it in step with the code.
 *
 * <p>{@link #theCommittedContractIsTheOneTheCodeServes} is the drift check: it fails, naming the
 * file, when a change to the API was not regenerated. To regenerate after an intended change, run
 * {@code ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true} and commit the file.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

  /** The committed contract, relative to the backend module the build runs in. */
  static final Path CONTRACT = Path.of("..", "api", "openapi.yaml");

  /** How the file is named in a failure: from the repository root, where it is committed. */
  static final String CONTRACT_NAME = "api/openapi.yaml";

  static final String UPDATE_PROPERTY = "openapi.update";

  private static final String REGENERATE =
      "./mvnw test -Dtest=OpenApiContractTest -D" + UPDATE_PROPERTY + "=true";

  // springdoc's suffix for a clashing generated operation id: get_1, get_2, ...
  private static final Pattern NUMBERED_ID = Pattern.compile(".*_\\d+$");
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

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
  @Autowired SpringDocCustomizers springDocCustomizers;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // The administrator set-up below runs once per test; nothing else is written.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
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

  @Test
  void theCommittedContractIsTheOneTheCodeServes() throws IOException {
    String served = served(bootstrapAdministrator());

    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      Files.createDirectories(CONTRACT.getParent());
      Files.writeString(CONTRACT, served, StandardCharsets.UTF_8);
    }

    assertThat(Files.exists(CONTRACT))
        .as("%s is missing; generate it with %s", CONTRACT_NAME, REGENERATE)
        .isTrue();
    assertThat(driftOf(Files.readString(CONTRACT, StandardCharsets.UTF_8), served)).isEmpty();
  }

  /**
   * The drift check's finding: empty when {@code committed} is the document the code serves,
   * otherwise a message naming the file, the first line that differs and how to regenerate it. Only
   * that line, not the whole document (some 300 KB), which would bury it in the build log.
   */
  static Optional<String> driftOf(String committedFile, String served) {
    // A checkout that converts line endings (core.autocrlf) is not drift; .gitattributes keeps the
    // file LF, this keeps the check honest where that is overridden.
    String committed = committedFile.replace("\r\n", "\n");
    if (committed.equals(served)) {
      return Optional.empty();
    }
    String[] was = committed.split("\n", -1);
    String[] is = served.split("\n", -1);
    int line = 0;
    while (line < was.length && line < is.length && was[line].equals(is[line])) {
      line++;
    }
    return Optional.of(
        ("%s differs from the API the code serves; first difference at line %d: committed '%s',"
                + " served '%s'. If the change is intended, regenerate it with %s and commit it.")
            .formatted(CONTRACT_NAME, line + 1, lineAt(was, line), lineAt(is, line), REGENERATE));
  }

  private static String lineAt(String[] lines, int index) {
    return index < lines.length ? lines[index] : "<end of file>";
  }

  /**
   * The committed file was generated in another JVM, and CI compares it in yet another: an order
   * that changes from one JVM to the next (identity hash codes, a {@code HashSet} of enums, thread
   * timing) would make the drift check fail at random. A second request in this JVM cannot show
   * that - it shares all of it - so the document is generated again by the application started in a
   * JVM of its own, on the same classpath and database. What differs between machines, such as the
   * classpath scanning order, the drift check itself catches on CI.
   */
  @Test
  void aFreshJvmServesTheSameDocument() throws Exception {
    String token = bootstrapAdministrator();
    String here = served(token);

    assertThat(FreshJvmApplication.serve(postgres, "/api-docs.yaml", token)).isEqualTo(here);
  }

  /**
   * {@code ApiDocumentOrder} ran after everything that adds to the document: whatever a customizer
   * added is in order, not appended after the sorted entries.
   */
  @Test
  void theServedDocumentIsSortedByName() {
    JsonNode spec = objectMapper.readTree(json(bootstrapAdministrator()));

    List<String> unsorted = new ArrayList<>();
    expectSorted("paths", names(spec.path("paths")), unsorted);
    spec.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation ->
                            expectSorted(
                                operation.getKey() + " " + path.getKey() + " responses",
                                names(operation.getValue().path("responses")),
                                unsorted)));
    spec.path("components")
        .properties()
        .forEach(
            section ->
                expectSorted(
                    "components." + section.getKey(), names(section.getValue()), unsorted));
    List<String> tags = new ArrayList<>();
    spec.path("tags").forEach(tag -> tags.add(tag.path("name").asString("")));
    expectSorted("tags", tags, unsorted);

    assertThat(spec.path("components").path("schemas").has("ApiProblem")).isTrue();
    assertThat(unsorted).isEmpty();
  }

  /**
   * The sorter runs after every customizer of this application, and has that place to itself: one
   * tying with it could run after it, in whichever order the bean set iterates. springdoc's own
   * {@code OperationIdCustomizer} may still run later; it only renames clashing operation ids, of
   * which there are none ({@link #everyOperationHasItsOwnExplicitIdOneTagAndASummary}), and adds
   * nothing - {@link #theServedDocumentIsSortedByName} checks the outcome either way.
   */
  @Test
  void theDocumentOrderRunsAfterEveryCustomizerOfTheApplication() {
    List<OpenApiCustomizer> ours =
        springDocCustomizers
            .getOpenApiCustomizersStream()
            .filter(customizer -> customizer.getClass().getName().startsWith("com.trackmywealth."))
            .toList();

    assertThat(ours).hasSizeGreaterThan(1);
    assertThat(ours.get(ours.size() - 1))
        .isInstanceOfSatisfying(
            Ordered.class,
            last -> assertThat(last.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE));
    assertThat(ours.subList(0, ours.size() - 1))
        .as("customizers tying with the document order: give them an OrderedOpenApiCustomizer")
        .allSatisfy(
            customizer ->
                assertThat(customizer)
                    .isInstanceOfSatisfying(
                        Ordered.class,
                        ordered ->
                            assertThat(ordered.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE)));
  }

  /**
   * The documented success is the one the handler answers with: springdoc alone would document 200
   * for all of them, with a body of any media type.
   */
  @Test
  void successResponsesHaveTheStatusAndMediaTypeTheHandlersAnswerWith() {
    JsonNode spec = objectMapper.readTree(json(bootstrapAdministrator()));

    JsonNode created = operation(spec, "createAccount").path("responses");
    assertThat(names(created)).contains("201").doesNotContain("200");
    assertThat(
            created
                .path("201")
                .path("content")
                .path("application/json")
                .path("schema")
                .isMissingNode())
        .isFalse();
    assertThat(names(operation(spec, "findOrCreateSecurity").path("responses")))
        .contains("200", "201");
    JsonNode deleted = operation(spec, "deleteCategory").path("responses");
    assertThat(names(deleted)).contains("204").doesNotContain("200");
    assertThat(deleted.path("204").has("content")).isFalse();
    assertThat(
            names(
                operation(spec, "getImportBatchErrors")
                    .path("responses")
                    .path("200")
                    .path("content")))
        .containsExactly("text/csv");

    List<String> anyMediaType = new ArrayList<>();
    spec.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        entry ->
                            entry
                                .getValue()
                                .path("responses")
                                .properties()
                                .forEach(
                                    response -> {
                                      if (response.getValue().path("content").has("*/*")) {
                                        anyMediaType.add(
                                            entry.getKey()
                                                + " "
                                                + path.getKey()
                                                + " "
                                                + response.getKey());
                                      }
                                    })));
    assertThat(anyMediaType).isEmpty();
  }

  private static JsonNode operation(JsonNode spec, String operationId) {
    List<JsonNode> found = new ArrayList<>();
    spec.path("paths")
        .forEach(
            path ->
                path.forEach(
                    operation -> {
                      if (operationId.equals(operation.path("operationId").asString(""))) {
                        found.add(operation);
                      }
                    }));
    assertThat(found).as("operation %s", operationId).hasSize(1);
    return found.get(0);
  }

  private static List<String> names(JsonNode section) {
    List<String> names = new ArrayList<>();
    section.properties().forEach(entry -> names.add(entry.getKey()));
    return names;
  }

  private static void expectSorted(String where, List<String> names, List<String> unsorted) {
    List<String> sorted = new ArrayList<>(names);
    Collections.sort(sorted);
    if (!sorted.equals(names)) {
      unsorted.add(where + ": " + names);
    }
  }

  /**
   * US-29-07 (#451): every property of a schema a response uses is required, a nullable record
   * component is nullable and no other is; a schema a request uses is never a response's.
   */
  @Test
  void responseFieldsAreRequiredAndNullableOnlyWhereTheBackendSendsNull() {
    JsonNode spec = objectMapper.readTree(json(bootstrapAdministrator()));
    JsonNode schemas = spec.path("components").path("schemas");

    Set<String> responseSide = new HashSet<>();
    Set<String> requestSide = new HashSet<>();
    spec.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation -> {
                          schemaRefs(operation.getValue().path("responses"), schemas, responseSide);
                          schemaRefs(
                              operation.getValue().path("requestBody"), schemas, requestSide);
                          schemaRefs(operation.getValue().path("parameters"), schemas, requestSide);
                        }));
    schemaRefs(spec.path("components").path("responses"), schemas, responseSide);

    assertThat(responseSide).contains("PortfolioListItemResponse", "ImportColumnMappingResponse");
    assertThat(requestSide)
        .contains("ImportColumnMapping")
        .doesNotContain("ImportTemplateResponse");
    assertThat(responseSide).doesNotContainAnyElementsOf(requestSide);
    List<String> notRequired = new ArrayList<>();
    for (String name : responseSide) {
      if (name.equals("ApiProblem")) {
        continue;
      }
      Set<String> required = new HashSet<>();
      schemas.path(name).path("required").forEach(item -> required.add(item.asString()));
      names(schemas.path(name).path("properties")).stream()
          .filter(property -> !required.contains(property))
          .forEach(property -> notRequired.add(name + "." + property));
    }
    assertThat(notRequired).isEmpty();

    JsonNode portfolio = schemas.path("PortfolioListItemResponse").path("properties");
    assertThat(portfolio.path("logoUrl").path("type").toString())
        .isEqualTo("[\"string\",\"null\"]");
    assertThat(portfolio.path("id").path("type").asString()).isEqualTo("string");
    assertThat(schemas.path("ApiProblem").path("required").toString())
        .contains("\"title\"", "\"status\"", "\"code\"")
        .doesNotContain("\"detail\"");
  }

  /** A response-shaped record with a component that can be null. */
  record NullProbe(String id, String note) {}

  /**
   * #451: "every key is required" holds only while the application's mapper writes a null component
   * as {@code null}; a global {@code spring.jackson.default-property-inclusion} would drop it
   * silently. And the record marks of the generation never reach the served document.
   */
  @Test
  void everyKeyIsWrittenAndNoRecordMarkIsServed() {
    assertThat(objectMapper.writeValueAsString(new NullProbe("1", null)))
        .isEqualTo("{\"id\":\"1\",\"note\":null}");

    String token = bootstrapAdministrator();
    assertThat(json(token)).doesNotContain("x-trackmywealth-record");
    assertThat(served(token)).doesNotContain("x-trackmywealth-record");
  }

  /** Adds the schema names {@code node} refers to, and those they refer to in turn. */
  private static void schemaRefs(JsonNode node, JsonNode schemas, Set<String> names) {
    if (node.isObject()) {
      String ref = node.path("$ref").asString("");
      String prefix = "#/components/schemas/";
      if (ref.startsWith(prefix) && names.add(ref.substring(prefix.length()))) {
        schemaRefs(schemas.path(ref.substring(prefix.length())), schemas, names);
      }
      node.properties().forEach(child -> schemaRefs(child.getValue(), schemas, names));
    } else if (node.isArray()) {
      node.forEach(child -> schemaRefs(child, schemas, names));
    }
  }

  @Test
  void everyOperationHasItsOwnExplicitIdOneTagAndASummary() {
    JsonNode spec = objectMapper.readTree(json(bootstrapAdministrator()));

    List<String> problems = new ArrayList<>();
    Map<String, String> firstUse = new HashMap<>();
    spec.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        entry -> {
                          if (!HTTP_METHODS.contains(entry.getKey())) {
                            return;
                          }
                          String where = entry.getKey().toUpperCase() + " " + path.getKey();
                          JsonNode operation = entry.getValue();
                          String id = operation.path("operationId").asString("");
                          if (id.isEmpty() || NUMBERED_ID.matcher(id).matches()) {
                            problems.add(where + ": operationId '" + id + "'");
                          } else if (firstUse.putIfAbsent(id, where) != null) {
                            problems.add(
                                where + ": operationId '" + id + "' also on " + firstUse.get(id));
                          }
                          if (operation.path("tags").size() != 1) {
                            problems.add(where + ": tags " + operation.path("tags"));
                          }
                          if (operation.path("summary").asString("").isBlank()) {
                            problems.add(where + ": no summary");
                          }
                        }));

    assertThat(firstUse).isNotEmpty();
    assertThat(problems).isEmpty();
  }

  private String served(String token) {
    return document(token, "/api-docs.yaml");
  }

  private String json(String token) {
    return document(token, "/api-docs");
  }

  private String document(String token, String uri) {
    return client(token)
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
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
