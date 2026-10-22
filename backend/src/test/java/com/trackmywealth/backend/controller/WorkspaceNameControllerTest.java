package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdateWorkspaceNameRequest;
import com.trackmywealth.backend.dto.WorkspaceResponse;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** US-03-07: name updates, setup, preconditions, workspace isolation and persistent audit. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceNameControllerTest {

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
      statement.execute(
          LedgerCleanup.withGuardsOff(
              "TRUNCATE TABLE financial_audit_log, admin_audit_log, user_session, refresh_token, app_user,"
                  + " workspace_member, financial_institution, workspace"
                  + " RESTART IDENTITY CASCADE"));
    }
  }

  @Test
  void trimsRenamesIncrementsVersionAndPersistsAudit() throws Exception {
    String token = member("Initial");
    EntityExchangeResult<WorkspaceResponse> initial = current(token);
    EntityExchangeResult<WorkspaceResponse> changed =
        rename(token, initial.getResponseHeaders().getETag(), "  Hugi  ");

    assertThat(changed.getResponseBody().name()).isEqualTo("Hugi");
    assertThat(changed.getResponseBody().version())
        .isGreaterThan(initial.getResponseBody().version());
    assertThat(changed.getResponseHeaders().getETag()).isNotEqualTo(initial.getResponseHeaders().getETag());
    assertThat(current(token).getResponseBody().name()).isEqualTo("Hugi");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT action, actor_user_id, workspace_id,"
                    + " before_data ->> 'name', after_data ->> 'name'"
                    + " FROM financial_audit_log WHERE entity_type = 'WORKSPACE'");
        ResultSet rs = statement.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("UPDATE");
      assertThat(rs.getObject(2)).isNotNull();
      assertThat(rs.getObject(3)).isEqualTo(initial.getResponseBody().id());
      assertThat(rs.getString(4)).isEqualTo("Initial");
      assertThat(rs.getString(5)).isEqualTo("Hugi");
      assertThat(rs.next()).isFalse();
    }
    // Rename content is never copied into the globally visible administrator audit domain.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
            "SELECT count(*) FROM admin_audit_log WHERE action = 'WORKSPACE_NAME_CHANGED'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  @Test
  void staleAndMissingIfMatchCannotChangeNameOrWriteAudit() throws Exception {
    String token = member("Initial");
    String etag = current(token).getResponseHeaders().getETag();
    rename(token, etag, "Current");

    requestRename(token, etag, "Stale").expectStatus().isEqualTo(HttpStatus.PRECONDITION_FAILED);
    requestRename(token, null, "Missing").expectStatus().isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    assertThat(current(token).getResponseBody().name()).isEqualTo("Current");
    assertThat(auditCount()).isEqualTo(1);
  }

  @Test
  void blankAndNullNamesAreStoredAsNull() {
    String token = member("Original");
    EntityExchangeResult<WorkspaceResponse> blank =
        rename(token, current(token).getResponseHeaders().getETag(), "  ");
    assertThat(blank.getResponseBody().name()).isNull();
    assertThat(current(token).getResponseBody().name()).isNull();
    rename(token, blank.getResponseHeaders().getETag(), "New");
    EntityExchangeResult<WorkspaceResponse> cleared =
        rename(token, current(token).getResponseHeaders().getETag(), null);
    assertThat(cleared.getResponseBody().name()).isNull();
  }

  @Test
  void invalidNamesAreRejectedWithoutChangingTheWorkspace() throws Exception {
    String token = member("Valid");
    String etag = current(token).getResponseHeaders().getETag();
    for (String invalid : new String[] {"x".repeat(61), "a\nb", "bad\u0000name"}) {
      requestRename(token, etag, invalid).expectStatus().isBadRequest();
    }
    assertThat(current(token).getResponseBody().name()).isEqualTo("Valid");
    assertThat(auditCount()).isZero();
  }

  @Test
  void setupAcceptsMissingAndBlankNameAndUsesSameValidation() {
    client(null)
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"email\":\"admin@example.com\",\"password\":\""
            + PASSWORD + "\",\"currencyCode\":\"CHF\"}")
        .exchange()
        .expectStatus()
        .isCreated();
    assertThat(workspaceNameInDatabase()).isNull();
  }

  @Test
  void setupRejectsInvalidName() {
    client(null)
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "x".repeat(61), "CHF"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void unchangedNameDoesNotIncrementVersionOrCreateAudit() throws Exception {
    String token = member("Hugi");
    EntityExchangeResult<WorkspaceResponse> before = current(token);
    EntityExchangeResult<WorkspaceResponse> after =
        rename(token, before.getResponseHeaders().getETag(), "  Hugi  ");
    assertThat(after.getResponseBody().version()).isEqualTo(before.getResponseBody().version());
    assertThat(auditCount()).isZero();
  }

  @Test
  void businessMemberCanRenameButAdministratorCannot() {
    String memberToken = member("Original");
    String adminToken = login("admin@example.com");
    client(adminToken)
        .put()
        .uri("/api/v1/workspace/name")
        .header("If-Match", current(memberToken).getResponseHeaders().getETag())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateWorkspaceNameRequest("Forbidden"))
        .exchange()
        .expectStatus()
        .isNotFound();
    rename(memberToken, current(memberToken).getResponseHeaders().getETag(), "Allowed");
    assertThat(current(memberToken).getResponseBody().name()).isEqualTo("Allowed");
  }

  @Test
  void workspaceNameCannotBeChangedThroughAnotherWorkspacesSession() throws Exception {
    String tokenA = member("Workspace A");
    String workspaceAId = current(tokenA).getResponseBody().id().toString();
    // A second user is a separate workspace member with a real login. A separate workspace
    // is created directly because the current setup API bootstraps only once.
    String adminToken = login("admin@example.com");
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest("other@example.com", PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement workspace = connection.prepareStatement(
            "INSERT INTO workspace (id, name, currency) VALUES (?, 'Workspace B', 'CHF')");
        PreparedStatement move = connection.prepareStatement(
            "UPDATE workspace_member SET workspace_id = ? WHERE id ="
                + " (SELECT workspace_member_id FROM app_user WHERE email = 'other@example.com')")) {
      UUID workspaceB = UUID.randomUUID();
      workspace.setObject(1, workspaceB);
      workspace.executeUpdate();
      move.setObject(1, workspaceB);
      move.executeUpdate();
    }
    String tokenB = login("other@example.com");
    assertThat(current(tokenB).getResponseBody().name()).isEqualTo("Workspace B");
    rename(tokenB, current(tokenB).getResponseHeaders().getETag(), "Renamed B");
    assertThat(current(tokenA).getResponseBody().name()).isEqualTo("Workspace A");
    assertThat(current(tokenA).getResponseBody().id().toString()).isEqualTo(workspaceAId);
    assertThat(current(tokenB).getResponseBody().name()).isEqualTo("Renamed B");
  }

  private String workspaceNameInDatabase() {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT name FROM workspace LIMIT 1")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String member(String name) {
    String adminToken = bootstrap(name);
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest("member@example.com", PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
    return login("member@example.com");
  }

  private long auditCount() throws Exception {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(
            "SELECT count(*) FROM financial_audit_log WHERE entity_type = 'WORKSPACE'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private EntityExchangeResult<WorkspaceResponse> rename(String token, String etag, String name) {
    return requestRename(token, etag, name)
        .expectStatus()
        .isOk()
        .expectBody(WorkspaceResponse.class)
        .returnResult();
  }

  private RestTestClient.ResponseSpec requestRename(String token, String etag, String name) {
    RestTestClient.RequestBodySpec request =
        client(token)
            .put()
            .uri("/api/v1/workspace/name")
            .contentType(MediaType.APPLICATION_JSON);
    if (etag != null) {
      request.header("If-Match", etag);
    }
    return request.body(new UpdateWorkspaceNameRequest(name)).exchange();
  }

  private EntityExchangeResult<WorkspaceResponse> current(String token) {
    return client(token)
        .get()
        .uri("/api/v1/workspace")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(WorkspaceResponse.class)
        .returnResult();
  }

  private String bootstrap(String name) {
    return client(null)
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, name, "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private String login(String email) {
    return client(null)
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

  private RestTestClient client(String token) {
    RestTestClient client =
        RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
    return token == null
        ? client
        : RestTestClient.bindToServer()
            .baseUrl("http://localhost:%d".formatted(port))
            .defaultHeader("Authorization", "Bearer " + token)
            .build();
  }
}
