package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UpdateCategoryRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CategoryService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-08-04: the workspace's category taxonomy against a real PostgreSQL. The DoD's test is {@link
 * #aCustomSubcategoryIsCreatedAndAUsedDefaultIsDeactivatedNotDeleted}; the others pin the remaining
 * acceptance criteria, the decisions recorded on issue #144 and the V34 guards.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CategoryControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String BASE = "/api/v1/categories";
  private static final ParameterizedTypeReference<List<CategoryResponse>> CATEGORY_LIST =
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

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @Autowired CategoryService categoryService;

  // Leaves the shipped defaults (workspace_id IS NULL, V19) in place: they are the subject here.
  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM workspace_category_override",
              "DELETE FROM categorization_rule",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction_category_split",
              "DELETE FROM transaction",
              // children first: the parent FK has no cascade
              "DELETE FROM category WHERE workspace_id IS NOT NULL AND parent_category_id IN"
                  + " (SELECT id FROM category WHERE workspace_id IS NOT NULL)",
              "DELETE FROM category WHERE workspace_id IS NOT NULL",
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
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

  // --- Definition of Done ------------------------------------------------------------------

  @Test
  void aCustomSubcategoryIsCreatedAndAUsedDefaultIsDeactivatedNotDeleted() throws Exception {
    String token = bootstrapAdministrator();
    UUID workspaceId = workspaceOf("admin@example.com");
    UUID leisure = defaultId("LEISURE");

    // AC 1: a workspace row whose parent is the shipped default.
    CategoryResponse streaming =
        create(
                token,
                new CreateCategoryRequest(leisure, "Streaming Subscriptions", "Streaming-Abos"))
            .expectStatus()
            .isCreated()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(streaming.parentId()).isEqualTo(leisure);
    assertThat(streaming.code()).isEqualTo("WS_STREAMING_SUBSCRIPTIONS");
    assertThat(streaming.level()).isEqualTo(2);
    assertThat(jdbcUuid("SELECT workspace_id FROM category WHERE id = ?", streaming.id()))
        .isEqualTo(workspaceId);
    assertThat(jdbcUuid("SELECT parent_category_id FROM category WHERE id = ?", streaming.id()))
        .isEqualTo(leisure);

    // AC 2: a default with a rule depending on it cannot be hard-deleted ...
    insertRule(workspaceId, leisure);
    delete(token, leisure).expectStatus().isEqualTo(HttpStatus.CONFLICT);
    assertThat(count("SELECT count(*) FROM category WHERE id = ?", leisure)).isEqualTo(1);

    // ... but deactivating it succeeds, cascades, and preserves what depends on it.
    CategoryResponse deactivated =
        deactivate(token, leisure)
            .expectStatus()
            .isOk()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(deactivated.active()).isFalse();
    assertThat(deactivated.customised()).isTrue();
    assertThat(codes(list(token, false))).doesNotContain("LEISURE", "WS_STREAMING_SUBSCRIPTIONS");
    assertThat(list(token, true))
        .filteredOn(c -> List.of(leisure, streaming.id()).contains(c.id()))
        .extracting(CategoryResponse::active)
        .containsExactly(false, false);
    assertThat(count("SELECT count(*) FROM categorization_rule")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM category WHERE code = 'LEISURE' AND is_active"))
        .as("the shared row itself is never edited")
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM workspace_category_override WHERE category_id = ? AND"
                    + " is_active = false",
                leisure))
        .isEqualTo(1);

    // The deactivation is this workspace's alone.
    AuthenticatedUserPrincipal other = seedSecondWorkspace();
    List<CategoryResponse> seenByOther = as(other, () -> categoryService.list(false, other));
    assertThat(codes(seenByOther)).contains("LEISURE").doesNotContain("WS_STREAMING_SUBSCRIPTIONS");
  }

  // --- EPIC-29 optimistic concurrency (FR-CNC-001/002) --------------------------------------

  @Test
  void categoryGetReturnsVersionAndMatchingEtag() {
    String token = bootstrapAdministrator();
    CategoryResponse created = created(token, new CreateCategoryRequest(null, "Hobby", "Hobby"));

    client(token)
        .get()
        .uri(BASE + "/" + created.id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"" + created.version() + "\"")
        .expectBody()
        .jsonPath("$.version")
        .isEqualTo(created.version());
  }

  @Test
  void categoryUpdateWithoutIfMatchIsPreconditionRequired() {
    String token = bootstrapAdministrator();
    CategoryResponse created = created(token, new CreateCategoryRequest(null, "Hobby", "Hobby"));

    client(token)
        .put()
        .uri(BASE + "/" + created.id())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpdateCategoryRequest(null, "Renamed", "Umbenannt"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");
  }

  @Test
  void staleCategoryUpdateIsRejectedAndTheWinningWriteIsKept() {
    String token = bootstrapAdministrator();
    CategoryResponse readByBothClients =
        created(token, new CreateCategoryRequest(null, "Original", "Original"));
    int versionReadByBoth = readByBothClients.version();

    CategoryResponse firstWrite =
        update(
                token,
                readByBothClients.id(),
                new UpdateCategoryRequest(null, "First Writer", "Erster"),
                versionReadByBoth)
            .expectStatus()
            .isOk()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(firstWrite.version()).isGreaterThan(versionReadByBoth);

    update(
            token,
            readByBothClients.id(),
            new UpdateCategoryRequest(null, "Stale Writer", "Veraltet"),
            versionReadByBoth)
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");

    CategoryResponse current = getCategory(token, readByBothClients.id());
    assertThat(current.nameEn()).isEqualTo("First Writer");
    assertThat(current.version()).isEqualTo(firstWrite.version());
  }

  @Test
  void sharedDefaultVersionNeverResetsAcrossCustomizeRevertCustomize() {
    String token = bootstrapAdministrator();
    UUID leisure = defaultId("LEISURE");
    CategoryResponse original = getCategory(token, leisure);

    CategoryResponse customised =
        update(
                token,
                leisure,
                new UpdateCategoryRequest(null, "Free Time", "Freizeit"),
                original.version())
            .expectStatus()
            .isOk()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();

    CategoryResponse reverted =
        update(
                token,
                leisure,
                new UpdateCategoryRequest(null, "Leisure", "Freizeit"),
                customised.version())
            .expectStatus()
            .isOk()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();

    CategoryResponse customisedAgain =
        update(
                token,
                leisure,
                new UpdateCategoryRequest(null, "Fun", "Freizeit"),
                reverted.version())
            .expectStatus()
            .isOk()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(customised.version()).isGreaterThan(original.version());
    assertThat(reverted.version()).isGreaterThan(customised.version());
    assertThat(reverted.customised()).isFalse();
    assertThat(customisedAgain.version()).isGreaterThan(reverted.version());

    update(
            token,
            leisure,
            new UpdateCategoryRequest(null, "Stale", "Veraltet"),
            customised.version())
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
  }

  // --- Acceptance criteria and decisions -----------------------------------------------------

  @Test
  void renamingADefaultLeavesHistoricalClassificationKeyedByItsCode() throws Exception {
    String token = bootstrapAdministrator();
    UUID groceries = defaultId("GROCERIES");
    UUID transactionId = insertTransaction(token, workspaceOf("admin@example.com"), groceries);

    CategoryResponse renamed =
        update(
                token,
                groceries,
                new UpdateCategoryRequest(null, "Food & Drink", "Essen & Trinken"),
                0)
            .expectStatus()
            .isOk()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(renamed.code()).isEqualTo("GROCERIES");
    assertThat(renamed.nameEn()).isEqualTo("Food & Drink");
    assertThat(renamed.nameDe()).isEqualTo("Essen & Trinken");
    assertThat(
            jdbcString(
                "SELECT c.code FROM transaction t JOIN category c ON c.id = t.category_id"
                    + " WHERE t.id = ?",
                transactionId))
        .isEqualTo("GROCERIES");
    assertThat(jdbcString("SELECT name_en FROM category WHERE id = ?", groceries))
        .as("shared label unchanged for every other workspace")
        .isEqualTo("Groceries");
  }

  @Test
  void categoriesNestAtMostThreeLevelsDeep() {
    String token = bootstrapAdministrator();
    CategoryResponse level2 =
        created(token, new CreateCategoryRequest(defaultId("LEISURE"), "Sport", "Sport"));
    CategoryResponse level3 =
        created(token, new CreateCategoryRequest(level2.id(), "Football", "Fussball"));

    assertThat(level3.level()).isEqualTo(3);
    create(token, new CreateCategoryRequest(level3.id(), "Boots", "Schuhe"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void anOwnCategoryCanBeMovedButNotUnderItsOwnSubtree() {
    String token = bootstrapAdministrator();
    CategoryResponse hobby = created(token, new CreateCategoryRequest(null, "Hobby", "Hobby"));
    CategoryResponse music =
        created(token, new CreateCategoryRequest(hobby.id(), "Music", "Musik"));

    update(
            token,
            hobby.id(),
            new UpdateCategoryRequest(music.id(), "Hobby", "Hobby"),
            hobby.version())
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    update(
            token,
            hobby.id(),
            new UpdateCategoryRequest(defaultId("LEISURE"), "Hobby", "Hobby"),
            hobby.version())
        .expectStatus()
        .isOk();
    assertThat(list(token, false))
        .filteredOn(c -> c.id().equals(music.id()))
        .singleElement()
        .extracting(CategoryResponse::level)
        .isEqualTo(3);
  }

  @Test
  void aNeverUsedOwnCategoryIsDeletedButOneWithASubcategoryIsNot() {
    String token = bootstrapAdministrator();
    CategoryResponse hobby = created(token, new CreateCategoryRequest(null, "Hobby", "Hobby"));
    CategoryResponse music =
        created(token, new CreateCategoryRequest(hobby.id(), "Music", "Musik"));

    delete(token, hobby.id()).expectStatus().isEqualTo(HttpStatus.CONFLICT);
    delete(token, music.id()).expectStatus().isNoContent();
    delete(token, hobby.id()).expectStatus().isNoContent();
    client(token).get().uri(BASE + "/" + hobby.id()).exchange().expectStatus().isNotFound();
  }

  @Test
  void protectedCategoriesCannotBeDeactivatedOrGivenSubcategories() {
    String token = bootstrapAdministrator();

    deactivate(token, defaultId("UNCATEGORIZED"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    create(token, new CreateCategoryRequest(defaultId("TRANSFER_INTERNAL"), "Child", "Kind"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(list(token, false))
        .filteredOn(CategoryResponse::protectedCategory)
        .extracting(CategoryResponse::code)
        .containsExactlyInAnyOrder("UNCATEGORIZED", "TRANSFER_INTERNAL");
  }

  @Test
  void bothLabelsAreRequiredAndUniqueAmongSiblings() {
    String token = bootstrapAdministrator();

    create(token, new CreateCategoryRequest(null, "Hobby", " ")).expectStatus().isBadRequest();
    create(token, new CreateCategoryRequest(null, "housing", "Anderes"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void theShippedDefaultsAreListedInTreeOrder() {
    String token = bootstrapAdministrator();

    assertThat(codes(list(token, false)))
        .containsExactly(
            "FEES", // V37: "Fees & Charges"
            "GROCERIES",
            "HEALTH",
            "HOUSING",
            "UTILITIES", // V37: "Utilities & Telecom" under Housing
            "INCOME",
            "INSURANCE",
            "TRANSFER_INTERNAL", // "Internal Transfer"
            "LEISURE",
            "DINING", // V37: "Dining Out" under Leisure
            "TRAVEL",
            "OTHER",
            "SAVINGS_INVEST",
            "SHOPPING",
            "TAXES",
            "TRANSPORT",
            "UNCATEGORIZED");
  }

  // --- authorization and isolation -------------------------------------------------------------

  @Test
  void changingTheTaxonomyNeedsWorkspaceEditWhileReadingNeedsMembershipOnly() {
    String adminToken = bootstrapAdministrator();
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    client(memberToken).get().uri(BASE).exchange().expectStatus().isOk();
    create(memberToken, new CreateCategoryRequest(null, "Hobby", "Hobby"))
        .expectStatus()
        .isNotFound();

    grantWorkspace(adminToken, memberId, AccessLevelValues.EDIT);
    create(memberToken, new CreateCategoryRequest(null, "Hobby", "Hobby"))
        .expectStatus()
        .isCreated();
  }

  @Test
  void anotherWorkspacesCategoryIsNotFound() throws Exception {
    String token = bootstrapAdministrator();
    CategoryResponse hobby = created(token, new CreateCategoryRequest(null, "Hobby", "Hobby"));
    AuthenticatedUserPrincipal other = seedSecondWorkspace();

    assertThatThrownBy(() -> as(other, () -> categoryService.get(hobby.id(), other)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  // --- V34 guards --------------------------------------------------------------------------

  @Test
  void theSchemaKeepsCodesUniqueAndInSeparateNamespaces() throws Exception {
    String token = bootstrapAdministrator();
    UUID workspaceId = workspaceOf("admin@example.com");

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO category (workspace_id, code, name_en, name_de) VALUES (NULL,"
                        + " 'LEISURE', 'x', 'x')"))
        .as("default codes are unique too (NULLS NOT DISTINCT)")
        .hasMessageContaining("uq_category_workspace_code");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO category (workspace_id, code, name_en, name_de) VALUES (?,"
                        + " 'LEISURE', 'x', 'x')",
                    workspaceId))
        .as("a workspace code cannot use the default namespace")
        .hasMessageContaining("category_code_namespace");
    CategoryResponse hobby = created(token, new CreateCategoryRequest(null, "Hobby", "Hobby"));
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO workspace_category_override (workspace_id, category_id, is_active)"
                        + " VALUES (?, ?, false)",
                    workspaceId,
                    hobby.id()))
        .as("only a shared default can be overridden")
        .hasMessageContaining("workspace_category_override_default_only");
  }

  // --- review follow-ups ---------------------------------------------------------------------

  @Test
  void canEditTellsAReadOnlyMemberWhatTheyMayNotDo() {
    String adminToken = bootstrapAdministrator();
    // The sole active member has FULL implicitly - only until a second member joins.
    assertThat(list(adminToken, false)).extracting(CategoryResponse::canEdit).containsOnly(true);
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    assertThat(list(memberToken, false)).extracting(CategoryResponse::canEdit).containsOnly(false);

    grantWorkspace(adminToken, memberId, AccessLevelValues.EDIT);
    assertThat(list(memberToken, false)).extracting(CategoryResponse::canEdit).containsOnly(true);
  }

  // Without the workspace lock both moves pass their in-memory cycle check and the two categories
  // end up each other's parent, vanishing from the tree. With it, the second sees the first.
  @Test
  void concurrentCrossMovesCannotFormACycle() throws Exception {
    String token = bootstrapAdministrator();
    CategoryResponse hobby = created(token, new CreateCategoryRequest(null, "Hobby", "Hobby"));
    CategoryResponse music = created(token, new CreateCategoryRequest(null, "Music", "Musik"));
    CountDownLatch start = new CountDownLatch(1);
    Callable<HttpStatus> hobbyUnderMusic =
        () -> {
          start.await();
          return moveStatus(token, hobby, music.id());
        };
    Callable<HttpStatus> musicUnderHobby =
        () -> {
          start.await();
          return moveStatus(token, music, hobby.id());
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<HttpStatus> first = pool.submit(hobbyUnderMusic);
      Future<HttpStatus> second = pool.submit(musicUnderHobby);
      start.countDown();
      assertThat(List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_CONTENT);
    } finally {
      pool.shutdownNow();
    }
    assertThat(codes(list(token, true))).contains(hobby.code(), music.code());
  }

  @Test
  void anOverrideGoesWithTheDefaultItCustomises() throws Exception {
    String token = bootstrapAdministrator();
    UUID retired = UUID.randomUUID();
    execute(
        "INSERT INTO category (id, workspace_id, code, name_en, name_de, is_system_default)"
            + " VALUES (?, NULL, 'TEST_RETIRED', 'Retired', 'Ausgemustert', TRUE)",
        retired);
    deactivate(token, retired).expectStatus().isOk();
    assertThat(
            count(
                "SELECT count(*) FROM workspace_category_override WHERE category_id = ?", retired))
        .isEqualTo(1);

    execute("DELETE FROM category WHERE id = ?", retired);

    assertThat(
            count(
                "SELECT count(*) FROM workspace_category_override WHERE category_id = ?", retired))
        .as("V35: ON DELETE CASCADE")
        .isZero();
  }

  private HttpStatus moveStatus(String token, CategoryResponse category, UUID parentId) {
    return HttpStatus.valueOf(
        update(
                token,
                category.id(),
                new UpdateCategoryRequest(parentId, category.nameEn(), category.nameDe()),
                category.version())
            .returnResult(Void.class)
            .getStatus()
            .value());
  }

  // --- helpers -------------------------------------------------------------------------------

  private RestTestClient.ResponseSpec create(String token, CreateCategoryRequest request) {
    return client(token)
        .post()
        .uri(BASE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private CategoryResponse created(String token, CreateCategoryRequest request) {
    return create(token, request)
        .expectStatus()
        .isCreated()
        .expectBody(CategoryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec update(String token, UUID id, UpdateCategoryRequest request) {
    return update(token, id, request, getCategory(token, id).version());
  }

  private RestTestClient.ResponseSpec update(
      String token, UUID id, UpdateCategoryRequest request, int version) {
    return client(token)
        .put()
        .uri(BASE + "/" + id)
        .header("If-Match", "\"" + version + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private RestTestClient.ResponseSpec deactivate(String token, UUID id) {
    return client(token)
        .post()
        .uri(BASE + "/" + id + "/deactivate")
        .header("If-Match", "\"" + getCategory(token, id).version() + "\"")
        .exchange();
  }

  private RestTestClient.ResponseSpec delete(String token, UUID id) {
    return client(token)
        .delete()
        .uri(BASE + "/" + id)
        .header("If-Match", "\"" + getCategory(token, id).version() + "\"")
        .exchange();
  }

  private CategoryResponse getCategory(String token, UUID id) {
    return client(token)
        .get()
        .uri(BASE + "/" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CategoryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<CategoryResponse> list(String token, boolean includeInactive) {
    return client(token)
        .get()
        .uri(BASE + "?includeInactive=" + includeInactive)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CATEGORY_LIST)
        .returnResult()
        .getResponseBody();
  }

  private static List<String> codes(List<CategoryResponse> categories) {
    return categories.stream().map(CategoryResponse::code).toList();
  }

  // Runs a service call under the given principal, so the transaction listener sets that
  // workspace's app.current_workspace_id exactly as it would for a request.
  private static <T> T as(AuthenticatedUserPrincipal principal, Supplier<T> call) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    try {
      return call.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private UUID defaultId(String code) {
    return jdbcUuid("SELECT id FROM category WHERE workspace_id IS NULL AND code = ?", code);
  }

  private UUID workspaceOf(String email) {
    return jdbcUuid(
        "SELECT wm.workspace_id FROM workspace_member wm JOIN app_user u ON u.workspace_member_id"
            + " = wm.id WHERE u.email = ?",
        email);
  }

  private void insertRule(UUID workspaceId, UUID categoryId) throws Exception {
    execute(
        "INSERT INTO categorization_rule (workspace_id, match_type, match_value, category_id)"
            + " VALUES (?, 'MERCHANT', 'Netflix', ?)",
        workspaceId,
        categoryId);
  }

  private UUID insertTransaction(String token, UUID workspaceId, UUID categoryId) throws Exception {
    client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account("Everyday Checking", "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated();
    UUID accountId = jdbcUuid("SELECT id FROM account WHERE name = ?", "Everyday Checking");
    UUID transactionId = UUID.randomUUID();
    execute(
        "INSERT INTO transaction (id, workspace_id, account_id, transaction_type, booking_date,"
            + " amount, currency, category_id) VALUES (?, ?, ?, 'EXPENSE', CURRENT_DATE, -12.50,"
            + " 'CHF', ?)",
        transactionId,
        workspaceId,
        accountId,
        categoryId);
    return transactionId;
  }

  // A second, independent workspace with one member and user. The test datasource's role
  // bypasses RLS, so the rows can be seeded directly, the way SecurityControllerTest does.
  private AuthenticatedUserPrincipal seedSecondWorkspace() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", workspaceId);
    execute(
        "INSERT INTO workspace_member (id, workspace_id, display_name) VALUES (?, ?, 'B')",
        memberId,
        workspaceId);
    execute(
        "INSERT INTO app_user (id, email, password_hash, workspace_member_id) VALUES (?,"
            + " 'b@example.com', 'x', ?)",
        userId,
        memberId);
    return new AuthenticatedUserPrincipal(userId, "STANDARD_USER", workspaceId, UUID.randomUUID());
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private UUID jdbcUuid(String sql, Object parameter) {
    return (UUID) jdbcValue(sql, parameter);
  }

  private String jdbcString(String sql, Object parameter) {
    return (String) jdbcValue(sql, parameter);
  }

  private Object jdbcValue(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as(sql).isTrue();
        return resultSet.getObject(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void grantWorkspace(String token, UUID memberId, String level) {
    client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateSharingGrantRequest(memberId, ScopeTypeValues.WORKSPACE, null, null, level))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private UUID createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isCreated();
    return jdbcUuid("SELECT workspace_member_id FROM app_user WHERE email = ?", email);
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
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
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
