package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * US-07-35 (#382), against a real PostgreSQL: {@code GET /api/v1/transactions} lists every
 * transaction of the workspace across its accounts - newest first, paged, filtered and searched -
 * and nothing of another workspace. One test per acceptance criterion of the story, plus the
 * filters' edges.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceTransactionControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 1);
  private static final LocalDate DAY_2 = LocalDate.of(2026, 9, 2);
  private static final LocalDate DAY_3 = LocalDate.of(2026, 9, 3);

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
      for (String sql :
          List.of(
              "DELETE FROM settlement_match",
              "DELETE FROM transfer_detection_fx_pending",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction_category_split",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM account_snapshot",
              "DELETE FROM account_credit_card",
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

  // --- acceptance criteria -----------------------------------------------------------------------

  @Test
  void withoutFiltersEveryAccountsRowsAppearNewestFirstAndPaged() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    AccountSummaryResponse savings = createAccount(token, "Savings", "SAVINGS", "CHF", null);
    AccountSummaryResponse card = createAccount(token, "Card", "CREDIT_CARD", "CHF", null);
    TransactionResponse oldest = record(token, checking.id(), expense(DAY_1, "-10.00", "Rent"));
    TransactionResponse middle = record(token, savings.id(), expense(DAY_2, "-20.00", "Fee"));
    TransactionResponse newest = record(token, card.id(), purchase(DAY_3, "-30.00", "Bookshop"));

    PageResponse<TransactionResponse> first = list(token, "?size=2");
    PageResponse<TransactionResponse> second = list(token, "?size=2&page=1");

    assertThat(first.content())
        .extracting(TransactionResponse::id)
        .containsExactly(newest.id(), middle.id());
    assertThat(second.content()).extracting(TransactionResponse::id).containsExactly(oldest.id());
    assertThat(first.page().totalElements()).isEqualTo(3);
    assertThat(first.page().totalPages()).isEqualTo(2);
    // One row shape for both lists: the per-account row plus the account's id and name.
    assertThat(first.content())
        .extracting(TransactionResponse::accountId, TransactionResponse::accountName)
        .containsExactly(tuple(card.id(), "Card"), tuple(savings.id(), "Savings"));
    assertThat(perAccount(token, card.id()).content().get(0))
        .usingRecursiveComparison()
        .isEqualTo(first.content().get(0));
  }

  @Test
  void anotherMembersRowsAppearLikeAnyOther() {
    String adminToken = bootstrapAdministrator();
    createStandardUser(adminToken, "anna@example.com");
    createStandardUser(adminToken, "ben@example.com");
    String anna = login("anna@example.com");
    String ben = login("ben@example.com");
    AccountSummaryResponse annas = createAccount(anna, "Anna's account", "CASH", "CHF", null);
    TransactionResponse annasRow = record(anna, annas.id(), expense(DAY_1, "-12.00", "Lunch"));

    assertThat(list(ben, "").content())
        .extracting(TransactionResponse::id)
        .containsExactly(annasRow.id());
  }

  @Test
  void anAccountOrPortfolioOfAnotherWorkspaceMatchesNothingExactlyLikeAnUnknownId()
      throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    record(token, checking.id(), expense(DAY_1, "-10.00", "Rent"));
    UUID foreignAccount = accountInAnotherWorkspace();
    UUID foreignPortfolio =
        (UUID) query("SELECT portfolio_id FROM account WHERE id = ?", foreignAccount);
    insertRow(foreignAccount, DAY_1, "-99.00", "Foreign secret");

    String foreign = list(token, "?accountId=" + foreignAccount).content().toString();
    String unknown = list(token, "?accountId=" + UUID.randomUUID()).content().toString();

    assertThat(foreign).isEqualTo("[]").isEqualTo(unknown);
    assertThat(list(token, "?portfolioId=" + foreignPortfolio).content()).isEmpty();
    // Unfiltered, the other workspace's row is not there either.
    assertThat(list(token, "").content())
        .extracting(TransactionResponse::merchantDescription)
        .containsExactly("Rent");
  }

  @Test
  void uncategorizedListsOnlyRowsInUncategorizedAcrossAccounts() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    AccountSummaryResponse card = createAccount(token, "Card", "CREDIT_CARD", "CHF", null);
    TransactionResponse open1 = record(token, checking.id(), expense(DAY_1, "-1.00", "A"));
    TransactionResponse open2 = record(token, card.id(), purchase(DAY_2, "-2.00", "B"));
    TransactionResponse done = record(token, card.id(), purchase(DAY_3, "-3.00", "C"));
    setCategory(open1.id(), "UNCATEGORIZED");
    setCategory(open2.id(), "UNCATEGORIZED");
    setCategory(done.id(), "SHOPPING");

    assertThat(list(token, "?uncategorized=true").content())
        .extracting(TransactionResponse::id)
        .containsExactly(open2.id(), open1.id());
    assertThat(list(token, "?categoryId=" + defaultCategory("SHOPPING")).content())
        .extracting(TransactionResponse::id)
        .containsExactly(done.id());
  }

  @Test
  void qFindsTheDescriptionOrTheCounterpartyIgnoringCaseAndAccents() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    TransactionResponse byDescription =
        record(token, checking.id(), expense(DAY_1, "-5.00", "MIGROS M-Budget"));
    TransactionResponse byCounterparty =
        record(
            token,
            checking.id(),
            TransactionRequests.withCounterpartyName(
                expense(DAY_2, "-6.00", "Card payment"), "Migros Genossenschaft"));
    TransactionResponse accented =
        record(
            token,
            checking.id(),
            TransactionRequests.withCounterpartyName(
                expense(DAY_3, "-7.00", "Brot"), "Bäckerei Zürich"));
    record(token, checking.id(), expense(DAY_3, "-8.00", "Coop"));

    assertThat(list(token, "?q=migros").content())
        .extracting(TransactionResponse::id)
        .containsExactlyInAnyOrder(byDescription.id(), byCounterparty.id());
    assertThat(list(token, "?q=zurich").content())
        .extracting(TransactionResponse::id)
        .containsExactly(accented.id());
    assertThat(list(token, "?q=BÄCKEREI").content())
        .extracting(TransactionResponse::id)
        .containsExactly(accented.id());
  }

  @Test
  void aSoftDeletedRowIsNotListed() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    TransactionResponse typo = record(token, checking.id(), expense(DAY_1, "-50.00", "Typo"));
    TransactionResponse kept = record(token, checking.id(), expense(DAY_2, "-5.00", "Kept"));

    client(token)
        .delete()
        .uri("/api/v1/accounts/" + checking.id() + "/transactions/" + typo.id())
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", typo.id()))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(list(token, "").content())
        .extracting(TransactionResponse::id)
        .containsExactly(kept.id());
  }

  // --- filters -----------------------------------------------------------------------------------

  @Test
  void accountAndPortfolioFiltersTakeSeveralValues() {
    String token = bootstrapAdministrator();
    UUID bank = createPortfolio(token, "Bank");
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    AccountSummaryResponse savings = createAccount(token, "Savings", "SAVINGS", "CHF", bank);
    AccountSummaryResponse card = createAccount(token, "Card", "CREDIT_CARD", "CHF", bank);
    TransactionResponse a = record(token, checking.id(), expense(DAY_1, "-1.00", "A"));
    TransactionResponse b = record(token, savings.id(), expense(DAY_2, "-2.00", "B"));
    TransactionResponse c = record(token, card.id(), purchase(DAY_3, "-3.00", "C"));

    assertThat(list(token, "?accountId=" + checking.id() + "&accountId=" + card.id()).content())
        .extracting(TransactionResponse::id)
        .containsExactly(c.id(), a.id());
    assertThat(list(token, "?portfolioId=" + bank).content())
        .extracting(TransactionResponse::id)
        .containsExactly(c.id(), b.id());
    assertThat(list(token, "?portfolioId=" + bank + "&accountId=" + checking.id()).content())
        .as("filters combine with AND")
        .isEmpty();
  }

  @Test
  void dateAmountAndCurrencyFiltersAreInclusiveAndAmountsAreInTheAccountsCurrency() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    TransactionResponse small = record(token, checking.id(), expense(DAY_1, "-10.00", "Small"));
    TransactionResponse large = record(token, checking.id(), expense(DAY_2, "-100.00", "Large"));
    TransactionResponse income = record(token, checking.id(), income(DAY_3, "50.00"));

    assertThat(list(token, "?bookingDateFrom=" + DAY_2 + "&bookingDateTo=" + DAY_3).content())
        .extracting(TransactionResponse::id)
        .containsExactly(income.id(), large.id());
    assertThat(list(token, "?amountMin=-100.00&amountMax=-10.00").content())
        .extracting(TransactionResponse::id)
        .containsExactly(large.id(), small.id());
    assertThat(list(token, "?amountMin=0").content())
        .extracting(TransactionResponse::id)
        .containsExactly(income.id());
    assertThat(list(token, "?currency=CHF").content()).hasSize(3);
    assertThat(list(token, "?currency=EUR").content()).isEmpty();
    assertThat(list(token, "?sort=amount,asc").content())
        .extracting(TransactionResponse::id)
        .containsExactly(large.id(), small.id(), income.id());
  }

  @Test
  void wildcardsInQMatchThemselves() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    TransactionResponse percent = record(token, checking.id(), expense(DAY_1, "-1.00", "100% Bio"));
    record(token, checking.id(), expense(DAY_2, "-2.00", "100 Bio"));
    TransactionResponse underscore =
        record(token, checking.id(), expense(DAY_3, "-3.00", "ref_42"));
    record(token, checking.id(), expense(DAY_3, "-4.00", "refX42"));

    assertThat(list(token, "?q=100%").content())
        .extracting(TransactionResponse::id)
        .containsExactly(percent.id());
    assertThat(list(token, "?q=ref_4").content())
        .extracting(TransactionResponse::id)
        .containsExactly(underscore.id());
    assertThat(list(token, "?q=  ").content()).as("a blank q is no filter").hasSize(4);
    // U+FF05 (fullwidth percent) folds to "%": escaped after folding, it is no wildcard either.
    assertThat(list(token, "?q=100\uFF05").content())
        .extracting(TransactionResponse::id)
        .containsExactly(percent.id());
  }

  @Test
  void qIgnoresSurroundingSpaceAndNeverMatchesAcrossTheTwoFields() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse checking = createAccount(token, "Checking", "CASH", "CHF", null);
    TransactionResponse migros =
        record(
            token,
            checking.id(),
            TransactionRequests.withCounterpartyName(
                expense(DAY_1, "-5.00", "Einkauf AG"), "Migros"));

    assertThat(list(token, "?q= migros ").content())
        .as("a keyboard's trailing or leading space")
        .extracting(TransactionResponse::id)
        .containsExactly(migros.id());
    assertThat(list(token, "?q=AG Migros").content())
        .as("the end of the description and the start of the counterparty are not one text")
        .isEmpty();
  }

  @Test
  void aFilterThatCannotMatchByConstructionIsA400() {
    String token = bootstrapAdministrator();
    String tooManyAccounts =
        IntStream.rangeClosed(0, 50)
            .mapToObj(i -> "accountId=" + UUID.randomUUID())
            .collect(Collectors.joining("&", "?", ""));

    for (String query :
        List.of(
            "?amountMin=10&amountMax=5",
            "?bookingDateFrom=" + DAY_3 + "&bookingDateTo=" + DAY_1,
            "?q=" + "x".repeat(101),
            "?currency=chf",
            "?currency=XYZ",
            "?q=a\u001Fb",
            tooManyAccounts)) {
      client(token)
          .get()
          .uri("/api/v1/transactions" + query)
          .exchange()
          .expectStatus()
          .isBadRequest();
    }
    client(token)
        .get()
        .uri("/api/v1/transactions?sort=merchantDescription,asc")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(ApiErrorCode.INVALID_SORT);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static CreateTransactionRequest expense(LocalDate day, String amount, String text) {
    return cash("EXPENSE", day, amount, text);
  }

  private static CreateTransactionRequest income(LocalDate day, String amount) {
    return cash("INCOME", day, amount, "Salary");
  }

  private static CreateTransactionRequest purchase(LocalDate day, String amount, String text) {
    return cash("CREDIT_CARD_PURCHASE", day, amount, text);
  }

  private static CreateTransactionRequest cash(
      String type, LocalDate day, String amount, String text) {
    return TransactionRequests.cash(
        type, day, new BigDecimal(amount), "CHF", text, null, null, null, null, null, null);
  }

  // The client encodes the URI, so a query is written with its raw characters ("100%", "Ä").
  private PageResponse<TransactionResponse> list(String token, String query) {
    return client(token)
        .get()
        .uri("/api/v1/transactions" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageResponse<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private PageResponse<TransactionResponse> perAccount(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageResponse<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private TransactionResponse record(
      String token, UUID accountId, CreateTransactionRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createAccount(
      String token, String name, String type, String currency, UUID portfolioId) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(name, type, currency).portfolioId(portfolioId).build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createPortfolio(String token, String name) {
    return client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePortfolioRequest(null, name, "CH", "BANK", null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(PortfolioResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  // The category is an annotation, not a frozen ledger field: set directly so the test does not
  // depend on what automatic categorization picks for a made-up merchant.
  private void setCategory(UUID transactionId, String code) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE transaction SET category_id = ? WHERE id = ?")) {
      statement.setObject(1, defaultCategory(code));
      statement.setObject(2, transactionId);
      statement.executeUpdate();
    }
  }

  private UUID defaultCategory(String code) {
    return (UUID) query("SELECT id FROM category WHERE workspace_id IS NULL AND code = ?", code);
  }

  // Workspace B, set up directly: this instance's API only ever bootstraps one workspace.
  private UUID accountInAnotherWorkspace() throws Exception {
    UUID workspace = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insertWorkspace =
            connection.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'B')");
        PreparedStatement insertAccount =
            connection.prepareStatement(
                "INSERT INTO account (id, workspace_id, portfolio_id, account_type, name,"
                    + " native_currency) SELECT ?, ?, id, 'CASH', 'B checking', 'CHF'"
                    + " FROM portfolio WHERE workspace_id = ?")) {
      insertWorkspace.setObject(1, workspace);
      insertWorkspace.executeUpdate();
      insertAccount.setObject(1, account);
      insertAccount.setObject(2, workspace);
      insertAccount.setObject(3, workspace);
      insertAccount.executeUpdate();
    }
    return account;
  }

  private void insertRow(UUID accountId, LocalDate day, String amount, String description)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, merchant_description, source)"
                    + " SELECT workspace_id, id, 'EXPENSE', ?, ?, 'CHF', ?, 'CSV'"
                    + " FROM account WHERE id = ?")) {
      statement.setObject(1, day);
      statement.setBigDecimal(2, new BigDecimal(amount));
      statement.setString(3, description);
      statement.setObject(4, accountId);
      statement.executeUpdate();
    }
  }

  private Object query(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getObject(1) : null;
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
}
