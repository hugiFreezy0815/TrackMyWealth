package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.dto.SettlementSourceResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.TransactionRequests;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * US-09-02: a card settlement is detected and matched, never double-counted. {@link
 * #theSettlementContributesZeroToSpendingOnlyThePurchasesDo} is the story's DoD - golden case V-13,
 * "credit-card purchase in month 1 settled from the current account in month 2: single expense
 * recognition" (run as an ordinary integration test: the golden-dataset framework, US-27-01, does
 * not exist yet). Spending is attributed to booking dates, so month 1's purchases stay in month 1.
 *
 * <p>The ledger is cash-direction signed: purchases and the payment out of the current account are
 * negative, the card-side credit is positive.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementMatchControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final LocalDate AUG_10 = LocalDate.of(2026, 8, 10);
  private static final LocalDate AUG_28 = LocalDate.of(2026, 8, 28);
  private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
  private static final LocalDate SEP_4 = LocalDate.of(2026, 9, 4);

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
    // Lets the tests count the SQL statements a request issues (see statementsFor).
    registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  @Autowired EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // settlement_match and transaction first: they reference accounts, and matches reference
    // transactions.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM settlement_match");
      statement.execute("DELETE FROM transaction_category_split");
      // US-08-01: every categorized row has a log row referencing it.
      statement.execute("DELETE FROM transaction_categorization_log");
      statement.execute("DELETE FROM transaction");
      for (String table :
          List.of(
              "sharing_grant",
              "account_ownership",
              "custom_asset_valuation",
              "account_custom_asset",
              "account_credit_card",
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

  // --- AC #1: both legs flagged and linked when a payment matches the card ------------------

  @Test
  void aPaymentAndItsCardCreditAreMatchedAndBothLegsAreFlaggedAnInternalTransfer() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    TransactionResponse payment = withdrawal(token, a.current(), "-1200.00", SEP_3);
    TransactionResponse credit = cardCredit(token, a.card(), "1200.00", SEP_3);

    // The response of the write that completed the pair already shows the flag.
    assertThat(credit.internalTransfer()).isTrue();
    assertThat(ledger(token, a.current()))
        .filteredOn(t -> t.id().equals(payment.id()))
        .singleElement()
        .satisfies(t -> assertThat(t.internalTransfer()).isTrue());

    List<SettlementMatchResponse> confirmed = matches(token, "CONFIRMED");
    assertThat(confirmed)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.matchBasis()).isEqualTo(SettlementMatchValues.LEG_PAIR);
              assertThat(m.cardAccountId()).isEqualTo(a.card());
              assertThat(m.paymentAccountId()).isEqualTo(a.current());
              assertThat(m.paymentTransactionId()).isEqualTo(payment.id());
              assertThat(m.cardTransactionId()).isEqualTo(credit.id());
              assertThat(m.amount()).isEqualByComparingTo("1200.00");
            });
    assertThat(matches(token, null)).isEmpty(); // nothing left needing a decision
  }

  @Test
  void theOrderThePaymentAndTheCreditAreRecordedInDoesNotMatter() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    TransactionResponse credit = cardCredit(token, a.card(), "1200.00", SEP_3);
    assertThat(credit.internalTransfer()).isFalse(); // one leg alone is not a settlement yet

    TransactionResponse payment = withdrawal(token, a.current(), "-1200.00", SEP_4);

    assertThat(payment.internalTransfer()).isTrue();
    assertThat(matches(token, "CONFIRMED")).hasSize(1);
  }

  @Test
  void aSettlementTypedPaymentMatchesToo() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    settlementPayment(token, a.current(), "-1200.00", SEP_3);
    TransactionResponse credit = cardCredit(token, a.card(), "1200.00", SEP_3);

    assertThat(credit.internalTransfer()).isTrue();
  }

  @Test
  void aCreditFiveDaysAwayPairsButSixDaysAwayDoesNot() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3.plusDays(6));
    assertThat(matches(token, "CONFIRMED")).isEmpty();

    cardCredit(token, a.card(), "1200.00", SEP_3.plusDays(5));
    assertThat(matches(token, "CONFIRMED")).hasSize(1);
  }

  // --- AC #2 / V-13: the settlement is never counted as spending ----------------------------

  @Test
  void theSettlementContributesZeroToSpendingOnlyThePurchasesDo() {
    // V-13: purchases in month 1, settled from the current account in month 2.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-700.00", AUG_10);
    purchase(token, a.card(), "-500.00", AUG_28);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3);

    // Month 1 carries the purchases, by transaction date (FR-CC-009)...
    CashFlowResponse august = cashFlow(token, "2026-08");
    assertThat(august.spending())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.currency()).isEqualTo("CHF");
              assertThat(s.amount()).isEqualByComparingTo("1200.00");
            });
    // ...and month 2 carries nothing: the CHF 1,200 settlement is not a second expense.
    CashFlowResponse september = cashFlow(token, "2026-09");
    assertThat(september.spending()).isEmpty();
    assertThat(september.pendingReview()).isEmpty();
    assertThat(september.complete()).isTrue();
    // The card is paid off.
    assertThat(balance(token, a.card()).value()).isEqualByComparingTo("0");
  }

  @Test
  void aCardBillRecordedAsAnExpenseIsMatchedNotCountedAsSecondSpending() {
    // Review finding: a member paying the card by hand types the payment EXPENSE. It must pair
    // with the card-side credit like a WITHDRAWAL, or it doubles the spending it settles.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    record(token, a.current(), "EXPENSE", "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3);

    CashFlowResponse september = cashFlow(token, "2026-09");
    assertThat(september.spending()).isEmpty();
    assertThat(september.pendingReview()).isEmpty();
    assertThat(balance(token, a.card()).value()).isEqualByComparingTo("0");
  }

  @Test
  void withoutASettlementSourceThePaymentCountsAsSpendingAndDoubleCounts() {
    // The control for the test above: matching is what prevents the double count.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithoutSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3);

    assertThat(cashFlow(token, "2026-09").spending())
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("1200.00"));
  }

  // --- AC #3: a wrong match can be rejected, and reverts ------------------------------------

  @Test
  void aWrongMatchCanBeRejectedAndTheLegsRevertToOrdinaryTransactions() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    // A coincidence: rent equal to a card credit on the same day.
    TransactionResponse rent = withdrawal(token, a.current(), "-1200.00", SEP_3);
    TransactionResponse credit = cardCredit(token, a.card(), "1200.00", SEP_3);
    assertThat(credit.internalTransfer()).isTrue();
    assertThat(cashFlow(token, "2026-09").spending()).isEmpty();

    SettlementMatchResponse match = matches(token, "CONFIRMED").get(0);
    SettlementMatchResponse rejected = decide(token, match.id(), "reject", HttpStatus.OK);

    assertThat(rejected.status()).isEqualTo(SettlementMatchValues.REJECTED);
    assertThat(ledger(token, a.current()))
        .filteredOn(t -> t.id().equals(rent.id()))
        .singleElement()
        .satisfies(t -> assertThat(t.internalTransfer()).isFalse());
    assertThat(ledger(token, a.card()))
        .filteredOn(t -> t.id().equals(credit.id()))
        .singleElement()
        .satisfies(t -> assertThat(t.internalTransfer()).isFalse());
    // Classified normally again: the rent is spending.
    assertThat(cashFlow(token, "2026-09").spending())
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("1200.00"));
  }

  @Test
  void aRejectedMatchIsNeverProposedAgainAndRunningMatchingIsIdempotent() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3);
    decide(token, matches(token, "CONFIRMED").get(0).id(), "reject", HttpStatus.OK);

    List<SettlementMatchResponse> first = run(token, a.card());
    List<SettlementMatchResponse> second = run(token, a.card());

    assertThat(first).singleElement().satisfies(m -> assertThat(m.status()).isEqualTo("REJECTED"));
    assertThat(second).extracting(SettlementMatchResponse::id).isEqualTo(ids(first));
    assertThat(matches(token, null)).isEmpty();
    assertThat(matches(token, "CONFIRMED")).isEmpty();
  }

  @Test
  void aRejectedMatchCannotBeConfirmedOrRejectedAgain() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3);
    UUID id = matches(token, "CONFIRMED").get(0).id();
    decide(token, id, "reject", HttpStatus.OK);

    decide(token, id, "confirm", HttpStatus.CONFLICT);
    decide(token, id, "reject", HttpStatus.CONFLICT);
  }

  // --- One-sided: only the payment exists (FR-CF-005) ---------------------------------------

  @Test
  void aPaymentEqualToTheCardBalanceIsProposedNotAppliedAndSurfacedAsPendingReview() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    TransactionResponse payment = withdrawal(token, a.current(), "-1200.00", SEP_3);

    assertThat(payment.internalTransfer()).isFalse();
    SettlementMatchResponse proposal = matches(token, null).get(0);
    assertThat(proposal.status()).isEqualTo(SettlementMatchValues.PROPOSED);
    assertThat(proposal.matchBasis()).isEqualTo(SettlementMatchValues.BALANCE_EQUALS_PAYMENT);
    assertThat(proposal.cardTransactionId()).isNull();

    // Not silently counted as a regular expense: reported separately, and the view says it is
    // incomplete until a member decides.
    CashFlowResponse september = cashFlow(token, "2026-09");
    assertThat(september.spending()).isEmpty();
    assertThat(september.pendingReview())
        .singleElement()
        .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("1200.00"));
    assertThat(september.complete()).isFalse();
  }

  @Test
  void aOneSidedMatchUsesTheFxConvertedBalanceNotANaiveMixedCurrencySum() {
    // US-09-04 regression: the card carries one CHF purchase and one EUR purchase. A naive
    // sum(amount) (mixing CHF and EUR figures) would equal -1780.00, matching no real payment.
    // The correctly-converted balance is 700.00 (CHF) + 1.10 * 1000.00 (EUR) = 1,800.00 CHF, and
    // the payment below is exactly that.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-700.00", AUG_10);
    foreignPurchase(token, a.card(), "-1000.00", "EUR", "1.1000000000", AUG_10);
    TransactionResponse payment = withdrawal(token, a.current(), "-1800.00", SEP_3);

    assertThat(payment.internalTransfer()).isFalse();
    SettlementMatchResponse proposal = matches(token, null).get(0);
    assertThat(proposal.matchBasis()).isEqualTo(SettlementMatchValues.BALANCE_EQUALS_PAYMENT);
    assertThat(proposal.paymentTransactionId()).isEqualTo(payment.id());
  }

  @Test
  void confirmingAOneSidedProposalFlagsThePaymentAndTheCardCreditLaterCompletesIt() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    SettlementMatchResponse proposal = matches(token, null).get(0);

    SettlementMatchResponse confirmed = decide(token, proposal.id(), "confirm", HttpStatus.OK);
    assertThat(confirmed.status()).isEqualTo(SettlementMatchValues.CONFIRMED);
    CashFlowResponse september = cashFlow(token, "2026-09");
    assertThat(september.spending()).isEmpty();
    assertThat(september.pendingReview()).isEmpty();
    assertThat(september.complete()).isTrue();

    // The card statement arrives later: the same match gains its second leg.
    TransactionResponse credit = cardCredit(token, a.card(), "1200.00", SEP_4);

    assertThat(credit.internalTransfer()).isTrue();
    assertThat(matches(token, "CONFIRMED"))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.id()).isEqualTo(proposal.id());
              assertThat(m.matchBasis()).isEqualTo(SettlementMatchValues.LEG_PAIR);
              assertThat(m.cardTransactionId()).isEqualTo(credit.id());
            });
  }

  @Test
  void aRejectedOneSidedProposalIsNotProposedAgain() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    decide(token, matches(token, null).get(0).id(), "reject", HttpStatus.OK);

    run(token, a.card());

    assertThat(matches(token, null)).isEmpty();
    // ... so the payment now counts as the ordinary spending the member said it is.
    assertThat(cashFlow(token, "2026-09").spending()).hasSize(1);
  }

  @Test
  void aPaymentThatDoesNotEqualTheCardBalanceIsJustSpending() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    withdrawal(token, a.current(), "-800.00", SEP_3); // a partial amount: not a candidate

    assertThat(matches(token, null)).isEmpty();
    assertThat(cashFlow(token, "2026-09").spending())
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("800.00"));
  }

  @Test
  void aSettlementTypedPaymentWithNoMatchIsPendingReviewNotSilentlyDropped() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    settlementPayment(token, a.current(), "-500.00", SEP_3);

    CashFlowResponse september = cashFlow(token, "2026-09");
    assertThat(september.spending()).isEmpty();
    assertThat(september.pendingReview())
        .singleElement()
        .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("500.00"));
    assertThat(september.complete()).isFalse();
  }

  // --- Ambiguity is proposed, never guessed --------------------------------------------------

  @Test
  void twoIdenticalPaymentsForOneCreditAreOnlyProposedAndConfirmingOneRejectsTheOther() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    TransactionResponse first = withdrawal(token, a.current(), "-1200.00", SEP_3);
    TransactionResponse second = withdrawal(token, a.current(), "-1200.00", SEP_4);
    TransactionResponse credit = cardCredit(token, a.card(), "1200.00", SEP_3);

    assertThat(credit.internalTransfer()).isFalse(); // ambiguous: nothing applied on a guess
    List<SettlementMatchResponse> proposals = matches(token, null);
    assertThat(proposals).hasSize(2);
    assertThat(proposals)
        .extracting(SettlementMatchResponse::paymentTransactionId)
        .containsExactlyInAnyOrder(first.id(), second.id());

    SettlementMatchResponse pick =
        proposals.stream()
            .filter(m -> m.paymentTransactionId().equals(first.id()))
            .findFirst()
            .get();
    decide(token, pick.id(), "confirm", HttpStatus.OK);

    assertThat(matches(token, "CONFIRMED"))
        .singleElement()
        .satisfies(m -> assertThat(m.paymentTransactionId()).isEqualTo(first.id()));
    assertThat(matches(token, null)).isEmpty(); // the competitor is no longer proposed
    assertThat(ledger(token, a.current()))
        .filteredOn(t -> t.id().equals(second.id()))
        .singleElement()
        .satisfies(t -> assertThat(t.internalTransfer()).isFalse());
    // The unmatched second payment is ordinary spending.
    assertThat(cashFlow(token, "2026-09").spending())
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("1200.00"));
  }

  // --- Settlement source ---------------------------------------------------------------------

  @Test
  void settingTheSettlementSourceMatchesWhatIsAlreadyInTheLedger() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithoutSource(token);
    withdrawal(token, a.current(), "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3);
    assertThat(matches(token, "CONFIRMED")).isEmpty();

    SettlementSourceResponse set = setSource(token, a.card(), a.current(), HttpStatus.OK);

    assertThat(set.settlementSourceAccountId()).isEqualTo(a.current());
    assertThat(getSource(token, a.card()).settlementSourceAccountId()).isEqualTo(a.current());
    assertThat(matches(token, "CONFIRMED")).hasSize(1);
  }

  @Test
  void clearingTheSettlementSourceStopsFurtherMatching() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    setSource(token, a.card(), null, HttpStatus.OK);

    withdrawal(token, a.current(), "-1200.00", SEP_3);
    cardCredit(token, a.card(), "1200.00", SEP_3);

    assertThat(getSource(token, a.card()).settlementSourceAccountId()).isNull();
    assertThat(matches(token, "CONFIRMED")).isEmpty();
  }

  @Test
  void aBadSettlementSourceIsRejected() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithoutSource(token);
    AccountSummaryResponse otherCard = createAccount(token, "Mastercard", "CREDIT_CARD", "CHF");
    AccountSummaryResponse euroAccount = createAccount(token, "Euro account", "CASH", "EUR");

    setSource(token, a.card(), a.card(), HttpStatus.UNPROCESSABLE_CONTENT); // itself
    setSource(token, a.card(), otherCard.id(), HttpStatus.UNPROCESSABLE_CONTENT); // a card
    setSource(token, a.card(), euroAccount.id(), HttpStatus.UNPROCESSABLE_CONTENT); // currency
    setSource(token, a.card(), UUID.randomUUID(), HttpStatus.NOT_FOUND); // unknown
    setSource(token, a.current(), a.card(), HttpStatus.UNPROCESSABLE_CONTENT); // not a card
    assertThat(getSource(token, a.card()).settlementSourceAccountId()).isNull();
  }

  @Test
  void runningMatchingForACardWithNoSourceIsRejected() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithoutSource(token);

    client(token)
        .post()
        .uri("/api/v1/accounts/" + a.card() + "/settlement-matches/run")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  // --- Ledger widening: the sign each type must carry ---------------------------------------

  @Test
  void eachTransactionTypeMustCarryItsCashDirectionSign() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithoutSource(token);
    AccountSummaryResponse asset = createAccount(token, "Vintage Car", "CUSTOM_ASSET", "CHF");

    // Accepted.
    post(token, a.current(), "WITHDRAWAL", "-10.00").expectStatus().isEqualTo(HttpStatus.CREATED);
    post(token, a.current(), "SETTLEMENT", "-10.00").expectStatus().isEqualTo(HttpStatus.CREATED);
    post(token, a.card(), "SETTLEMENT", "10.00").expectStatus().isEqualTo(HttpStatus.CREATED);
    // Wrong sign.
    post(token, a.current(), "WITHDRAWAL", "10.00")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    post(token, a.current(), "SETTLEMENT", "10.00")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    post(token, a.card(), "SETTLEMENT", "-10.00")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    post(token, a.current(), "WITHDRAWAL", "0.00")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    // Wrong account for the type.
    post(token, a.card(), "WITHDRAWAL", "-10.00")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    post(token, asset.id(), "WITHDRAWAL", "-10.00")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    // Not a type the ledger accepts yet (a loan repayment, US-10-02).
    post(token, a.current(), "DEBT_REPAYMENT", "-10.00")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aPaymentInAnotherCurrencyIsNeverMatchedToACardInChf() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    // A EUR ledger row on the CHF source account is refused outright until US-09-04.
    post(token, a.current(), "WITHDRAWAL", "-1200.00", "EUR")
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  // --- Authorization (US-03-03) --------------------------------------------------------------

  @Test
  void decidingAMatchNeedsEditOnBothAccounts() {
    String adminToken = bootstrapAdministrator();
    Accounts a = accountsWithSource(adminToken);
    purchase(adminToken, a.card(), "-1200.00", AUG_10);
    withdrawal(adminToken, a.current(), "-1200.00", SEP_3);
    UUID proposalId = matches(adminToken, null).get(0).id();
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");

    // No access at all, then EDIT on the card only: the match is invisible and undecidable.
    assertThat(matches(bobToken, null)).isEmpty();
    grant(adminToken, bobMemberId, a.card(), AccessLevelValues.EDIT);
    assertThat(matches(bobToken, null)).isEmpty();
    decide(bobToken, proposalId, "confirm", HttpStatus.NOT_FOUND);
    decide(bobToken, proposalId, "reject", HttpStatus.NOT_FOUND);
    // READ on the paying account is still not enough.
    grant(adminToken, bobMemberId, a.current(), AccessLevelValues.READ);
    decide(bobToken, proposalId, "confirm", HttpStatus.NOT_FOUND);

    // EDIT on both: visible and decidable.
    grant(adminToken, bobMemberId, a.current(), AccessLevelValues.EDIT);
    assertThat(matches(bobToken, null))
        .extracting(SettlementMatchResponse::id)
        .contains(proposalId);
    decide(bobToken, proposalId, "confirm", HttpStatus.OK);
  }

  @Test
  void choosingTheSettlementSourceNeedsEditOnBothAccountsAndNeverRevealsAHiddenSource() {
    String adminToken = bootstrapAdministrator();
    Accounts a = accountsWithSource(adminToken);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");

    // Bob may see the card's balance but not its paying account: the id is withheld.
    grant(adminToken, bobMemberId, a.card(), AccessLevelValues.BALANCE_ONLY);
    assertThat(getSource(bobToken, a.card()).settlementSourceAccountId()).isNull();
    // Bob cannot change it with anything less than EDIT on both.
    setSource(bobToken, a.card(), a.current(), HttpStatus.NOT_FOUND);
    grant(adminToken, bobMemberId, a.card(), AccessLevelValues.EDIT);
    setSource(bobToken, a.card(), a.current(), HttpStatus.NOT_FOUND);
    grant(adminToken, bobMemberId, a.current(), AccessLevelValues.EDIT);
    setSource(bobToken, a.card(), a.current(), HttpStatus.OK);
    assertThat(getSource(bobToken, a.card()).settlementSourceAccountId()).isEqualTo(a.current());
  }

  @Test
  void spendingOnlyCoversAccountsTheCallerMayRead() {
    String adminToken = bootstrapAdministrator();
    Accounts a = accountsWithSource(adminToken);
    purchase(adminToken, a.card(), "-300.00", AUG_10);
    withdrawal(adminToken, a.current(), "-100.00", AUG_10);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");

    assertThat(cashFlow(bobToken, "2026-08").spending()).isEmpty();
    grant(adminToken, bobMemberId, a.card(), AccessLevelValues.BALANCE_ONLY);
    assertThat(cashFlow(bobToken, "2026-08").spending()).isEmpty(); // balance only: no detail
    grant(adminToken, bobMemberId, a.card(), AccessLevelValues.READ);
    assertThat(cashFlow(bobToken, "2026-08").spending())
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("300.00"));
    assertThat(cashFlow(adminToken, "2026-08").spending())
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("400.00"));
  }

  @Test
  void aBadMonthOrStatusIsABadRequest() {
    String token = bootstrapAdministrator();
    for (String uri :
        List.of(
            "/api/v1/cash-flow",
            "/api/v1/cash-flow?month=2026-13",
            "/api/v1/settlement-matches?status=BOGUS")) {
      client(token).get().uri(uri).exchange().expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  // --- Concurrency -----------------------------------------------------------------------------

  @Test
  void concurrentWritesToOneCardAndItsSourceAccountAllSucceed() throws Exception {
    // Every write re-runs matching for the card. Two writes racing on the same card must serialise
    // cleanly - not deadlock (a 500 on a valid purchase) and not lose a row.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    int rounds = 6;
    int writers = 16;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      int expectedPurchases = 0;
      for (int round = 0; round < rounds; round++) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
          boolean onCard = i % 4 != 3; // mostly the card: that is where the writes contend
          String amount = "-" + (10 + i + round * writers) + ".00";
          expectedPurchases += onCard ? 1 : 0;
          statuses.add(
              pool.submit(
                  () -> {
                    start.await();
                    return postOn(
                            token,
                            onCard ? a.card() : a.current(),
                            onCard ? "CREDIT_CARD_PURCHASE" : "WITHDRAWAL",
                            amount,
                            SEP_3,
                            "CHF")
                        .returnResult(String.class)
                        .getStatus()
                        .value();
                  }));
        }
        start.countDown();
        for (Future<Integer> status : statuses) {
          assertThat(status.get()).isEqualTo(201);
        }
      }
      // Counted in the DB, not through the paged endpoint, which caps a page at 200 rows.
      assertThat(countLedgerRows(a.card())).isEqualTo(expectedPurchases);
      assertThat(countLedgerRows(a.current())).isEqualTo(rounds * writers - expectedPurchases);
    } finally {
      pool.shutdownNow();
    }
  }

  // --- Cost of a write does not grow with the ledger's history --------------------------------

  @Test
  void theCostOfAWriteDoesNotGrowWithTheHistoryOnTheSourceAccount() {
    // Each write re-runs matching. Years of ordinary withdrawals that never pair must not turn that
    // into one query per historical payment.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    insertHistoricWithdrawals(a.current(), 10);
    long withFew = statementsFor(() -> purchase(token, a.card(), "-5.00", SEP_3));

    insertHistoricWithdrawals(a.current(), 400);
    long withMany = statementsFor(() -> purchase(token, a.card(), "-6.00", SEP_4));

    assertThat(withMany)
        .as("statements for one write with 10 vs 410 historic withdrawals")
        .isLessThanOrEqualTo(withFew + 3);
  }

  private long statementsFor(Runnable action) {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    action.run();
    return statistics.getPrepareStatementCount();
  }

  private int countLedgerRows(UUID accountId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM transaction WHERE account_id = ?")) {
      statement.setObject(1, accountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Old, ordinary debits that equal nothing: each amount is unique and never the card's balance.
  private void insertHistoricWithdrawals(UUID accountId, int count) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                    + " amount, currency) SELECT a.workspace_id, a.id, 'WITHDRAWAL',"
                    + " DATE '2026-01-01' + (g % 200),"
                    + " -(g + 0.37 + (SELECT count(*) FROM transaction) / 100000.0), 'CHF'"
                    + " FROM account a, generate_series(1, ?) g WHERE a.id = ?")) {
      statement.setInt(1, count);
      statement.setObject(2, accountId);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- Review follow-ups -----------------------------------------------------------------------

  @Test
  void aRejectedOneSidedProposalIsNotOverriddenByALaterMatchingCredit() {
    // A member said "this payment is not a card settlement". A credit of the same amount arriving
    // later is new information, but it must not let the system decide against that on its own.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    TransactionResponse payment = withdrawal(token, a.current(), "-1200.00", SEP_3);
    decide(token, matches(token, null).get(0).id(), "reject", HttpStatus.OK);

    TransactionResponse credit = cardCredit(token, a.card(), "1200.00", SEP_4);

    assertThat(credit.internalTransfer()).isFalse();
    assertThat(matches(token, "CONFIRMED")).isEmpty();
    SettlementMatchResponse proposal = matches(token, null).get(0);
    assertThat(proposal.matchBasis()).isEqualTo(SettlementMatchValues.LEG_PAIR);
    assertThat(proposal.paymentTransactionId()).isEqualTo(payment.id());
    assertThat(proposal.cardTransactionId()).isEqualTo(credit.id());
    // The member can still decide it either way.
    decide(token, proposal.id(), "confirm", HttpStatus.OK);
    assertThat(matches(token, "CONFIRMED")).hasSize(1);
  }

  @Test
  void runningMatchingNeverRevealsAnEarlierSettlementSourcesMatches() {
    String adminToken = bootstrapAdministrator();
    Accounts a = accountsWithSource(adminToken);
    UUID savings = createAccount(adminToken, "Savings", "SAVINGS", "CHF").id();
    withdrawal(adminToken, a.current(), "-1200.00", SEP_3);
    cardCredit(adminToken, a.card(), "1200.00", SEP_3);
    assertThat(matches(adminToken, "CONFIRMED")).hasSize(1); // a match made while the source was A

    // The source changes to another account. Bob may edit the card and the new source, not the old.
    setSource(adminToken, a.card(), savings, HttpStatus.OK);
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");
    grant(adminToken, bobMemberId, a.card(), AccessLevelValues.EDIT);
    grant(adminToken, bobMemberId, savings, AccessLevelValues.EDIT);

    assertThat(run(bobToken, a.card())).isEmpty();
    assertThat(run(adminToken, a.card()))
        .singleElement()
        .satisfies(m -> assertThat(m.paymentAccountId()).isEqualTo(a.current()));
  }

  @Test
  void anotherMembersMatchesCannotCrowdTheCallersOwnOutOfTheWorkQueue() {
    String adminToken = bootstrapAdministrator();
    Accounts mine = accountsWithSource(adminToken);
    purchase(adminToken, mine.card(), "-1200.00", AUG_10);
    withdrawal(adminToken, mine.current(), "-1200.00", SEP_3);
    UUID bobsProposal = matches(adminToken, null).get(0).id();
    UUID bobMemberId = createSecondMember(adminToken, "bob@example.com");
    String bobToken = login("bob@example.com");
    grant(adminToken, bobMemberId, mine.card(), AccessLevelValues.EDIT);
    grant(adminToken, bobMemberId, mine.current(), AccessLevelValues.EDIT);

    // More proposals than one page, all newer, all on accounts Bob cannot see.
    Accounts hidden = accountsWithoutSource(adminToken);
    insertProposedMatches(
        hidden.card(), hidden.current(), SettlementMatchControllerTestLimits.PAGE + 5);

    assertThat(matches(bobToken, null))
        .extracting(SettlementMatchResponse::id)
        .contains(bobsProposal);
    assertThat(matches(adminToken, null)).hasSize(SettlementMatchControllerTestLimits.PAGE);
  }

  @Test
  void matchesAreListedNewestFirstWithAStableTieBreak() throws Exception {
    // Rows created in one statement share created_at (now() is the transaction's start), so the
    // order among them must come from a tie-breaker, not from whatever order the database returns.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    insertProposedMatches(a.card(), a.current(), 40);

    List<UUID> expected = matchIdsNewestFirst("PROPOSED");
    assertThat(matches(token, "PROPOSED"))
        .extracting(SettlementMatchResponse::id)
        .containsExactlyElementsOf(expected);
    assertThat(run(token, a.card()))
        .extracting(SettlementMatchResponse::id)
        .containsExactlyElementsOf(expected);
  }

  @Test
  void listingMatchesDoesNotIssueAQueryPerMatch() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    insertProposedMatches(a.card(), a.current(), 3);
    long withFew = statementsFor(() -> matches(token, null));

    insertProposedMatches(a.card(), a.current(), 30);
    long withMany = statementsFor(() -> matches(token, null));

    assertThat(withMany).as("statements to list 3 vs 33 matches").isLessThanOrEqualTo(withFew + 2);
  }

  @Test
  void aVoidedPaymentWithAProposalIsNotHeldOutOfSpendingWhileItsReversalCounts() {
    // The proposal on a payment that was later voided is moot. Excluding the voided original as
    // "pending" while still counting its reversing row would understate spending by the payment.
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", SEP_3);
    TransactionResponse payment = withdrawal(token, a.current(), "-1200.00", SEP_3);
    assertThat(matches(token, null)).hasSize(1);

    voidWithReversal(payment.id(), a.current(), "WITHDRAWAL", "1200.00");

    CashFlowResponse september = cashFlow(token, "2026-09");
    assertThat(september.spending())
        .singleElement()
        .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("1200.00")); // the purchase
    assertThat(september.pendingReview()).isEmpty();
    assertThat(september.complete()).isTrue();
  }

  @Test
  void aVoidedSettlementTypedPaymentIsNoLongerPendingReview() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    TransactionResponse payment = settlementPayment(token, a.current(), "-500.00", SEP_3);
    assertThat(cashFlow(token, "2026-09").pendingReview()).hasSize(1);

    voidWithReversal(payment.id(), a.current(), "SETTLEMENT", "500.00");

    CashFlowResponse september = cashFlow(token, "2026-09");
    assertThat(september.pendingReview()).isEmpty();
    assertThat(september.complete()).isTrue();
  }

  @Test
  void aProposalOnAVoidedPaymentCannotBeConfirmed() {
    String token = bootstrapAdministrator();
    Accounts a = accountsWithSource(token);
    purchase(token, a.card(), "-1200.00", AUG_10);
    TransactionResponse payment = withdrawal(token, a.current(), "-1200.00", SEP_3);
    UUID proposalId = matches(token, null).get(0).id();

    voidWithReversal(payment.id(), a.current(), "WITHDRAWAL", "1200.00");

    decide(token, proposalId, "confirm", HttpStatus.CONFLICT);
  }

  // The page size of the work queue (SettlementMatchService.MAX_LISTED), spelled out for the tests.
  private static final class SettlementMatchControllerTestLimits {
    static final int PAGE = 200;
  }

  // PROPOSED one-sided matches on fresh withdrawals, made directly (as detection would), all in one
  // statement so they share created_at.
  private void insertProposedMatches(UUID cardId, UUID sourceId, int count) {
    try (Connection connection = dataSource.getConnection()) {
      UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", cardId);
      String marker = "us0902-" + UUID.randomUUID();
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                  + " amount, currency, merchant_description) SELECT ?, ?, 'WITHDRAWAL',"
                  + " DATE '2026-03-01' + (g % 100), -(g + 0.11), 'CHF', ?"
                  + " FROM generate_series(1, ?) g")) {
        statement.setObject(1, workspaceId);
        statement.setObject(2, sourceId);
        statement.setString(3, marker);
        statement.setInt(4, count);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO settlement_match (workspace_id, card_account_id,"
                  + " payment_transaction_id, status, match_basis) SELECT workspace_id, ?, id,"
                  + " 'PROPOSED', 'BALANCE_EQUALS_PAYMENT' FROM transaction"
                  + " WHERE account_id = ? AND merchant_description = ?")) {
        statement.setObject(1, cardId);
        statement.setObject(2, sourceId);
        statement.setString(3, marker);
        statement.executeUpdate();
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private List<UUID> matchIdsNewestFirst(String status) throws Exception {
    List<UUID> ids = new java.util.ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM settlement_match WHERE status = ?"
                    + " ORDER BY created_at DESC, id DESC")) {
      statement.setString(1, status);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          ids.add((UUID) resultSet.getObject(1));
        }
      }
    }
    return ids;
  }

  // What the (not yet built) void path of US-07-02 leaves behind: the original marked voided, and a
  // reversing row of the same type and the opposite sign.
  private void voidWithReversal(UUID transactionId, UUID accountId, String type, String reversal) {
    UUID workspaceId = jdbcUuid("SELECT workspace_id FROM account WHERE id = ?", accountId);
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE transaction SET voided_at = now(), void_reason = 'test' WHERE id = ?")) {
        statement.setObject(1, transactionId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO transaction (workspace_id, account_id, transaction_type, booking_date,"
                  + " amount, currency, replaces_transaction_id)"
                  + " SELECT ?, ?, ?, booking_date, ?, 'CHF', id FROM transaction WHERE id = ?")) {
        statement.setObject(1, workspaceId);
        statement.setObject(2, accountId);
        statement.setString(3, type);
        statement.setBigDecimal(4, new BigDecimal(reversal));
        statement.setObject(5, transactionId);
        statement.executeUpdate();
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID jdbcUuid(String sql, Object parameter) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as(sql).isTrue();
        return (UUID) resultSet.getObject(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private record Accounts(UUID card, UUID current) {}

  private Accounts accountsWithoutSource(String token) {
    UUID card = createAccount(token, "Visa Gold", "CREDIT_CARD", "CHF").id();
    UUID current = createAccount(token, "Everyday Checking", "CASH", "CHF").id();
    return new Accounts(card, current);
  }

  private Accounts accountsWithSource(String token) {
    Accounts accounts = accountsWithoutSource(token);
    setSource(token, accounts.card(), accounts.current(), HttpStatus.OK);
    return accounts;
  }

  private AccountSummaryResponse createAccount(
      String token, String name, String accountType, String currency) {
    String customAssetType = "CUSTOM_ASSET".equals(accountType) ? "VEHICLE" : null;
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account(name, accountType, currency)
                .customAssetType(customAssetType)
                .build())
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec post(
      String token, UUID accountId, String type, String amount) {
    return post(token, accountId, type, amount, "CHF");
  }

  private RestTestClient.ResponseSpec post(
      String token, UUID accountId, String type, String amount, String currency) {
    return postOn(token, accountId, type, amount, SEP_3, currency);
  }

  private RestTestClient.ResponseSpec postOn(
      String token, UUID accountId, String type, String amount, LocalDate date, String currency) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                type,
                date,
                new BigDecimal(amount),
                currency,
                null,
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange();
  }

  private TransactionResponse record(
      String token, UUID accountId, String type, String amount, LocalDate date) {
    return postOn(token, accountId, type, amount, date, "CHF")
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionResponse purchase(String token, UUID card, String amount, LocalDate date) {
    return record(token, card, "CREDIT_CARD_PURCHASE", amount, date);
  }

  // US-09-04: a card purchase in a currency other than the card's own billing currency, with an
  // explicit applied rate.
  private TransactionResponse foreignPurchase(
      String token, UUID card, String amount, String currency, String rate, LocalDate date) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + card + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                "CREDIT_CARD_PURCHASE",
                date,
                new BigDecimal(amount),
                currency,
                null,
                null,
                null,
                null,
                new BigDecimal(rate),
                null,
                null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionResponse withdrawal(
      String token, UUID account, String amount, LocalDate date) {
    return record(token, account, "WITHDRAWAL", amount, date);
  }

  private TransactionResponse settlementPayment(
      String token, UUID account, String amount, LocalDate date) {
    return record(token, account, "SETTLEMENT", amount, date);
  }

  private TransactionResponse cardCredit(String token, UUID card, String amount, LocalDate date) {
    return record(token, card, "SETTLEMENT", amount, date);
  }

  private List<TransactionResponse> ledger(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/transactions?size=200")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageOf<TransactionResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  // Only the one member of Spring Data's page JSON these tests read; the rest is ignored.
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PageOf<T>(List<T> content) {}

  private List<SettlementMatchResponse> matches(String token, String status) {
    return client(token)
        .get()
        .uri("/api/v1/settlement-matches" + (status == null ? "" : "?status=" + status))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<SettlementMatchResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private List<SettlementMatchResponse> run(String token, UUID card) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + card + "/settlement-matches/run")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<SettlementMatchResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private static List<UUID> ids(List<SettlementMatchResponse> matches) {
    return matches.stream().map(SettlementMatchResponse::id).toList();
  }

  private SettlementMatchResponse decide(
      String token, UUID matchId, String action, HttpStatus expected) {
    RestTestClient.ResponseSpec response =
        client(token).post().uri("/api/v1/settlement-matches/" + matchId + "/" + action).exchange();
    response.expectStatus().isEqualTo(expected);
    return expected == HttpStatus.OK
        ? response.expectBody(SettlementMatchResponse.class).returnResult().getResponseBody()
        : null;
  }

  private SettlementSourceResponse setSource(
      String token, UUID card, UUID source, HttpStatus expected) {
    RestTestClient.ResponseSpec response =
        client(token)
            .put()
            .uri("/api/v1/accounts/" + card + "/settlement-source")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SetSettlementSourceRequest(source))
            .exchange();
    response.expectStatus().isEqualTo(expected);
    return expected == HttpStatus.OK
        ? response.expectBody(SettlementSourceResponse.class).returnResult().getResponseBody()
        : null;
  }

  private SettlementSourceResponse getSource(String token, UUID card) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + card + "/settlement-source")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(SettlementSourceResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private CashFlowResponse cashFlow(String token, String month) {
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

  private AccountValuation balance(String token, UUID accountId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + accountId + "/balance")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
        .returnResult()
        .getResponseBody();
  }

  private void grant(String token, UUID memberId, UUID accountId, String level) {
    client(token)
        .post()
        .uri("/api/v1/sharing-grants")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSharingGrantRequest(
                memberId, ScopeTypeValues.ACCOUNT, accountId, null, level))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
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
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT workspace_member_id FROM app_user WHERE email = ?")) {
      statement.setString(1, email);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("app_user " + email).isTrue();
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
