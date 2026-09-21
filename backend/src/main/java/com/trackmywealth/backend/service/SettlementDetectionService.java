package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-09-02: finds the payment from a card's settlement-source account that settles the card, and
 * links it so it is never counted as a second expense (FR-CC-004/005/007, FR-CF-001/005, DM-05).
 *
 * <p>The matching itself is an idempotent, resumable operation over the ledger (FR-JOB-002): {@link
 * #detectForCard} can run any number of times - after each relevant write, after the settlement
 * source is set, or on demand - and only ever adds what is missing. It is deliberately not a
 * scheduled job yet (EPIC 30); a Quartz wrapper can call it unchanged.
 *
 * <h2>What matches</h2>
 *
 * A candidate <em>payment</em> is a negative, non-voided {@code WITHDRAWAL}/{@code SETTLEMENT} row
 * on the card's settlement-source account; a candidate <em>card credit</em> is a positive,
 * non-voided {@code SETTLEMENT} row on the card. Both must be in the card's currency
 * (cross-currency settlement is US-09-04's). A payment and a credit pair up when their amounts are
 * exactly equal and their booking dates are at most {@value #WINDOW_DAYS} days apart.
 *
 * <ul>
 *   <li><b>Unambiguous pair</b> - the payment has exactly one credit candidate and that credit has
 *       exactly one payment candidate: applied automatically ({@code CONFIRMED}, decided by the
 *       system).
 *   <li><b>Ambiguous</b> - anything else that pairs (two identical payments, two identical
 *       credits): every pair is only {@code PROPOSED}, never applied on a guess (US-10-01's rule
 *       for low-confidence matches).
 *   <li><b>One-sided</b> - a payment with no credit candidate at all whose amount equals what the
 *       card owed on the payment date: {@code PROPOSED} as a {@code BALANCE_EQUALS_PAYMENT}
 *       candidate (FR-CF-005). It is never auto-applied: an ordinary debit can equal the balance by
 *       coincidence. When the card-side credit is recorded later, the same row becomes the pair.
 * </ul>
 *
 * <p>Matching never re-types a leg: {@code transaction_type} is frozen by {@code
 * trg_transaction_append_only}, so a match only sets {@code is_internal_transfer} and {@code
 * counterparty_account_id} - both mutable, which is also what lets a rejected match revert cleanly.
 * A rejected pair (or a payment whose one-sided candidate was rejected) is remembered in {@code
 * settlement_match} and never proposed again.
 *
 * <p>Runs as the system, not as the caller: whether a payment is a settlement must not depend on
 * who happened to record it. RLS still confines every read and write to the one workspace, and
 * nothing here returns the linked account to a caller who could not see it.
 */
@Service
public class SettlementDetectionService {

  /** Booking dates further apart than this never pair (payment vs card-side credit). */
  static final int WINDOW_DAYS = 5;

  private static final Set<String> PAYMENT_TYPES = Set.of("WITHDRAWAL", "SETTLEMENT");

  private final AccountCreditCardRepository accountCreditCardRepository;
  private final AccountRepository accountRepository;
  private final TransactionRepository transactionRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final Clock clock;

  public SettlementDetectionService(
      AccountCreditCardRepository accountCreditCardRepository,
      AccountRepository accountRepository,
      TransactionRepository transactionRepository,
      SettlementMatchRepository settlementMatchRepository,
      Clock clock) {
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.clock = clock;
  }

  /**
   * Re-runs matching for every card a write to {@code written} can affect: the card itself if it is
   * one, and every card that names {@code written} as its settlement source.
   */
  @Transactional
  public void detectAfterWrite(Account written) {
    Set<UUID> cardIds = new LinkedHashSet<>();
    if (written.isHasStatementCycle()) {
      cardIds.add(written.getId());
    } else {
      accountCreditCardRepository
          .findBySettlementSourceAccountId(written.getId())
          .forEach(card -> cardIds.add(card.getAccountId()));
    }
    cardIds.forEach(this::detectForCard);
  }

  /** Runs matching for one card. A card with no (usable) settlement source has nothing to do. */
  @Transactional
  public void detectForCard(UUID cardAccountId) {
    // Serialise matching per card: two writes racing here would each read the same "nothing
    // proposed yet" state and both insert the same proposal, and the loser's whole write would be
    // rolled back by the unique index. Under READ COMMITTED the second run, once it gets the lock,
    // sees what the first committed and adds nothing.
    accountRepository.findByIdForUpdate(cardAccountId);
    AccountCreditCard card = accountCreditCardRepository.findById(cardAccountId).orElse(null);
    if (card == null || card.getSettlementSourceAccountId() == null) {
      return;
    }
    Account cardAccount = card.getAccount();
    Account source = accountRepository.findById(card.getSettlementSourceAccountId()).orElse(null);
    // Cross-currency settlement needs the payment converted at the issuer's rate: US-09-04.
    if (source == null || !source.getNativeCurrency().equals(cardAccount.getNativeCurrency())) {
      return;
    }
    String currency = cardAccount.getNativeCurrency();

    List<Transaction> payments =
        transactionRepository.findSettlementPaymentCandidates(
            source.getId(), currency, PAYMENT_TYPES);
    List<Transaction> credits =
        transactionRepository.findUnmatchedCardCredits(cardAccountId, currency);
    if (payments.isEmpty()) {
      return;
    }

    Map<UUID, List<SettlementMatch>> known =
        byPayment(settlementMatchRepository.findByCardAccountId(cardAccountId));
    Map<UUID, List<Transaction>> creditsByPayment = new HashMap<>();
    Map<UUID, List<Transaction>> paymentsByCredit = new HashMap<>();
    for (Transaction payment : payments) {
      for (Transaction credit : credits) {
        if (pairs(payment, credit) && !isRejectedPair(known, payment, credit)) {
          creditsByPayment.computeIfAbsent(payment.getId(), k -> new ArrayList<>()).add(credit);
          paymentsByCredit.computeIfAbsent(credit.getId(), k -> new ArrayList<>()).add(payment);
        }
      }
    }

    for (Transaction payment : payments) {
      List<Transaction> candidates = creditsByPayment.getOrDefault(payment.getId(), List.of());
      if (candidates.isEmpty()) {
        proposeOneSided(cardAccount, payment, known);
      } else if (candidates.size() == 1
          && paymentsByCredit.get(candidates.get(0).getId()).size() == 1) {
        applyUnambiguousPair(cardAccount, payment, candidates.get(0), known);
      } else {
        proposeAmbiguousPairs(cardAccount, payment, candidates, known);
      }
    }
  }

  /**
   * Flags the legs of an applied match as an internal transfer (DM-05): the payment points at the
   * card, the card-side credit (when there is one) at the payment's account.
   */
  void applyFlags(SettlementMatch match) {
    Transaction payment = match.getPaymentTransaction();
    payment.setInternalTransfer(true);
    payment.setCounterpartyAccountId(match.getCardAccount().getId());
    Transaction credit = match.getCardTransaction();
    if (credit != null) {
      credit.setInternalTransfer(true);
      credit.setCounterpartyAccountId(payment.getAccount().getId());
    }
  }

  /**
   * Reverts both legs to ordinary transactions (FR-CF-004: "reverts to being classified normally").
   */
  void clearFlags(SettlementMatch match) {
    Transaction payment = match.getPaymentTransaction();
    payment.setInternalTransfer(false);
    payment.setCounterpartyAccountId(null);
    Transaction credit = match.getCardTransaction();
    if (credit != null) {
      credit.setInternalTransfer(false);
      credit.setCounterpartyAccountId(null);
    }
  }

  private void applyUnambiguousPair(
      Account cardAccount,
      Transaction payment,
      Transaction credit,
      Map<UUID, List<SettlementMatch>> known) {
    SettlementMatch existingPair = pair(known, payment, credit);
    if (existingPair != null) {
      // A PROPOSED pair already awaits a member's decision - the system does not overrule it later.
      return;
    }
    SettlementMatch oneSided = activeOneSided(known, payment);
    SettlementMatch match = oneSided != null ? oneSided : newMatch(cardAccount, payment);
    boolean alreadyDecided = SettlementMatchValues.CONFIRMED.equals(match.getStatus());
    match.setCardTransaction(credit);
    match.setMatchBasis(SettlementMatchValues.LEG_PAIR);
    match.setStatus(SettlementMatchValues.CONFIRMED);
    if (!alreadyDecided) {
      // Decided by the system: an exact, unique pair. A one-sided match a member already confirmed
      // keeps its decider - the second leg only completes it.
      match.setDecidedBy(null);
      match.setDecidedAt(OffsetDateTime.now(clock));
    }
    settlementMatchRepository.saveAndFlush(match);
    applyFlags(match);
  }

  private void proposeAmbiguousPairs(
      Account cardAccount,
      Transaction payment,
      List<Transaction> candidates,
      Map<UUID, List<SettlementMatch>> known) {
    if (activeOneSided(known, payment) != null
        && SettlementMatchValues.CONFIRMED.equals(activeOneSided(known, payment).getStatus())) {
      // A member already confirmed this payment on its own; two equal credits cannot be told apart,
      // so it stays as confirmed rather than being replaced by a guess.
      return;
    }
    for (Transaction credit : candidates) {
      if (pair(known, payment, credit) != null) {
        continue;
      }
      SettlementMatch proposal = newMatch(cardAccount, payment);
      proposal.setCardTransaction(credit);
      proposal.setMatchBasis(SettlementMatchValues.LEG_PAIR);
      proposal.setStatus(SettlementMatchValues.PROPOSED);
      settlementMatchRepository.saveAndFlush(proposal);
    }
  }

  private void proposeOneSided(
      Account cardAccount, Transaction payment, Map<UUID, List<SettlementMatch>> known) {
    if (activeOneSided(known, payment) != null
        || hasRejection(known, payment)
        || hasAnyPair(known, payment)) {
      return;
    }
    BigDecimal owedOnPaymentDate =
        transactionRepository
            .sumAmountByAccountIdAsOf(cardAccount.getId(), payment.getBookingDate())
            .orElse(BigDecimal.ZERO)
            .negate();
    // Only a positive balance can be settled, and only to the cent: a payment that merely resembles
    // the balance is not a candidate (an ordinary debit must not vanish from spending on a guess).
    if (owedOnPaymentDate.signum() > 0
        && owedOnPaymentDate.compareTo(payment.getAmount().negate()) == 0) {
      SettlementMatch proposal = newMatch(cardAccount, payment);
      proposal.setMatchBasis(SettlementMatchValues.BALANCE_EQUALS_PAYMENT);
      proposal.setStatus(SettlementMatchValues.PROPOSED);
      settlementMatchRepository.saveAndFlush(proposal);
    }
  }

  private SettlementMatch newMatch(Account cardAccount, Transaction payment) {
    SettlementMatch match = new SettlementMatch();
    match.setWorkspace(cardAccount.getWorkspace());
    match.setCardAccount(cardAccount);
    match.setPaymentTransaction(payment);
    return match;
  }

  private static boolean pairs(Transaction payment, Transaction credit) {
    return credit.getAmount().compareTo(payment.getAmount().negate()) == 0
        && Math.abs(ChronoUnit.DAYS.between(payment.getBookingDate(), credit.getBookingDate()))
            <= WINDOW_DAYS;
  }

  // What has already been decided or proposed for a payment, in any status. Plain static helpers
  // over one map, not a nested type: ArchitectureTest requires every class in this package to be
  // a *Service.

  private static Map<UUID, List<SettlementMatch>> byPayment(List<SettlementMatch> matches) {
    Map<UUID, List<SettlementMatch>> byPayment = new HashMap<>();
    for (SettlementMatch match : matches) {
      byPayment
          .computeIfAbsent(match.getPaymentTransaction().getId(), k -> new ArrayList<>())
          .add(match);
    }
    return byPayment;
  }

  private static List<SettlementMatch> forPayment(
      Map<UUID, List<SettlementMatch>> known, Transaction payment) {
    return known.getOrDefault(payment.getId(), List.of());
  }

  private static SettlementMatch pair(
      Map<UUID, List<SettlementMatch>> known, Transaction payment, Transaction credit) {
    return forPayment(known, payment).stream()
        .filter(m -> m.getCardTransaction() != null)
        .filter(m -> m.getCardTransaction().getId().equals(credit.getId()))
        .findFirst()
        .orElse(null);
  }

  private static boolean isRejectedPair(
      Map<UUID, List<SettlementMatch>> known, Transaction payment, Transaction credit) {
    SettlementMatch match = pair(known, payment, credit);
    return match != null && SettlementMatchValues.REJECTED.equals(match.getStatus());
  }

  /** The payment's one-sided match unless it was rejected (a rejected one is history only). */
  private static SettlementMatch activeOneSided(
      Map<UUID, List<SettlementMatch>> known, Transaction payment) {
    return forPayment(known, payment).stream()
        .filter(m -> m.getCardTransaction() == null)
        .filter(m -> !SettlementMatchValues.REJECTED.equals(m.getStatus()))
        .findFirst()
        .orElse(null);
  }

  private static boolean hasRejection(Map<UUID, List<SettlementMatch>> known, Transaction payment) {
    return forPayment(known, payment).stream()
        .anyMatch(m -> SettlementMatchValues.REJECTED.equals(m.getStatus()));
  }

  private static boolean hasAnyPair(Map<UUID, List<SettlementMatch>> known, Transaction payment) {
    return forPayment(known, payment).stream().anyMatch(m -> m.getCardTransaction() != null);
  }
}
