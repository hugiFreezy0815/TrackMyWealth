package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CreateLoanRepaymentRequest;
import com.trackmywealth.backend.dto.LoanPaymentSplit;
import com.trackmywealth.backend.dto.LoanRepaymentSplitValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AmortisationScheduleEntry;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.LoanPaymentRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US-10-02: how {@link LoanRepaymentService} splits a payment into interest and principal - the
 * arithmetic of each rule, and which rule applies (stated, schedule, estimate, unknown), with the
 * repositories mocked. {@code LoanRepaymentControllerTest} covers the rows, cash flow and loan
 * value against PostgreSQL.
 */
class LoanRepaymentServiceTest {

  private static final LocalDate BOOKED = LocalDate.of(2026, 3, 31);
  private static final UUID ENTRY_ID = UUID.randomUUID();

  private final AmortisationScheduleService scheduleService =
      mock(AmortisationScheduleService.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final AccountMortgageRepository mortgageRepository =
      mock(AccountMortgageRepository.class);
  private final AccountLoanRepository loanRepository = mock(AccountLoanRepository.class);
  private final LoanRepaymentService service =
      new LoanRepaymentService(
          mock(AccountLookupService.class),
          mock(AccountRepository.class),
          mock(AccessControlService.class),
          mock(TransactionService.class),
          scheduleService,
          mock(LoanPaymentRepository.class),
          transactionRepository,
          mortgageRepository,
          loanRepository);

  private Account loan;

  @BeforeEach
  void setUp() {
    loan = mock(Account.class);
    when(loan.getId()).thenReturn(UUID.randomUUID());
  }

  // --- the arithmetic of each rule ---------------------------------------------------------------

  @Test
  void aStatedInterestIsRecordedAsGivenAndIsNoEstimate() {
    LoanPaymentSplit split = LoanRepaymentService.stated(amount("2000.00"), amount("800.00"));

    assertThat(split.splitSource()).isEqualTo(LoanRepaymentSplitValues.STATED);
    assertThat(split.interest()).isEqualByComparingTo("800.00");
    assertThat(split.principal()).isEqualByComparingTo("1200.00");
    assertThat(split.estimated()).isFalse();
    assertThat(split.known()).isTrue();
  }

  // V-14 (EPIC 27): CHF 2,000 where the schedule says 800 interest and 1,200 principal.
  @Test
  void aPaymentMatchingTheScheduleIsSplitAsTheScheduleSaysAndIsNoEstimate() {
    LoanPaymentSplit split =
        LoanRepaymentService.fromSchedule(
            amount("2000.00"), amount("800.00"), amount("2000.00"), ENTRY_ID);

    assertThat(split.splitSource()).isEqualTo(LoanRepaymentSplitValues.SCHEDULE);
    assertThat(split.interest()).isEqualByComparingTo("800.00");
    assertThat(split.principal()).isEqualByComparingTo("1200.00");
    assertThat(split.estimated()).isFalse();
    assertThat(split.scheduleEntryId()).isEqualTo(ENTRY_ID);
  }

  @Test
  void anExtraRepaymentGoesToPrincipalAndFlagsTheSplitAsAnEstimate() {
    LoanPaymentSplit split =
        LoanRepaymentService.fromSchedule(
            amount("2500.00"), amount("800.00"), amount("2000.00"), ENTRY_ID);

    assertThat(split.interest()).isEqualByComparingTo("800.00");
    assertThat(split.principal()).isEqualByComparingTo("1700.00");
    assertThat(split.estimated()).isTrue();
  }

  @Test
  void aPaymentSmallerThanTheScheduledInterestIsAllInterestNeverANegativePrincipal() {
    LoanPaymentSplit split =
        LoanRepaymentService.fromSchedule(
            amount("500.00"), amount("800.00"), amount("2000.00"), ENTRY_ID);

    assertThat(split.interest()).isEqualByComparingTo("500.00");
    assertThat(split.principal()).isEqualByComparingTo("0");
    assertThat(split.estimated()).isTrue();
  }

  @Test
  void theEstimateIsAMonthsInterestOnTheOutstandingBalanceRoundedToTheMinorUnit() {
    // 480,000 x 2% / 12 = 800.00
    LoanPaymentSplit split =
        LoanRepaymentService.estimated(amount("2000.00"), amount("480000"), amount("2"), "CHF");

    assertThat(split.splitSource()).isEqualTo(LoanRepaymentSplitValues.ESTIMATED);
    assertThat(split.interest()).isEqualByComparingTo("800.00");
    assertThat(split.principal()).isEqualByComparingTo("1200.00");
    assertThat(split.estimated()).isTrue();
  }

  @Test
  void theEstimateRoundsHalfUpToTheCurrencysOwnMinorUnit() {
    // 100,001 x 1.5% / 12 = 125.00125 -> CHF 125.00; JPY has no minor unit -> 125.
    assertThat(
            LoanRepaymentService.estimated(amount("1000"), amount("100001"), amount("1.5"), "CHF")
                .interest())
        .isEqualByComparingTo("125.00");
    // 100,004 x 1.5% / 12 = 125.005 -> 125.01
    assertThat(
            LoanRepaymentService.estimated(amount("1000"), amount("100004"), amount("1.5"), "CHF")
                .interest())
        .isEqualByComparingTo("125.01");
    assertThat(
            LoanRepaymentService.estimated(amount("1000"), amount("100004"), amount("1.5"), "JPY")
                .interest()
                .scale())
        .isZero();
  }

  @Test
  void theEstimatedInterestNeverExceedsThePayment() {
    LoanPaymentSplit split =
        LoanRepaymentService.estimated(amount("100.00"), amount("480000"), amount("2"), "CHF");

    assertThat(split.interest()).isEqualByComparingTo("100.00");
    assertThat(split.principal()).isEqualByComparingTo("0");
  }

  @Test
  void aLoanTheLedgerShowsRepaidOwesNoInterest() {
    LoanPaymentSplit split =
        LoanRepaymentService.estimated(amount("100.00"), amount("-50"), amount("2"), "CHF");

    assertThat(split.interest()).isEqualByComparingTo("0");
    assertThat(split.principal()).isEqualByComparingTo("100.00");
  }

  @Test
  void anUnknownSplitSetsNeitherPartAndIsFlagged() {
    LoanPaymentSplit split = LoanRepaymentService.unknown();

    assertThat(split.known()).isFalse();
    assertThat(split.interest()).isNull();
    assertThat(split.principal()).isNull();
    assertThat(split.estimated()).isTrue();
  }

  // --- which rule applies ------------------------------------------------------------------------

  @Test
  void aStatedInterestWinsOverTheSchedule() {
    LoanPaymentSplit split = service.split(loan, request("2000.00", "750.00"));

    assertThat(split.splitSource()).isEqualTo(LoanRepaymentSplitValues.STATED);
    verify(scheduleService, never()).entryForPayment(any(), any());
  }

  @Test
  void theScheduleEntryOfThePeriodWinsOverAnEstimate() {
    AmortisationScheduleEntry entry = mock(AmortisationScheduleEntry.class);
    when(entry.getId()).thenReturn(ENTRY_ID);
    when(entry.getInterestComponent()).thenReturn(amount("800.00"));
    when(entry.getInstalment()).thenReturn(amount("2000.00"));
    when(scheduleService.entryForPayment(loan.getId(), BOOKED)).thenReturn(Optional.of(entry));

    LoanPaymentSplit split = service.split(loan, request("2000.00", null));

    assertThat(split.splitSource()).isEqualTo(LoanRepaymentSplitValues.SCHEDULE);
    assertThat(split.scheduleEntryId()).isEqualTo(ENTRY_ID);
    verify(mortgageRepository, never()).findById(any());
  }

  @Test
  void withoutAnEntryTheMortgageRateEstimatesInterestOnWhatIsStillOwed() {
    AccountMortgage mortgage = mock(AccountMortgage.class);
    when(mortgage.getOriginalPrincipal()).thenReturn(amount("500000"));
    when(mortgage.getInterestRatePercent()).thenReturn(amount("2"));
    when(mortgageRepository.findById(loan.getId())).thenReturn(Optional.of(mortgage));
    // 20,000 already repaid on the ledger by the booking date: 480,000 x 2% / 12 = 800.
    when(transactionRepository.sumAmountByAccountIdAsOf(loan.getId(), BOOKED))
        .thenReturn(Optional.of(amount("20000")));

    LoanPaymentSplit split = service.split(loan, request("2000.00", null));

    assertThat(split.splitSource()).isEqualTo(LoanRepaymentSplitValues.ESTIMATED);
    assertThat(split.interest()).isEqualByComparingTo("800.00");
    assertThat(split.principal()).isEqualByComparingTo("1200.00");
  }

  @Test
  void aLoanWithoutARateAndWithoutAnEntryHasAnUnknownSplit() {
    AccountLoan accountLoan = mock(AccountLoan.class);
    when(accountLoan.getOriginalPrincipal()).thenReturn(amount("20000"));
    when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(accountLoan));

    LoanPaymentSplit split = service.split(loan, request("500.00", null));

    assertThat(split.splitSource()).isEqualTo(LoanRepaymentSplitValues.UNKNOWN);
  }

