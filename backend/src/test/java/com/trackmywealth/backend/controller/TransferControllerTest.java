package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.service.TransferDetectionService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-10-01: a transfer between two of the workspace's own accounts is never income or spending. The
 * DoD's tests are {@link #aTransferToSavingsIsSavingNeverSpending} and {@link
 * #aTransferIntoADepotAndThePurchaseThereAreNeitherSpending}; the rest pin matching of separately
 * recorded legs, one-sided legs, pension contributions and the cash-flow figures.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String TRANSFER = "TRANSFER";

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

  @Autowired TransferDetectionService transferDetectionService;

  @BeforeEach
  void cleanDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM settlement_match",
              "DELETE FROM transaction_categorization_log",
              "DELETE FROM transaction WHERE replaces_transaction_id IS NOT NULL",
              "DELETE FROM transaction WHERE related_transaction_id IS NOT NULL",
              "DELETE FROM transaction",
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_securities",
              "DELETE FROM account_credit_card",
              "DELETE FROM account_pension",
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

  // --- DoD --------------------------------------------------------------------------------------

  @Test
  void aTransferToSavingsIsSavingNeverSpending() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");

    TransactionResponse debit =
        record(token, current.id(), transfer("-500.00", savings.id(), null));

    assertThat(debit.internalTransfer()).isTrue();
    assertThat(debit.counterpartyAccountId()).isEqualTo(savings.id());
    TransactionResponse credit = list(token, savings.id()).get(0);
    assertThat(credit.amount()).isEqualByComparingTo("500.00");
    assertThat(credit.internalTransfer()).isTrue();
    assertThat(credit.counterpartyAccountId()).isEqualTo(current.id());
    assertThat(credit.relatedTransactionId()).isEqualTo(debit.id());

    CashFlowResponse flow = cashFlow(token);
    assertThat(flow.income()).isEmpty();
    assertThat(flow.spending()).isEmpty();
    assertThat(chf(flow.saving())).isEqualByComparingTo("500.00");
    assertThat(flow.pendingReview()).isEmpty();
    assertThat(flow.complete()).isTrue();
  }

  @Test
  void aTransferIntoADepotAndThePurchaseThereAreNeitherSpending() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse depot = createAccount(token, "SECURITIES", "CHF");
    record(token, current.id(), transfer("-1005.00", depot.id(), null));
    UUID security = insertSecurity();
    execute(
        "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
            + " amount, currency, security_id, quantity, unit_price, fee_amount, source)"
            + " SELECT workspace_id, id, 'BUY', CURRENT_DATE, -1005, 'CHF', ?, 10, 100, 5, 'CSV'"
            + " FROM account WHERE id = ?",
        security,
        depot.id());

    CashFlowResponse flow = cashFlow(token);
    assertThat(flow.spending()).isEmpty();
    assertThat(chf(flow.saving())).isEqualByComparingTo("1005.00");
    assertThat(flow.complete()).isTrue();
  }

  // --- matching separately recorded legs ---------------------------------------------------------

  @Test
  void twoImportedTransferLegsArePairedAutomaticallyAndLeaveIncomeAndSpending() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    // Two days apart, both inside this month whatever today's date is.
    LocalDate sent = today().withDayOfMonth(10);
    UUID out = insertImported(current.id(), TRANSFER, "-500.00", sent);
    UUID in = insertImported(savings.id(), TRANSFER, "500.00", sent.plusDays(2));

    transferDetectionService.detectAround(workspaceOf(current.id()), sent);

    assertThat(counterpartyOf(out)).isEqualTo(savings.id());
    assertThat(counterpartyOf(in)).isEqualTo(current.id());
    assertThat(matches(token, "CONFIRMED"))
        .singleElement()
        .satisfies(match -> assertThat(match.matchKind()).isEqualTo("TRANSFER"));
    CashFlowResponse flow = cashFlow(token);
    assertThat(flow.pendingReview()).isEmpty();
    assertThat(chf(flow.saving())).isEqualByComparingTo("500.00");
  }

  @Test
  void aWithdrawalAndADepositOfTheSameAmountAreOnlyProposed() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    TransactionResponse withdrawal = record(token, current.id(), cash("WITHDRAWAL", "-150.00"));
    record(token, savings.id(), cash("DEPOSIT", "150.00"));

    SettlementMatchResponse proposal = matches(token, "PROPOSED").get(0);
    assertThat(proposal.matchKind()).isEqualTo("TRANSFER");
    assertThat(proposal.paymentTransactionId()).isEqualTo(withdrawal.id());
    // The kind-neutral names a client reads for a transfer.
    assertThat(proposal.debitTransactionId()).isEqualTo(withdrawal.id());
    assertThat(proposal.debitAccountId()).isEqualTo(current.id());
    assertThat(proposal.creditAccountId()).isEqualTo(savings.id());
    assertThat(proposal.creditTransactionId()).isEqualTo(proposal.cardTransactionId()).isNotNull();
    // Neither spending nor silently dropped while a member decides; counted once, not per leg.
    CashFlowResponse pending = cashFlow(token);
    assertThat(pending.spending()).isEmpty();
    assertThat(chf(pending.pendingReview())).isEqualByComparingTo("150.00");
    assertThat(pending.complete()).isFalse();

    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + proposal.id() + "/confirm")
        .exchange()
        .expectStatus()
        .isOk();

    CashFlowResponse confirmed = cashFlow(token);
    assertThat(confirmed.pendingReview()).isEmpty();
    assertThat(chf(confirmed.saving())).isEqualByComparingTo("150.00");
  }

  @Test
  void twoEqualLegsForOneCounterpartAreProposedNeverGuessed() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    record(token, current.id(), transfer("-100.00", null, null));
    record(token, current.id(), transfer("-100.00", null, null));
    record(token, savings.id(), transfer("100.00", null, null));

    assertThat(matches(token, "CONFIRMED")).isEmpty();
    assertThat(matches(token, "PROPOSED")).hasSize(2);
  }

  @Test
  void aProposedPairAcrossTwoMonthsIsPendingInBothNeverDropped() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse other = createAccount(token, "CASH", "CHF");
    YearMonth month = YearMonth.from(today()).minusMonths(1);
    LocalDate paid = month.minusMonths(1).atEndOfMonth();
    // An expense and an income of the same amount two days apart: a possible transfer, proposed.
    insertImported(current.id(), "EXPENSE", "-3000.00", paid);
    insertImported(other.id(), "INCOME", "3000.00", paid.plusDays(2));
    transferDetectionService.detectAround(workspaceOf(current.id()), paid);
    assertThat(matches(token, "PROPOSED")).hasSize(1);

    CashFlowResponse before = cashFlow(token, month.minusMonths(1));
    assertThat(before.spending()).isEmpty();
    assertThat(chf(before.pendingReview())).isEqualByComparingTo("3000.00");
    // The income leg is held back from income, so it must be pending in its own month.
    CashFlowResponse after = cashFlow(token, month);
    assertThat(after.income()).isEmpty();
    assertThat(chf(after.pendingReview())).isEqualByComparingTo("3000.00");
    assertThat(after.complete()).isFalse();
  }

  @Test
  void aRejectedPairIsNeverProposedAgain() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    record(token, current.id(), cash("WITHDRAWAL", "-150.00"));
    record(token, savings.id(), cash("DEPOSIT", "150.00"));
    SettlementMatchResponse proposal = matches(token, "PROPOSED").get(0);

    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + proposal.id() + "/reject")
        .exchange()
        .expectStatus()
        .isOk();
    record(token, current.id(), cash("EXPENSE", "-7.00")); // another write re-runs matching

    assertThat(matches(token, "PROPOSED")).isEmpty();
    // Rejected: the withdrawal is spending after all.
    assertThat(chf(cashFlow(token).spending())).isEqualByComparingTo("157.00");
  }

  @Test
  void theMatchListCanBeNarrowedToOneKind() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    record(token, current.id(), cash("WITHDRAWAL", "-150.00"));
    record(token, savings.id(), cash("DEPOSIT", "150.00"));

    assertThat(matches(token, "PROPOSED", "TRANSFER")).hasSize(1);
    assertThat(matches(token, "PROPOSED", "CARD_SETTLEMENT")).isEmpty();
    client(token)
        .get()
        .uri("/api/v1/settlement-matches?kind=SOMETHING")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void aCardSettlementConfirmedAutomaticallyRejectsATransferProposalForTheSamePayment() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    client(token)
        .put()
        .uri("/api/v1/accounts/" + card.id() + "/settlement-source")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetSettlementSourceRequest(current.id()))
        .exchange()
        .expectStatus()
        .isOk();
    TransactionResponse payment = record(token, current.id(), cash("WITHDRAWAL", "-200.00"));
    record(token, savings.id(), cash("DEPOSIT", "200.00"));
    SettlementMatchResponse transfer = matches(token, "PROPOSED").get(0);
    assertThat(transfer.matchKind()).isEqualTo("TRANSFER");

    // The card's own credit makes the payment an unambiguous card settlement.
    record(token, card.id(), cash("SETTLEMENT", "200.00"));

    assertThat(matches(token, "CONFIRMED"))
        .singleElement()
        .satisfies(
            match -> {
              assertThat(match.matchKind()).isEqualTo("CARD_SETTLEMENT");
              assertThat(match.debitTransactionId()).isEqualTo(payment.id());
            });
    // Not left behind as a proposal that could never be confirmed.
    assertThat(matches(token, "PROPOSED")).isEmpty();
    assertThat(matches(token, "REJECTED"))
        .singleElement()
        .satisfies(match -> assertThat(match.id()).isEqualTo(transfer.id()));
  }

  @Test
  void aPairIsOnlyDecidedWithEveryCompetitorInView() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    LocalDate run = today().minusDays(60);
    // The credit pairs with both debits; the second one lies beyond two windows from the run.
    insertImported(current.id(), TRANSFER, "-400.00", run.plusDays(6));
    insertImported(savings.id(), TRANSFER, "400.00", run.plusDays(10));
    insertImported(current.id(), TRANSFER, "-400.00", run.plusDays(14));
    UUID workspace = workspaceOf(current.id());

    // Neither leg of the first pair is within a window of this run: it is not decided here.
    transferDetectionService.detectAround(workspace, run);
    assertThat(matchCount(workspace)).isZero();

    // Around the credit, both pairs are in view: ambiguous, so only proposed.
    transferDetectionService.detectAround(workspace, run.plusDays(10));
    assertThat(matches(token, "CONFIRMED")).isEmpty();
    assertThat(matches(token, "PROPOSED")).hasSize(2);
  }

  // --- one-sided legs --------------------------------------------------------------------------

  @Test
  void aOneSidedLegIsPendingUntilConfirmedAsATransferToAnUntrackedAccount() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    TransactionResponse leg = record(token, current.id(), transfer("-300.00", null, null));

    CashFlowResponse pending = cashFlow(token);
    assertThat(pending.spending()).isEmpty();
    assertThat(chf(pending.pendingReview())).isEqualByComparingTo("300.00");
    assertThat(pending.complete()).isFalse();

    TransactionResponse confirmed =
        client(token)
            .post()
            .uri(rowUri(current.id(), leg.id()) + "/untracked-transfer")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(confirmed.internalTransfer()).isTrue();
    assertThat(confirmed.counterpartyAccountId()).isNull();
    assertThat(cashFlow(token).complete()).isTrue();

    client(token)
        .delete()
        .uri(rowUri(current.id(), leg.id()) + "/untracked-transfer")
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(cashFlow(token).complete()).isFalse();
  }

  @Test
  void aLegConfirmedAsUntrackedStillPairsWithACounterpartRecordedLater() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    TransactionResponse leg = record(token, current.id(), transfer("-250.00", null, null));
    client(token)
        .post()
        .uri(rowUri(current.id(), leg.id()) + "/untracked-transfer")
        .exchange()
        .expectStatus()
        .isOk();

    record(token, savings.id(), transfer("250.00", null, null));

    assertThat(counterpartyOf(leg.id())).isEqualTo(savings.id());
    assertThat(chf(cashFlow(token).saving())).isEqualByComparingTo("250.00");

    // Undoing that match does not bring back "untracked": both legs are pending review again.
    SettlementMatchResponse match = matches(token, "CONFIRMED").get(0);
    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + match.id() + "/reject")
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(query("SELECT is_internal_transfer FROM transaction WHERE id = ?", leg.id()))
        .isEqualTo(false);
    assertThat(chf(cashFlow(token).pendingReview())).isEqualByComparingTo("500.00");
  }

  @Test
  void onlyAnUnlinkedTransferLegCanBeConfirmedAsUntracked() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    TransactionResponse expense = record(token, current.id(), cash("EXPENSE", "-20.00"));
    TransactionResponse linked =
        record(token, current.id(), transfer("-100.00", savings.id(), null));

    client(token)
        .post()
        .uri(rowUri(current.id(), expense.id()) + "/untracked-transfer")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    client(token)
        .post()
        .uri(rowUri(current.id(), linked.id()) + "/untracked-transfer")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // --- recording rules -------------------------------------------------------------------------

  @Test
  void aTransferBetweenCurrenciesNamesWhatTheOtherAccountReceives() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse euro = createAccount(token, "SAVINGS", "EUR");

    post(token, current.id(), transfer("-100.00", euro.id(), null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    record(token, current.id(), transfer("-100.00", euro.id(), "104.20"));

    TransactionResponse credit = list(token, euro.id()).get(0);
    assertThat(credit.currency()).isEqualTo("EUR");
    assertThat(credit.amount()).isEqualByComparingTo("104.20");
  }

  @Test
  void aTransferIsRejectedWhereItDoesNotFit() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse pension = createPension(token);

    for (CreateTransactionRequest wrong :
        List.of(
            transfer("-100.00", current.id(), null), // to itself
            transfer("-100.00", card.id(), null), // a card is paid by a SETTLEMENT
            transfer("-100.00", pension.id(), null), // into a pension: PENSION_CONTRIBUTION
            transfer("100.00", savings.id(), null), // money leaves the account it is recorded on
            transfer("-100.00", savings.id(), "100.00"), // same currency: no counterpartyAmount
            typed("PENSION_CONTRIBUTION", "-100.00", savings.id()), // not a pension
            typed("PENSION_CONTRIBUTION", "-100.00", null), // needs its pension
            TransactionRequests.cash(
                TRANSFER,
                today(),
                new BigDecimal("-100.00"),
                "EUR",
                null,
                null,
                null,
                null,
                null,
                null,
                null))) { // a leg is in its account's own currency
      post(token, current.id(), wrong).expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
    assertThat(list(token, current.id())).isEmpty();
  }

  @Test
  void aPensionContributionIsSaving() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse pension = createPension(token);

    record(token, current.id(), typed("PENSION_CONTRIBUTION", "-7258.00", pension.id()));

    assertThat(list(token, pension.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.transactionType()).isEqualTo("PENSION_CONTRIBUTION"));
    assertThat(chf(cashFlow(token).saving())).isEqualByComparingTo("7258.00");
  }

  @Test
  void aTransferBetweenTwoCurrentAccountsIsInNoFigure() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse joint = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    record(token, current.id(), transfer("-400.00", joint.id(), null));
    // Money back out of savings is negative saving.
    record(token, savings.id(), transfer("-50.00", current.id(), null));
    record(token, current.id(), cash("INCOME", "3000.00"));

    CashFlowResponse flow = cashFlow(token);
    assertThat(chf(flow.income())).isEqualByComparingTo("3000.00");
    assertThat(flow.spending()).isEmpty();
    assertThat(chf(flow.saving())).isEqualByComparingTo("-50.00");
  }

  @Test
  void theSavingFlagHasDefaultsAndCanBeOverridden() {
    String token = bootstrapAdministrator();
    assertThat(createAccount(token, "CASH", "CHF").countsAsSaving()).isFalse();
    assertThat(createAccount(token, "SAVINGS", "CHF").countsAsSaving()).isTrue();
    assertThat(createAccount(token, "SECURITIES", "CHF").countsAsSaving()).isTrue();
    AccountSummaryResponse spendingPot =
        client(token)
            .post()
            .uri("/api/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                AccountRequests.account("Holiday pot", "SAVINGS", "CHF")
                    .countsAsSaving(false)
                    .build())
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(AccountSummaryResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(spendingPot.countsAsSaving()).isFalse();
  }

  // --- access and removal ------------------------------------------------------------------------

  @Test
  void aTransferNeedsEditOnBothAccounts() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(adminToken, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(adminToken, "SAVINGS", "CHF");
    UUID memberId = createSecondMember(adminToken, "member@example.com");
    String memberToken = login("member@example.com");
    grantAccount(adminToken, memberId, current.id(), AccessLevelValues.EDIT);
    grantAccount(adminToken, memberId, savings.id(), AccessLevelValues.READ);

    post(memberToken, current.id(), transfer("-100.00", savings.id(), null))
        .expectStatus()
        .isNotFound();

    grantAccount(adminToken, memberId, savings.id(), AccessLevelValues.EDIT);
    post(memberToken, current.id(), transfer("-100.00", savings.id(), null))
        .expectStatus()
        .isCreated();
  }

  @Test
  void removingEitherLegRemovesTheWholeTransferAndRestoringBringsBothBack() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    record(token, current.id(), transfer("-500.00", savings.id(), null));
    TransactionResponse credit = list(token, savings.id()).get(0);

    TransactionRemovalResponse removed =
        client(token)
            .delete()
            .uri(rowUri(savings.id(), credit.id()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionRemovalResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(removed.affected()).hasSize(2);
    assertThat(list(token, current.id())).isEmpty();
    assertThat(list(token, savings.id())).isEmpty();

    client(token)
        .post()
        .uri(rowUri(savings.id(), credit.id()) + "/restore")
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(list(token, current.id())).hasSize(1);
    assertThat(list(token, savings.id())).hasSize(1);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private static CreateTransactionRequest transfer(
      String amount, UUID counterparty, String counterpartyAmount) {
    return new CreateTransactionRequest(
        TRANSFER,
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
        counterpartyAmount == null ? null : new BigDecimal(counterpartyAmount));
  }

  private static CreateTransactionRequest typed(String type, String amount, UUID counterparty) {
    return new CreateTransactionRequest(
        type,
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

  private static CreateTransactionRequest cash(String type, String amount) {
    return TransactionRequests.cash(
        type, today(), new BigDecimal(amount), "CHF", null, null, null, null, null, null, null);
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

  private List<SettlementMatchResponse> matches(String token, String status) {
    return matches(token, status, null);
  }

  private List<SettlementMatchResponse> matches(String token, String status, String kind) {
    return client(token)
        .get()
        .uri("/api/v1/settlement-matches?status=" + status + (kind == null ? "" : "&kind=" + kind))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<SettlementMatchResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private CashFlowResponse cashFlow(String token) {
    return cashFlow(token, YearMonth.from(today()));
  }

  private CashFlowResponse cashFlow(String token, YearMonth month) {
    return client(token)
        .get()
        .uri("/api/v1/cash-flow?month=" + month)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CashFlowResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static BigDecimal chf(List<CashFlowResponse.CurrencyAmount> amounts) {
    return amounts.stream()
        .filter(line -> "CHF".equals(line.currency()))
        .map(CashFlowResponse.CurrencyAmount::amount)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no CHF line in " + amounts));
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

  private AccountSummaryResponse createPension(String token) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account("Pillar 3a", "PENSION", "CHF")
                .pensionScheme("CH_PILLAR_3A")
                .build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // An imported row as an import (EPIC 07) would leave it, before matching has run.
  private UUID insertImported(UUID accountId, String type, String amount, LocalDate bookedOn)
      throws Exception {
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
    }
    return id;
  }

  private UUID insertSecurity() throws Exception {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO security (id, synthetic_key, legal_name, display_name, denomination_currency)"
            + " VALUES (?, ?, 'Test ETF', 'Test ETF', 'CHF')",
        id,
        "MANUAL-" + id);
    return id;
  }

  private long matchCount(UUID workspaceId) {
    return (Long)
        query("SELECT count(*) FROM settlement_match WHERE workspace_id = ?", workspaceId);
  }

  private UUID workspaceOf(UUID accountId) {
    return (UUID) query("SELECT workspace_id FROM account WHERE id = ?", accountId);
  }

  private UUID counterpartyOf(UUID transactionId) {
    return (UUID)
        query("SELECT counterparty_account_id FROM transaction WHERE id = ?", transactionId);
  }

  private Object query(String sql, UUID parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getObject(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void execute(String sql, Object... parameters) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
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
