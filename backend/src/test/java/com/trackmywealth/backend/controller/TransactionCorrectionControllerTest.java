package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetTransactionCategoryRequest;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionCorrectionResponse;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UpdateTransactionRequest;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-06/FR-LIF-004: editing a transaction. A financial change is a removal plus a replacement,
 * atomically - soft delete for a manual row (T1), void with a reason for an imported one (T2) - and
 * a description or notes change is a normal update. The DoD's tests are {@link
 * #aManualTransactionIsCorrectedAsSoftDeletePlusReplacement} (T1), {@link
 * #anImportedTransactionIsCorrectedAsVoidPlusReplacement} (T2) and {@link
 * #aNonFinancialEditIsANormalUpdateNotAVoid}. Imports do not exist yet, so an imported row is
 * inserted the way one would leave it, as in {@code TransactionRemovalControllerTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionCorrectionControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String PURCHASE = "CREDIT_CARD_PURCHASE";

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
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM categorization_rule",
              "DELETE FROM transaction",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
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

  // --- T1: manual entry -------------------------------------------------------------------------

  @Test
  void aManualTransactionIsCorrectedAsSoftDeletePlusReplacement() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                today(),
                new BigDecimal("-85.00"),
                "CHF",
                "Restaurant Krone",
                "5812",
                "team lunch",
                "key-1",
                null,
                null,
                null));

    TransactionCorrectionResponse corrected =
        update(token, card.id(), typo.id(), withAmount(asRead(typo), "-58.00"));

    assertThat(corrected.removal()).isEqualTo("SOFT_DELETE");
    assertThat(corrected.reversals()).isEmpty();
    assertThat(corrected.affected())
        .singleElement()
        .satisfies(
            original -> {
              assertThat(original.id()).isEqualTo(typo.id());
              assertThat(original.deletedAt()).isNotNull();
              assertThat(original.amount()).isEqualByComparingTo("-85.00");
            });
    TransactionResponse replacement = corrected.transaction();
    assertThat(replacement.id()).isNotEqualTo(typo.id());
    assertThat(replacement.correctsTransactionId()).isEqualTo(typo.id());
    assertThat(replacement.amount()).isEqualByComparingTo("-58.00");
    assertThat(replacement.source()).isEqualTo("MANUAL");
    assertThat(replacement.removal()).isEqualTo("SOFT_DELETE");
    // What the member did not change is carried over; the source's MCC still describes it.
    assertThat(replacement.merchantDescription()).isEqualTo("Restaurant Krone");
    assertThat(replacement.notes()).isEqualTo("team lunch");
    assertThat(replacement.mcc()).isEqualTo("5812");
    assertThat(replacement.externalId()).as("the key stays with the original").isNull();

    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(replacement.id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("58.00");
    assertThat(spending(token)).isEqualByComparingTo("58.00");
    // The original stays in the data, restorable like any soft delete (FR-LIF-006).
    assertThat(countRows(card.id())).isEqualTo(2);
    assertThat(restorable(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(typo.id());

    // A replacement is an ordinary row: it can be corrected again.
    TransactionCorrectionResponse again =
        update(token, card.id(), replacement.id(), withAmount(replacement, "-59.00"));
    assertThat(again.transaction().correctsTransactionId()).isEqualTo(replacement.id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("59.00");
  }

  // --- T2: imported -----------------------------------------------------------------------------

  @Test
  void anImportedTransactionIsCorrectedAsVoidPlusReplacement() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), "-85.00");
    TransactionResponse read = list(token, card.id()).get(0);
    UpdateTransactionRequest correction = withAmount(asRead(read), "-80.00");

    // The system decided on a void, which needs a reason; nothing is touched without one.
    put(token, card.id(), imported, correction)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(list(token, card.id())).hasSize(1);
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");

    TransactionCorrectionResponse corrected =
        update(token, card.id(), imported, withReason(correction, "Statement shows 80.00"));

    assertThat(corrected.removal()).isEqualTo("VOID");
    assertThat(corrected.affected())
        .singleElement()
        .satisfies(
            original -> {
              assertThat(original.voidedAt()).isNotNull();
              assertThat(original.voidReason()).isEqualTo("Statement shows 80.00");
              assertThat(original.amount()).isEqualByComparingTo("-85.00");
            });
    assertThat(corrected.reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.amount()).isEqualByComparingTo("85.00");
              assertThat(reversal.replacesTransactionId()).isEqualTo(imported);
            });
    TransactionResponse replacement = corrected.transaction();
    assertThat(replacement.amount()).isEqualByComparingTo("-80.00");
    assertThat(replacement.correctsTransactionId()).isEqualTo(imported);
    assertThat(replacement.source()).as("still imported, so still T2").isEqualTo("CSV");
    assertThat(replacement.removal()).isEqualTo("VOID");

    // Original, reversal and replacement all stay listed (FR-LIF-003); only the replacement counts.
    assertThat(list(token, card.id())).hasSize(3);
    assertThat(balance(token, card.id())).isEqualByComparingTo("80.00");
    assertThat(spending(token)).isEqualByComparingTo("80.00");
  }

  // --- non-financial ----------------------------------------------------------------------------

  @Test
  void aNonFinancialEditIsANormalUpdateNotAVoid() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), "-85.00");
    TransactionResponse read = list(token, card.id()).get(0);
    UpdateTransactionRequest edit =
        new UpdateTransactionRequest(
            card.id(),
            read.bookingDate(),
            new BigDecimal("-85.0000"),
            "CHF",
            "Migros Zurich",
            "weekly shop",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    // No reason needed: nothing is voided.
    TransactionCorrectionResponse updated = update(token, card.id(), imported, edit);

    assertThat(updated.removal()).isNull();
    assertThat(updated.affected()).isEmpty();
    assertThat(updated.reversals()).isEmpty();
    assertThat(updated.transaction().id()).isEqualTo(imported);
    assertThat(updated.transaction().merchantDescription()).isEqualTo("Migros Zurich");
    assertThat(updated.transaction().notes()).isEqualTo("weekly shop");
    assertThat(updated.transaction().voidedAt()).isNull();
    assertThat(updated.version()).isGreaterThan(read.version());
    assertThat(countRows(card.id())).isEqualTo(1);
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");
  }

  // --- categorization ---------------------------------------------------------------------------

  @Test
  void theReplacementKeepsTheMembersOverrideAndIsOtherwiseCategorizedLikeANewRow() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse overridden = record(token, card.id(), purchase("-85.00"));
    UUID shopping = defaultCategory("SHOPPING");
    TransactionResponse withOverride =
        overrideCategory(token, card.id(), overridden.id(), shopping);
    TransactionResponse automatic = record(token, card.id(), purchase("-20.00"));

    TransactionResponse keptOverride =
        update(token, card.id(), overridden.id(), withAmount(withOverride, "-58.00")).transaction();
    TransactionResponse recategorized =
        update(token, card.id(), automatic.id(), withAmount(automatic, "-21.00")).transaction();

    assertThat(keptOverride.categoryId()).isEqualTo(shopping);
    assertThat(keptOverride.categoryAssignedBy()).isEqualTo("USER");
    assertThat(recategorized.categoryId()).isEqualTo(automatic.categoryId());
    assertThat(recategorized.categoryAssignedBy()).isEqualTo(automatic.categoryAssignedBy());
  }

  // --- validation and atomicity -----------------------------------------------------------------

  @Test
  void theReplacementIsValidatedLikeANewTransactionAndARejectionChangesNothing() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));

    put(token, card.id(), typo.id(), withAmount(typo, "85.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.detail")
        .value(detail -> assertThat(detail.toString()).contains("amount must be negative"));

    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(typo.id());
    assertThat(restorable(token, card.id())).as("the removal was rolled back").isEmpty();
    assertThat(countRows(card.id())).isEqualTo(1);
  }

  // --- moving to another account ----------------------------------------------------------------

  @Test
  void aCorrectionMovesTheTransactionOnlyWithEditOnBothAccounts() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse wrongCard = createAccount(adminToken, "CREDIT_CARD", "CHF");
    AccountSummaryResponse rightCard = createAccount(adminToken, "CREDIT_CARD", "CHF");
    TransactionResponse misbooked = record(adminToken, wrongCard.id(), purchase("-85.00"));
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    grantAccount(adminToken, memberId, wrongCard.id(), AccessLevelValues.EDIT);
    grantAccount(adminToken, memberId, rightCard.id(), AccessLevelValues.READ);
    UpdateTransactionRequest move = withAccount(asRead(misbooked), rightCard.id());

    put(memberToken, wrongCard.id(), misbooked.id(), move).expectStatus().isNotFound();
    assertThat(list(adminToken, wrongCard.id())).hasSize(1);

    grantAccount(adminToken, memberId, rightCard.id(), AccessLevelValues.EDIT);
    TransactionCorrectionResponse moved = update(memberToken, wrongCard.id(), misbooked.id(), move);

    assertThat(moved.transaction().accountId()).isEqualTo(rightCard.id());
    assertThat(list(adminToken, wrongCard.id())).isEmpty();
    assertThat(list(adminToken, rightCard.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(moved.transaction().id());
    assertThat(balance(adminToken, wrongCard.id())).isEqualByComparingTo("0");
    assertThat(balance(adminToken, rightCard.id())).isEqualByComparingTo("85.00");
  }

  // --- linked rows ------------------------------------------------------------------------------

  @Test
  void aPurchaseWithItsFeeRowIsNotCorrectedYetButItsNotesCanChange() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse foreign =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                today(),
                new BigDecimal("-100.00"),
                "EUR",
                "Hotel Berlin",
                null,
                null,
                null,
                new BigDecimal("0.95"),
                null,
                new BigDecimal("1.50")));
    UpdateTransactionRequest read = asRead(foreign);

    put(token, card.id(), foreign.id(), withAmount(read, "-90.00"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(list(token, card.id())).hasSize(2);

    TransactionCorrectionResponse noted =
        update(token, card.id(), foreign.id(), withNotes(read, "conference"));
    assertThat(noted.removal()).isNull();
    assertThat(noted.transaction().notes()).isEqualTo("conference");
  }

  @Test
  void correctingAMatchedPaymentDissolvesTheMatchAndFreesTheCredit() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    TransactionResponse payment = record(token, checking.id(), cashRow("WITHDRAWAL", "-300.00"));
    TransactionResponse credit = record(token, card.id(), cashRow("SETTLEMENT", "300.00"));
    confirmMatch(card.id(), payment.id(), credit.id());

    TransactionCorrectionResponse corrected =
        update(token, checking.id(), payment.id(), withAmount(asRead(payment), "-250.00"));

    assertThat(corrected.unmatchedTransactionIds()).containsExactly(credit.id());
    assertThat(
            queryDecimal(
                "SELECT count(*) FROM settlement_match WHERE status = 'CONFIRMED' AND"
                    + " card_account_id = ?",
                card.id()))
        .isEqualByComparingTo("0");
    assertThat(list(token, card.id()).get(0).internalTransfer()).isFalse();
  }

  // --- lifecycle and preconditions --------------------------------------------------------------

  @Test
  void aVoidedTransactionAndItsReversalCannotBeEdited() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), "-85.00");
    UpdateTransactionRequest read = asRead(list(token, card.id()).get(0));
    TransactionRemovalResponse voided =
        client(token)
            .delete()
            .uri(rowUri(card.id(), imported) + "?reason=Duplicate")
            .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionRemovalResponse.class)
            .returnResult()
            .getResponseBody();

    for (UUID row : List.of(imported, voided.reversals().get(0).id())) {
      put(token, card.id(), row, withNotes(read, "too late"))
          .expectStatus()
          .isEqualTo(HttpStatus.CONFLICT);
    }
  }

  // FR-CNC-001/ADR 0004: read-modify-write, so If-Match is required and must be current.
  @Test
  void anEditRequiresTheCurrentVersion() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));
    UpdateTransactionRequest correction = withAmount(typo, "-58.00");

    client(token)
        .put()
        .uri(rowUri(card.id(), typo.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(correction)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");

    update(token, card.id(), typo.id(), withNotes(asRead(typo), "first edit"));

    client(token)
        .put()
        .uri(rowUri(card.id(), typo.id()))
        .headers(headers -> headers.setIfMatch("\"" + typo.version() + "\""))
        .contentType(MediaType.APPLICATION_JSON)
        .body(correction)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    assertThat(list(token, card.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("-85.00"));
  }

  @Test
  void anotherWorkspacesTransactionIsNotFound() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(adminToken, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(adminToken, card.id(), purchase("-85.00"));
    createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    // Without If-Match too: never the 428 that would confirm the row exists.
    client(memberToken)
        .put()
        .uri(rowUri(card.id(), typo.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(withAmount(typo, "-1.00"))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(list(adminToken, card.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("-85.00"));
  }

  // --- requests ---------------------------------------------------------------------------------

  private static UpdateTransactionRequest asRead(TransactionResponse row) {
    return new UpdateTransactionRequest(
        row.accountId(),
        row.bookingDate(),
        row.amount(),
        row.currency(),
        row.merchantDescription(),
        row.notes(),
        row.fxRateToAccountCurrency(),
        null,
        row.feeAmount(),
        row.securityId(),
        row.quantity(),
        row.unitPrice(),
        row.tradeDate(),
        row.settlementDate(),
        row.grossAmount(),
        row.taxWithheldAmount(),
        null);
  }

  private static UpdateTransactionRequest withAmount(TransactionResponse row, String amount) {
    return withAmount(asRead(row), amount);
  }

  private static UpdateTransactionRequest withAmount(UpdateTransactionRequest r, String amount) {
    return new UpdateTransactionRequest(
        r.accountId(),
        r.bookingDate(),
        new BigDecimal(amount),
        r.currency(),
        r.merchantDescription(),
        r.notes(),
        r.fxRateToAccountCurrency(),
        r.billedAmount(),
        r.feeAmount(),
        r.securityId(),
        r.quantity(),
        r.unitPrice(),
        r.tradeDate(),
        r.settlementDate(),
        r.grossAmount(),
        r.taxWithheldAmount(),
        r.reason());
  }

  private static UpdateTransactionRequest withNotes(UpdateTransactionRequest r, String notes) {
    return new UpdateTransactionRequest(
        r.accountId(),
        r.bookingDate(),
        r.amount(),
        r.currency(),
        r.merchantDescription(),
        notes,
        r.fxRateToAccountCurrency(),
        r.billedAmount(),
        r.feeAmount(),
        r.securityId(),
        r.quantity(),
        r.unitPrice(),
        r.tradeDate(),
        r.settlementDate(),
        r.grossAmount(),
        r.taxWithheldAmount(),
        r.reason());
  }

  private static UpdateTransactionRequest withAccount(UpdateTransactionRequest r, UUID accountId) {
    return new UpdateTransactionRequest(
        accountId,
        r.bookingDate(),
        r.amount(),
        r.currency(),
        r.merchantDescription(),
        r.notes(),
        r.fxRateToAccountCurrency(),
        r.billedAmount(),
        r.feeAmount(),
        r.securityId(),
        r.quantity(),
        r.unitPrice(),
        r.tradeDate(),
        r.settlementDate(),
        r.grossAmount(),
        r.taxWithheldAmount(),
        r.reason());
  }

  private static UpdateTransactionRequest withReason(UpdateTransactionRequest r, String reason) {
    return new UpdateTransactionRequest(
        r.accountId(),
        r.bookingDate(),
        r.amount(),
        r.currency(),
        r.merchantDescription(),
        r.notes(),
        r.fxRateToAccountCurrency(),
        r.billedAmount(),
        r.feeAmount(),
        r.securityId(),
        r.quantity(),
        r.unitPrice(),
        r.tradeDate(),
        r.settlementDate(),
        r.grossAmount(),
        r.taxWithheldAmount(),
        reason);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private static CreateTransactionRequest purchase(String amount) {
    return TransactionRequests.cash(
        PURCHASE,
        today(),
        new BigDecimal(amount),
        "CHF",
        "Shop",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static CreateTransactionRequest cashRow(String type, String amount) {
    return TransactionRequests.cash(
        type, today(), new BigDecimal(amount), "CHF", null, null, null, null, null, null, null);
  }

  // --- calls ------------------------------------------------------------------------------------

  private static String rowUri(UUID accountId, UUID transactionId) {
    return "/api/v1/accounts/" + accountId + "/transactions/" + transactionId;
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

  private RestTestClient.ResponseSpec put(
      String token, UUID accountId, UUID transactionId, UpdateTransactionRequest request) {
    return client(token)
        .put()
        .uri(rowUri(accountId, transactionId))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", transactionId))
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  // The ETag must be the version the resulting row - edited or replacement - is stored with.
  private TransactionCorrectionResponse update(
      String token, UUID accountId, UUID transactionId, UpdateTransactionRequest request) {
    EntityExchangeResult<TransactionCorrectionResponse> result =
        put(token, accountId, transactionId, request)
            .expectStatus()
            .isOk()
            .expectBody(TransactionCorrectionResponse.class)
            .returnResult();
    UUID resulting = result.getResponseBody().transaction().id();
    return CurrentVersion.storedEtag(result, dataSource, "transaction", resulting);
  }

  private TransactionResponse overrideCategory(
      String token, UUID accountId, UUID transactionId, UUID categoryId) {
    return client(token)
        .put()
        .uri(rowUri(accountId, transactionId) + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", transactionId))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(categoryId))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  private List<TransactionResponse> list(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  private List<TransactionResponse> restorable(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions/deleted")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private BigDecimal balance(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
        .returnResult()
        .getResponseBody()
        .value();
  }

  // This month's CHF spending across the workspace.
  private BigDecimal spending(String token) {
    CashFlowResponse flow =
        client(token)
            .get()
            .uri("/api/v1/cash-flow?month=" + YearMonth.from(today()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(CashFlowResponse.class)
            .returnResult()
            .getResponseBody();
    return flow.spending().stream()
        .filter(line -> "CHF".equals(line.currency()))
        .map(CashFlowResponse.CurrencyAmount::amount)
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  // --- database ---------------------------------------------------------------------------------

  // An imported row as a CSV import (EPIC 07) would leave it: source CSV, so its removal is a void.
  private UUID insertImported(UUID accountId, String amount) {
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, source) SELECT ?, workspace_id, id, ?, ?,"
                    + " ?, 'CHF', 'CSV' FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setString(2, PURCHASE);
      statement.setObject(3, today());
      statement.setBigDecimal(4, new BigDecimal(amount));
      statement.setObject(5, accountId);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  // A confirmed LEG_PAIR match flags both legs an internal transfer (US-09-02).
  private void confirmMatch(UUID cardAccountId, UUID paymentId, UUID creditId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO settlement_match (workspace_id, card_account_id,"
                    + " payment_transaction_id, card_transaction_id, status, match_basis)"
                    + " SELECT workspace_id, id, ?, ?, 'CONFIRMED', 'LEG_PAIR' FROM account"
                    + " WHERE id = ?")) {
      statement.setObject(1, paymentId);
      statement.setObject(2, creditId);
      statement.setObject(3, cardAccountId);
      statement.executeUpdate();
    }
    execute("UPDATE transaction SET is_internal_transfer = TRUE WHERE id = ?", paymentId);
    execute("UPDATE transaction SET is_internal_transfer = TRUE WHERE id = ?", creditId);
  }

  private UUID defaultCategory(String code) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM category WHERE workspace_id IS NULL AND code = ?")) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return (UUID) resultSet.getObject(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private int countRows(UUID accountId) {
    return queryDecimal("SELECT count(*) FROM transaction WHERE account_id = ?", accountId)
        .intValue();
  }

  private BigDecimal queryDecimal(String sql, UUID parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getBigDecimal(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void execute(String sql, UUID parameter) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      statement.executeUpdate();
    }
  }

  // --- users and access -------------------------------------------------------------------------

  private void grantAccount(String token, UUID memberId, UUID accountId, String level) {
    client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                memberId, ScopeTypeValues.ACCOUNT, accountId, null, level))
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
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return (UUID) resultSet.getObject(1);
      }
    } catch (SQLException e) {
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

  private AccountSummaryResponse createAccount(String token, String type, String currency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account(type, type, currency).build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
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
