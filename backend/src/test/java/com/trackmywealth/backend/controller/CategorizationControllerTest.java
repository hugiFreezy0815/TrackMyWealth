package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CategorizationRuleResponse;
import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCategorizationRuleRequest;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.service.CategorizationService;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-08-01: automatic categorization of newly recorded transactions, and the minimal rule API it
 * needs (FR-CAT-007). The DoD's table-driven test is {@link #eachLayerAssignsItsCategory}; the rest
 * pin the order of the layers, the fuzzy fallback's guard rails, the Uncategorized list and the
 * rule API.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CategorizationControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String RULES = "/api/v1/categorization-rules";
  private static final String EXPENSE = "EXPENSE";
  private static final String PURCHASE = "CREDIT_CARD_PURCHASE";
  private static final String UNCATEGORIZED = "UNCATEGORIZED";

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

  @Autowired CategorizationService categorizationService;

  @Autowired TransactionRepository transactionRepository;

  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM categorization_rule",
              "DELETE FROM transaction",
              "DELETE FROM workspace_category_override",
              "DELETE FROM category WHERE workspace_id IS NOT NULL AND parent_category_id IN"
                  + " (SELECT id FROM category WHERE workspace_id IS NOT NULL)",
              "DELETE FROM category WHERE workspace_id IS NOT NULL",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  // --- DoD: source code, rule, uncategorized ---------------------------------------------------

  static Stream<Arguments> layers() {
    return Stream.of(
        // type, merchant, mcc, expected category code, expected assigned_by
        Arguments.of(PURCHASE, "COOP PRONTO", "5411", "GROCERIES", "SOURCE_CODE"),
        Arguments.of(PURCHASE, "HILTL ZUERICH", "5812", "DINING", "SOURCE_CODE"),
        Arguments.of(EXPENSE, "Mietzins Mueller AG", null, "HOUSING", "RULE"),
        Arguments.of(EXPENSE, "Unknown Shop", null, UNCATEGORIZED, null),
        // A code with no mapping falls through the later layers instead of stopping there.
        Arguments.of(PURCHASE, "Kiosk", "5999", UNCATEGORIZED, null));
  }

  @ParameterizedTest(name = "{0} {1} (MCC {2}) -> {3} by {4}")
  @MethodSource("layers")
  void eachLayerAssignsItsCategory(
      String type, String merchant, String mcc, String expectedCode, String expectedAssignedBy) {
    String token = bootstrapAdministrator();
    createRule(token, "MERCHANT", "mietzins", defaultId("HOUSING"), null);
    AccountSummaryResponse account =
        PURCHASE.equals(type) ? createAccount(token, "CREDIT_CARD") : createAccount(token, "CASH");

    TransactionResponse recorded = record(token, account.id(), type, merchant, mcc);

    assertThat(codeOf(recorded.categoryId())).isEqualTo(expectedCode);
    assertThat(recorded.categoryAssignedBy()).isEqualTo(expectedAssignedBy);
    assertThat(logAssignments(recorded.id()))
        .containsExactlyElementsOf(
            expectedAssignedBy == null ? List.of() : List.of(expectedAssignedBy));
    // The listed row agrees with what the POST said.
    TransactionResponse listed = list(token, account.id(), false).get(0);
    assertThat(listed.categoryId()).isEqualTo(recorded.categoryId());
    assertThat(listed.categoryAssignedBy()).isEqualTo(expectedAssignedBy);
  }

  // --- order of the layers --------------------------------------------------------------------

  @Test
  void aUserRuleBeatsTheShippedMappingAndIsNamedInTheLog() {
    String token = bootstrapAdministrator();
    CategorizationRuleResponse rule =
        createRule(token, "MERCHANT", "MIGROS", defaultId("SHOPPING"), null);
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD");

    TransactionResponse recorded = record(token, card.id(), PURCHASE, "MIGROS ZUERICH 123", "5411");

    assertThat(codeOf(recorded.categoryId())).isEqualTo("SHOPPING");
    assertThat(recorded.categoryAssignedBy()).isEqualTo("RULE");
    assertThat(
            jdbcUuid(
                "SELECT rule_id FROM transaction_categorization_log WHERE transaction_id = ?",
                recorded.id()))
        .isEqualTo(rule.id());
  }

  @Test
  void theLowestPriorityRuleWins() {
    String token = bootstrapAdministrator();
    createRule(token, "MERCHANT", "coop", defaultId("SHOPPING"), 100);
    createRule(token, "MERCHANT", "coop pronto", defaultId("GROCERIES"), 10);
    AccountSummaryResponse cash = createAccount(token, "CASH");

    TransactionResponse recorded = record(token, cash.id(), EXPENSE, "COOP  Pronto Bern", null);

    assertThat(codeOf(recorded.categoryId())).isEqualTo("GROCERIES");
  }

  @Test
  void aSourceCodeRuleCanRedirectAnMccToAWorkspaceCategory() {
    String token = bootstrapAdministrator();
    UUID coffee =
        createCategory(token, new CreateCategoryRequest(defaultId("LEISURE"), "Coffee", "Kaffee"));
    createRule(token, "SOURCE_CODE", "mcc:5812", coffee, null);
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD");

    TransactionResponse recorded = record(token, card.id(), PURCHASE, "Cafe Henrici", "5812");

    assertThat(recorded.categoryId()).isEqualTo(coffee);
    assertThat(recorded.categoryAssignedBy()).isEqualTo("RULE");
  }

  @Test
  void aCategoryTheWorkspaceDeactivatedIsSkippedNotAssigned() {
    String token = bootstrapAdministrator();
    // Deactivating LEISURE deactivates DINING under it for this workspace.
    client(token)
        .post()
        .uri("/api/v1/categories/" + defaultId("LEISURE") + "/deactivate")
        .exchange()
        .expectStatus()
        .isOk();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD");

    TransactionResponse recorded = record(token, card.id(), PURCHASE, "HILTL ZUERICH", "5812");

    assertThat(codeOf(recorded.categoryId())).isEqualTo(UNCATEGORIZED);
  }

  @Test
  void anIso20022PurposeCodeIsMoreSpecificThanAnMcc() throws Exception {
    // Only an import writes ISO 20022 codes, so the row is inserted the way one would leave it.
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, raw_source_data)"
                    + " SELECT ?, workspace_id, id, 'INCOME', CURRENT_DATE, 5000, 'CHF',"
                    + " CAST(? AS jsonb) FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setString(2, "{\"purposeCode\": \"sala\", \"mcc\": 5411}");
      statement.setObject(3, cash.id());
      statement.executeUpdate();
    }

    assertThat(categorizationService.categorize(transactionRepository.findById(id).orElseThrow()))
        .contains("SOURCE_CODE");
    assertThat(codeOf(jdbcUuid("SELECT category_id FROM transaction WHERE id = ?", id)))
        .isEqualTo("INCOME");
  }

  @Test
  void aBankTransactionCodeRuleCategorizesAnImportedRow() throws Exception {
    // Only an import writes ISO 20022 codes, so the row is inserted the way one would leave it.
    String token = bootstrapAdministrator();
    createRule(token, "SOURCE_CODE", "iso20022_btc:pmnt-rcdt-esct", defaultId("INCOME"), null);
    AccountSummaryResponse cash = createAccount(token, "CASH");
    UUID id =
        insertImportedRow(cash.id(), "INCOME", "{\"bankTransactionCode\": \"PMNT-RCDT-ESCT\"}");

    assertThat(categorizationService.categorize(transactionRepository.findById(id).orElseThrow()))
        .contains("RULE");
    assertThat(codeOf(jdbcUuid("SELECT category_id FROM transaction WHERE id = ?", id)))
        .isEqualTo("INCOME");
  }

  // --- the type-implied category (V37) --------------------------------------------------------

  @Test
  void aFeeOrTaxWithNothingMoreSpecificLandsInItsTypesCategory() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse cash = createAccount(token, "CASH");

    TransactionResponse fee = record(token, cash.id(), "FEE", "Kontofuehrung", null);
    TransactionResponse tax = record(token, cash.id(), "TAX", "Steuerverwaltung", null);

    assertThat(codeOf(fee.categoryId())).isEqualTo("FEES");
    assertThat(fee.categoryAssignedBy()).isEqualTo("TRANSACTION_TYPE");
    assertThat(logAssignments(fee.id())).containsExactly("TRANSACTION_TYPE");
    assertThat(codeOf(tax.categoryId())).isEqualTo("TAXES");
    assertThat(tax.categoryAssignedBy()).isEqualTo("TRANSACTION_TYPE");
  }

  @Test
  void aForeignCardPurchasesFeeRowLandsInFeesNotUncategorized() {
    // Review follow-up: the FEE row created alongside a foreign-currency purchase has no merchant
    // or code of its own, so without the type layer it always ended up in UNCATEGORIZED.
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD");

    post(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                LocalDate.now(ZoneId.of("Europe/Zurich")),
                new BigDecimal("-50.00"),
                "EUR",
                "HOTEL DU LAC PARIS",
                null,
                null,
                null,
                new BigDecimal("0.95"),
                null,
                new BigDecimal("1.50")))
        .expectStatus()
        .isCreated();

    TransactionResponse feeRow =
        list(token, card.id(), false).stream()
            .filter(row -> "FEE".equals(row.transactionType()))
            .findFirst()
            .orElseThrow();
    assertThat(codeOf(feeRow.categoryId())).isEqualTo("FEES");
    assertThat(feeRow.categoryAssignedBy()).isEqualTo("TRANSACTION_TYPE");
    assertThat(list(token, card.id(), true))
        .extracting(TransactionResponse::transactionType)
        .doesNotContain("FEE");
  }

  // --- the fuzzy query's SQL (review follow-up) -------------------------------------------------

  @Test
  void theFuzzyFilterIsTheIndexableTrigramOperatorUnderATransactionLocalThreshold() {
    // similarity() >= t alone cannot use V10's GIN trigram index; pg_trgm's % can. If the query
    // filters with %, a threshold of 0.99 set for the transaction hides a 0.3-similar candidate
    // that the explicit threshold alone would return.
    String token = bootstrapAdministrator();
    createRule(token, "MERCHANT", "migros zuerich", defaultId("GROCERIES"), null);
    AccountSummaryResponse cash = createAccount(token, "CASH");
    record(token, cash.id(), EXPENSE, "MIGROS ZUERICH", null);
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", cash.id());
    TransactionTemplate inTransaction = new TransactionTemplate(transactionManager);

    List<?> strict =
        inTransaction.execute(
            status -> {
              transactionRepository.setSimilarityThresholdForTransaction("0.99");
              return transactionRepository.findFuzzyCandidates(
                  workspaceId, UUID.randomUUID(), "MIGROS BASEL", 0.3, 20);
            });
    // is_local = true: the 0.99 ended with that transaction, even on the same pooled connection.
    String thresholdAfterwards =
        inTransaction.execute(
            status ->
                new JdbcTemplate(dataSource)
                    .queryForObject(
                        "SELECT current_setting('pg_trgm.similarity_threshold')", String.class));
    List<?> configured =
        inTransaction.execute(
            status -> {
              transactionRepository.setSimilarityThresholdForTransaction("0.3");
              return transactionRepository.findFuzzyCandidates(
                  workspaceId, UUID.randomUUID(), "MIGROS BASEL", 0.3, 20);
            });

    assertThat(strict).isEmpty();
    assertThat(thresholdAfterwards).isEqualTo("0.3");
    assertThat(configured).hasSize(1);
  }

  // --- fuzzy fallback -------------------------------------------------------------------------

  @Test
  void aSimilarMerchantOfTheSameBrandLearnsFromARuleAssignedRow() {
    String token = bootstrapAdministrator();
    createRule(token, "MERCHANT", "migros zuerich", defaultId("GROCERIES"), null);
    AccountSummaryResponse cash = createAccount(token, "CASH");
    record(token, cash.id(), EXPENSE, "MIGROS ZUERICH", null);

    TransactionResponse similar = record(token, cash.id(), EXPENSE, "MIGROS BASEL", null);

    assertThat(codeOf(similar.categoryId())).isEqualTo("GROCERIES");
    assertThat(similar.categoryAssignedBy()).isEqualTo("FALLBACK_MATCH");
    assertThat(
            jdbcDecimal(
                "SELECT confidence FROM transaction_categorization_log WHERE transaction_id = ?",
                similar.id()))
        .isBetween(new BigDecimal("0.3"), BigDecimal.ONE);
  }

  @Test
  void aSharedCityIsNotEnoughForAFuzzyMatch() {
    // COOP BASEL is as trigram-similar to MIGROS BASEL as MIGROS ZUERICH is; only the brand
    // differs.
    String token = bootstrapAdministrator();
    createRule(token, "MERCHANT", "migros basel", defaultId("GROCERIES"), null);
    AccountSummaryResponse cash = createAccount(token, "CASH");
    record(token, cash.id(), EXPENSE, "MIGROS BASEL", null);

    TransactionResponse other = record(token, cash.id(), EXPENSE, "COOP BASEL", null);

    assertThat(codeOf(other.categoryId())).isEqualTo(UNCATEGORIZED);
  }

  @Test
  void aFuzzyMatchNeverLearnsFromAShippedMappingOrAnEarlierGuess() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD");
    record(token, card.id(), PURCHASE, "DENNER ZUERICH", "5411"); // SOURCE_CODE

    TransactionResponse unmapped = record(token, card.id(), PURCHASE, "DENNER BASEL", null);

    assertThat(codeOf(unmapped.categoryId())).isEqualTo(UNCATEGORIZED);
  }

  // --- what is categorized, and the Uncategorized list ------------------------------------------

  @Test
  void aSettlementIsNotCategorized() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD");

    TransactionResponse settlement =
        post(token, card.id(), transaction("SETTLEMENT", "85.00", null, null))
            .expectStatus()
            .isCreated()
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(settlement.categoryId()).isNull();
    assertThat(settlement.categoryAssignedBy()).isNull();
  }

  @Test
  void uncategorizedRowsFormTheirOwnList() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD");
    record(token, card.id(), PURCHASE, "COOP PRONTO", "5411");
    TransactionResponse open = record(token, card.id(), PURCHASE, "Unknown Shop", null);

    assertThat(list(token, card.id(), true))
        .extracting(TransactionResponse::id)
        .containsExactly(open.id());
    assertThat(list(token, card.id(), false)).hasSize(2);
  }

  // --- rule API -------------------------------------------------------------------------------

  @Test
  void aRuleIsStoredNormalizedAndListedInEvaluationOrder() {
    String token = bootstrapAdministrator();
    createRule(token, "MERCHANT", "  Migros   Zürich ", defaultId("GROCERIES"), 50);
    createRule(token, "SOURCE_CODE", "iso20022_purpose:sala", defaultId("INCOME"), 5);

    List<CategorizationRuleResponse> rules = listRules(token, false);

    assertThat(rules)
        .extracting(CategorizationRuleResponse::matchValue)
        .containsExactly("ISO20022_PURPOSE:SALA", "migros zürich");
    assertThat(rules).extracting(CategorizationRuleResponse::priority).containsExactly(5, 50);
  }

  @Test
  void unsupportedOrMalformedRulesAreRejected() {
    String token = bootstrapAdministrator();
    UUID groceries = defaultId("GROCERIES");

    for (CreateCategorizationRuleRequest invalid :
        List.of(
            new CreateCategorizationRuleRequest(
                "COUNTERPARTY_IBAN", "CH93 0076 2011", groceries, null),
            new CreateCategorizationRuleRequest("AMOUNT_PATTERN", "-1200.00", groceries, null),
            new CreateCategorizationRuleRequest("SOURCE_CODE", "5411", groceries, null),
            new CreateCategorizationRuleRequest("SOURCE_CODE", "MCC:54", groceries, null),
            // Two letters would match almost every merchant (a MERCHANT rule is a "contains").
            new CreateCategorizationRuleRequest("MERCHANT", "  ab ", groceries, null),
            new CreateCategorizationRuleRequest("MERCHANT", "co", groceries, null))) {
      postRule(token, invalid).expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
    postRule(
            token,
            new CreateCategorizationRuleRequest("MERCHANT", "migros", UUID.randomUUID(), null))
        .expectStatus()
        .isNotFound();
    assertThat(listRules(token, true)).isEmpty();
  }

  @Test
  void aRuleCannotTargetAnInactiveCategory() {
    String token = bootstrapAdministrator();
    client(token)
        .post()
        .uri("/api/v1/categories/" + defaultId("SHOPPING") + "/deactivate")
        .exchange()
        .expectStatus()
        .isOk();

    postRule(
            token,
            new CreateCategorizationRuleRequest("MERCHANT", "ikea", defaultId("SHOPPING"), null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aDeactivatedRuleNoLongerAppliesAndDeactivatingIsIdempotent() {
    String token = bootstrapAdministrator();
    CategorizationRuleResponse rule =
        createRule(token, "MERCHANT", "unknown shop", defaultId("SHOPPING"), null);
    for (int attempt = 0; attempt < 2; attempt++) {
      client(token)
          .post()
          .uri(RULES + "/" + rule.id() + "/deactivate")
          .exchange()
          .expectStatus()
          .isOk();
    }
    AccountSummaryResponse cash = createAccount(token, "CASH");

    TransactionResponse recorded = record(token, cash.id(), EXPENSE, "Unknown Shop", null);

    assertThat(codeOf(recorded.categoryId())).isEqualTo(UNCATEGORIZED);
    assertThat(listRules(token, false)).isEmpty();
    assertThat(listRules(token, true))
        .extracting(CategorizationRuleResponse::active)
        .containsExactly(false);
  }

  @Test
  void changingRulesNeedsWorkspaceEditWhileReadingNeedsMembershipOnly() {
    String adminToken = bootstrapAdministrator();
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    CreateCategorizationRuleRequest request =
        new CreateCategorizationRuleRequest("MERCHANT", "migros", defaultId("GROCERIES"), null);

    client(memberToken).get().uri(RULES).exchange().expectStatus().isOk();
    postRule(memberToken, request).expectStatus().isNotFound();

    grantWorkspace(adminToken, memberId, AccessLevelValues.EDIT);
    postRule(memberToken, request).expectStatus().isCreated();
  }

  @Test
  void anotherWorkspacesRuleCannotBeDeactivated() throws Exception {
    String token = bootstrapAdministrator();
    UUID foreignRule = UUID.randomUUID();
    UUID foreignWorkspace = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Other')", foreignWorkspace);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO categorization_rule (id, workspace_id, match_type, match_value,"
                    + " category_id) VALUES (?, ?, 'MERCHANT', 'x', ?)")) {
      statement.setObject(1, foreignRule);
      statement.setObject(2, foreignWorkspace);
      statement.setObject(3, defaultId("GROCERIES"));
      statement.executeUpdate();
    }

    client(token)
        .post()
        .uri(RULES + "/" + foreignRule + "/deactivate")
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(listRules(token, true)).isEmpty();
    execute("DELETE FROM categorization_rule WHERE id = ?", foreignRule);
    execute("DELETE FROM financial_institution WHERE workspace_id = ?", foreignWorkspace);
    execute("DELETE FROM workspace WHERE id = ?", foreignWorkspace);
  }

  // --- helpers ---------------------------------------------------------------------------------

  // A row as an import (EPIC 07) would leave it, with ISO 20022 codes the API cannot carry yet.
  private UUID insertImportedRow(UUID accountId, String type, String rawSourceData)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, raw_source_data)"
                    + " SELECT ?, workspace_id, id, ?, CURRENT_DATE, 5000, 'CHF',"
                    + " CAST(? AS jsonb) FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setString(2, type);
      statement.setString(3, rawSourceData);
      statement.setObject(4, accountId);
      statement.executeUpdate();
    }
    return id;
  }

  private TransactionResponse record(
      String token, UUID accountId, String type, String merchant, String mcc) {
    return post(token, accountId, transaction(type, "-45.00", merchant, mcc))
        .expectStatus()
        .isCreated()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static CreateTransactionRequest transaction(
      String type, String amount, String merchant, String mcc) {
    return TransactionRequests.cash(
        type,
        LocalDate.now(ZoneId.of("Europe/Zurich")),
        new BigDecimal(amount),
        "CHF",
        merchant,
        mcc,
        null,
        null,
        null,
        null,
        null);
  }

  private RestTestClient.ResponseSpec post(
      String token, UUID accountId, CreateTransactionRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  private List<TransactionResponse> list(String token, UUID accountId, boolean uncategorized) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions?uncategorized=" + uncategorized)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  private CategorizationRuleResponse createRule(
      String token, String matchType, String matchValue, UUID categoryId, Integer priority) {
    return postRule(
            token, new CreateCategorizationRuleRequest(matchType, matchValue, categoryId, priority))
        .expectStatus()
        .isCreated()
        .expectBody(CategorizationRuleResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec postRule(
      String token, CreateCategorizationRuleRequest request) {
    return client(token)
        .post()
        .uri(RULES)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private List<CategorizationRuleResponse> listRules(String token, boolean includeInactive) {
    return client(token)
        .get()
        .uri(RULES + "?includeInactive=" + includeInactive)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<CategorizationRuleResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private UUID createCategory(String token, CreateCategoryRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/categories")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(CategoryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private AccountSummaryResponse createAccount(String token, String accountType) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateAccountRequest(
                null, accountType, accountType, "CHF", null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID defaultId(String code) {
    return jdbcUuid("SELECT id FROM category WHERE workspace_id IS NULL AND code = ?", code);
  }

  private String codeOf(UUID categoryId) {
    return categoryId == null
        ? null
        : jdbcString("SELECT code FROM category WHERE id = ?", categoryId);
  }

  private List<String> logAssignments(UUID transactionId) {
    List<String> assignments = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT assigned_by FROM transaction_categorization_log WHERE transaction_id = ?")) {
      statement.setObject(1, transactionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          assignments.add(resultSet.getString(1));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return assignments;
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

  private void execute(String sql, Object parameter) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      statement.executeUpdate();
    }
  }

  private UUID jdbcUuid(String sql, Object parameter) {
    return (UUID) jdbcValue(sql, parameter);
  }

  private String jdbcString(String sql, Object parameter) {
    return (String) jdbcValue(sql, parameter);
  }

  private BigDecimal jdbcDecimal(String sql, Object parameter) {
    return (BigDecimal) jdbcValue(sql, parameter);
  }

  private Object jdbcValue(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as(sql).isTrue();
        return resultSet.getObject(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
