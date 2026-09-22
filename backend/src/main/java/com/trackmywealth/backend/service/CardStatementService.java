package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CardStatementResponse;
import com.trackmywealth.backend.dto.SetStatementConfigRequest;
import com.trackmywealth.backend.dto.StatementConfigResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-09-03: a card's statement cycle (FR-CC-008/009) - configuring {@code statement_day}/{@code
 * due_date_offset_days}, and computing the current period's boundaries, closing balance, due date
 * and paid status from them. Transaction-date attribution itself (FR-CC-009: a purchase counts in
 * the month it was made, never the month it is settled) needs no code here - {@link
 * CashFlowService} already sums by booking date, never settlement date.
 *
 * <p><b>"Current period" means the most recently closed cycle as of today</b>, not the
 * still-accumulating one: that is the reading under which "closing balance" and "due date" are both
 * concrete rather than a running, not-yet-final projection - the same thing a cardholder means by
 * "my current statement" in online banking. A statement closes on {@code statementDay}, or the
 * month's last day if it is shorter (e.g. {@code statementDay = 31} in February closes on the
 * 28th/29th). Per the story's own edge case ("a purchase whose booking date falls exactly on the
 * statement boundary" must be deterministic): <b>the closing day belongs to the period ending on
 * it</b>, matching ordinary bank statement convention.
 *
 * <p><b>Paid status</b> is named in the story's narrative sentence but not tested by its Given/
 * When/Then, so this is a documented reading rather than the letter of the acceptance criteria: a
 * statement with a zero-or-credit closing balance is trivially paid; otherwise it is paid when a
 * {@code CONFIRMED} {@link SettlementMatch} pays this card exactly {@code closingBalance}, booked
 * on or between {@code periodEnd} and {@code dueDate}. A late or partial payment outside that
 * window is not reflected - EPIC 10's fuller card-statement history can revisit this.
 *
 * <p>{@code SettlementMatch} has no link to a specific statement period, so <b>the paid-check
 * window is capped one day before the next period's own close</b>, never {@code dueDate} alone:
 * without that cap, a {@code dueDateOffsetDays} at or beyond a cycle's length would let two
 * consecutive periods' windows overlap, and a single payment (or two periods that coincidentally
 * close with the same balance) could satisfy both - marking a still-unpaid statement paid. Capping
 * keeps every period's window disjoint from its neighbours' by construction, independent of the
 * configured offset. The reported {@code dueDate} itself is never altered by this - only what
 * counts as evidence of payment is.
 */
@Service
public class CardStatementService {

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountCreditCardRepository accountCreditCardRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final AccountValuationService accountValuationService;
  private final BusinessDateService businessDateService;

