package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateCategorizationRuleRequest;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SetTransactionCategoryRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionCorrectionResponse;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.SettlementDetectionService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-02: removing a transaction the way its provenance requires - a manual row is soft-deleted
 * (T1) and restorable for 30 days, an imported row is voided and reversed (T2). The DoD's tests are
 * {@link #aManualTransactionIsSoftDeletedAndRestorable} (T1), {@link
 * #anImportedTransactionIsVoidedWithAReversingEntry} (T2) and {@link
 * #theLedgerTriggerRejectsAFinancialUpdateWhateverTheTier}; T3 arrives with reconciliation
 * (US-25-02). Imports do not exist yet, so an imported row is inserted the way one would leave it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionRemovalControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String PURCHASE = "CREDIT_CARD_PURCHASE";
  private static final String SOFT_DELETE = "SOFT_DELETE";
  private static final String VOID = "VOID";

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

  @Autowired TransactionRepository transactionRepository;

  @Autowired SettlementDetectionService settlementDetectionService;

  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM settlement_match",
              "DELETE FROM fx_rate",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM categorization_rule",
              // reversals first: they reference their originals
              "DELETE FROM transaction WHERE replaces_transaction_id IS NOT NULL",
              "DELETE FROM transaction WHERE related_transaction_id IS NOT NULL",
              "DELETE FROM transaction",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
              "DELETE FROM account_securities",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM authorization_denial_log",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              // a member's own categories (only a flat one is created here); V19's shipped
              // defaults have no workspace and stay
              "DELETE FROM workspace_category_override",
              "DELETE FROM category WHERE workspace_id IS NOT NULL",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
  }

  // --- US-07-06: correction ---------------------------------------------------------------------

  @Test
  void aManualFinancialCorrectionSoftDeletesAndReplacesAtomically() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));

    TransactionCorrectionResponse corrected =
        correct(token, card.id(), original, "-80.00", null, "Corrected shop", "fixed", null);

    assertThat(corrected.removal().removal()).isEqualTo(SOFT_DELETE);
    assertThat(corrected.transaction())
        .satisfies(
            replacement -> {
              assertThat(replacement.id()).isNotEqualTo(original.id());
              assertThat(replacement.amount()).isEqualByComparingTo("-80.00");
              assertThat(replacement.correctsTransactionId()).isEqualTo(original.id());
              assertThat(replacement.source()).isEqualTo("MANUAL");
              assertThat(replacement.externalId()).isNull();
            });
    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(corrected.transaction().id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("80.00");
    assertThat(queryDecimal("SELECT count(*) FROM transaction WHERE account_id = ?", card.id()))
        .isEqualByComparingTo("2");
    assertThat(
            queryDecimal(
                "SELECT count(*) FROM transaction WHERE id = ? AND deleted_at IS NOT NULL",
                original.id()))
        .isEqualByComparingTo("1");
  }

  @Test
  void anImportedFinancialCorrectionVoidsAndLinksTheReplacement() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID importedId = insertImported(card.id(), PURCHASE, "-85.00", today());
    TransactionResponse imported = list(token, card.id()).get(0);

    TransactionCorrectionResponse corrected =
        correct(
            token,
            card.id(),
            imported,
            "-80.00",
            "Wrong imported amount",
            "Imported shop",
            null,
            null);

    assertThat(corrected.removal().removal()).isEqualTo(VOID);
    assertThat(corrected.removal().reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.replacesTransactionId()).isEqualTo(importedId);
              assertThat(reversal.amount()).isEqualByComparingTo("85.00");
            });
    assertThat(corrected.transaction())
        .satisfies(
            replacement -> {
              assertThat(replacement.amount()).isEqualByComparingTo("-80.00");
              assertThat(replacement.correctsTransactionId()).isEqualTo(importedId);
              assertThat(replacement.source()).isEqualTo("CSV");
              assertThat(replacement.externalId()).isNull();
            });
    assertThat(balance(token, card.id())).isEqualByComparingTo("80.00");
    assertThat(countRows(card.id())).isEqualTo(3);
  }

  @Test
  void anAccountCorrectionCreatesTheReplacementOnTheDestinationAccount() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse source = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse destination = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, source.id(), purchase("-85.00"));

    TransactionCorrectionResponse corrected =
        correct(
            token,
            source.id(),
            original,
            "-80.00",
            null,
            original.merchantDescription(),
            original.notes(),
            destination.id());

    assertThat(corrected.removal().removal()).isEqualTo(SOFT_DELETE);
    assertThat(corrected.transaction().accountId()).isEqualTo(destination.id());
    assertThat(corrected.transaction().correctsTransactionId()).isEqualTo(original.id());
    assertThat(list(token, source.id())).isEmpty();
    assertThat(list(token, destination.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(corrected.transaction().id());
    assertThat(balance(token, source.id())).isEqualByComparingTo("0");
    assertThat(balance(token, destination.id())).isEqualByComparingTo("80.00");
  }

  @Test
  void aTextOnlyCorrectionUpdatesInPlaceWithoutRemoval() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));

    TransactionCorrectionResponse corrected =
        correct(
            token, card.id(), original, "-85.00", null, "Correct merchant", "Correct note", null);

    assertThat(corrected.removal()).isNull();
    assertThat(corrected.transaction().id()).isEqualTo(original.id());
    assertThat(corrected.transaction().merchantDescription()).isEqualTo("Correct merchant");
    assertThat(corrected.transaction().notes()).isEqualTo("Correct note");
    assertThat(corrected.transaction().correctsTransactionId()).isNull();
    assertThat(countRows(card.id())).isEqualTo(1);
  }

  @Test
  void aUserCategoryOverrideCarriesToTheReplacement() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));
    UUID shopping = defaultCategory("SHOPPING");

    client(token)
        .put()
        .uri(rowUri(card.id(), original.id()) + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", original.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(shopping))
        .exchange()
        .expectStatus()
        .isOk();
    TransactionResponse overridden = list(token, card.id()).get(0);
    assertThat(overridden.categoryAssignedBy()).isEqualTo("USER");

    TransactionCorrectionResponse corrected =
        correct(
            token,
            card.id(),
            overridden,
            "-80.00",
            null,
            overridden.merchantDescription(),
            overridden.notes(),
            null);

    assertThat(corrected.transaction().categoryId()).isEqualTo(shopping);
    assertThat(corrected.transaction().categoryAssignedBy()).isEqualTo("USER");
  }

  @Test
  void anInvalidReplacementRollsBackTheRemoval() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));

    client(token)
        .put()
        .uri(rowUri(card.id(), original.id()))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", original.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(correction(original, "85.00", null, "Invalid positive purchase", null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    assertThat(list(token, card.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(original.id());
              assertThat(row.amount()).isEqualByComparingTo("-85.00");
              assertThat(row.deletedAt()).isNull();
              assertThat(row.voidedAt()).isNull();
            });
    assertThat(countRows(card.id())).isEqualTo(1);
  }

  // --- T1: manual entry, soft delete ------------------------------------------------------------

  @Test
  void aManualTransactionIsSoftDeletedAndRestorable() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse kept = record(token, card.id(), purchase("-20.00"));
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));
    assertThat(typo.removal()).isEqualTo(SOFT_DELETE);
    assertThat(balance(token, card.id())).isEqualByComparingTo("105.00");

    TransactionRemovalResponse removed = remove(token, card.id(), typo.id(), null);

    assertThat(removed.removal()).isEqualTo(SOFT_DELETE);
    assertThat(removed.reversals()).isEmpty();
    assertThat(removed.affected())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(typo.id());
              assertThat(row.deletedAt()).isNotNull();
              assertThat(row.removal()).isNull();
            });
    // Hidden from every view and figure; no compensating entry.
    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(kept.id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("20.00");
    assertThat(spending(token)).isEqualByComparingTo("20.00");
    assertThat(countRows(card.id())).isEqualTo(2);
    assertThat(restorable(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(typo.id());

    TransactionRemovalResponse restored = restore(token, card.id(), typo.id());

    assertThat(restored.affected()).extracting(TransactionResponse::id).containsExactly(typo.id());
    assertThat(list(token, card.id())).hasSize(2);
    assertThat(balance(token, card.id())).isEqualByComparingTo("105.00");
    assertThat(restorable(token, card.id())).isEmpty();
  }

  @Test
  void aSoftDeleteCannotBeRestoredAfterThirtyDays() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));
    remove(token, card.id(), typo.id(), null);
    execute(
        "UPDATE transaction SET deleted_at = now() - interval '31 days' WHERE id = ?", typo.id());

    assertThat(restorable(token, card.id())).isEmpty();
    client(token)
        .post()
        .uri(rowUri(card.id(), typo.id()) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", typo.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    // Still in the data (never purged, FR-LIF-001), still hidden.
    assertThat(countRows(card.id())).isEqualTo(1);
    assertThat(list(token, card.id())).isEmpty();
  }

  @Test
  void aCardPurchasesFeeRowGoesAndComesBackWithIt() {
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
    assertThat(list(token, card.id())).hasSize(2);

    TransactionRemovalResponse removed = remove(token, card.id(), foreign.id(), null);

    assertThat(removed.affected()).hasSize(2);
    assertThat(list(token, card.id())).isEmpty();

    TransactionRemovalResponse restored = restore(token, card.id(), foreign.id());

    assertThat(restored.affected()).hasSize(2);
    assertThat(list(token, card.id())).hasSize(2);
  }

  @Test
  void aRetryWithTheKeyOfADeletedTransactionIsAConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    CreateTransactionRequest keyed =
        TransactionRequests.cash(
            PURCHASE,
            today(),
            new BigDecimal("-85.00"),
            "CHF",
            null,
            null,
            null,
            "key-1",
            null,
            null,
            null);
    TransactionResponse recorded = record(token, card.id(), keyed);
    remove(token, card.id(), recorded.id(), null);

    post(token, card.id(), keyed).expectStatus().isEqualTo(HttpStatus.CONFLICT);
    assertThat(countRows(card.id())).isEqualTo(1);
  }

  // --- T2: imported, void -----------------------------------------------------------------------

  @Test
  void anImportedTransactionIsVoidedWithAReversingEntry() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", LocalDate.now());
    TransactionResponse listed = list(token, card.id()).get(0);
    assertThat(listed.removal()).isEqualTo(VOID);
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");

    // The system decided on a void, which needs a reason.
    client(token)
        .delete()
        .uri(rowUri(card.id(), imported))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    TransactionRemovalResponse voided = remove(token, card.id(), imported, "Duplicate import");

    assertThat(voided.removal()).isEqualTo(VOID);
    assertThat(voided.affected())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.voidedAt()).isNotNull();
              assertThat(row.voidReason()).isEqualTo("Duplicate import");
              assertThat(row.amount()).isEqualByComparingTo("-85.00"); // financial fields untouched
            });
    assertThat(voided.reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.amount()).isEqualByComparingTo("85.00");
              assertThat(reversal.transactionType()).isEqualTo(PURCHASE);
              assertThat(reversal.replacesTransactionId()).isEqualTo(imported);
              assertThat(reversal.bookingDate()).isEqualTo(today());
              assertThat(reversal.categoryId()).isNull();
              assertThat(reversal.removal()).isNull();
            });
    // Both stay listed (FR-LIF-003); balances net to zero; spending leaves the pair out.
    assertThat(list(token, card.id())).hasSize(2);
    assertThat(balance(token, card.id())).isEqualByComparingTo("0");
    assertThat(spending(token)).isEqualByComparingTo("0");
    assertThat(uncategorized(token, card.id())).isEmpty();

    // Neither the voided original nor its reversal can be removed again.
    client(token)
        .delete()
        .uri(rowUri(card.id(), imported) + "?reason=again")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    client(token)
        .delete()
        .uri(rowUri(card.id(), voided.reversals().get(0).id()) + "?reason=again")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", voided.reversals().get(0).id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    // A reversal's category follows its original.
    client(token)
        .put()
        .uri(rowUri(card.id(), voided.reversals().get(0).id()) + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", voided.reversals().get(0).id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(defaultCategory("SHOPPING")))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // --- US-07-07: restoring a void ------------------------------------------------------------

  // The DoD's restore within the window: the void and its reversal stay, an ordinary copy of the
  // original re-instates it (A, -A, A'), and balances and figures count it again.
  @Test
  void aVoidIsRestoredByAnOrdinaryCopyWithinThirtyDays() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    TransactionRemovalResponse voided = remove(token, card.id(), imported, "Duplicate import");
    assertThat(balance(token, card.id())).isEqualByComparingTo("0");
    assertThat(spending(token)).isEqualByComparingTo("0");
    // FR-LIF-006: restorable through the interface - the restore list offers the void.
    assertThat(restorable(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(imported);

    TransactionRemovalResponse restored = restore(token, card.id(), imported);

    assertThat(restored.removal()).isEqualTo(VOID);
    assertThat(restored.reversals()).isEmpty();
    assertThat(restored.affected())
        .singleElement()
        .satisfies(
            original -> {
              assertThat(original.id()).isEqualTo(imported);
              assertThat(original.voidedAt()).isNotNull(); // the void stays history
              assertThat(original.voidReason()).isEqualTo("Duplicate import");
            });
    TransactionResponse copy = restored.restored().get(0);
    assertThat(restored.restored()).hasSize(1);
    assertThat(copy.restoresTransactionId()).isEqualTo(imported);
    assertThat(copy.amount()).isEqualByComparingTo("-85.00");
    assertThat(copy.source()).as("still imported, so still T2").isEqualTo("CSV");
    assertThat(copy.voidedAt()).isNull();
    assertThat(copy.removal()).isEqualTo(VOID);
    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactlyInAnyOrder(imported, voided.reversals().get(0).id(), copy.id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");
    assertThat(spending(token)).isEqualByComparingTo("85.00");
    assertThat(restorable(token, card.id())).isEmpty();

    // A second restore of the same void is refused and adds nothing.
    client(token)
        .post()
        .uri(rowUri(card.id(), imported) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .value(detail -> assertThat(detail.toString()).contains(copy.id().toString()));
    assertThat(countRows(card.id())).isEqualTo(3);
  }

  @Test
  void aVoidCannotBeRestoredAfterThirtyDays() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    remove(token, card.id(), imported, "Duplicate import");
    execute("UPDATE transaction SET voided_at = now() - interval '31 days' WHERE id = ?", imported);

    assertThat(restorable(token, card.id())).isEmpty();
    client(token)
        .post()
        .uri(rowUri(card.id(), imported) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(countRows(card.id())).isEqualTo(2);
    assertThat(balance(token, card.id())).isEqualByComparingTo("0");
    assertThat(spending(token)).isEqualByComparingTo("0");
  }

  // Just inside the window the void is still restorable.
  @Test
  void aVoidIsRestorableUntilTheWindowCloses() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    remove(token, card.id(), imported, "Duplicate import");
    execute(
        "UPDATE transaction SET voided_at = now() - interval '29 days 23 hours' WHERE id = ?",
        imported);

    assertThat(restore(token, card.id(), imported).restored()).hasSize(1);
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");
  }

  // The copy is dated like the original, so a balance on a date between the booking and the void
  // must count the transaction once: the void pair counts as zero on every date (restated).
  @Test
  void aRestoreDoesNotCountThePurchaseTwiceInAPastBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today().minusDays(10));
    remove(token, card.id(), imported, "Duplicate import");
    assertThat(pastBalance(card.id(), today().minusDays(5))).isEqualByComparingTo("0");
    assertThat(valueBasis(token, card.id()))
        .as("all rows voided: a measured zero")
        .isEqualTo("LEDGER");

    restore(token, card.id(), imported);

    assertThat(pastBalance(card.id(), today().minusDays(5))).isEqualByComparingTo("-85.00");
    assertThat(pastBalance(card.id(), today())).isEqualByComparingTo("-85.00");
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");
  }

  // The same holds for a correction (US-07-06): the replacement is dated like the original.
  @Test
  void aCorrectionDoesNotCountThePurchaseTwiceInAPastBalance() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    insertImported(card.id(), PURCHASE, "-85.00", today().minusDays(10));
    TransactionResponse read = list(token, card.id()).get(0);

    correct(token, card.id(), read, "-80.00", "Statement shows 80.00", null, null, null);

    assertThat(pastBalance(card.id(), today().minusDays(5))).isEqualByComparingTo("-80.00");
    assertThat(balance(token, card.id())).isEqualByComparingTo("80.00");
  }

  // A fee voided on its own belongs to its purchase: while the purchase is voided too, the fee
  // cannot come back alone, or it would count without it and miss the purchase's later restore.
  @Test
  void aFeeCannotBeRestoredWhileItsPurchaseIsStillVoided() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID purchase = insertImported(card.id(), PURCHASE, "-100.00", today());
    UUID fee = insertImportedFee(card.id(), purchase, "-2.00");
    remove(token, card.id(), fee, "Fee waived");
    remove(token, card.id(), purchase, "Duplicate import");

    client(token)
        .post()
        .uri(rowUri(card.id(), fee) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", fee))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .value(detail -> assertThat(detail.toString()).contains(purchase.toString()));
    assertThat(countRows(card.id())).isEqualTo(4);
    assertThat(balance(token, card.id())).isEqualByComparingTo("0");
  }

  // The copy keeps a foreign-currency row's amount in its own currency and the rate it was
  // converted at, so it counts exactly as the original did.
  @Test
  void aForeignCurrencyVoidIsRestoredWithItsRate() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, fx_rate_to_account_currency,"
                    + " fx_rate_date, fx_rate_estimated, source) SELECT ?, workspace_id, id, ?,"
                    + " CURRENT_DATE, -100, 'EUR', 0.95, CURRENT_DATE, FALSE, 'CSV'"
                    + " FROM account WHERE id = ?")) {
      statement.setObject(1, imported);
      statement.setString(2, PURCHASE);
      statement.setObject(3, card.id());
      statement.executeUpdate();
    }
    remove(token, card.id(), imported, "Duplicate import");

    TransactionResponse copy = restore(token, card.id(), imported).restored().get(0);

    assertThat(copy.currency()).isEqualTo("EUR");
    assertThat(copy.amount()).isEqualByComparingTo("-100");
    assertThat(copy.fxRateToAccountCurrency()).isEqualByComparingTo("0.95");
    assertThat(copy.fxRateEstimated()).isFalse();
    assertThat(balance(token, card.id())).isEqualByComparingTo("95.00");
  }

  // A trade's copy carries the security, quantity, price and fee, so the position is back too.
  @Test
  void aVoidedTradeIsRestoredWithItsQuantity() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "SECURITIES", "CHF");
    UUID security = createSecurity(token);
    UUID buy = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, security_id, quantity, unit_price,"
                    + " fee_amount, source) SELECT ?, workspace_id, id, 'BUY', CURRENT_DATE,"
                    + " -1005, 'CHF', ?, 10, 100, 5, 'CSV' FROM account WHERE id = ?")) {
      statement.setObject(1, buy);
      statement.setObject(2, security);
      statement.setObject(3, depot.id());
      statement.executeUpdate();
    }
    remove(token, depot.id(), buy, "Wrong depot");

    TransactionResponse copy = restore(token, depot.id(), buy).restored().get(0);

    assertThat(copy.securityId()).isEqualTo(security);
    assertThat(copy.quantity()).isEqualByComparingTo("10");
    assertThat(copy.unitPrice()).isEqualByComparingTo("100");
    assertThat(copy.feeAmount()).isEqualByComparingTo("5");
    assertThat(
            queryDecimal("SELECT sum(quantity) FROM transaction WHERE account_id = ?", depot.id()))
        .isEqualByComparingTo("10");
  }

  // A restored transaction is a normal one again: it can be categorized, removed and restored
  // again, any number of times; the voided original points the member at it.
  @Test
  void aRestoredTransactionCanBeCategorizedVoidedAndRestoredAgain() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    remove(token, card.id(), imported, "Duplicate import");
    TransactionResponse copy = restore(token, card.id(), imported).restored().get(0);
    assertThat(copy.categoryId()).as("categorized like a new row").isNotNull();

    UUID shopping = defaultCategory("SHOPPING");
    client(token)
        .put()
        .uri(rowUri(card.id(), copy.id()) + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", copy.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(shopping))
        .exchange()
        .expectStatus()
        .isOk();

    // The voided original stays voided and names the entry to act on instead.
    client(token)
        .delete()
        .uri(rowUri(card.id(), imported) + "?reason=Again")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .value(detail -> assertThat(detail.toString()).contains(copy.id().toString()));

    remove(token, card.id(), copy.id(), "Duplicate after all");
    assertThat(balance(token, card.id())).isEqualByComparingTo("0");
    TransactionResponse again = restore(token, card.id(), copy.id()).restored().get(0);

    assertThat(again.restoresTransactionId()).isEqualTo(copy.id());
    assertThat(again.categoryId()).as("the member's override carries over").isEqualTo(shopping);
    assertThat(again.categoryAssignedBy()).isEqualTo("USER");
    assertThat(balance(token, card.id())).isEqualByComparingTo("85.00");
    assertThat(spending(token)).isEqualByComparingTo("85.00");
  }

  // A FEE row voided with its purchase comes back with it - also when the member restores the fee -
  // and its copy belongs to the purchase's copy, never to the voided purchase.
  @Test
  void aPurchaseAndItsFeeAreRestoredTogetherFromEitherRow() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID purchase = insertImported(card.id(), PURCHASE, "-100.00", today());
    UUID fee = insertImportedFee(card.id(), purchase, "-2.00");
    TransactionRemovalResponse voided = remove(token, card.id(), purchase, "Duplicate import");
    assertThat(voided.affected())
        .extracting(TransactionResponse::id)
        .containsExactly(purchase, fee);
    assertThat(balance(token, card.id())).isEqualByComparingTo("0");

    TransactionRemovalResponse restored = restore(token, card.id(), fee);

    assertThat(restored.affected())
        .extracting(TransactionResponse::id)
        .containsExactly(purchase, fee);
    assertThat(restored.restored())
        .extracting(TransactionResponse::restoresTransactionId)
        .containsExactly(purchase, fee);
    TransactionResponse purchaseCopy = restored.restored().get(0);
    TransactionResponse feeCopy = restored.restored().get(1);
    assertThat(feeCopy.relatedTransactionId()).isEqualTo(purchaseCopy.id());
    assertThat(countRows(card.id())).isEqualTo(6);
    assertThat(balance(token, card.id())).isEqualByComparingTo("102.00");
  }

  // A fee voided on its own earlier stays voided when its purchase's void is restored; restored
  // later, its copy joins the purchase's copy.
  @Test
  void aSeparatelyVoidedFeeStaysVoidedUntilRestoredItself() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID purchase = insertImported(card.id(), PURCHASE, "-100.00", today());
    UUID fee = insertImportedFee(card.id(), purchase, "-2.00");
    remove(token, card.id(), fee, "Fee waived");
    remove(token, card.id(), purchase, "Duplicate import");

    TransactionRemovalResponse purchaseRestored = restore(token, card.id(), purchase);

    assertThat(purchaseRestored.restored())
        .extracting(TransactionResponse::restoresTransactionId)
        .containsExactly(purchase);
    assertThat(balance(token, card.id())).isEqualByComparingTo("100.00");
    assertThat(restorable(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(fee);

    TransactionResponse feeCopy = restore(token, card.id(), fee).restored().get(0);

    assertThat(feeCopy.relatedTransactionId()).isEqualTo(purchaseRestored.restored().get(0).id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("102.00");
  }

  // A two-sided transfer comes back whole, from either leg, and only with EDIT on both accounts.
  @Test
  void aVoidedTransferIsRestoredWithBothLegsAndNeedsEditOnBothAccounts() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(adminToken, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(adminToken, "CASH", "CHF");
    UUID[] legs = insertImportedTransfer(current.id(), savings.id(), "100.00");
    UUID debit = legs[0];
    UUID credit = legs[1];
    TransactionRemovalResponse voided = remove(adminToken, current.id(), debit, "Duplicate import");
    assertThat(voided.affected())
        .extracting(TransactionResponse::id)
        .containsExactly(debit, credit);
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    grantAccount(adminToken, memberId, savings.id(), AccessLevelValues.EDIT);
    grantAccount(adminToken, memberId, current.id(), AccessLevelValues.READ);

    client(memberToken)
        .post()
        .uri(rowUri(savings.id(), credit) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", credit))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(countRows(current.id()) + countRows(savings.id())).isEqualTo(4);

    grantAccount(adminToken, memberId, current.id(), AccessLevelValues.EDIT);
    TransactionRemovalResponse restored = restore(memberToken, savings.id(), credit);

    assertThat(restored.restored())
        .extracting(TransactionResponse::restoresTransactionId)
        .containsExactly(debit, credit);
    TransactionResponse debitCopy = restored.restored().get(0);
    TransactionResponse creditCopy = restored.restored().get(1);
    assertThat(debitCopy.accountId()).isEqualTo(current.id());
    assertThat(debitCopy.counterpartyAccountId()).isEqualTo(savings.id());
    assertThat(debitCopy.internalTransfer()).isTrue();
    assertThat(creditCopy.relatedTransactionId()).isEqualTo(debitCopy.id());
    assertThat(creditCopy.amount()).isEqualByComparingTo("100.00");
    assertThat(countRows(current.id()) + countRows(savings.id())).isEqualTo(6);
  }

  @Test
  void restoringAVoidedPaymentMatchesItsCopyAgain() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    setSettlementSource(token, card.id(), checking.id());
    UUID payment = insertImported(checking.id(), "WITHDRAWAL", "-300.00", today());
    record(token, card.id(), cashRow("SETTLEMENT", "300.00"));
    assertThat(matches(card.id())).isEqualByComparingTo("1");
    remove(token, checking.id(), payment, "Duplicate import");
    assertThat(matches(card.id())).isEqualByComparingTo("0");

    TransactionResponse copy = restore(token, checking.id(), payment).restored().get(0);

    assertThat(matches(card.id())).isEqualByComparingTo("1");
    assertThat(list(token, checking.id()))
        .filteredOn(row -> row.id().equals(copy.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.internalTransfer()).isTrue());
  }

  // AC3: as with a soft delete, a pair the member rejected stays rejected through a void and its
  // restore - the copy inherits the decision instead of being matched to the same credit again.
  @Test
  void aRejectedPairStaysRejectedThroughAVoidAndRestore() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    setSettlementSource(token, card.id(), checking.id());
    UUID payment = insertImported(checking.id(), "WITHDRAWAL", "-300.00", today());
    record(token, card.id(), cashRow("SETTLEMENT", "300.00"));
    UUID match = matchIdOf(card.id());
    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + match + "/reject")
        .headers(CurrentVersion.ifMatch(dataSource, "settlement_match", match))
        .exchange()
        .expectStatus()
        .isOk();
    remove(token, checking.id(), payment, "Duplicate import");

    TransactionResponse copy = restore(token, checking.id(), payment).restored().get(0);

    assertThat(matches(card.id())).isEqualByComparingTo("2");
    assertThat(rejectedMatches(card.id())).isEqualByComparingTo("2");
    assertThat(list(token, checking.id()))
        .filteredOn(row -> row.id().equals(copy.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.internalTransfer()).isFalse());
  }

  // A void made by a correction (US-07-06) is not restorable: the replacement is the current entry.
  @Test
  void aCorrectedImportedTransactionIsNotRestorable() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    TransactionResponse read = list(token, card.id()).get(0);
    correct(token, card.id(), read, "-80.00", "Statement shows 80.00", null, null, null);

    assertThat(restorable(token, card.id())).isEmpty();
    client(token)
        .post()
        .uri(rowUri(card.id(), imported) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(balance(token, card.id())).isEqualByComparingTo("80.00");
  }

  // FR-CNC-001: restoring a void is read-modify-write on the voided row too.
  @Test
  void restoringAVoidRequiresTheCurrentVersion() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    int beforeVoid = list(token, card.id()).get(0).version();
    remove(token, card.id(), imported, "Duplicate import");

    client(token)
        .post()
        .uri(rowUri(card.id(), imported) + "/restore")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    client(token)
        .post()
        .uri(rowUri(card.id(), imported) + "/restore")
        .headers(headers -> headers.setIfMatch("\"" + beforeVoid + "\""))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    assertThat(countRows(card.id())).isEqualTo(2);
  }

  @Test
  void restoringAVoidNeedsEditOnTheAccount() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(adminToken, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    remove(adminToken, card.id(), imported, "Duplicate import");
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    // No grant: the generic 404, also without If-Match. READ: still 404, the same as for removal.
    client(memberToken)
        .post()
        .uri(rowUri(card.id(), imported) + "/restore")
        .exchange()
        .expectStatus()
        .isNotFound();
    grantAccount(adminToken, memberId, card.id(), AccessLevelValues.READ);
    assertThat(restorable(memberToken, card.id())).hasSize(1);
    client(memberToken)
        .post()
        .uri(rowUri(card.id(), imported) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", imported))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(countRows(card.id())).isEqualTo(2);
  }

  // V50: the restore lineage is frozen like the other lineage columns (RULE-024).
  @Test
  void theRestoreLinkCannotBeRewritten() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImported(card.id(), PURCHASE, "-85.00", today());
    remove(token, card.id(), imported, "Duplicate import");
    UUID copy = restore(token, card.id(), imported).restored().get(0).id();

    assertThatThrownBy(
            () ->
                execute("UPDATE transaction SET restores_transaction_id = NULL WHERE id = ?", copy))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("transaction_ledger_append_only");
  }

  @Test
  void aVoidedTradeIsReversedWithItsQuantityNegated() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "SECURITIES", "CHF");
    UUID security = createSecurity(token);
    UUID buy = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, security_id, quantity, unit_price,"
                    + " fee_amount, source) SELECT ?, workspace_id, id, 'BUY', CURRENT_DATE,"
                    + " -1005, 'CHF', ?, 10, 100, 5, 'CSV' FROM account WHERE id = ?")) {
      statement.setObject(1, buy);
      statement.setObject(2, security);
      statement.setObject(3, depot.id());
      statement.executeUpdate();
    }

    TransactionRemovalResponse voided = remove(token, depot.id(), buy, "Wrong depot");

    assertThat(voided.reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.quantity()).isEqualByComparingTo("-10");
              assertThat(reversal.amount()).isEqualByComparingTo("1005");
              assertThat(reversal.unitPrice()).isEqualByComparingTo("100");
              assertThat(reversal.feeAmount()).isEqualByComparingTo("5");
              assertThat(reversal.securityId()).isEqualTo(security);
            });
    assertThat(
            queryDecimal("SELECT sum(quantity) FROM transaction WHERE account_id = ?", depot.id()))
        .isEqualByComparingTo("0");
  }

  @Test
  void aFutureDatedVoidIsReversedOnItsOwnDate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    LocalDate later = today().plusDays(10);
    UUID imported = insertImported(card.id(), PURCHASE, "-40.00", later);

    TransactionRemovalResponse voided = remove(token, card.id(), imported, "Cancelled");

    assertThat(voided.reversals().get(0).bookingDate()).isEqualTo(later);
  }

  // --- the append-only backstop -----------------------------------------------------------------

  @Test
  void theLedgerTriggerRejectsAFinancialUpdateWhateverTheTier() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse manual = record(token, card.id(), purchase("-85.00"));
    remove(token, card.id(), manual.id(), null);
    UUID imported = insertImported(card.id(), PURCHASE, "-40.00", LocalDate.now());
    remove(token, card.id(), imported, "Duplicate");

    for (UUID row : List.of(manual.id(), imported)) {
      assertThatThrownBy(() -> execute("UPDATE transaction SET amount = -1 WHERE id = ?", row))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("transaction_ledger_append_only");
    }
  }

  // --- linked rows -----------------------------------------------------------------------------

  @Test
  void removingAMatchedPaymentDissolvesTheMatchAndFreesTheCredit() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    TransactionResponse payment =
        record(
            token,
            checking.id(),
            TransactionRequests.cash(
                "WITHDRAWAL",
                today(),
                new BigDecimal("-300.00"),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    TransactionResponse credit =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                "SETTLEMENT",
                today(),
                new BigDecimal("300.00"),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    confirmMatch(card.id(), payment.id(), credit.id());

    TransactionRemovalResponse removed = remove(token, checking.id(), payment.id(), null);

    assertThat(removed.unmatchedTransactionIds()).containsExactly(credit.id());
    assertThat(
            queryDecimal(
                "SELECT count(*) FROM settlement_match WHERE card_account_id = ?", card.id()))
        .isEqualByComparingTo("0");
    TransactionResponse freed = list(token, card.id()).get(0);
    assertThat(freed.internalTransfer()).isFalse();
  }

  // --- review findings on PR #180 ------------------------------------------------------------

  // V36 required tax_withheld_amount >= 0 on every row; the reversal negates it (V40).
  @Test
  void aVoidedDividendWithWithholdingTaxIsReversedInFull() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse depot = createAccount(token, "SECURITIES", "CHF");
    UUID security = createSecurity(token);
    UUID dividend = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, security_id, gross_amount,"
                    + " tax_withheld_amount, net_amount, source) SELECT ?, workspace_id, id,"
                    + " 'DIVIDEND', CURRENT_DATE, 75, 'CHF', ?, 100, 25, 75, 'CSV' FROM account"
                    + " WHERE id = ?")) {
      statement.setObject(1, dividend);
      statement.setObject(2, security);
      statement.setObject(3, depot.id());
      statement.executeUpdate();
    }

    TransactionRemovalResponse voided = remove(token, depot.id(), dividend, "Booked twice");

    assertThat(voided.reversals())
        .singleElement()
        .satisfies(
            reversal -> {
              assertThat(reversal.amount()).isEqualByComparingTo("-75");
              assertThat(reversal.grossAmount()).isEqualByComparingTo("-100");
              assertThat(reversal.taxWithheldAmount()).isEqualByComparingTo("-25");
              assertThat(reversal.netAmount()).isEqualByComparingTo("-75");
            });
    assertThat(queryDecimal("SELECT sum(amount) FROM transaction WHERE account_id = ?", depot.id()))
        .isEqualByComparingTo("0");
  }

  // A soft delete used to drop the row's rejected matches, so a restore re-confirmed the very pair
  // the member had rejected.
  @Test
  void aRejectedPairStaysRejectedThroughASoftDeleteAndRestore() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    setSettlementSource(token, card.id(), checking.id());
    TransactionResponse payment = record(token, checking.id(), cashRow("WITHDRAWAL", "-300.00"));
    TransactionResponse credit = record(token, card.id(), cashRow("SETTLEMENT", "300.00"));
    UUID match = matchIdOf(card.id());
    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + match + "/reject")
        .headers(CurrentVersion.ifMatch(dataSource, "settlement_match", match))
        .exchange()
        .expectStatus()
        .isOk();

    remove(token, card.id(), credit.id(), null);

    assertThat(rejectedMatches(card.id())).isEqualByComparingTo("1");
    // While its card leg is deleted the match is neither listed nor actionable - and loads fine.
    assertThat(settlementMatches(token, "REJECTED")).isEmpty();
    client(token)
        .post()
        .uri("/api/v1/accounts/" + card.id() + "/settlement-matches/run")
        .exchange()
        .expectStatus()
        .isOk();
    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + match + "/confirm")
        .headers(CurrentVersion.ifMatch(dataSource, "settlement_match", match))
        .exchange()
        .expectStatus()
        .isNotFound();

    restore(token, card.id(), credit.id());

    assertThat(rejectedMatches(card.id())).isEqualByComparingTo("1");
    assertThat(
            queryDecimal(
                "SELECT count(*) FROM settlement_match WHERE card_account_id = ?", card.id()))
        .isEqualByComparingTo("1");
    assertThat(list(token, checking.id()))
        .filteredOn(row -> row.id().equals(payment.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.internalTransfer()).isFalse());
    assertThat(settlementMatches(token, "REJECTED")).singleElement().isEqualTo(match);
  }

  // Voiding a +500 card credit adds a -500 SETTLEMENT reversal; it is not a payment to review.
  @Test
  void aVoidedCardCreditIsNotAnUnresolvedSettlement() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID credit = insertImported(card.id(), "SETTLEMENT", "500.00", today());

    remove(token, card.id(), credit, "Duplicate import");

    assertThat(pendingReview(token)).isEmpty();
  }

  // Removal used to lock only the cards already matched to the row. A detection run holding a
  // card's lock may be about to match this unmatched payment, so removal must wait for it.
  @Test
  void aRemovalWaitsForMatchingOnTheCardEvenBeforeTheRowIsMatched() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse checking = createAccount(token, "CASH", "CHF");
    setSettlementSource(token, card.id(), checking.id());
    TransactionResponse payment = record(token, checking.id(), cashRow("WITHDRAWAL", "-120.00"));
    AuthenticatedUserPrincipal admin = adminPrincipal();

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> matching =
          pool.submit(
              () -> {
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(admin, null, List.of()));
                try {
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            settlementDetectionService.lockCard(card.id());
                            locked.countDown();
                            await(release);
                          });
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
      Future<TransactionRemovalResponse> removal =
          pool.submit(() -> remove(token, checking.id(), payment.id(), null));

      assertThatThrownBy(() -> removal.get(1, TimeUnit.SECONDS))
          .isInstanceOf(TimeoutException.class);
      release.countDown();
      matching.get(30, TimeUnit.SECONDS);
      assertThat(removal.get(30, TimeUnit.SECONDS).removal()).isEqualTo(SOFT_DELETE);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  // --- access ----------------------------------------------------------------------------------

  @Test
  void removingAndRestoringNeedEditOnTheAccount() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(adminToken, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(adminToken, card.id(), purchase("-85.00"));
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    grantAccount(adminToken, memberId, card.id(), AccessLevelValues.READ);

    client(memberToken)
        .delete()
        .uri(rowUri(card.id(), typo.id()))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", typo.id()))
        .exchange()
        .expectStatus()
        .isNotFound();
    // Reading the restore list needs READ only.
    remove(adminToken, card.id(), typo.id(), null);
    assertThat(restorable(memberToken, card.id())).hasSize(1);
    client(memberToken)
        .post()
        .uri(rowUri(card.id(), typo.id()) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", typo.id()))
        .exchange()
        .expectStatus()
        .isNotFound();

    grantAccount(adminToken, memberId, card.id(), AccessLevelValues.EDIT);
    restore(memberToken, card.id(), typo.id());
    assertThat(list(adminToken, card.id())).hasSize(1);
  }

  @Test
  void anotherAccountsTransactionIsNotFound() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse other = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));

    client(token)
        .delete()
        .uri(rowUri(other.id(), typo.id()))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", typo.id()))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(list(token, card.id())).hasSize(1);
  }

  // --- #207: If-Match on removal and restore ---------------------------------------------------

  // FR-CNC-001: removal and restore are read-modify-write on the transaction. Without If-Match
  // nothing is attempted (428); with the version from before another write it is a 412 and the
  // row is unchanged.
  @Test
  void removalAndRestoreRequireTheCurrentVersion() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(token, card.id(), purchase("-85.00"));

    client(token)
        .delete()
        .uri(rowUri(card.id(), typo.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");
    assertThat(list(token, card.id())).hasSize(1);

    remove(token, card.id(), typo.id(), null);

    client(token)
        .post()
        .uri(rowUri(card.id(), typo.id()) + "/restore")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");
    client(token)
        .post()
        .uri(rowUri(card.id(), typo.id()) + "/restore")
        .headers(headers -> headers.setIfMatch("\"" + typo.version() + "\""))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    assertThat(list(token, card.id())).as("still deleted").isEmpty();
  }

  // ADR 0004 / US-28-02: an existing row the caller cannot see is the generic 404 even without
  // If-Match - never the 428 that would confirm the row exists.
  @Test
  void aRowTheCallerCannotSeeIsNotFoundEvenWithoutIfMatch() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(adminToken, "CREDIT_CARD", "CHF");
    TransactionResponse typo = record(adminToken, card.id(), purchase("-85.00"));
    createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");

    client(memberToken)
        .delete()
        .uri(rowUri(card.id(), typo.id()))
        .exchange()
        .expectStatus()
        .isNotFound();
    client(memberToken)
        .post()
        .uri(rowUri(card.id(), typo.id()) + "/restore")
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(list(adminToken, card.id())).hasSize(1);
  }

  // --- US-07-06 review: correction edges ---------------------------------------------------------

  // A T1 correction only soft-deletes the original. Restoring it next to its replacement would
  // count the transaction twice, so restore refuses it.
  @Test
  void aCorrectedOriginalCannotBeRestored() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));
    correct(token, card.id(), original, "-80.00", null, null, null, null);

    client(token)
        .post()
        .uri(rowUri(card.id(), original.id()) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", original.id()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(list(token, card.id())).hasSize(1);
    assertThat(balance(token, card.id())).isEqualByComparingTo("80.00");
  }

  // The body is the desired state: no counterparty means a one-sided transfer, not "unchanged".
  @Test
  void droppingTheCounterpartyIsAFinancialCorrection() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "CASH", "CHF");
    TransactionResponse debit = record(token, current.id(), transfer("-100.00", savings.id()));

    TransactionCorrectionResponse corrected =
        correct(
            token,
            current.id(),
            debit.id(),
            desiredState(debit, "-100.00", null, null, null, null));

    assertThat(corrected.removal()).isNotNull();
    assertThat(corrected.transaction().counterpartyAccountId()).isNull();
    assertThat(list(token, savings.id())).isEmpty();
    assertThat(list(token, current.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(corrected.transaction().id());
  }

  // A two-sided transfer corrected with its counterparty kept replaces both legs.
  @Test
  void correctingATransferAmountReplacesBothLegs() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "CASH", "CHF");
    TransactionResponse debit = record(token, current.id(), transfer("-100.00", savings.id()));

    TransactionCorrectionResponse corrected =
        correct(token, current.id(), debit.id(), desiredState(debit, "-120.00", null, null, null));

    assertThat(corrected.transaction().counterpartyAccountId()).isEqualTo(savings.id());
    assertThat(list(token, savings.id()))
        .singleElement()
        .satisfies(credit -> assertThat(credit.amount()).isEqualByComparingTo("120.00"));
    assertThat(list(token, current.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("-120.00"));
  }

  // #216: the incoming leg exists only as part of its transfer. Correcting it on its own used to
  // remove both legs and record only a one-sided incoming row, so the outgoing leg vanished.
  @Test
  void theIncomingLegOfATransferIsCorrectedThroughItsOutgoingLeg() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "CASH", "CHF");
    TransactionResponse debit = record(token, current.id(), transfer("-100.00", savings.id()));
    TransactionResponse credit = list(token, savings.id()).get(0);

    for (UUID counterparty : Arrays.asList(credit.counterpartyAccountId(), null)) {
      correctRaw(
              token,
              savings.id(),
              credit.id(),
              desiredState(credit, "120.00", null, null, null, counterparty),
              CurrentVersion.ifMatch(dataSource, "transaction", credit.id()))
          .expectStatus()
          .isEqualTo(HttpStatus.CONFLICT)
          .expectBody()
          .jsonPath("$.detail")
          .value(detail -> assertThat(detail.toString()).contains(debit.id().toString()));
    }

    assertThat(list(token, current.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(debit.id());
    assertThat(list(token, savings.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(credit.id());
    assertThat(activeLedgerTotal(current.id())).isEqualByComparingTo("-100.00");
    assertThat(activeLedgerTotal(savings.id())).isEqualByComparingTo("100.00");

    // Its description and notes are its own.
    TransactionCorrectionResponse described =
        correct(
            token,
            savings.id(),
            credit.id(),
            desiredState(credit, "100.00", null, "Rainy-day fund", null));
    assertThat(described.removal()).isNull();
    assertThat(described.transaction().id()).isEqualTo(credit.id());
    assertThat(described.transaction().merchantDescription()).isEqualTo("Rainy-day fund");
  }

  // #216: a fee row is part of its purchase; its amount is corrected as the purchase's feeAmount.
  @Test
  void aFeeRowIsCorrectedThroughItsPurchase() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse purchase =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                today(),
                new BigDecimal("-100.00"),
                "EUR",
                "Shop",
                null,
                null,
                null,
                new BigDecimal("0.95"),
                null,
                new BigDecimal("1.50")));
    UUID feeId = feeOf(purchase.id());
    TransactionResponse fee =
        list(token, card.id()).stream()
            .filter(row -> row.id().equals(feeId))
            .findFirst()
            .orElseThrow();

    correctRaw(
            token,
            card.id(),
            feeId,
            desiredState(fee, "-2.00", null, null, null),
            CurrentVersion.ifMatch(dataSource, "transaction", feeId))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .value(detail -> assertThat(detail.toString()).contains(purchase.id().toString()));
    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactlyInAnyOrder(purchase.id(), feeId);
    assertThat(balance(token, card.id())).isEqualByComparingTo("96.50");

    TransactionCorrectionResponse described =
        correct(token, card.id(), feeId, desiredState(fee, "-1.50", null, "FX fee", null));
    assertThat(described.removal()).isNull();
    assertThat(described.transaction().merchantDescription()).isEqualTo("FX fee");
  }

  // #216 with US-07-07: a restored fee copy is linked to the purchase copy, so it too is corrected
  // through its purchase, never on its own.
  @Test
  void aRestoredFeeCopyIsCorrectedThroughItsPurchaseToo() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID purchase = insertImported(card.id(), PURCHASE, "-100.00", today());
    UUID fee = insertImportedFee(card.id(), purchase, "-2.00");
    remove(token, card.id(), purchase, "Duplicate import");
    TransactionRemovalResponse restored = restore(token, card.id(), purchase);
    TransactionResponse purchaseCopy = restored.restored().get(0);
    TransactionResponse feeCopy = restored.restored().get(1);
    assertThat(feeCopy.restoresTransactionId()).isEqualTo(fee);

    correctRaw(
            token,
            card.id(),
            feeCopy.id(),
            desiredState(feeCopy, "-3.00", null, null, "Fee was higher"),
            CurrentVersion.ifMatch(dataSource, "transaction", feeCopy.id()))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .value(detail -> assertThat(detail.toString()).contains(purchaseCopy.id().toString()));
    assertThat(balance(token, card.id())).isEqualByComparingTo("102.00");
  }

  // #216: removing the pair and recording its replacement is one unit - a replacement that fails
  // validation leaves both legs exactly as they were.
  @Test
  void aRejectedTransferReplacementLeavesBothLegsInPlace() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "CASH", "CHF");
    TransactionResponse debit = record(token, current.id(), transfer("-100.00", savings.id()));
    UUID creditId = list(token, savings.id()).get(0).id();

    correctRaw(
            token,
            current.id(),
            debit.id(),
            desiredState(debit, "120.00", null, null, null),
            CurrentVersion.ifMatch(dataSource, "transaction", debit.id()))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    assertThat(list(token, current.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(debit.id());
    assertThat(list(token, savings.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(creditId);
    assertThat(activeLedgerTotal(current.id())).isEqualByComparingTo("-100.00");
    assertThat(activeLedgerTotal(savings.id())).isEqualByComparingTo("100.00");
    assertThat(restorable(token, current.id())).isEmpty();
    assertThat(restorable(token, savings.id())).isEmpty();
  }

  // An explicit FX rate left out means "derive it": the replacement gets the estimated daily rate.
  @Test
  void droppingAnExplicitFxRateIsAFinancialCorrection() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    seedFxRate("EUR", "CHF", "0.9000000000");
    TransactionResponse original =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                today(),
                new BigDecimal("-100.00"),
                "EUR",
                "Shop",
                null,
                null,
                null,
                new BigDecimal("0.95"),
                null,
                null));

    TransactionCorrectionResponse corrected =
        correct(
            token,
            card.id(),
            original.id(),
            withFxAndFee(desiredState(original, "-100.00", null, "Shop", null), null, null));

    assertThat(corrected.removal()).isNotNull();
    assertThat(corrected.transaction().fxRateEstimated()).isTrue();
    assertThat(corrected.transaction().fxRateToAccountCurrency()).isEqualByComparingTo("0.90");
  }

  // A different MCC is source data: corrected through a replacement. Omitted, it is kept.
  @Test
  void aDifferentMccIsCorrectedByReplacementAndAnOmittedOneIsKept() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                today(),
                new BigDecimal("-85.00"),
                "CHF",
                "Shop",
                "5411",
                null,
                null,
                null,
                null,
                null));

    TransactionCorrectionResponse recoded =
        correct(
            token,
            card.id(),
            original.id(),
            desiredState(original, "-85.00", "5812", "Shop", null));
    assertThat(recoded.removal()).isNotNull();
    assertThat(recoded.transaction().mcc()).isEqualTo("5812");

    TransactionResponse replacement = recoded.transaction();
    TransactionCorrectionResponse renamed =
        correct(
            token,
            card.id(),
            replacement.id(),
            desiredState(replacement, "-85.00", null, "Shop renamed", null));
    assertThat(renamed.removal()).isNull();
    assertThat(renamed.transaction().id()).isEqualTo(replacement.id());
    assertThat(renamed.transaction().mcc()).isEqualTo("5812");
  }

  // Rules match on the merchant description, so an in-place description edit re-runs automatic
  // categorization; a member's override stays.
  @Test
  void aDescriptionEditRecategorizesUnlessOverridden() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID groceries = defaultCategory("GROCERIES");
    createMerchantRule(token, "coop pronto", groceries);
    TransactionResponse automatic = record(token, card.id(), purchase("-85.00"));
    TransactionResponse overridden = record(token, card.id(), purchase("-12.00"));
    UUID shopping = defaultCategory("SHOPPING");
    client(token)
        .put()
        .uri(rowUri(card.id(), overridden.id()) + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", overridden.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(shopping))
        .exchange()
        .expectStatus()
        .isOk();

    TransactionCorrectionResponse recategorized =
        correct(
            token,
            card.id(),
            automatic.id(),
            desiredState(automatic, "-85.00", null, "Coop Pronto Zurich", null));
    TransactionCorrectionResponse kept =
        correct(
            token,
            card.id(),
            overridden.id(),
            desiredState(overridden, "-12.00", null, "Coop Pronto Zurich", null));

    assertThat(recategorized.removal()).isNull();
    assertThat(recategorized.transaction().categoryId()).isEqualTo(groceries);
    assertThat(kept.transaction().categoryId()).isEqualTo(shopping);
    assertThat(kept.transaction().categoryAssignedBy()).isEqualTo("USER");
  }

  // FR-CNC-001 per endpoint (#207): no If-Match is 428, a stale one 412; neither changes anything.
  @Test
  void aCorrectionNeedsTheCurrentVersion() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));
    String staleEtag = "\"" + original.version() + "\"";
    CorrectTransactionRequest request = desiredState(original, "-80.00", null, "Shop", null);

    correctRaw(token, card.id(), original.id(), request, headers -> {})
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");
    correct(
        token, card.id(), original.id(), desiredState(original, "-85.00", null, "Renamed", null));
    correctRaw(token, card.id(), original.id(), request, headers -> headers.setIfMatch(staleEtag))
        .expectStatus()
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");

    assertThat(list(token, card.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(original.id());
              assertThat(row.amount()).isEqualByComparingTo("-85.00");
            });
  }

  // A T2 correction voids the original, and a void needs its reason (FR-LIF-002b): without one
  // nothing happens at all.
  @Test
  void anImportedCorrectionWithoutAReasonChangesNothing() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID importedId = insertImported(card.id(), PURCHASE, "-85.00", today());
    TransactionResponse imported = list(token, card.id()).get(0);

    correctRaw(
            token,
            card.id(),
            importedId,
            desiredState(imported, "-80.00", null, null, null),
            CurrentVersion.ifMatch(dataSource, "transaction", importedId))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    assertThat(list(token, card.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.voidedAt()).isNull();
              assertThat(row.amount()).isEqualByComparingTo("-85.00");
            });
  }

  // Moving a row writes to the destination too: without EDIT there it is the generic 404, checked
  // before anything is removed.
  @Test
  void aMoveToAnAccountWithoutEditChangesNothing() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(adminToken, "CREDIT_CARD", "CHF");
    AccountSummaryResponse target = createAccount(adminToken, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(adminToken, card.id(), purchase("-85.00"));
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    grantAccount(adminToken, memberId, card.id(), AccessLevelValues.EDIT);
    grantAccount(adminToken, memberId, target.id(), AccessLevelValues.READ);

    correctRaw(
            memberToken,
            card.id(),
            original.id(),
            correction(original, "-85.00", null, null, null, target.id()),
            CurrentVersion.ifMatch(dataSource, "transaction", original.id()))
        .expectStatus()
        .isNotFound();

    assertThat(list(adminToken, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(original.id());
    assertThat(list(adminToken, target.id())).isEmpty();
  }

  // A reversing entry goes with its void; it is never corrected on its own.
  @Test
  void aReversingEntryCannotBeCorrected() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID importedId = insertImported(card.id(), PURCHASE, "-85.00", today());
    TransactionResponse reversal = remove(token, card.id(), importedId, "wrong").reversals().get(0);

    correctRaw(
            token,
            card.id(),
            reversal.id(),
            desiredState(reversal, "80.00", null, null, "again"),
            CurrentVersion.ifMatch(dataSource, "transaction", reversal.id()))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // Corrections chain: each replacement points at the row it corrected; only the latest counts.
  @Test
  void aCorrectionOfACorrectionChains() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));
    TransactionResponse first =
        correct(token, card.id(), original, "-80.00", null, null, null, null).transaction();
    TransactionResponse second =
        correct(token, card.id(), first, "-75.00", null, null, null, null).transaction();

    assertThat(first.correctsTransactionId()).isEqualTo(original.id());
    assertThat(second.correctsTransactionId()).isEqualTo(first.id());
    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(second.id());
    assertThat(balance(token, card.id())).isEqualByComparingTo("75.00");
  }

  // A card purchase's FX fee is part of it: the correction replaces the fee too, and the old fee
  // cannot be restored on its own next to the new one.
  @Test
  void correctingAPurchaseReplacesItsFee() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original =
        record(
            token,
            card.id(),
            TransactionRequests.cash(
                PURCHASE,
                today(),
                new BigDecimal("-100.00"),
                "EUR",
                "Shop",
                null,
                null,
                null,
                new BigDecimal("0.95"),
                null,
                new BigDecimal("1.50")));
    UUID oldFee = feeOf(original.id());

    TransactionCorrectionResponse corrected =
        correct(
            token,
            card.id(),
            original.id(),
            withFxAndFee(
                desiredState(original, "-120.00", null, "Shop", null),
                new BigDecimal("0.95"),
                new BigDecimal("1.50")));

    UUID newFee = feeOf(corrected.transaction().id());
    assertThat(newFee).isNotEqualTo(oldFee);
    assertThat(list(token, card.id()))
        .extracting(TransactionResponse::id)
        .containsExactlyInAnyOrder(corrected.transaction().id(), newFee);
    client(token)
        .post()
        .uri(rowUri(card.id(), oldFee) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", oldFee))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // A transfer whose counterparty and amounts stay as they are is edited in place, both legs kept.
  @Test
  void aTransferDescriptionEditIsNotAVoid() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "CASH", "CHF");
    TransactionResponse debit = record(token, current.id(), transfer("-100.00", savings.id()));
    TransactionResponse credit = list(token, savings.id()).get(0);

    TransactionCorrectionResponse edited =
        correct(
            token, current.id(), debit.id(), desiredState(debit, "-100.00", null, "Rent", null));

    assertThat(edited.removal()).isNull();
    assertThat(edited.transaction().id()).isEqualTo(debit.id());
    assertThat(edited.transaction().merchantDescription()).isEqualTo("Rent");
    assertThat(list(token, savings.id()))
        .extracting(TransactionResponse::id)
        .containsExactly(credit.id());
  }

  // The same explicit FX rate is no financial change, and neither is an estimated rate sent back
  // as read (a client echoing the row); a different explicit rate where the server had estimated
  // one is.
  @Test
  void anExplicitFxRateIsComparedAsDesiredState() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    seedFxRate("EUR", "CHF", "0.9000000000");
    TransactionResponse explicit =
        record(token, card.id(), eurPurchase("-100.00", new BigDecimal("0.95")));
    TransactionResponse estimated = record(token, card.id(), eurPurchase("-50.00", null));
    assertThat(estimated.fxRateEstimated()).isTrue();

    TransactionCorrectionResponse kept =
        correct(
            token,
            card.id(),
            explicit.id(),
            withFxAndFee(
                desiredState(explicit, "-100.00", null, "Renamed", null),
                new BigDecimal("0.95"),
                null));
    TransactionCorrectionResponse echoed =
        correct(
            token,
            card.id(),
            estimated.id(),
            withFxAndFee(
                desiredState(estimated, "-50.00", null, "Renamed", null),
                estimated.fxRateToAccountCurrency(),
                null));
    TransactionCorrectionResponse pinned =
        correct(
            token,
            card.id(),
            estimated.id(),
            withFxAndFee(
                desiredState(echoed.transaction(), "-50.00", null, "Renamed", null),
                new BigDecimal("0.92"),
                null));

    assertThat(kept.removal()).isNull();
    assertThat(kept.transaction().id()).isEqualTo(explicit.id());
    assertThat(echoed.removal()).isNull();
    assertThat(echoed.transaction().id()).isEqualTo(estimated.id());
    assertThat(echoed.transaction().fxRateEstimated()).isTrue();
    assertThat(pinned.removal()).isNotNull();
    assertThat(pinned.transaction().fxRateEstimated()).isFalse();
    assertThat(pinned.transaction().fxRateToAccountCurrency()).isEqualByComparingTo("0.92");
  }

  // The echo's worst symptom before #220: a description edit of an imported row that sent back its
  // estimated rate counted as a financial change - a void, which needs a reason, so 422. It is an
  // in-place edit, no reason needed.
  @Test
  void anImportedRowWithAnEstimatedRateTakesADescriptionEditInPlace() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    UUID imported = insertImportedEstimated(card.id(), "-100.00", "0.9000000000");
    TransactionResponse row = list(token, card.id()).get(0);
    assertThat(row.fxRateEstimated()).isTrue();

    TransactionCorrectionResponse edited =
        correct(
            token,
            card.id(),
            imported,
            withFxAndFee(
                desiredState(row, "-100.00", null, "Renamed", null),
                row.fxRateToAccountCurrency(),
                null));

    assertThat(edited.removal()).isNull();
    assertThat(edited.transaction().id()).isEqualTo(imported);
    assertThat(edited.transaction().voidedAt()).isNull();
    assertThat(edited.transaction().merchantDescription()).isEqualTo("Renamed");
    assertThat(edited.transaction().fxRateEstimated()).isTrue();
  }

  // An estimated rate echoed back with a corrected amount is not a disclosed rate either: the
  // replacement estimates its own instead of pinning the old estimate as if the member stated it.
  @Test
  void anEchoedEstimateIsEstimatedAgainForTheReplacement() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    seedFxRate("EUR", "CHF", "0.9000000000");
    TransactionResponse estimated = record(token, card.id(), eurPurchase("-50.00", null));

    TransactionCorrectionResponse corrected =
        correct(
            token,
            card.id(),
            estimated.id(),
            withFxAndFee(
                desiredState(estimated, "-60.00", null, "Shop", null),
                estimated.fxRateToAccountCurrency(),
                null));

    assertThat(corrected.removal()).isNotNull();
    assertThat(corrected.transaction().amount()).isEqualByComparingTo("-60.00");
    assertThat(corrected.transaction().fxRateEstimated()).isTrue();
  }

  // A member's override follows the correction only while its category can still be assigned;
  // a deactivated one does not block the correction - the replacement is categorized like any new
  // row instead.
  @Test
  void anOverrideToADeactivatedCategoryDoesNotCarryOver() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    CategoryResponse hobby =
        client(token)
            .post()
            .uri("/api/v1/categories")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new CreateCategoryRequest(null, "Hobby", "Hobby"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(CategoryResponse.class)
            .returnResult()
            .getResponseBody();
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));
    client(token)
        .put()
        .uri(rowUri(card.id(), original.id()) + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", original.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(hobby.id()))
        .exchange()
        .expectStatus()
        .isOk();
    client(token)
        .post()
        .uri("/api/v1/categories/" + hobby.id() + "/deactivate")
        .header("If-Match", "\"" + hobby.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk();
    TransactionResponse overridden = list(token, card.id()).get(0);

    TransactionCorrectionResponse corrected =
        correct(token, card.id(), overridden, "-80.00", null, "Shop", null, null);

    // No rule matches "Shop", so a new row with it is UNCATEGORIZED.
    assertThat(corrected.removal()).isNotNull();
    assertThat(corrected.transaction().categoryId()).isEqualTo(defaultCategory("UNCATEGORIZED"));
    assertThat(corrected.transaction().categoryAssignedBy()).isNotEqualTo("USER");
  }

  // An override follows the correction only to a type that is categorized at all: corrected into
  // a transfer, the row has no category, as a recorded transfer would not.
  @Test
  void anOverrideDoesNotCarryOverToAnUncategorizedType() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    TransactionResponse original =
        record(
            token,
            current.id(),
            TransactionRequests.cash(
                "EXPENSE",
                today(),
                new BigDecimal("-100.00"),
                "CHF",
                "Rent",
                null,
                null,
                null,
                null,
                null,
                null));
    client(token)
        .put()
        .uri(rowUri(current.id(), original.id()) + "/category")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", original.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetTransactionCategoryRequest(defaultCategory("SHOPPING")))
        .exchange()
        .expectStatus()
        .isOk();
    TransactionResponse overridden = list(token, current.id()).get(0);
    assertThat(overridden.categoryAssignedBy()).isEqualTo("USER");

    TransactionCorrectionResponse corrected =
        correct(
            token,
            current.id(),
            overridden.id(),
            new CorrectTransactionRequest(
                null,
                "TRANSFER",
                overridden.bookingDate(),
                new BigDecimal("-100.00"),
                "CHF",
                "Rent",
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
                null,
                null,
                null,
                null,
                null));

    assertThat(corrected.removal()).isNotNull();
    assertThat(corrected.transaction().transactionType()).isEqualTo("TRANSFER");
    assertThat(corrected.transaction().categoryId()).isNull();
    assertThat(corrected.transaction().categoryAssignedBy()).isNull();
  }

  // Sending the current state again changes nothing - not even the version; a notes-only edit
  // stays on the row.
  @Test
  void anUnchangedCorrectionIsANoOpAndANotesEditStaysOnTheRow() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    TransactionResponse original = record(token, card.id(), purchase("-85.00"));

    TransactionCorrectionResponse unchanged =
        correct(token, card.id(), original, "-85.00", null, "Shop", null, null);
    TransactionCorrectionResponse noted =
        correct(token, card.id(), original, "-85.00", null, "Shop", "receipt filed", null);

    assertThat(unchanged.removal()).isNull();
    assertThat(unchanged.version()).isEqualTo(original.version());
    assertThat(noted.removal()).isNull();
    assertThat(noted.transaction().id()).isEqualTo(original.id());
    assertThat(noted.transaction().notes()).isEqualTo("receipt filed");
    assertThat(noted.version()).isGreaterThan(original.version());
  }

  // --- helpers ---------------------------------------------------------------------------------

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

  private static String rowUri(UUID accountId, UUID transactionId) {
    return "/api/v1/accounts/" + accountId + "/transactions/" + transactionId;
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

  private TransactionResponse record(
      String token, UUID accountId, CreateTransactionRequest request) {
    return post(token, accountId, request)
        .expectStatus()
        .isCreated()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionCorrectionResponse correct(
      String token,
      UUID accountId,
      TransactionResponse original,
      String amount,
      String reason,
      String merchant,
      String notes,
      UUID targetAccountId) {
    EntityExchangeResult<TransactionCorrectionResponse> result =
        client(token)
            .put()
            .uri(rowUri(accountId, original.id()))
            .headers(CurrentVersion.ifMatch(dataSource, "transaction", original.id()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(correction(original, amount, reason, merchant, notes, targetAccountId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionCorrectionResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(
        result, dataSource, "transaction", result.getResponseBody().transaction().id());
  }

  private TransactionCorrectionResponse correct(
      String token, UUID accountId, UUID transactionId, CorrectTransactionRequest request) {
    EntityExchangeResult<TransactionCorrectionResponse> result =
        correctRaw(
                token,
                accountId,
                transactionId,
                request,
                CurrentVersion.ifMatch(dataSource, "transaction", transactionId))
            .expectStatus()
            .isOk()
            .expectBody(TransactionCorrectionResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(
        result, dataSource, "transaction", result.getResponseBody().transaction().id());
  }

  private RestTestClient.ResponseSpec correctRaw(
      String token,
      UUID accountId,
      UUID transactionId,
      CorrectTransactionRequest request,
      Consumer<HttpHeaders> ifMatch) {
    return client(token)
        .put()
        .uri(rowUri(accountId, transactionId))
        .headers(ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  // The row's current state with amount, MCC and description replaced and no counterparty amount:
  // what a client that read the row and edited those fields would send.
  private static CorrectTransactionRequest desiredState(
      TransactionResponse row, String amount, String mcc, String merchant, String reason) {
    return desiredState(row, amount, mcc, merchant, reason, row.counterpartyAccountId());
  }

  private static CorrectTransactionRequest desiredState(
      TransactionResponse row,
      String amount,
      String mcc,
      String merchant,
      String reason,
      UUID counterpartyAccountId) {
    return new CorrectTransactionRequest(
        null,
        row.transactionType(),
        row.bookingDate(),
        new BigDecimal(amount),
        row.currency(),
        merchant,
        mcc,
        row.notes(),
        row.fxRateEstimated() ? null : row.fxRateToAccountCurrency(),
        null,
        row.feeAmount(),
        row.securityId(),
        row.quantity(),
        row.unitPrice(),
        row.tradeDate(),
        row.settlementDate(),
        row.grossAmount(),
        row.taxWithheldAmount(),
        counterpartyAccountId,
        null,
        reason);
  }

  // A card purchase's response carries neither its fee (a row of its own) nor, once estimated, an
  // explicit rate; a client sets both explicitly in the desired state.
  private static CorrectTransactionRequest withFxAndFee(
      CorrectTransactionRequest request, BigDecimal fxRate, BigDecimal feeAmount) {
    return new CorrectTransactionRequest(
        request.targetAccountId(),
        request.transactionType(),
        request.bookingDate(),
        request.amount(),
        request.currency(),
        request.merchantDescription(),
        request.mcc(),
        request.notes(),
        fxRate,
        request.billedAmount(),
        feeAmount,
        request.securityId(),
        request.quantity(),
        request.unitPrice(),
        request.tradeDate(),
        request.settlementDate(),
        request.grossAmount(),
        request.taxWithheldAmount(),
        request.counterpartyAccountId(),
        request.counterpartyAmount(),
        request.reason());
  }

  private static CreateTransactionRequest eurPurchase(String amount, BigDecimal fxRate) {
    return TransactionRequests.cash(
        PURCHASE,
        today(),
        new BigDecimal(amount),
        "EUR",
        "Shop",
        null,
        null,
        null,
        fxRate,
        null,
        null);
  }

  private static CreateTransactionRequest transfer(String amount, UUID counterparty) {
    return new CreateTransactionRequest(
        "TRANSFER",
        today(),
        new BigDecimal(amount),
        "CHF",
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
        null,
        null,
        null,
        null,
        counterparty,
        null);
  }

  private void createMerchantRule(String token, String matchValue, UUID categoryId) {
    client(token)
        .post()
        .uri("/api/v1/categorization-rules")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateCategorizationRuleRequest("MERCHANT", matchValue, categoryId, null))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private void seedFxRate(String base, String quote, String rate) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO fx_rate (base_currency, quote_currency, rate_date, rate, source)"
                    + " VALUES (?, ?, ?, ?, 'ECB')")) {
      statement.setString(1, base);
      statement.setString(2, quote);
      statement.setObject(3, today());
      statement.setBigDecimal(4, new BigDecimal(rate));
      statement.executeUpdate();
    }
  }

  // The fee row a card purchase's disclosed FX fee was recorded as (US-09-04).
  private UUID feeOf(UUID purchaseId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM transaction WHERE related_transaction_id = ?"
                    + " AND deleted_at IS NULL AND voided_at IS NULL")) {
      statement.setObject(1, purchaseId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("a fee row for " + purchaseId).isTrue();
        return (UUID) resultSet.getObject(1);
      }
    }
  }

  private static CorrectTransactionRequest correction(
      TransactionResponse original,
      String amount,
      String reason,
      String merchant,
      String notes,
      UUID targetAccountId) {
    return new CorrectTransactionRequest(
        targetAccountId,
        original.transactionType(),
        original.bookingDate(),
        new BigDecimal(amount),
        original.currency(),
        merchant,
        null,
        notes,
        original.fxRateToAccountCurrency(),
        null,
        original.feeAmount(),
        original.securityId(),
        original.quantity(),
        original.unitPrice(),
        original.tradeDate(),
        original.settlementDate(),
        original.grossAmount(),
        original.taxWithheldAmount(),
        original.counterpartyAccountId(),
        null,
        reason);
  }

  private TransactionRemovalResponse remove(
      String token, UUID accountId, UUID transactionId, String reason) {
    EntityExchangeResult<TransactionRemovalResponse> result =
        client(token)
            .delete()
            .uri(rowUri(accountId, transactionId) + (reason == null ? "" : "?reason=" + reason))
            .headers(CurrentVersion.ifMatch(dataSource, "transaction", transactionId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionRemovalResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(result, dataSource, "transaction", transactionId);
  }

  private TransactionRemovalResponse restore(String token, UUID accountId, UUID transactionId) {
    EntityExchangeResult<TransactionRemovalResponse> result =
        client(token)
            .post()
            .uri(rowUri(accountId, transactionId) + "/restore")
            .headers(CurrentVersion.ifMatch(dataSource, "transaction", transactionId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionRemovalResponse.class)
            .returnResult();
    return CurrentVersion.storedEtag(result, dataSource, "transaction", transactionId);
  }

  record PageOf<T>(List<T> content, long totalElements) {}

  private List<TransactionResponse> list(String token, UUID accountId) {
    return page(token, "/api/v1/accounts/" + accountId + "/transactions");
  }

  private List<TransactionResponse> uncategorized(String token, UUID accountId) {
    return page(token, "/api/v1/accounts/" + accountId + "/transactions?uncategorized=true");
  }

  private List<TransactionResponse> page(String token, String uri) {
    return client(token)
        .get()
        .uri(uri)
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

  // The signed ledger sum on a past date, which statements, snapshots and matching read.
  private BigDecimal pastBalance(UUID accountId, LocalDate asOf) {
    return transactionRepository.sumAmountByAccountIdAsOf(accountId, asOf).orElseThrow();
  }

  private String valueBasis(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
        .returnResult()
        .getResponseBody()
        .valueBasis();
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

  // What a cash account's rows add up to. Its /balance cannot show this: a cash account is valued
  // from manual valuations only (no opening balance yet, AccountValuationService), so its value is
  // unknown here - only a card is valued from its ledger.
  private BigDecimal activeLedgerTotal(UUID accountId) {
    return queryDecimal(
        "SELECT COALESCE(sum(amount), 0) FROM transaction"
            + " WHERE account_id = ? AND deleted_at IS NULL AND voided_at IS NULL",
        accountId);
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

  private static CreateTransactionRequest cashRow(String type, String amount) {
    return TransactionRequests.cash(
        type, today(), new BigDecimal(amount), "CHF", null, null, null, null, null, null, null);
  }

  private void setSettlementSource(String token, UUID cardAccountId, UUID sourceAccountId) {
    client(token)
        .put()
        .uri("/api/v1/accounts/" + cardAccountId + "/settlement-source")
        .headers(CurrentVersion.ifMatchForCard(dataSource, cardAccountId))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetSettlementSourceRequest(sourceAccountId))
        .exchange()
        .expectStatus()
        .is2xxSuccessful();
  }

  private UUID matchIdOf(UUID cardAccountId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM settlement_match WHERE card_account_id = ?")) {
      statement.setObject(1, cardAccountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("a match for the card").isTrue();
        return (UUID) resultSet.getObject(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private BigDecimal rejectedMatches(UUID cardAccountId) {
    return queryDecimal(
        "SELECT count(*) FROM settlement_match WHERE card_account_id = ? AND status = 'REJECTED'",
        cardAccountId);
  }

  private List<UUID> settlementMatches(String token, String status) {
    return client(token)
        .get()
        .uri("/api/v1/settlement-matches?status=" + status)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<SettlementMatchResponse>>() {})
        .returnResult()
        .getResponseBody()
        .stream()
        .map(SettlementMatchResponse::id)
        .toList();
  }

  private List<CashFlowResponse.CurrencyAmount> pendingReview(String token) {
    return client(token)
        .get()
        .uri("/api/v1/cash-flow?month=" + YearMonth.from(today()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody()
        .pendingReview();
  }

  private AuthenticatedUserPrincipal adminPrincipal() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT u.id, wm.workspace_id FROM app_user u JOIN workspace_member wm ON wm.id"
                    + " = u.workspace_member_id WHERE u.email = 'admin@example.com'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return new AuthenticatedUserPrincipal(
            (UUID) resultSet.getObject(1),
            "SYSTEM_ADMINISTRATOR",
            (UUID) resultSet.getObject(2),
            UUID.randomUUID(),
            "EN");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // An imported row as a CSV import (EPIC 07) would leave it: source CSV, so its removal is a void.
  private UUID insertImported(UUID accountId, String type, String amount, LocalDate bookedOn) {
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, source) SELECT ?, workspace_id, id, ?, ?,"
                    + " ?, 'CHF', 'CSV' FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setString(2, type);
      statement.setObject(3, bookedOn);
      statement.setBigDecimal(4, new BigDecimal(amount));
      statement.setObject(5, accountId);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  // An imported EUR row on a CHF account whose rate the server estimated (fx_rate_estimated).
  private UUID insertImportedEstimated(UUID accountId, String amount, String rate) {
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, fx_rate_to_account_currency, fx_rate_date,"
                    + " fx_rate_estimated, source) SELECT ?, workspace_id, id, ?, ?, ?, 'EUR', ?,"
                    + " ?, TRUE, 'CSV' FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setString(2, PURCHASE);
      statement.setObject(3, today());
      statement.setBigDecimal(4, new BigDecimal(amount));
      statement.setBigDecimal(5, new BigDecimal(rate));
      statement.setObject(6, today());
      statement.setObject(7, accountId);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  private BigDecimal matches(UUID cardAccountId) {
    return queryDecimal(
        "SELECT count(*) FROM settlement_match WHERE card_account_id = ?", cardAccountId);
  }

  // A two-sided transfer as an import would leave it: both legs CSV, linked and flagged (US-10-01).
  private UUID[] insertImportedTransfer(UUID fromAccount, UUID toAccount, String amount) {
    UUID debit = UUID.randomUUID();
    UUID credit = UUID.randomUUID();
    String insert =
        "INSERT INTO transaction (id, workspace_id, account_id, transaction_type, booking_date,"
            + " amount, currency, source, is_internal_transfer, counterparty_account_id,"
            + " related_transaction_id) SELECT ?, workspace_id, id, 'TRANSFER', CURRENT_DATE, ?,"
            + " 'CHF', 'CSV', TRUE, ?, ? FROM account WHERE id = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(insert)) {
      statement.setObject(1, debit);
      statement.setBigDecimal(2, new BigDecimal(amount).negate());
      statement.setObject(3, toAccount);
      statement.setNull(4, Types.OTHER);
      statement.setObject(5, fromAccount);
      statement.executeUpdate();
      statement.setObject(1, credit);
      statement.setBigDecimal(2, new BigDecimal(amount));
      statement.setObject(3, fromAccount);
      statement.setObject(4, debit);
      statement.setObject(5, toAccount);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return new UUID[] {debit, credit};
  }

  private UUID insertImportedFee(UUID accountId, UUID purchaseId, String amount) {
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (id, workspace_id, account_id, transaction_type,"
                    + " booking_date, amount, currency, source, related_transaction_id)"
                    + " SELECT ?, workspace_id, id, 'FEE', CURRENT_DATE, ?, 'CHF', 'CSV', ?"
                    + " FROM account WHERE id = ?")) {
      statement.setObject(1, id);
      statement.setBigDecimal(2, new BigDecimal(amount));
      statement.setObject(3, purchaseId);
      statement.setObject(4, accountId);
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

  private UUID createSecurity(String token) {
    return client(token)
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                "IE00B4L5Y983",
                "iShares Core MSCI World",
                "USD",
                "ETF",
                "EQUITY",
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
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
    } catch (Exception e) {
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
    } catch (Exception e) {
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
