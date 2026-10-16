package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AmortisationScheduleEntryRequest;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CashFlowResponse;
import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateLoanRepaymentRequest;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.LoanRepaymentResponse;
import com.trackmywealth.backend.dto.LoanRepaymentSplitValues;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
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
 * US-10-02: a loan or mortgage payment is split into interest (an expense) and principal (a
 * balance-sheet movement). The DoD's V-14 of the golden verification dataset (EPIC 27) is {@link
 * #v14aMortgagePaymentIsSplitIntoInterestExpenseAndPrincipalRepayment}; the rest pin each split
 * source, the savings-rate methodology, the loan's value, and removing, restoring and correcting a
 * payment as one entry - all against PostgreSQL.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanRepaymentControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String DEBT_REPAYMENT = "DEBT_REPAYMENT";
  private static final String EXPENSE = "EXPENSE";

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
              // V87 refuses to delete a loan payment, as V68 a transaction.
              LedgerCleanup.withGuardsOff("DELETE FROM loan_payment"),
              "DELETE FROM amortisation_schedule_entry",
              "DELETE FROM savings_rate_methodology",
              "DELETE FROM settlement_match",
              "DELETE FROM transaction_categorization_log",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM sharing_grant",
              "DELETE FROM account_ownership",
              "DELETE FROM account_credit_card",
              "DELETE FROM account_mortgage",
              "DELETE FROM account_loan",
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

  // --- DoD: V-14
  // ----------------------------------------------------------------------------------

  @Test
  void v14aMortgagePaymentIsSplitIntoInterestExpenseAndPrincipalRepayment() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    scheduleEntry(token, mortgage.id(), today(), "800.00", "1200.00", "498800.00");

    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "2000.00", null, null));

    assertThat(payment.splitSource()).isEqualTo(LoanRepaymentSplitValues.SCHEDULE);
    assertThat(payment.splitEstimated()).isFalse();
    assertThat(payment.interestAmount()).isEqualByComparingTo("800.00");
    assertThat(payment.principalAmount()).isEqualByComparingTo("1200.00");
    assertThat(payment.amortisationScheduleEntryId()).isNotNull();
    TransactionResponse outflow = row(payment, current.id(), DEBT_REPAYMENT);
    TransactionResponse inflow = row(payment, mortgage.id(), DEBT_REPAYMENT);
    TransactionResponse interest = row(payment, current.id(), EXPENSE);
    assertThat(outflow.amount()).isEqualByComparingTo("-1200.00");
    assertThat(outflow.internalTransfer()).isTrue();
    assertThat(outflow.counterpartyAccountId()).isEqualTo(mortgage.id());
    assertThat(inflow.amount()).isEqualByComparingTo("1200.00");
    assertThat(inflow.internalTransfer()).isTrue();
    assertThat(inflow.relatedTransactionId()).isEqualTo(outflow.id());
    assertThat(interest.amount()).isEqualByComparingTo("-800.00");
    assertThat(interest.internalTransfer()).isFalse();
    assertThat(interest.relatedTransactionId()).isEqualTo(outflow.id());
    assertThat(interest.categoryId()).isEqualTo(shippedCategory("LOAN_INTEREST"));
    assertThat(interest.categoryAssignedBy()).isEqualTo("TRANSACTION_TYPE");

    // CHF 800 is an expense, CHF 1,200 debt repayment - saving by default, never spending.
    CashFlowResponse flow = cashFlow(token);
    assertThat(chf(flow.spending())).isEqualByComparingTo("800.00");
    assertThat(chf(flow.saving())).isEqualByComparingTo("1200.00");
    assertThat(flow.income()).isEmpty();
    assertThat(flow.pendingReview()).isEmpty();
    assertThat(flow.complete()).isTrue();

    // The debt fell by the principal only, so net worth fell by the interest only.
    AccountValuation owed = balance(token, mortgage.id());
    assertThat(owed.value()).isEqualByComparingTo("498800.00");
    assertThat(owed.valueBasis()).isEqualTo(ValueBasisValues.ORIGINAL_PRINCIPAL_LESS_REPAYMENTS);
    NetWorthResponse netWorth = netWorth(token);
    assertThat(netWorth.totalLiabilities()).isEqualByComparingTo("498800.00");
    assertThat(netWorth.approximate()).isTrue();
  }

  // --- savings-rate methodology
  // -------------------------------------------------------------------

  @Test
  void withoutMortgagePrincipalInTheMethodologyThePrincipalIsNotSaving() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    execute(
        "INSERT INTO savings_rate_methodology (workspace_id, include_mortgage_principal)"
            + " SELECT workspace_id, FALSE FROM account WHERE id = ?",
        mortgage.id());

    repay(token, mortgage.id(), payment(current.id(), "2000.00", "800.00", null));

    CashFlowResponse flow = cashFlow(token);
    assertThat(chf(flow.spending())).isEqualByComparingTo("800.00");
    assertThat(flow.saving()).isEmpty();
    assertThat(flow.complete()).isTrue();
  }

  @Test
  void principalPaidFromASavingsAccountMovesMoneyBetweenTwoSavingAccounts() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse savings = createAccount(token, "SAVINGS", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");

    repay(token, mortgage.id(), payment(savings.id(), "2000.00", "800.00", null));

    CashFlowResponse flow = cashFlow(token);
    assertThat(chf(flow.spending())).isEqualByComparingTo("800.00");
    assertThat(flow.saving()).isEmpty();
  }

  // --- the other split sources
  // --------------------------------------------------------------------

  @Test
  void aStatedSplitIsRecordedAsGivenWithoutTheEstimateFlag() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    scheduleEntry(token, mortgage.id(), today(), "800.00", "1200.00", "498800.00");

    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "2000.00", "750.00", null));

    assertThat(payment.splitSource()).isEqualTo(LoanRepaymentSplitValues.STATED);
    assertThat(payment.splitEstimated()).isFalse();
    assertThat(payment.interestAmount()).isEqualByComparingTo("750.00");
    assertThat(payment.principalAmount()).isEqualByComparingTo("1250.00");
  }

  @Test
  void anExtraRepaymentAgainstTheScheduleIsFlaggedAsAnEstimate() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    scheduleEntry(token, mortgage.id(), today(), "800.00", "1200.00", "498800.00");

    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "2500.00", null, null));

    assertThat(payment.splitSource()).isEqualTo(LoanRepaymentSplitValues.SCHEDULE);
    assertThat(payment.splitEstimated()).isTrue();
    assertThat(payment.principalAmount()).isEqualByComparingTo("1700.00");
  }

  @Test
  void withoutAScheduleTheInterestIsEstimatedFromTheRateAndWhatIsStillOwed() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "480000.00", "2.0");

    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "2000.00", null, null));

    // 480,000 x 2% / 12
    assertThat(payment.splitSource()).isEqualTo(LoanRepaymentSplitValues.ESTIMATED);
    assertThat(payment.splitEstimated()).isTrue();
    assertThat(payment.interestAmount()).isEqualByComparingTo("800.00");
    assertThat(payment.principalAmount()).isEqualByComparingTo("1200.00");
  }

  @Test
  void anUnknownSplitIsPendingReviewNeverSilentlyInterestOrPrincipal() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse loan = createLoanWithoutRate(token, "20000.00");

    LoanRepaymentResponse payment =
        repay(token, loan.id(), payment(current.id(), "500.00", null, null));

    assertThat(payment.splitSource()).isEqualTo(LoanRepaymentSplitValues.UNKNOWN);
    assertThat(payment.splitEstimated()).isTrue();
    assertThat(payment.interestAmount()).isNull();
    assertThat(payment.principalAmount()).isNull();
    assertThat(payment.transactions())
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row.transactionType()).isEqualTo(DEBT_REPAYMENT);
              assertThat(row.internalTransfer()).isFalse();
            });

    CashFlowResponse flow = cashFlow(token);
    assertThat(flow.spending()).isEmpty();
    assertThat(flow.saving()).isEmpty();
    assertThat(chf(flow.pendingReview())).isEqualByComparingTo("500.00");
    assertThat(flow.complete()).isFalse();
  }

  @Test
  void anInterestOnlyPaymentWritesOnlyTheExpenseAndLeavesTheDebtAsItWas() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");

    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "800.00", "800.00", null));

    assertThat(payment.principalAmount()).isEqualByComparingTo("0");
    assertThat(payment.transactions())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.transactionType()).isEqualTo(EXPENSE);
              assertThat(row.counterpartyAccountId()).isEqualTo(mortgage.id());
              assertThat(row.relatedTransactionId()).isNull();
            });
    AccountValuation owed = balance(token, mortgage.id());
    assertThat(owed.value()).isEqualByComparingTo("500000.00");
    assertThat(owed.valueBasis()).isEqualTo(ValueBasisValues.ORIGINAL_PRINCIPAL);
  }

  // --- one entry: removed, restored and corrected together
  // ----------------------------------------

  @Test
  void removingTheInterestRemovesTheWholePaymentAndRestoringItBringsItAllBack() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "2000.00", "800.00", null));
    TransactionResponse interest = row(payment, current.id(), EXPENSE);

    TransactionRemovalResponse removed =
        client(token)
            .delete()
            .uri(rowUri(current.id(), interest.id()))
            .headers(CurrentVersion.ifMatch(dataSource, "transaction", interest.id()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionRemovalResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(removed.affected())
        .extracting(TransactionResponse::id)
        .containsExactlyInAnyOrderElementsOf(
            payment.transactions().stream().map(TransactionResponse::id).toList());
    assertThat(cashFlow(token).spending()).isEmpty();
    assertThat(cashFlow(token).saving()).isEmpty();
    assertThat(balance(token, mortgage.id()).value()).isEqualByComparingTo("500000.00");

    TransactionResponse inflow = row(payment, mortgage.id(), DEBT_REPAYMENT);
    TransactionRemovalResponse restored =
        client(token)
            .post()
            .uri(rowUri(mortgage.id(), inflow.id()) + "/restore")
            .headers(CurrentVersion.ifMatch(dataSource, "transaction", inflow.id()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionRemovalResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(restored.affected()).hasSize(3);
    assertThat(chf(cashFlow(token).spending())).isEqualByComparingTo("800.00");
    assertThat(balance(token, mortgage.id()).value()).isEqualByComparingTo("498800.00");
  }

  @Test
  void aLoanPaymentRowTakesTextEditsButNoFinancialCorrection() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "800.00", "800.00", null));
    TransactionResponse interest = payment.transactions().get(0);

    client(token)
        .put()
        .uri(rowUri(current.id(), interest.id()))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", interest.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(correction(interest, "-700.00", "Mortgage interest Q1"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);

    client(token)
        .put()
        .uri(rowUri(current.id(), interest.id()))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", interest.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(correction(interest, "-800.00", "Mortgage interest Q1"))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(list(token, current.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.merchantDescription()).isEqualTo("Mortgage interest Q1");
              assertThat(row.amount()).isEqualByComparingTo("-800.00");
              assertThat(row.counterpartyAccountId()).isEqualTo(mortgage.id());
            });
  }

  // --- idempotency, listing
  // -----------------------------------------------------------------------

  @Test
  void aRetryWithTheSameKeyReplaysThePaymentAndAnotherPaymentUnderItIsAConflict() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    CreateLoanRepaymentRequest request = payment(current.id(), "2000.00", "800.00", "pay-03");

    LoanRepaymentResponse first = repay(token, mortgage.id(), request);
    LoanRepaymentResponse retry = repay(token, mortgage.id(), request);

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(list(token, current.id())).hasSize(2);
    postRepayment(token, mortgage.id(), payment(current.id(), "2100.00", "800.00", "pay-03"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(repayments(token, mortgage.id()))
        .singleElement()
        .satisfies(
            listed -> {
              assertThat(listed.id()).isEqualTo(first.id());
              assertThat(listed.transactions()).hasSize(3);
            });
  }

  @Test
  void aRemovedPaymentLeavesTheListAndKeepsItsKeyUntilItIsRestored() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    CreateLoanRepaymentRequest request = payment(current.id(), "2000.00", "800.00", "pay-07");
    LoanRepaymentResponse payment = repay(token, mortgage.id(), request);
    TransactionResponse interest = row(payment, current.id(), EXPENSE);
    client(token)
        .delete()
        .uri(rowUri(current.id(), interest.id()))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", interest.id()))
        .exchange()
        .expectStatus()
        .isOk();

    // Gone from the list, and its key is not replayed as if the payment were still recorded.
    assertThat(repayments(token, mortgage.id())).isEmpty();
    postRepayment(token, mortgage.id(), request).expectStatus().isEqualTo(HttpStatus.CONFLICT);
    assertThat(list(token, current.id())).as("nothing recorded by the refused retry").isEmpty();

    TransactionResponse inflow = row(payment, mortgage.id(), DEBT_REPAYMENT);
    client(token)
        .post()
        .uri(rowUri(mortgage.id(), inflow.id()) + "/restore")
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", inflow.id()))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(repayments(token, mortgage.id()))
        .singleElement()
        .satisfies(listed -> assertThat(listed.transactions()).hasSize(3));
    assertThat(repay(token, mortgage.id(), request).id()).isEqualTo(payment.id());
  }

  @Test
  void deletingTheScheduleEntryAPaymentWasSplitByKeepsTheSplitAsRecorded() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    scheduleEntry(token, mortgage.id(), today(), "800.00", "1200.00", "498800.00");
    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "2000.00", null, null));
    UUID entryId = payment.amortisationScheduleEntryId();
    assertThat(entryId).isNotNull();

    // V83's ON DELETE SET NULL, the one change V87 lets through.
    client(token)
        .delete()
        .uri("/api/v1/accounts/" + mortgage.id() + "/amortisation-schedule/" + entryId)
        .headers(CurrentVersion.ifMatch(dataSource, "amortisation_schedule_entry", entryId))
        .exchange()
        .expectStatus()
        .isNoContent();

    assertThat(repayments(token, mortgage.id()))
        .singleElement()
        .satisfies(
            listed -> {
              assertThat(listed.splitSource()).isEqualTo(LoanRepaymentSplitValues.SCHEDULE);
              assertThat(listed.interestAmount()).isEqualByComparingTo("800.00");
              assertThat(listed.amortisationScheduleEntryId()).isNull();
            });
  }

  @Test
  void aLoanPaymentsRecordCannotBeChangedOrDeletedInTheDatabase() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    UUID paymentId =
        repay(token, mortgage.id(), payment(current.id(), "2000.00", "800.00", null)).id();

    for (String sql :
        List.of(
            "UPDATE loan_payment SET interest_amount = 0, principal_amount = 2000 WHERE id = ?",
            "UPDATE loan_payment SET external_id = 'other' WHERE id = ?",
            "DELETE FROM loan_payment WHERE id = ?")) {
      assertThatThrownBy(() -> execute(sql, paymentId))
          .as(sql)
          .hasMessageContaining("loan_payment_append_only");
    }
    assertThat(repayments(token, mortgage.id()))
        .singleElement()
        .satisfies(listed -> assertThat(listed.interestAmount()).isEqualByComparingTo("800.00"));
  }

  @Test
  void anotherWorkspacesLoanOrPayingAccountIsTheGenericNotFoundAndEachDenialIsAudited()
      throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    UUID foreign = accountInAnotherWorkspace();

    postRepayment(token, foreign, payment(current.id(), "2000.00", "800.00", null))
        .expectStatus()
        .isNotFound();
    client(token)
        .get()
        .uri("/api/v1/accounts/" + foreign + "/repayments")
        .exchange()
        .expectStatus()
        .isNotFound();
    client(token)
        .get()
        .uri("/api/v1/accounts/" + foreign + "/amortisation-schedule")
        .exchange()
        .expectStatus()
        .isNotFound();
    client(token)
        .post()
        .uri("/api/v1/accounts/" + foreign + "/amortisation-schedule")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AmortisationScheduleEntryRequest(
                today(), new BigDecimal("800.00"), new BigDecimal("1200.00"), BigDecimal.ONE))
        .exchange()
        .expectStatus()
        .isNotFound();
    // Paid from another workspace's account onto the member's own mortgage.
    postRepayment(token, mortgage.id(), payment(foreign, "2000.00", "800.00", null))
        .expectStatus()
        .isNotFound();

    assertThat(deniedRequestsFor(foreign)).isEqualTo(5);
    assertThat(repayments(token, mortgage.id())).isEmpty();
  }

  // --- refusals
  // -----------------------------------------------------------------------------------

  @Test
  void aDebtRepaymentCannotBeRecordedAsAPlainTransaction() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");

    client(token)
        .post()
        .uri("/api/v1/accounts/" + current.id() + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                DEBT_REPAYMENT,
                today(),
                new BigDecimal("-2000.00"),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.detail")
        .value(detail -> assertThat((String) detail).contains("/repayments"));
    client(token)
        .post()
        .uri("/api/v1/accounts/" + mortgage.id() + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                EXPENSE,
                today(),
                new BigDecimal("-800.00"),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void aRepaymentNeedsALoanAnOrdinaryPayingAccountOneCurrencyAndAPlausibleSplit() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse euro = createAccount(token, "CASH", "EUR");
    AccountSummaryResponse card = createAccount(token, "CREDIT_CARD", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");

    for (CreateLoanRepaymentRequest invalid :
        List.of(
            payment(card.id(), "2000.00", null, null),
            payment(mortgage.id(), "2000.00", null, null),
            payment(euro.id(), "2000.00", null, null),
            payment(current.id(), "0", null, null),
            payment(current.id(), "2000.00", "2000.01", null),
            payment(current.id(), "2000.00", "-1", null))) {
      postRepayment(token, mortgage.id(), invalid)
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
    postRepayment(token, current.id(), payment(current.id(), "2000.00", null, null))
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(list(token, current.id())).isEmpty();
  }

  @Test
  void aMemberWhoMayOnlyReadTheLoanCannotRecordAPaymentOnIt() {
    String adminToken = bootstrapAdministrator();
    AccountSummaryResponse mortgage = createMortgage(adminToken, "500000.00", "2.0");
    UUID memberId = createSecondMember(adminToken, "partner@example.com");
    grantAccount(adminToken, memberId, mortgage.id(), AccessLevelValues.READ);
    String memberToken = login("partner@example.com");
    AccountSummaryResponse own = createAccount(memberToken, "CASH", "CHF");

    postRepayment(memberToken, mortgage.id(), payment(own.id(), "2000.00", "800.00", null))
        .expectStatus()
        .isNotFound();
    assertThat(repayments(memberToken, mortgage.id())).isEmpty();
  }

  @Test
  void aSharedLoanNeverExposesAnUnsharedPayingAccountsRepayments() {
    String ownerToken = bootstrapAdministrator();
    AccountSummaryResponse paying = createAccount(ownerToken, "CASH", "CHF");
    AccountSummaryResponse loan = createMortgage(ownerToken, "500000.00", "2.0");
    LoanRepaymentResponse original =
        repay(
            ownerToken,
            loan.id(),
            new CreateLoanRepaymentRequest(
                paying.id(),
                today(),
                new BigDecimal("2000.00"),
                "CHF",
                new BigDecimal("800.00"),
                "Rent share from joint account",
                "private note",
                "private-key"));

    UUID partnerId = createSecondMember(ownerToken, "reader@example.com");
    grantAccount(ownerToken, partnerId, loan.id(), AccessLevelValues.READ);
    String readerToken = login("reader@example.com");

    // Shared loan does not confer transaction-level READ on the paying account.
    assertThat(repayments(readerToken, loan.id())).isEmpty();
    // Nor does the loan's own ledger tell what the owner wrote on the paying account.
    assertThat(list(readerToken, loan.id()))
        .singleElement()
        .satisfies(
            inflow -> {
              assertThat(inflow.amount()).isEqualByComparingTo("1200.00");
              assertThat(inflow.merchantDescription()).isNull();
              assertThat(inflow.notes()).isNull();
            });
    postRepayment(readerToken, loan.id(), payment(paying.id(), "2000.00", "800.00", "private-key"))
        .expectStatus()
        .isNotFound();

    grantAccount(ownerToken, partnerId, paying.id(), AccessLevelValues.BALANCE_ONLY);
    assertThat(repayments(readerToken, loan.id())).isEmpty();

    UUID fullReaderId = createSecondMember(ownerToken, "full-reader@example.com");
    grantAccount(ownerToken, fullReaderId, loan.id(), AccessLevelValues.READ);
    grantAccount(ownerToken, fullReaderId, paying.id(), AccessLevelValues.READ);
    assertThat(repayments(login("full-reader@example.com"), loan.id()))
        .extracting(LoanRepaymentResponse::id)
        .containsExactly(original.id());
  }

  @Test
  void unknownSplitLeavesLoanValueUnknownAndPendingPaymentVisibleFromPayingAccount() {
    String ownerToken = bootstrapAdministrator();
    AccountSummaryResponse paying = createAccount(ownerToken, "CASH", "CHF");
    AccountSummaryResponse loan = createLoanWithoutRate(ownerToken, "20000.00");
    repay(ownerToken, loan.id(), payment(paying.id(), "500.00", null, null));

    assertThat(balance(ownerToken, loan.id()).valueKnown()).isFalse();
    // Left out of the totals, so net worth says it is incomplete rather than overstating it
    // silently.
    assertThat(netWorth(ownerToken).complete()).isFalse();

    UUID partnerId = createSecondMember(ownerToken, "payer-reader@example.com");
    grantAccount(ownerToken, partnerId, paying.id(), AccessLevelValues.READ);
    String readerToken = login("payer-reader@example.com");
    CashFlowResponse flow = cashFlow(readerToken);
    assertThat(chf(flow.pendingReview())).isEqualByComparingTo("500.00");
    assertThat(flow.complete()).isFalse();

    // When both legs are visible, the same payment still counts only once.
    grantAccount(ownerToken, partnerId, loan.id(), AccessLevelValues.READ);
    assertThat(chf(cashFlow(readerToken).pendingReview())).isEqualByComparingTo("500.00");
  }

  // --- card settlements
  // ---------------------------------------------------------------------------

  @Test
  void aLoansInterestIsNeverTakenForACardPaymentOfTheSameAmount() {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    AccountSummaryResponse mortgage = createMortgage(token, "500000.00", "2.0");
    UUID card = createCardPaidFrom(token, current.id());
    record(token, card, "CREDIT_CARD_PURCHASE", "-800.00", today().minusDays(5));

    LoanRepaymentResponse payment =
        repay(token, mortgage.id(), payment(current.id(), "800.00", "800.00", null));

    assertThat(settlementMatches(token)).isEmpty();
    TransactionResponse interest = row(payment, current.id(), EXPENSE);
    assertThat(list(token, current.id()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(interest.id());
              assertThat(row.counterpartyAccountId()).isEqualTo(mortgage.id());
              assertThat(row.internalTransfer()).isFalse();
            });
  }

  @Test
  void aCardPaymentConfirmedAsASettlementStaysAnOrdinaryCorrectableExpense() throws Exception {
    String token = bootstrapAdministrator();
    AccountSummaryResponse current = createAccount(token, "CASH", "CHF");
    UUID card = createCardPaidFrom(token, current.id());
    record(token, card, "CREDIT_CARD_PURCHASE", "-300.00", today().minusDays(5));
    TransactionResponse cardBill = record(token, current.id(), EXPENSE, "-300.00", today());
    SettlementMatchResponse proposal = settlementMatches(token).get(0);
    client(token)
        .post()
        .uri("/api/v1/settlement-matches/" + proposal.id() + "/confirm")
        .headers(CurrentVersion.ifMatch(dataSource, "settlement_match", proposal.id()))
        .exchange()
        .expectStatus()
        .isOk();
    TransactionResponse settled = list(token, current.id()).get(0);
    assertThat(settled.counterpartyAccountId()).isEqualTo(card);
    assertThat(settled.internalTransfer()).isTrue();

    // An EXPENSE naming the card is not a loan payment's interest: a financial correction is no
    // 409, and it never becomes loan interest.
    assertThat(settled.categoryId()).isNotEqualTo(shippedCategory("LOAN_INTEREST"));
    client(token)
        .put()
        .uri(rowUri(current.id(), cardBill.id()))
        .headers(CurrentVersion.ifMatch(dataSource, "transaction", cardBill.id()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(correction(settled, "-250.00", "Card bill"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  // --- helpers
  // ------------------------------------------------------------------------------------

  private static CreateLoanRepaymentRequest payment(
      UUID payingAccountId, String amount, String interestAmount, String externalId) {
    return new CreateLoanRepaymentRequest(
        payingAccountId,
        today(),
        new BigDecimal(amount),
        "CHF",
        interestAmount == null ? null : new BigDecimal(interestAmount),
        "Mortgage payment",
        null,
        externalId);
  }

  private static CorrectTransactionRequest correction(
      TransactionResponse original, String amount, String merchant) {
    return new CorrectTransactionRequest(
        null,
        original.transactionType(),
        original.bookingDate(),
        new BigDecimal(amount),
        original.currency(),
        merchant,
        null,
        original.notes(),
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
        "typo");
  }

  private static TransactionResponse row(
      LoanRepaymentResponse payment, UUID accountId, String type) {
    return payment.transactions().stream()
        .filter(row -> row.accountId().equals(accountId) && row.transactionType().equals(type))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type + " on " + accountId));
  }

  private LoanRepaymentResponse repay(
      String token, UUID loanId, CreateLoanRepaymentRequest request) {
    return postRepayment(token, loanId, request)
        .expectStatus()
        .isCreated()
        .expectBody(LoanRepaymentResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec postRepayment(
      String token, UUID loanId, CreateLoanRepaymentRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + loanId + "/repayments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange();
  }

  private List<LoanRepaymentResponse> repayments(String token, UUID loanId) {
    return client(token)
        .get()
        .uri("/api/v1/accounts/" + loanId + "/repayments")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<LoanRepaymentResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private void scheduleEntry(
      String token,
      UUID loanId,
      LocalDate dueDate,
      String interest,
      String principal,
      String remaining) {
    client(token)
        .post()
        .uri("/api/v1/accounts/" + loanId + "/amortisation-schedule")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new AmortisationScheduleEntryRequest(
                dueDate,
                new BigDecimal(interest),
                new BigDecimal(principal),
                new BigDecimal(remaining)))
        .exchange()
        .expectStatus()
        .isCreated();
  }

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

  record PageOf<T>(List<T> content, long totalElements) {}

  private CashFlowResponse cashFlow(String token) {
    return client(token)
        .get()
        .uri("/api/v1/cash-flow?month=" + YearMonth.from(today()))
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

  private NetWorthResponse netWorth(String token) {
    return client(token)
        .get()
        .uri("/api/v1/net-worth")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(NetWorthResponse.class)
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

  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private static String rowUri(UUID accountId, UUID transactionId) {
    return "/api/v1/accounts/" + accountId + "/transactions/" + transactionId;
  }

  // A credit card whose bills are paid from sourceAccountId (US-09-02's settlement source).
  private UUID createCardPaidFrom(String token, UUID sourceAccountId) {
    UUID card = create(token, AccountRequests.account("Visa", "CREDIT_CARD", "CHF").build()).id();
    client(token)
        .put()
        .uri("/api/v1/accounts/" + card + "/settlement-source")
        .headers(CurrentVersion.ifMatchForCard(dataSource, card))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetSettlementSourceRequest(sourceAccountId))
        .exchange()
        .expectStatus()
        .isOk();
    return card;
  }

  private TransactionResponse record(
      String token, UUID accountId, String type, String amount, LocalDate date) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            TransactionRequests.cash(
                type,
                date,
                new BigDecimal(amount),
                "CHF",
                null,
                null,
                null,
                null,
                null,
                null,
                null))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<SettlementMatchResponse> settlementMatches(String token) {
    return client(token)
        .get()
        .uri("/api/v1/settlement-matches")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<List<SettlementMatchResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createAccount(String token, String type, String currency) {
    return create(token, AccountRequests.account(type, type, currency).build());
  }

  private AccountSummaryResponse createMortgage(String token, String principal, String rate) {
    return create(
        token,
        AccountRequests.account("Home Mortgage", "MORTGAGE", "CHF")
            .originalPrincipal(new BigDecimal(principal))
            .interestRatePercent(new BigDecimal(rate))
            .build());
  }

  private AccountSummaryResponse createLoanWithoutRate(String token, String principal) {
    return create(
        token,
        AccountRequests.account("Car Loan", "LOAN", "CHF")
            .originalPrincipal(new BigDecimal(principal))
            .build());
  }

  private AccountSummaryResponse create(String token, Object request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID shippedCategory(String code) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM category WHERE workspace_id IS NULL AND code = ?")) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return (UUID) resultSet.getObject(1);
      }
    }
  }

  // Workspace B, set up directly: this instance's API only ever bootstraps one workspace. V19's
  // trigger gives it the personal-assets institution its account needs.
  private UUID accountInAnotherWorkspace() throws Exception {
    UUID workspace = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", workspace);
    execute(
        "INSERT INTO account (id, workspace_id, financial_institution_id, account_type, name,"
            + " native_currency) SELECT ?, ?, id, 'CASH', 'B checking', 'CHF'"
            + " FROM financial_institution WHERE workspace_id = ?",
        account,
        workspace,
        workspace);
    return account;
  }

  private long deniedRequestsFor(UUID entityId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM authorization_denial_log WHERE requested_entity_id = ?")) {
      statement.setObject(1, entityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
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
