package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.RefreshTokenRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.dto.WorkspaceMemberListItemResponse;
import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
 * US-03-04/FR-HHL-015: a workspace must always retain at least one active member.
 *
 * <p>The DoD's two scenarios are {@link #deactivatingTheLastActiveMemberIsRejected} (one active
 * member) and {@link #deactivatingSelfWithAnotherActiveMemberRemainingSucceeds} (two active
 * members). Self-service only for now (see {@code WorkspaceMemberService}'s own Javadoc for why) -
 * {@link #deactivatingAnotherMembersAccountIsNotFound} and {@link
 * #deactivatingARandomUnrelatedIdIsNotFound} both cover that boundary via {@code
 * WorkspaceMemberService.requireSelf} (one with a real id belonging to someone else, one with an id
 * that doesn't exist at all - both rejected identically, which is the point). Neither reaches
 * {@code extractTargetOrThrow}'s own not-found branch, which self-service-only scope makes
 * unreachable via this API today (it would need the acting member's own row to disappear between
 * authentication and the lock query).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceMemberControllerTest {

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

  private static final String SECOND_MEMBER_PASSWORD = "correct-horse-battery-staple";

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // See InstitutionControllerTest's identical method for why this is plain per-table DELETEs,
    // not TRUNCATE ... CASCADE.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "authorization_denial_log",
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
  void deactivatingTheLastActiveMemberIsRejected() {
    // AC #1: a workspace with one active member - the bootstrap administrator is always that
    // workspace's sole member until a second is created.
    String token = bootstrapAdministrator();
    UUID selfMemberId = workspaceMemberIdForEmail("admin@example.com");

    client(token)
        .post()
        .uri("/api/v1/workspace-members/" + selfMemberId + "/deactivate")
        .headers(CurrentVersion.ifMatch(dataSource, "workspace_member", selfMemberId))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(memberStatus(selfMemberId)).isEqualTo("ACTIVE");
  }

  // #207 / FR-CNC-001: GET /workspace-members/{id} (own membership only) returns the ETag. Only the
  // member can deactivate themselves, and a deactivation logs every device out, so the race is
  // with any other change to the member row in between - simulated by bumping its version directly.
  // A deactivation based on that stale read is a 412 and changes nothing; one without If-Match is
  // a 428; one with the current ETag succeeds.
  @Test
  void aStaleMemberDeactivationIsRejectedAndTheFirstIsKept() throws Exception {
    bootstrapAdministrator();
    createSecondMember("partner@example.com");
    String partnerToken = login("partner@example.com", SECOND_MEMBER_PASSWORD);
    UUID partnerMemberId = workspaceMemberIdForEmail("partner@example.com");

    deactivateWith(partnerToken, partnerMemberId, null)
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");

    String staleEtag = memberEtag(partnerToken, partnerMemberId);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE workspace_member SET updated_at = now() WHERE id = ?")) {
      statement.setObject(1, partnerMemberId);
      statement.executeUpdate();
    }

    deactivateWith(partnerToken, partnerMemberId, staleEtag)
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    assertThat(memberStatus(partnerMemberId)).isEqualTo("ACTIVE");

    CurrentVersion.storedEtag(
        deactivateWith(partnerToken, partnerMemberId, memberEtag(partnerToken, partnerMemberId))
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult(),
        dataSource,
        "workspace_member",
        partnerMemberId);
    assertThat(memberStatus(partnerMemberId)).isEqualTo("INACTIVE");
  }

  private String memberEtag(String token, UUID memberId) {
    String etag =
        client(token)
            .get()
            .uri("/api/v1/workspace-members/" + memberId)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult()
            .getResponseHeaders()
            .getETag();
    assertThat(etag).isNotNull();
    return etag;
  }

  @Test
  void deactivatingSelfWithAnotherActiveMemberRemainingSucceeds() {
    // AC #2: a workspace with two active members - deactivating one succeeds and the other
    // remains ACTIVE.
    String adminToken = bootstrapAdministrator();
    UUID adminMemberId = workspaceMemberIdForEmail("admin@example.com");
    createSecondMember("partner@example.com");
    String partnerToken = login("partner@example.com", SECOND_MEMBER_PASSWORD);
    UUID partnerMemberId = workspaceMemberIdForEmail("partner@example.com");

    WorkspaceMemberSummaryResponse deactivated =
        client(partnerToken)
            .post()
            .uri("/api/v1/workspace-members/" + partnerMemberId + "/deactivate")
            .headers(CurrentVersion.ifMatch(dataSource, "workspace_member", partnerMemberId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(WorkspaceMemberSummaryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(deactivated.status()).isEqualTo("INACTIVE");
    assertThat(deactivated.memberUntil()).isEqualTo(LocalDate.now());
    assertThat(memberStatus(partnerMemberId)).isEqualTo("INACTIVE");
    assertThat(memberStatus(adminMemberId)).isEqualTo("ACTIVE");
  }

  // US-02-06: a deactivated member's access tokens already fail JwtAuthenticationFilter, so login
  // and refresh must not issue new ones either. Otherwise a client is handed a token that is
  // rejected on first use and cannot tell "renew the session" from "sign in again".
  @Test
  void aDeactivatedMemberCanNeitherSignInAgainNorRefresh() {
    bootstrapAdministrator();
    UUID partnerMemberId = createSecondMember("partner@example.com");
    AuthTokensResponse partner = loginTokens("partner@example.com", SECOND_MEMBER_PASSWORD);
    deactivateWith(
            partner.accessToken(),
            partnerMemberId,
            memberEtag(partner.accessToken(), partnerMemberId))
        .expectStatus()
        .isOk();

    anonymousClient()
        .post()
        .uri("/api/v1/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RefreshTokenRequest(partner.refreshToken()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    anonymousClient()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest("partner@example.com", SECOND_MEMBER_PASSWORD))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // #207 review M2/M3: the GET added for refreshing a member's ETag is self-only like deactivation.
  // Another member's row is the audited generic 404 - and so is deactivating it without If-Match,
  // never the 428 that would confirm it exists.
  @Test
  void anotherMembersRowCannotBeReadOrProbedWithoutIfMatch() {
    String adminToken = bootstrapAdministrator();
    UUID adminUserId = appUserIdForEmail("admin@example.com");
    UUID partnerMemberId = createSecondMember("partner@example.com");

    client(adminToken)
        .get()
        .uri("/api/v1/workspace-members/" + partnerMemberId)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(authorizationDenialLogged(adminUserId, partnerMemberId)).isTrue();

    client(adminToken)
        .post()
        .uri("/api/v1/workspace-members/" + partnerMemberId + "/deactivate")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(memberStatus(partnerMemberId)).isEqualTo("ACTIVE");
  }

  @Test
  void deactivatingAnotherMembersAccountIsNotFound() {
    // Self-service only for now: an active member with no grant mechanism yet available (US-03-03
    // is not merged - see WorkspaceMemberService's Javadoc) must not be able to deactivate someone
    // else, even another member of their own workspace. Also verifies FR-TEN-006/US-28-02: this
    // denial must be audited via AuthorizationDenialAuditService, the same as every other
    // single-resource authorization denial in the app (see SessionService.revokeSession).
    String adminToken = bootstrapAdministrator();
    UUID adminUserId = appUserIdForEmail("admin@example.com");
    UUID partnerMemberId = createSecondMember("partner@example.com");

    client(adminToken)
        .post()
        .uri("/api/v1/workspace-members/" + partnerMemberId + "/deactivate")
        .headers(CurrentVersion.ifMatch(dataSource, "workspace_member", partnerMemberId))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);

    assertThat(memberStatus(partnerMemberId)).isEqualTo("ACTIVE");
    assertThat(authorizationDenialLogged(adminUserId, partnerMemberId)).isTrue();
  }

  @Test
  void deactivatingARandomUnrelatedIdIsNotFound() {
    // Same requireSelf mismatch branch as deactivatingAnotherMembersAccountIsNotFound above, not
    // WorkspaceMemberService.extractTargetOrThrow's own not-found branch - see this class's own
    // Javadoc for why that branch isn't reachable via this API today. Still worth pinning
    // separately: a syntactically-valid-but-nonexistent id must be rejected exactly as uniformly
    // as a real other member's id, never distinguishably.
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/workspace-members/" + UUID.randomUUID() + "/deactivate")
        .headers(CurrentVersion.ifMatch(dataSource, "workspace_member", UUID.randomUUID()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void anonymousRequestIsUnauthorized() {
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/workspace-members/" + UUID.randomUUID() + "/deactivate")
        .headers(CurrentVersion.ifMatch(dataSource, "workspace_member", UUID.randomUUID()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // US-03-05 AC 1 and 3: every member is listed, a deactivated one with active=false. The
  // administrator's bootstrap membership is not one of the workspace's people (FR-TEN-007).
  @Test
  void aMemberListsAllMembersOfTheWorkspaceIncludingDeactivatedOnes() throws Exception {
    bootstrapAdministrator();
    UUID anna = seedBusinessMember("anna@example.com");
    UUID ben = seedBusinessMember("ben@example.com");
    UUID clara = seedBusinessMember("clara@example.com");
    // Directly, not through the deactivation endpoint: that is self-service only.
    updateMemberStatus(clara, "INACTIVE");

    WorkspaceMemberListItemResponse[] listed =
        listMembers(login("anna@example.com", SECOND_MEMBER_PASSWORD));

    assertThat(listed)
        .extracting(WorkspaceMemberListItemResponse::id)
        .containsExactly(anna, ben, clara);
    assertThat(listed)
        .extracting(WorkspaceMemberListItemResponse::active)
        .containsExactly(true, true, false);
    assertThat(listed)
        .extracting(WorkspaceMemberListItemResponse::userId)
        .containsExactly(
            appUserIdForEmail("anna@example.com"),
            appUserIdForEmail("ben@example.com"),
            appUserIdForEmail("clara@example.com"));
    assertThat(listed)
        .extracting(WorkspaceMemberListItemResponse::displayName)
        .containsExactly("anna", "ben", "clara");
  }

  @Test
  void anAnonymousCallerCannotListMembers() {
    anonymousClient()
        .get()
        .uri("/api/v1/workspace-members")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void listNeverReturnsMembersOfAnotherWorkspace() throws Exception {
    bootstrapAdministrator();
    UUID own = seedBusinessMember("own@example.com");
    UUID foreign = seedBusinessMember("foreign@example.com");
    UUID otherWorkspace = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement createWorkspace =
            connection.prepareStatement(
                "INSERT INTO workspace (id, name, currency) VALUES (?, 'Foreign', 'CHF')");
        PreparedStatement moveMember =
            connection.prepareStatement(
                "UPDATE workspace_member SET workspace_id = ? WHERE id = ?")) {
      createWorkspace.setObject(1, otherWorkspace);
      createWorkspace.executeUpdate();
      moveMember.setObject(1, otherWorkspace);
      moveMember.setObject(2, foreign);
      moveMember.executeUpdate();
    }

    String ownToken = login("own@example.com", SECOND_MEMBER_PASSWORD);
    String foreignToken = login("foreign@example.com", SECOND_MEMBER_PASSWORD);
    WorkspaceMemberListItemResponse[] ownList = listMembers(ownToken);
    WorkspaceMemberListItemResponse[] foreignList = listMembers(foreignToken);
    assertThat(ownList).extracting(WorkspaceMemberListItemResponse::id).containsExactly(own);
    assertThat(foreignList)
        .extracting(WorkspaceMemberListItemResponse::id)
        .containsExactly(foreign);
  }

  // FR-USR-07: the administrator is not one of the workspace's people, even while setup still
  // makes it the sole member (until FR-USR-013) - so it gets the generic 404, not an empty list.
  @Test
  void theAdministratorCannotListMembers() {
    String adminToken = bootstrapAdministrator();

    client(adminToken)
        .get()
        .uri("/api/v1/workspace-members")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  // The id tie-break makes the order stable when display names repeat. PostgreSQL orders uuid
  // bytewise, which is the order of their lower-case text form.
  @Test
  void membersWithTheSameDisplayNameAreOrderedById() throws Exception {
    bootstrapAdministrator();
    UUID first = seedBusinessMember("sam@one.example.com");
    UUID second = seedBusinessMember("sam@two.example.com");

    WorkspaceMemberListItemResponse[] listed =
        listMembers(login("sam@one.example.com", SECOND_MEMBER_PASSWORD));

    assertThat(listed)
        .extracting(WorkspaceMemberListItemResponse::id)
        .containsExactlyElementsOf(
            Stream.of(first, second).sorted(Comparator.comparing(UUID::toString)).toList());
  }

  // SQL fixture stays valid after #389 removes administrator-created multi-member onboarding.
  private UUID seedBusinessMember(String email) throws Exception {
    UUID memberId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement member =
            connection.prepareStatement(
                "INSERT INTO workspace_member (id, workspace_id, display_name)"
                    + " SELECT ?, w.workspace_id, ? FROM workspace_member w"
                    + " JOIN app_user a ON a.workspace_member_id = w.id"
                    + " WHERE a.email = 'admin@example.com'");
        PreparedStatement user =
            connection.prepareStatement(
                "INSERT INTO app_user (email, password_hash, role, workspace_member_id)"
                    + " SELECT ?, password_hash, 'STANDARD_USER', ?"
                    + " FROM app_user WHERE email = 'admin@example.com'")) {
      member.setObject(1, memberId);
      member.setString(2, email.split("@", 2)[0]);
      assertThat(member.executeUpdate()).isEqualTo(1);
      user.setString(1, email);
      user.setObject(2, memberId);
      assertThat(user.executeUpdate()).isEqualTo(1);
    }
    return memberId;
  }

  private WorkspaceMemberListItemResponse[] listMembers(String token) {
    return client(token)
        .get()
        .uri("/api/v1/workspace-members")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(WorkspaceMemberListItemResponse[].class)
        .returnResult()
        .getResponseBody();
  }

  private void updateMemberStatus(UUID memberId, String status) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE workspace_member SET status = ? WHERE id = ?")) {
      statement.setString(1, status);
      statement.setObject(2, memberId);
      statement.executeUpdate();
    }
  }

  private RestTestClient.ResponseSpec deactivateWith(String token, UUID memberId, String ifMatch) {
    return client(token)
        .post()
        .uri("/api/v1/workspace-members/" + memberId + "/deactivate")
        .headers(
            headers -> {
              if (ifMatch != null) {
                headers.setIfMatch(ifMatch);
              }
            })
        .exchange();
  }

  private String memberStatus(UUID memberId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT status FROM workspace_member WHERE id = ?")) {
      statement.setObject(1, memberId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("workspace_member " + memberId).isTrue();
        return resultSet.getString("status");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID workspaceMemberIdForEmail(String email) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("app_user with email " + email).isTrue();
        return (UUID) resultSet.getObject("workspace_member_id");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID appUserIdForEmail(String email) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("app_user with email " + email).isTrue();
        return (UUID) resultSet.getObject("id");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // FR-TEN-006/US-28-02: confirms WorkspaceMemberService's denial actually reached
  // AuthorizationDenialAuditService, not just that the caller saw a 404 - the two are only
  // guaranteed to coincide because this test exercises the real service, not a mock of it.
  private boolean authorizationDenialLogged(UUID principalUserId, UUID requestedEntityId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT 1 FROM authorization_denial_log WHERE principal_user_id = ? AND"
                    + " requested_entity_type = 'WorkspaceMember' AND requested_entity_id = ?")) {
      statement.setObject(1, principalUserId);
      statement.setObject(2, requestedEntityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID createSecondMember(String email) {
    String adminToken = login("admin@example.com", "correct-horse-battery-staple");
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, SECOND_MEMBER_PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
    return workspaceMemberIdForEmail(email);
  }

  private String login(String email, String password) {
    return loginTokens(email, password).accessToken();
  }

  private AuthTokensResponse loginTokens(String email, String password) {
    return anonymousClient()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, password))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens();
  }

  private RestTestClient anonymousClient() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(port)).build();
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
