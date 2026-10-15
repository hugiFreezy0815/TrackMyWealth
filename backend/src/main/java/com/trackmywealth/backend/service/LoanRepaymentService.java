package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateLoanRepaymentRequest;
import com.trackmywealth.backend.dto.LoanPaymentPlan;
import com.trackmywealth.backend.dto.LoanPaymentSplit;
import com.trackmywealth.backend.dto.LoanRepaymentResponse;
import com.trackmywealth.backend.dto.LoanRepaymentSplitValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AmortisationScheduleEntry;
import com.trackmywealth.backend.entity.LoanPayment;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.LoanPaymentRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-10-02/FR-CF-006: a loan or mortgage payment, split into its interest - a real expense - and
 * its principal - a balance-sheet movement that is never spending - so spending and the savings
 * rate are not distorted. One payment from an ordinary account to a loan writes up to three ledger
 * rows ({@link TransactionService#recordLoanPayment}) and one {@code loan_payment} row recording
 * the split.
 *
 * <p><b>The split</b>, in priority order (see {@link #split}):
 *
 * <ol>
 *   <li><b>STATED</b> - {@code interestAmount} in the request: what a statement booking interest
 *       and instalment separately says, recorded as given, not an estimate;
 *   <li><b>SCHEDULE</b> - the loan's amortisation schedule entry for the payment's month ({@link
 *       AmortisationScheduleService#entryForPayment}): its interest (at most the payment), the rest
 *       principal - flagged an estimate when the payment differs from the entry's instalment (an
 *       extra repayment, a missed part);
 *   <li><b>ESTIMATED</b> - without an entry, a month's interest on the outstanding balance, {@code
 *       balance x interest_rate_percent / 100 / 12}, rounded to the currency's minor unit, at most
 *       the payment; the balance is the original principal less what the loan's ledger shows repaid
 *       up to the booking date (as {@link AccountValuationService} values it);
 *   <li><b>UNKNOWN</b> - without a rate either, nothing is guessed (PR-011): the whole payment is
 *       recorded as one principal pair that is not an internal transfer, so cash flow keeps it
 *       pending review until the member removes it and records it again with the split.
 * </ol>
 *
 * <p><b>Rules</b>, in the order they are checked: {@code EDIT} on the loan and on the paying
 * account, another account of the same workspace; then the loan's row is locked, so payments to one
 * loan are recorded one after another - an estimate always reads the balance the previous payment
 * left, and a concurrent retry waits for the first and replays it; a retry with the same {@code
 * externalId} replays the first payment, as {@code POST /transactions} does - before the remaining
 * checks, so an archived loan does not turn a successful request into an error on retry; the loan
 * has {@code hasAmortisation} (422) and is active (409 {@code ACCOUNT_ARCHIVED}); the paying
 * account takes an {@code EXPENSE} (an ordinary cash account: not a card, loan, pension or depot -
 * the ledger's own rule, {@link TransactionService#requireRecordable}); the amount is positive, in
 * the currency both accounts share; a stated interest lies within [0, amount].
 */
@Service
public class LoanRepaymentService {

  private static final String EXPENSE = "EXPENSE";
  private static final String ACTIVE = "ACTIVE";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
  // Intermediate precision of the monthly interest before it is rounded to the minor unit.
  private static final int RATE_SCALE = 10;

  private final AccountLookupService accountLookupService;
  private final AccountRepository accountRepository;
  private final AccessControlService accessControlService;
  private final TransactionService transactionService;
  private final AmortisationScheduleService amortisationScheduleService;
  private final LoanPaymentRepository loanPaymentRepository;
  private final TransactionRepository transactionRepository;
  private final AccountMortgageRepository accountMortgageRepository;
  private final AccountLoanRepository accountLoanRepository;

  public LoanRepaymentService(
      AccountLookupService accountLookupService,
      AccountRepository accountRepository,
      AccessControlService accessControlService,
      TransactionService transactionService,
      AmortisationScheduleService amortisationScheduleService,
      LoanPaymentRepository loanPaymentRepository,
      TransactionRepository transactionRepository,
      AccountMortgageRepository accountMortgageRepository,
      AccountLoanRepository accountLoanRepository) {
    this.accountLookupService = accountLookupService;
    this.accountRepository = accountRepository;
    this.accessControlService = accessControlService;
    this.transactionService = transactionService;
    this.amortisationScheduleService = amortisationScheduleService;
    this.loanPaymentRepository = loanPaymentRepository;
    this.transactionRepository = transactionRepository;
    this.accountMortgageRepository = accountMortgageRepository;
    this.accountLoanRepository = accountLoanRepository;
  }

  @Transactional
  public LoanRepaymentResponse record(
      UUID loanAccountId, CreateLoanRepaymentRequest request, AuthenticatedUserPrincipal actor) {
    Account loan = accountLookupService.findAccountOrThrow(loanAccountId, actor);
    accessControlService.requireAccountAccess(actor, loan, AccessLevelValues.EDIT);
    // Check the paying account before replaying an idempotency key: the key must never
    // be an oracle for a payment on an account whose access has since been revoked.
    Account paying = findPayingAccount(loan, request.payingAccountId(), actor);
    // Only after access is checked: a caller who may not edit the loan never holds its lock.
    accountRepository.findByIdForUpdate(loan.getId());
    Optional<LoanPayment> replay = findReplay(loan, request);
    if (replay.isPresent()) {
      return toResponses(List.of(replay.get())).get(0);
    }
    if (!loan.isHasAmortisation()) {
      throw unprocessable("Only a loan or mortgage takes a repayment.");
    }
    if (!ACTIVE.equals(loan.getStatus())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.ACCOUNT_ARCHIVED,
          "Cannot record a repayment on an archived loan.");
    }
    validateAmounts(loan, paying, request);
    // The interest row is an EXPENSE on the paying account; the ledger's own rules decide whether
    // that account takes one (active, holds transactions, an ordinary cash account).
    transactionService.requireRecordable(
        paying,
        TransactionService.loanPaymentRequest(
            EXPENSE,
            request.bookingDate(),
            request.amount().negate(),
            request.currency(),
            null,
            null,
            null));

    LoanPaymentSplit split = split(loan, request);
    LoanPaymentPlan plan =
        new LoanPaymentPlan(
            request.bookingDate(),
            request.currency(),
            split.known() ? split.principal() : request.amount(),
            split.known() ? split.interest() : null,
            split.known(),
            request.merchantDescription(),
            request.notes());
    List<Transaction> rows =
        transactionService.recordLoanPayment(paying, loan, plan, TransactionService.MANUAL, actor);

    LoanPayment payment = new LoanPayment();
    payment.setWorkspace(loan.getWorkspace());
    payment.setLoanAccount(loan);
    payment.setPayingAccount(paying);
    payment.setBookingDate(request.bookingDate());
    payment.setCurrency(request.currency());
    payment.setTotalAmount(request.amount());
    payment.setInterestAmount(split.interest());
    payment.setPrincipalAmount(split.principal());
    payment.setSplitSource(split.splitSource());
    payment.setSplitEstimated(split.estimated());
    payment.setAmortisationScheduleEntryId(split.scheduleEntryId());
    payment.setPrincipalTransactionId(
        rows.stream()
            .filter(row -> row.getAccount().getId().equals(paying.getId()))
            .filter(row -> TransactionService.DEBT_REPAYMENT.equals(row.getTransactionType()))
            .map(Transaction::getId)
            .findFirst()
            .orElse(null));
    payment.setInterestTransactionId(
        rows.stream()
            .filter(TransactionService::isLoanInterest)
            .map(Transaction::getId)
            .findFirst()
            .orElse(null));
    payment.setExternalId(request.externalId());
    payment.setCreatedBy(actor.userId());
    // flush, not a plain save: a concurrent retry with the same externalId fails
    // uq_loan_payment_external_id here, as a 409 from GlobalExceptionHandler, and the caller's next
    // retry replays the winner.
    LoanPayment saved = loanPaymentRepository.saveAndFlush(payment);
    return toResponse(saved, rows);
  }

  /** The loan's payments, newest booking first. */
  @Transactional(readOnly = true)
  public List<LoanRepaymentResponse> list(UUID loanAccountId, AuthenticatedUserPrincipal actor) {
    Account loan = accountLookupService.findAccountOrThrow(loanAccountId, actor);
    accessControlService.requireAccountAccess(actor, loan, AccessLevelValues.READ);
    List<LoanPayment> payments =
        loanPaymentRepository.findByLoanAccountIdOrderByBookingDateDescCreatedAtDesc(loan.getId());
    if (payments.isEmpty()) {
      return List.of();
    }
    // Loan READ does not imply READ on the paying account: a workspace member might be allowed
    // to see a shared mortgage but not the owner's cash account. Never serialize its transaction
    // details, notes or id through this endpoint without its own object-level READ permission.
    UUID memberId = accessControlService.requireActingMember(actor);
    Set<UUID> readablePayerIds =
        accessControlService
            .accountsWithAccess(
                memberId,
                payments.stream().map(LoanPayment::getPayingAccount).distinct().toList(),
                AccessLevelValues.READ)
            .stream()
            .map(Account::getId)
            .collect(Collectors.toSet());
    return toResponses(
        payments.stream()
            .filter(payment -> readablePayerIds.contains(payment.getPayingAccount().getId()))
            .toList());
  }

  /**
   * The split for {@code request}, see the class comment. Package-private for the unit tests of
   * each rule; the arithmetic itself is in the static methods below.
   */
  LoanPaymentSplit split(Account loan, CreateLoanRepaymentRequest request) {
    BigDecimal total = request.amount();
    if (request.interestAmount() != null) {
      return stated(total, request.interestAmount());
    }
    Optional<AmortisationScheduleEntry> entry =
        amortisationScheduleService.entryForPayment(loan.getId(), request.bookingDate());
    if (entry.isPresent()) {
      return fromSchedule(
          total,
          entry.get().getInterestComponent(),
          entry.get().getInstalment(),
          entry.get().getId());
    }
    Optional<BigDecimal> principal = originalPrincipal(loan);
    Optional<BigDecimal> rate = interestRatePercent(loan);
    if (principal.isEmpty() || rate.isEmpty()) {
      return unknown();
    }
    // A prior payment with UNKNOWN split has no known principal. Do not treat its full
    // loan-side leg as principal when estimating interest on a subsequent payment.
    if (transactionRepository.existsUnresolvedLoanRepaymentAsOf(
        loan.getId(), request.bookingDate())) {
      return unknown();
    }
    BigDecimal repaid =
        transactionRepository
            .sumAmountByAccountIdAsOf(loan.getId(), request.bookingDate())
            .orElse(BigDecimal.ZERO);
    return estimated(total, principal.get().subtract(repaid), rate.get(), request.currency());
  }

  /** STATED: the interest a source gives; the rest is principal. Not an estimate. */
  static LoanPaymentSplit stated(BigDecimal total, BigDecimal interest) {
    return new LoanPaymentSplit(
        LoanRepaymentSplitValues.STATED, interest, total.subtract(interest), false, null);
  }

  /**
   * SCHEDULE: the entry's interest, at most the payment, and the rest principal - so an extra
   * repayment goes to principal, and a payment smaller than the interest is all interest. An
   * estimate unless the payment is exactly the entry's instalment.
   */
  static LoanPaymentSplit fromSchedule(
      BigDecimal total, BigDecimal scheduledInterest, BigDecimal instalment, UUID entryId) {
    BigDecimal interest = scheduledInterest.min(total);
    return new LoanPaymentSplit(
        LoanRepaymentSplitValues.SCHEDULE,
        interest,
        total.subtract(interest),
        instalment.compareTo(total) != 0,
        entryId);
  }

  /**
   * ESTIMATED: a month's interest on {@code outstanding} at {@code ratePercent} a year, rounded
   * half up to the currency's minor unit and kept within [0, total]; an outstanding balance of zero
   * or less (repaid in full by the ledger's account) owes no interest.
   */
  static LoanPaymentSplit estimated(
      BigDecimal total, BigDecimal outstanding, BigDecimal ratePercent, String currency) {
    BigDecimal monthly =
        outstanding.signum() <= 0 || ratePercent.signum() <= 0
            ? BigDecimal.ZERO
            : outstanding
                .multiply(ratePercent)
                .divide(HUNDRED.multiply(MONTHS_PER_YEAR), RATE_SCALE, RoundingMode.HALF_UP);
    BigDecimal interest =
        monthly.setScale(TransactionService.minorDigits(currency), RoundingMode.HALF_UP).min(total);
    return new LoanPaymentSplit(
        LoanRepaymentSplitValues.ESTIMATED, interest, total.subtract(interest), true, null);
  }

  /** UNKNOWN: nothing to split by - neither part is set, and the payment is flagged. */
  static LoanPaymentSplit unknown() {
    return new LoanPaymentSplit(LoanRepaymentSplitValues.UNKNOWN, null, null, true, null);
  }

  private Optional<BigDecimal> originalPrincipal(Account loan) {
    return accountMortgageRepository
        .findById(loan.getId())
        .map(AccountMortgage::getOriginalPrincipal)
        .or(
            () ->
                accountLoanRepository
                    .findById(loan.getId())
                    .map(AccountLoan::getOriginalPrincipal));
  }

  // A mortgage requires its rate (AccountService); a loan's is optional.
  private Optional<BigDecimal> interestRatePercent(Account loan) {
    Optional<AccountMortgage> mortgage = accountMortgageRepository.findById(loan.getId());
    if (mortgage.isPresent()) {
      return Optional.ofNullable(mortgage.get().getInterestRatePercent());
    }
    return accountLoanRepository.findById(loan.getId()).map(AccountLoan::getInterestRatePercent);
  }

  // The account the money leaves: as a two-sided transfer's counterparty is checked
  // (TransferRecordingService#findCounterparty), it must be another account of the same workspace
  // that the member may edit, since a row is written there too.
  private Account findPayingAccount(
      Account loan, UUID payingAccountId, AuthenticatedUserPrincipal actor) {
    Account paying = accountLookupService.findAccountOrThrow(payingAccountId, actor);
    if (!paying.getWorkspace().getId().equals(loan.getWorkspace().getId())) {
      throw accessControlService.denyAsNotFound(actor, "Account", payingAccountId);
    }
    accessControlService.requireAccountAccess(actor, paying, AccessLevelValues.EDIT);
    if (paying.getId().equals(loan.getId())) {
      throw unprocessable("A loan cannot pay itself: payingAccountId must be another account.");
    }
    return paying;
  }

  private static void validateAmounts(
      Account loan, Account paying, CreateLoanRepaymentRequest request) {
    if (request.amount().signum() <= 0) {
      throw unprocessable("amount must be positive: what was paid (e.g. 2000.00).");
    }
    if (!loan.getNativeCurrency().equals(request.currency())) {
      throw unprocessable(
          "currency must be the loan's own currency (" + loan.getNativeCurrency() + ").");
    }
    if (!paying.getNativeCurrency().equals(loan.getNativeCurrency())) {
      throw unprocessable(
          "The paying account is in "
              + paying.getNativeCurrency()
              + " and the loan in "
              + loan.getNativeCurrency()
              + "; a repayment between two currencies cannot be recorded yet.");
    }
    BigDecimal interest = request.interestAmount();
    if (interest != null && (interest.signum() < 0 || interest.compareTo(request.amount()) > 0)) {
      throw unprocessable(
          "interestAmount must lie between 0 and amount: the interest part of what was paid.");
    }
  }

  // Same rule as POST /transactions (TransactionService#findReplay): the key must mean the same
  // payment, compared on what was paid, not on how it was described.
  private Optional<LoanPayment> findReplay(Account loan, CreateLoanRepaymentRequest request) {
    if (request.externalId() == null) {
      return Optional.empty();
    }
    Optional<LoanPayment> existing =
        loanPaymentRepository.findByLoanAccountIdAndExternalId(loan.getId(), request.externalId());
    existing.ifPresent(
        payment -> {
          boolean samePayment =
              payment.getPayingAccount().getId().equals(request.payingAccountId())
                  && payment.getBookingDate().equals(request.bookingDate())
                  && payment.getTotalAmount().compareTo(request.amount()) == 0
                  && payment.getCurrency().equals(request.currency())
                  && sameStatedInterest(payment, request.interestAmount());
          if (!samePayment) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "externalId '"
                    + request.externalId()
                    + "' was already used for a different repayment on this loan.");
          }
        });
    return existing;
  }

  // Not stating the interest on a retry is not a different request; stating it must match a
  // stated split.
  private static boolean sameStatedInterest(LoanPayment payment, BigDecimal interestAmount) {
    return interestAmount == null
        || LoanRepaymentSplitValues.STATED.equals(payment.getSplitSource())
            && payment.getInterestAmount().compareTo(interestAmount) == 0;
  }

  // The rows each payment wrote, as they stand now: its outgoing principal leg and interest by id,
  // the incoming principal leg through the outgoing one. Two queries for any number of payments.
  private List<LoanRepaymentResponse> toResponses(List<LoanPayment> payments) {
    List<UUID> principalIds =
        payments.stream()
            .map(LoanPayment::getPrincipalTransactionId)
            .filter(Objects::nonNull)
            .toList();
    List<UUID> ownIds =
        payments.stream()
            .flatMap(
                payment ->
                    Stream.of(
                        payment.getPrincipalTransactionId(), payment.getInterestTransactionId()))
            .filter(Objects::nonNull)
            .toList();
    Map<UUID, Transaction> byId = new HashMap<>();
    transactionRepository.findAllById(ownIds).forEach(row -> byId.put(row.getId(), row));
    Map<UUID, List<Transaction>> incomingByOutgoing =
        principalIds.isEmpty()
            ? Map.of()
            : transactionRepository.findByRelatedTransactionIdIn(principalIds).stream()
                .filter(row -> TransactionService.DEBT_REPAYMENT.equals(row.getTransactionType()))
                .collect(Collectors.groupingBy(Transaction::getRelatedTransactionId));
    return payments.stream()
        .map(
            payment -> {
              List<Transaction> rows = new ArrayList<>(3);
              Optional.ofNullable(payment.getPrincipalTransactionId())
                  .map(byId::get)
                  .ifPresent(
                      outgoing -> {
                        rows.add(outgoing);
                        rows.addAll(incomingByOutgoing.getOrDefault(outgoing.getId(), List.of()));
                      });
              Optional.ofNullable(payment.getInterestTransactionId())
                  .map(byId::get)
                  .ifPresent(rows::add);
              return toResponse(payment, rows);
            })
        .toList();
  }

  private LoanRepaymentResponse toResponse(LoanPayment payment, Collection<Transaction> rows) {
    return new LoanRepaymentResponse(
        payment.getId(),
        payment.getLoanAccount().getId(),
        payment.getPayingAccount().getId(),
        payment.getBookingDate(),
        payment.getTotalAmount(),
        payment.getCurrency(),
        payment.getInterestAmount(),
        payment.getPrincipalAmount(),
        payment.getSplitSource(),
        payment.isSplitEstimated(),
        payment.getAmortisationScheduleEntryId(),
        payment.getExternalId(),
        transactionService.toResponses(List.copyOf(rows)),
        payment.getCreatedAt());
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }
}