  public CardStatementService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountCreditCardRepository accountCreditCardRepository,
      SettlementMatchRepository settlementMatchRepository,
      AccountValuationService accountValuationService,
      BusinessDateService businessDateService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.accountValuationService = accountValuationService;
    this.businessDateService = businessDateService;
  }

  /** Sets (or, for a {@code null} field, clears) this card's statement-cycle configuration. */
  @Transactional
  public StatementConfigResponse setStatementConfig(
      UUID cardAccountId, SetStatementConfigRequest request, AuthenticatedUserPrincipal actor) {
    Account card = requireCard(cardAccountId, actor, AccessLevelValues.EDIT);
    if (request.statementDay() != null
        && (request.statementDay() < 1 || request.statementDay() > 31)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "statementDay must be between 1 and 31.");
    }
    if (request.dueDateOffsetDays() != null && request.dueDateOffsetDays() < 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "dueDateOffsetDays must not be negative.");
    }
    AccountCreditCard extension = extensionOf(card);
    extension.setStatementDay(toShort(request.statementDay(), "statementDay"));
    extension.setDueDateOffsetDays(toShort(request.dueDateOffsetDays(), "dueDateOffsetDays"));
    accountCreditCardRepository.saveAndFlush(extension);
    return new StatementConfigResponse(
        cardAccountId, request.statementDay(), request.dueDateOffsetDays());
  }

  @Transactional(readOnly = true)
  public StatementConfigResponse getStatementConfig(
      UUID cardAccountId, AuthenticatedUserPrincipal actor) {
    Account card = requireCard(cardAccountId, actor, AccessLevelValues.BALANCE_ONLY);
    AccountCreditCard extension = extensionOf(card);
    return new StatementConfigResponse(
        cardAccountId,
        toInteger(extension.getStatementDay()),
        toInteger(extension.getDueDateOffsetDays()));
  }

  // account_credit_card.statement_day/due_date_offset_days are SMALLINT (V5), so the entity holds
  // a Short - the REST API stays plain int (its statementDay range check already keeps it inside
  // Short's range; dueDateOffsetDays has no upper bound, so that is checked here instead of letting
  // it silently truncate).
  private static Short toShort(Integer value, String fieldName) {
    if (value == null) {
      return null;
    }
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, fieldName + " is out of range.");
    }
    return value.shortValue();
  }

  private static Integer toInteger(Short value) {
    return value == null ? null : value.intValue();
  }

  /**
   * The card's current statement (see class Javadoc for what "current" means): its period, closing
   * balance, due date and paid status. Requires both {@code statementDay} and {@code
   * dueDateOffsetDays} to be configured first.
   */
  @Transactional(readOnly = true)
  public CardStatementResponse getCurrentStatement(
      UUID cardAccountId, AuthenticatedUserPrincipal actor) {
    Account card = requireCard(cardAccountId, actor, AccessLevelValues.BALANCE_ONLY);
    AccountCreditCard extension = extensionOf(card);
    Short statementDay = extension.getStatementDay();
    Short dueDateOffsetDays = extension.getDueDateOffsetDays();
    if (statementDay == null || dueDateOffsetDays == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "This card's statement cycle is not configured (statementDay and dueDateOffsetDays are"
              + " both required).");
    }

    LocalDate periodEnd = closingOnOrBefore(businessDateService.today(), statementDay);
    LocalDate periodStart =
        closingDateFor(YearMonth.from(periodEnd).minusMonths(1), statementDay).plusDays(1);
    LocalDate dueDate = periodEnd.plusDays(dueDateOffsetDays);

    AccountValuation valuation =
        accountValuationService.valueIn(card, card.getNativeCurrency(), periodEnd);
    BigDecimal closingBalance = valuation.value();
    boolean paid = isPaid(cardAccountId, closingBalance, periodEnd, dueDate, statementDay);

    return new CardStatementResponse(
        cardAccountId,
        periodStart,
        periodEnd,
        dueDate,
        closingBalance,
        card.getNativeCurrency(),
        paid);
  }

  // The most recent closing date that is on or before `today` - this cycle's own close if today has
  // reached it, otherwise the previous cycle's (the closing day belongs to the period ending on
  // it).
  private static LocalDate closingOnOrBefore(LocalDate today, int statementDay) {
    YearMonth thisMonth = YearMonth.from(today);
    LocalDate thisMonthClose = closingDateFor(thisMonth, statementDay);
    return today.isBefore(thisMonthClose)
        ? closingDateFor(thisMonth.minusMonths(1), statementDay)
        : thisMonthClose;
  }

  // statementDay itself, or the month's last day when the month is shorter than that.
  private static LocalDate closingDateFor(YearMonth month, int statementDay) {
    return month.atDay(Math.min(statementDay, month.lengthOfMonth()));
  }

  private boolean isPaid(
      UUID cardAccountId,
      BigDecimal closingBalance,
      LocalDate periodEnd,
      LocalDate dueDate,
      int statementDay) {
    if (closingBalance.signum() <= 0) {
      return true; // nothing owed, or the card is in credit
    }
    // Capped one day before the next period's own close (see class Javadoc): keeps this window
    // disjoint from the next period's, so a payment can never be read as evidence for both.
    LocalDate nextClose = closingDateFor(YearMonth.from(periodEnd).plusMonths(1), statementDay);
    LocalDate windowEnd = dueDate.isBefore(nextClose) ? dueDate : nextClose.minusDays(1);
    return settlementMatchRepository
        .findConfirmedByCardAccountIdAndPaymentBookingDateBetween(
            cardAccountId, periodEnd, windowEnd)
        .stream()
        .anyMatch(
            m -> m.getPaymentTransaction().getAmount().negate().compareTo(closingBalance) == 0);
  }

  private Account requireCard(UUID cardAccountId, AuthenticatedUserPrincipal actor, String level) {
    Account card = accountLookupService.findAccountOrThrow(cardAccountId);
    accessControlService.requireAccountAccess(actor, card, level);
    if (!card.isHasStatementCycle()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "Only a credit-card account has a statement cycle.");
    }
    return card;
  }

  private AccountCreditCard extensionOf(Account card) {
    return accountCreditCardRepository
        .findById(card.getId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Credit-card details are missing."));
  }
}
