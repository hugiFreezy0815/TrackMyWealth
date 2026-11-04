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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
  static Optional<String> driftOf(String committed, String served) {
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

  @Test
  void theDocumentIsTheSameEveryTimeItIsGenerated() {
    String token = bootstrapAdministrator();

    assertThat(served(token)).isEqualTo(served(token));
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
    return client(token)
        .get()
        .uri("/api-docs.yaml")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private String json(String token) {
    return client(token)
        .get()
        .uri("/api-docs")
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
