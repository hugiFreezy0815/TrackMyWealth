package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountOwnershipResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest.OwnerAllocation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.AccountOwnership;
import com.trackmywealth.backend.repository.AccountOwnershipRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * US-03-02: assign fractional or joint ownership of an account. The DoD's test is {@link
 * #jointOwnershipSplitsFiftyFiftyBetweenTwoMembers}: person-scoped attribution comes straight off
 * each member's own row ({@code share × account value}, though no net-worth feature exists yet to
 * actually multiply by a value - see {@code AssignAccountOwnershipRequest}'s Javadoc for the same
 * gap US-05-03/#68 through US-05-04/#70 already documented); the workspace-scoped "counted exactly
 * once" half of the AC falls out of the data model for free (both rows reference the same {@code
 * account_id}, and {@code account} itself has exactly one row for that account regardless of how
 * many owners it has), so this test only needs to verify the ownership rows themselves are shaped
 * correctly.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountOwnershipControllerTest {

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
  @Autowired AccountOwnershipRepository accountOwnershipRepository;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // See InstitutionControllerTest's identical method for why this is plain per-table DELETEs,
    // not TRUNCATE ... CASCADE.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "sharing_grant",
              "account_ownership",
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
  void assigningFullOwnershipToOneselfCreatesARowEffectiveToday() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID selfMemberId = workspaceMemberIdForEmail("admin@example.com");

    List<AccountOwnershipResponse> ownership =
        assignOwnership(
            token,
            account.id(),
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(selfMemberId, BigDecimal.ONE))));

    assertThat(ownership).hasSize(1);
    assertThat(ownership.get(0).accountId()).isEqualTo(account.id());
    assertThat(ownership.get(0).workspaceMemberId()).isEqualTo(selfMemberId);
    assertThat(ownership.get(0).share()).isEqualByComparingTo("1");
    assertThat(ownership.get(0).effectiveFrom()).isEqualTo(LocalDate.now());
    assertThat(ownership.get(0).effectiveTo()).isNull();
  }

  @Test
  void jointOwnershipSplitsFiftyFiftyBetweenTwoMembers() {
    // DoD: the 50/50 joint-account scenario. Person-scoped: each member's own row carries their
    // own share (0.5 each). Workspace-scoped "counted exactly once": both rows share the same
    // accountId - there is exactly one account row regardless of the two ownership rows fanning
    // out from it, which is the whole reason FR-HOU-005 models ownership this way.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");
    UUID memberB = createSecondMember(token, "partner@example.com");

    List<AccountOwnershipResponse> ownership =
        assignOwnership(
            token,
            account.id(),
            new AssignAccountOwnershipRequest(
                List.of(
                    new OwnerAllocation(memberA, new BigDecimal("0.5")),
                    new OwnerAllocation(memberB, new BigDecimal("0.5")))));

    assertThat(ownership).hasSize(2);
    assertThat(ownership).allMatch(o -> o.accountId().equals(account.id()));
    assertThat(ownership)
        .extracting(AccountOwnershipResponse::workspaceMemberId)
        .containsExactlyInAnyOrder(memberA, memberB);
    assertThat(ownership).allSatisfy(o -> assertThat(o.share()).isEqualByComparingTo("0.5"));
  }

  @Test
  void buyingOutAPartnersShareClosesTheOldRowsAndOpensANewOne() {
    // AC #3/FR-HOU-006: the old rows are closed (effectiveTo set), not overwritten in place.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");
    UUID memberB = createSecondMember(token, "partner@example.com");
    assignOwnership(
        token,
        account.id(),
        new AssignAccountOwnershipRequest(
            List.of(
                new OwnerAllocation(memberA, new BigDecimal("0.5")),
                new OwnerAllocation(memberB, new BigDecimal("0.5")))));

    List<AccountOwnershipResponse> afterBuyout =
        assignOwnership(
            token,
            account.id(),
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberA, BigDecimal.ONE))));

    assertThat(afterBuyout).hasSize(1);
    assertThat(afterBuyout.get(0).workspaceMemberId()).isEqualTo(memberA);
    assertThat(afterBuyout.get(0).share()).isEqualByComparingTo("1");

    // 3, not 2: createAccount's own auto-created row (US-03-03/AccountService's own Javadoc) is
    // closed by the first assignOwnership call above too, before the 50/50 split and the buyout
    // each close a row of their own.
    assertThat(closedRowCount(account.id())).isEqualTo(3);
  }

  @Test
  void concurrentAssignmentsToDisjointMembersNeverLeaveTwoRowsSimultaneouslyEffective()
      throws Exception {
    // Regression test for the race this class's own Javadoc documents: without
    // AccountRepository.findByIdForUpdate's lock, two concurrent full-replacement PUTs for
    // *disjoint* members (neither collides with V6's uq_account_ownership_current, since that
    // index is keyed per member) could both read the same pre-change state and both commit a new
    // open row, leaving the account simultaneously "owned" by both - silently over-allocated with
    // no error anywhere. The fix forces the second writer to block until the first commits and
    // then read its result, so exactly one row must be left effective afterward, regardless of
    // which request happened to win.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");
    // A standing ACCOUNT-scope FULL grant to the admin themselves, created while still the sole
    // active member (so it needs no further authorization of its own) - both racing calls below
    // are made as this same admin, and assignOwnership is now gated at EDIT (US-03-03 follow-up).
    // Without this, whichever of the two calls' findByIdForUpdate lock is granted second would
    // correctly - but for this test, unhelpfully - see that the other call already transferred
    // ownership away from the admin, and 404 instead of racing to completion at all.
    client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                memberA, ScopeTypeValues.ACCOUNT, account.id(), null, AccessLevelValues.FULL))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    UUID memberB = createSecondMember(token, "partner@example.com");
    assignOwnership(
        token,
        account.id(),
        new AssignAccountOwnershipRequest(List.of(new OwnerAllocation(memberA, BigDecimal.ONE))));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> reassertA =
          executor.submit(
              raceTask(
                  ready,
                  go,
                  () ->
                      assignOwnership(
                          token,
                          account.id(),
                          new AssignAccountOwnershipRequest(
                              List.of(new OwnerAllocation(memberA, BigDecimal.ONE))))));
      Future<?> buyoutB =
          executor.submit(
              raceTask(
                  ready,
                  go,
                  () ->
                      assignOwnership(
                          token,
                          account.id(),
                          new AssignAccountOwnershipRequest(
                              List.of(new OwnerAllocation(memberB, BigDecimal.ONE))))));

      assertThat(ready.await(10, TimeUnit.SECONDS))
          .as("both racing threads must reach the start line before either is released")
          .isTrue();
      go.countDown();

      reassertA.get(20, TimeUnit.SECONDS);
      buyoutB.get(20, TimeUnit.SECONDS);
    } finally {
      executor.shutdown();
    }

    List<AccountOwnership> stillEffective =
        accountOwnershipRepository.findByAccountIdAndEffectiveToIsNull(account.id());
    assertThat(stillEffective)
        .as("exactly one row must be effective after two racing disjoint-member assignments")
        .hasSize(1);
  }

  private static Callable<Void> raceTask(CountDownLatch ready, CountDownLatch go, Runnable action) {
    return () -> {
      ready.countDown();
      go.await(15, TimeUnit.SECONDS);
      action.run();
      return null;
    };
  }

  @Test
  void aShareSumAboveOneHundredPercentIsRejectedWithAStructuredConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");
    UUID memberB = createSecondMember(token, "partner@example.com");

    client(token)
        .put()
        .uri("/api/v1/accounts/" + account.id() + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                List.of(
                    new OwnerAllocation(memberA, new BigDecimal("0.6")),
                    new OwnerAllocation(memberB, new BigDecimal("0.6")))))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void aShareBelowOneHundredPercentIsNotRejected() {
    // "Partial data entry during onboarding is normal and must not be blocked" - only exceeding
    // 100% is an error.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");

    List<AccountOwnershipResponse> ownership =
        assignOwnership(
            token,
            account.id(),
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberA, new BigDecimal("0.5")))));

    assertThat(ownership).hasSize(1);
  }

  @Test
  void aDuplicateMemberInTheSameRequestIsRejected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");

    client(token)
        .put()
        .uri("/api/v1/accounts/" + account.id() + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                List.of(
                    new OwnerAllocation(memberA, new BigDecimal("0.3")),
                    new OwnerAllocation(memberA, new BigDecimal("0.3")))))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void assigningOwnershipForAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");

    client(token)
        .put()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberA, BigDecimal.ONE))))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void assigningOwnershipToAnUnknownMemberIsNotFound() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);

    client(token)
        .put()
        .uri("/api/v1/accounts/" + account.id() + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(UUID.randomUUID(), BigDecimal.ONE))))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void gettingCurrentOwnershipReturnsTheCurrentlyEffectiveRows() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");
    assignOwnership(
        token,
        account.id(),
        new AssignAccountOwnershipRequest(List.of(new OwnerAllocation(memberA, BigDecimal.ONE))));

    List<AccountOwnershipResponse> ownership =
        client(token)
            .get()
            .uri("/api/v1/accounts/" + account.id() + "/ownership")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<AccountOwnershipResponse>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(ownership).hasSize(1);
    assertThat(ownership.get(0).workspaceMemberId()).isEqualTo(memberA);
    assertThat(ownership.get(0).effectiveTo()).isNull();
  }

  @Test
  void gettingCurrentOwnershipForAnUnknownAccountIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .get()
        .uri("/api/v1/accounts/" + UUID.randomUUID() + "/ownership")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void assigningAnEmptyOwnerListRemovesAllOwnership() {
    // AssignAccountOwnershipRequest's own Javadoc: an empty owners list is valid and removes all
    // ownership.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");
    assignOwnership(
        token,
        account.id(),
        new AssignAccountOwnershipRequest(List.of(new OwnerAllocation(memberA, BigDecimal.ONE))));

    List<AccountOwnershipResponse> ownership =
        assignOwnership(token, account.id(), new AssignAccountOwnershipRequest(List.of()));

    assertThat(ownership).isEmpty();
    // 2, not 1: createAccount's own auto-created row (US-03-03/AccountService's own Javadoc) is
    // closed by the assignOwnership call above too, before the empty-list call closes that one.
    assertThat(closedRowCount(account.id())).isEqualTo(2);
  }

  @Test
  void reassigningTheSameShareStillClosesTheOldRowAndOpensANewOne() {
    // FR-HOU-006/AccountOwnership's own Javadoc: never an in-place edit, even when the share is
    // unchanged - a second row with a new id must be created, not the original row left as-is.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");
    List<AccountOwnershipResponse> first =
        assignOwnership(
            token,
            account.id(),
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberA, BigDecimal.ONE))));

    List<AccountOwnershipResponse> second =
        assignOwnership(
            token,
            account.id(),
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberA, BigDecimal.ONE))));

    assertThat(second.get(0).id()).isNotEqualTo(first.get(0).id());
    // 2, not 1: createAccount's own auto-created row (US-03-03/AccountService's own Javadoc) is
    // closed by the first assignOwnership call above too, before the second call closes "first".
    assertThat(closedRowCount(account.id())).isEqualTo(2);
  }

  @Test
  void aShareWithMoreThanFiveDecimalPlacesIsRejected() {
    // V6's ownership_share is NUMERIC(6,5) - more fractional digits than that must be rejected,
    // not silently rounded by Postgres on insert.
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");

    client(token)
        .put()
        .uri("/api/v1/accounts/" + account.id() + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                List.of(new OwnerAllocation(memberA, new BigDecimal("0.123456")))))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aNullElementInTheOwnersListIsRejectedWithAStructured400() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse account = createAccount(token);
    UUID memberA = workspaceMemberIdForEmail("admin@example.com");

    client(token)
        .put()
        .uri("/api/v1/accounts/" + account.id() + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AssignAccountOwnershipRequest(
                Arrays.asList(null, new OwnerAllocation(memberA, BigDecimal.ONE))))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private int closedRowCount(UUID accountId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM account_ownership WHERE account_id = ? AND effective_to IS"
                    + " NOT NULL")) {
      statement.setObject(1, accountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
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

  private UUID createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, "correct-horse-battery-staple", "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
    return workspaceMemberIdForEmail(email);
  }

  private List<AccountOwnershipResponse> assignOwnership(
      String token, UUID accountId, AssignAccountOwnershipRequest request) {
    return client(token)
        .put()
        .uri("/api/v1/accounts/" + accountId + "/ownership")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<AccountOwnershipResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createAccount(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, "Family Home", "CASH", "CHF", null, null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
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
