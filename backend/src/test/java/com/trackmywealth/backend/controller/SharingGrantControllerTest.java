package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountOwnershipResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest.OwnerAllocation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.SharingGrantResponse;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import java.math.BigDecimal;
import java.sql.Connection;
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
 * US-03-03's Definition of Done: grants, verifies access, revokes, and verifies immediate denial on
 * the next request. Scoped to {@code ACCOUNT}-scope grants only, proven against {@code
 * AccountController}'s new {@code GET}/{@code PUT} endpoints - the only endpoints that consult
 * {@code AccessControlService} today (see the class's own Javadoc: {@code INSTITUTION}/{@code
 * WORKSPACE} scope have no consuming endpoint yet).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SharingGrantControllerTest {

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
    // sharing_grant/account_ownership first - both reference account/workspace_member and must be
    // deleted before their parents to satisfy FK constraints (same reasoning as
    // AccountOwnershipControllerTest's identical method).
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "sharing_grant",
              "account_ownership",
              "custom_asset_valuation",
              "account_custom_asset",
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

  @Test
  void withNoGrantAccessIsDeniedByDefault() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    String bobToken = createAndLoginSecondMember(adminToken, "bob@example.com");

    getAccount(bobToken, account.id()).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void grantingReadAccessAllowsViewingButNotEditing() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    // Own the account first: once Bob exists, the admin is no longer the workspace's sole active
    // member, so - like anyone else - the admin needs their own FULL access (here, via ownership)
    // before they can grant anyone else access to it (the same "can't share what you can't fully
    // see" rule this story applies to every other granter, including its own bootstrap admin).
    assignOwnership(adminToken, account.id(), workspaceMemberIdForEmail("admin@example.com"));
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com", PASSWORD);

    SharingGrantResponse grant =
        grant(
            adminToken,
            new CreateSharingGrantRequest(
                bobMemberId, ScopeTypeValues.ACCOUNT, account.id(), null, AccessLevelValues.READ));
    assertThat(grant.grantedToMemberId()).isEqualTo(bobMemberId);
    assertThat(grant.accessLevel()).isEqualTo(AccessLevelValues.READ);
    assertThat(grant.revokedAt()).isNull();

    getAccount(bobToken, account.id()).expectStatus().isOk();

    client(bobToken)
        .put()
        .uri("/api/v1/accounts/" + account.id())
        .contentType(MediaType.APPLICATION_JSON)
        .body(updateRequestBody(account))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void revokingAGrantDeniesTheVeryNextRequest() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    assignOwnership(adminToken, account.id(), workspaceMemberIdForEmail("admin@example.com"));
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com", PASSWORD);
    SharingGrantResponse grant =
        grant(
            adminToken,
            new CreateSharingGrantRequest(
                bobMemberId, ScopeTypeValues.ACCOUNT, account.id(), null, AccessLevelValues.READ));
    getAccount(bobToken, account.id()).expectStatus().isOk();

    SharingGrantResponse revoked = revoke(adminToken, grant.id());

    assertThat(revoked.revokedAt()).isNotNull();
    getAccount(bobToken, account.id()).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void grantingRequiresTheGranterToThemselvesHaveFullAccess() {
    // AC/error case: "a member cannot share what they cannot fully see". Bob has no access at all
    // to the account (no ownership, no grant), so Bob attempting to grant Carol access must be
    // rejected exactly like any other deny-by-default lookup - a 404, not a 403.
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com", PASSWORD);
    UUID carolMemberId = createSecondMember(adminToken, "carol@example.com");

    client(bobToken)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                carolMemberId, ScopeTypeValues.ACCOUNT, account.id(), null, AccessLevelValues.READ))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void aMalformedScopeCombinationIsRejectedWithBadRequest() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");

    client(adminToken)
        .post()
        .uri("/api/v1/sharing-grants")
        // scopeType ACCOUNT but no scopeAccountId set - V6's own CHECK constraint shape.
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                bobMemberId, ScopeTypeValues.ACCOUNT, null, null, AccessLevelValues.READ))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void grantingForAnUnknownAccountIsNotFound() {
    String adminToken = bootstrapAdministrator();
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");

    client(adminToken)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                bobMemberId,
                ScopeTypeValues.ACCOUNT,
                UUID.randomUUID(),
                null,
                AccessLevelValues.READ))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void revokingAnAlreadyRevokedGrantIsConflict() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    assignOwnership(adminToken, account.id(), workspaceMemberIdForEmail("admin@example.com"));
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    SharingGrantResponse grant =
        grant(
            adminToken,
            new CreateSharingGrantRequest(
                bobMemberId, ScopeTypeValues.ACCOUNT, account.id(), null, AccessLevelValues.READ));
    revoke(adminToken, grant.id());

    client(adminToken)
        .post()
        .uri("/api/v1/sharing-grants/" + grant.id() + "/revoke")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void theSoleActiveMemberHasImplicitFullAccessToTheirOwnAccountWithNoGrantAtAll() {
    // The bootstrap case AccessControlService's own Javadoc documents: immediately after
    // SetupService creates the workspace, the administrator has zero sharing_grant rows and no
    // account_ownership row either, yet must still be able to use their own workspace.
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);

    getAccount(adminToken, account.id()).expectStatus().isOk();
    client(adminToken)
        .put()
        .uri("/api/v1/accounts/" + account.id())
        .contentType(MediaType.APPLICATION_JSON)
        .body(updateRequestBody(account))
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void anAccountOwnerHasImplicitFullAccessWithoutAnyGrant() {
    // Once a second member exists, the sole-member bootstrap rule stops applying to a NEW account
    // created under someone else's ownership - but ownership itself (account_ownership) still
    // grants the owner implicit FULL access, with no sharing_grant row needed.
    String adminToken = bootstrapAdministrator();
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com", PASSWORD);
    AccountSummaryResponse account = createAccount(adminToken);
    assignOwnership(adminToken, account.id(), bobMemberId);

    getAccount(bobToken, account.id()).expectStatus().isOk();
  }

  @Test
  void grantingBalanceOnlyAccessAllowsViewingTheAccountSummaryInsteadOfBeingANoOp() {
    // Before this fix, getAccount required READ, one rung above BALANCE_ONLY in AccessLevelValues
    // - a BALANCE_ONLY grant was then indistinguishable from no grant at all (both 404 here).
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com", PASSWORD);

    SharingGrantResponse grant =
        grant(
            adminToken,
            new CreateSharingGrantRequest(
                bobMemberId,
                ScopeTypeValues.ACCOUNT,
                account.id(),
                null,
                AccessLevelValues.BALANCE_ONLY));
    assertThat(grant.accessLevel()).isEqualTo(AccessLevelValues.BALANCE_ONLY);

    getAccount(bobToken, account.id()).expectStatus().isOk();
  }

  @Test
  void ownershipReadAndValuationEndpointsAreGatedByAccessControlServiceToo() {
    // Not just AccountController's own GET/PUT: currentOwnership and the CUSTOM_ASSET valuation
    // endpoints expose account-scoped financial data (ownership shares, dollar-valued valuations)
    // through the very same accountId, and must deny/allow exactly in step with it.
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createCustomAssetAccount(adminToken);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com", PASSWORD);
    recordValuation(adminToken, account.id()).expectStatus().isEqualTo(HttpStatus.CREATED);

    currentOwnership(bobToken, account.id()).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    listValuations(bobToken, account.id()).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    recordValuation(bobToken, account.id()).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);

    grant(
        adminToken,
        new CreateSharingGrantRequest(
            bobMemberId, ScopeTypeValues.ACCOUNT, account.id(), null, AccessLevelValues.READ));

    currentOwnership(bobToken, account.id()).expectStatus().isOk();
    listValuations(bobToken, account.id()).expectStatus().isOk();
    // READ, not EDIT: recording a new valuation is a write and must still be denied.
    recordValuation(bobToken, account.id()).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void dependentMembersDoNotCountTowardTheSoleActiveMemberRule() {
    // A dependent workspace_member (is_dependent=true) can never log in, so it must never count
    // toward "am I the only person who could possibly be making this request" - otherwise adding
    // one would strip the one real login user of their own implicit FULL access.
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(adminToken);
    insertDependentMember(workspaceIdForEmail("admin@example.com"), "Family Dog Fund");

    getAccount(adminToken, account.id()).expectStatus().isOk();
  }

  private RestTestClient.ResponseSpec currentOwnership(String token, UUID accountId) {
    return client(token).get().uri("/api/v1/accounts/" + accountId + "/ownership").exchange();
  }

  private RestTestClient.ResponseSpec listValuations(String token, UUID accountId) {
    return client(token).get().uri("/api/v1/accounts/" + accountId + "/valuations").exchange();
  }

  private RestTestClient.ResponseSpec recordValuation(String token, UUID accountId) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateCustomAssetValuationRequest(LocalDate.now(), new BigDecimal("1000.00")))
        .exchange();
  }

  private AccountSummaryResponse createCustomAssetAccount(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null,
                "Vintage Car",
                "CUSTOM_ASSET",
                "CHF",
                null,
                null,
                null,
                null,
                null,
                "VEHICLE"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void insertDependentMember(UUID workspaceId, String displayName) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO workspace_member (id, workspace_id, display_name, is_dependent,"
                    + " status) VALUES (gen_random_uuid(), ?, ?, true, 'ACTIVE')")) {
      statement.setObject(1, workspaceId);
      statement.setString(2, displayName);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID workspaceIdForEmail(String email) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT w.id FROM workspace w JOIN workspace_member m ON m.workspace_id = w.id"
                    + " JOIN app_user u ON u.workspace_member_id = m.id WHERE u.email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("workspace for app_user with email " + email).isTrue();
        return (UUID) resultSet.getObject("id");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private RestTestClient.ResponseSpec getAccount(String token, UUID accountId) {
    return client(token).get().uri("/api/v1/accounts/" + accountId).exchange();
  }

  private UpdateAccountRequest updateRequestBody(AccountSummaryResponse account) {
    return new UpdateAccountRequest(
        account.name(), account.accountType(), account.nativeCurrency(), null, null, null, null);
  }

  private SharingGrantResponse grant(String token, CreateSharingGrantRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(SharingGrantResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private SharingGrantResponse revoke(String token, UUID grantId) {
    return client(token)
        .post()
        .uri("/api/v1/sharing-grants/" + grantId + "/revoke")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(SharingGrantResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void assignOwnership(String token, UUID accountId, UUID memberId) {
    client(token)
        .put()
        .uri("/api/v1/accounts/" + accountId + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberId, BigDecimal.ONE))))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<AccountOwnershipResponse>>() {});
  }

  private AccountSummaryResponse createAccount(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, "Family Home", "CASH", "CHF", null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
    return workspaceMemberIdForEmail(email);
  }

  private String createAndLoginSecondMember(String adminToken, String email) {
    createSecondMember(adminToken, email);
    return login(email, PASSWORD);
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

  private String login(String email, String password) {
    LoginResponse response =
        anonymousClient()
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest(email, password))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(LoginResponse.class)
            .returnResult()
            .getResponseBody();
    return response.tokens().accessToken();
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