  private static CreateLoanRepaymentRequest request(String amount, String interestAmount) {
    return new CreateLoanRepaymentRequest(
        UUID.randomUUID(),
        BOOKED,
        new BigDecimal(amount),
        "CHF",
        interestAmount == null ? null : new BigDecimal(interestAmount),
        null,
        null,
        null);
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value);
  }

  // --- which rows are a loan payment's interest --------------------------------------------------

  @Test
  void anExpenseNamingItsLoanIsTheInterestOfALoanPayment() {
    Transaction interest = expense(UUID.randomUUID(), false);

    assertThat(TransactionService.isLoanInterest(interest)).isTrue();
    assertThat(TransactionService.isLoanPaymentRow(interest)).isTrue();
  }

  @Test
  void aCardPaymentConfirmedAsASettlementIsNoLoanInterestThoughItNamesTheCard() {
    // SettlementDetectionService#applyFlags points the payment at the card and flags it internal.
    Transaction settled = expense(UUID.randomUUID(), true);

    assertThat(TransactionService.isLoanInterest(settled)).isFalse();
    assertThat(TransactionService.isLoanPaymentRow(settled)).isFalse();
  }

  @Test
  void anOrdinaryExpenseIsNoLoanInterest() {
    assertThat(TransactionService.isLoanInterest(expense(null, false))).isFalse();
  }

  private static Transaction expense(UUID counterpartyAccountId, boolean internalTransfer) {
    Transaction row = new Transaction();
    row.setTransactionType("EXPENSE");
    row.setCounterpartyAccountId(counterpartyAccountId);
    row.setInternalTransfer(internalTransfer);
    return row;
  }
}
