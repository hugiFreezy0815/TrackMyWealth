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
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * A candidate <em>payment</em> is a negative, non-voided {@code WITHDRAWAL}/{@code EXPENSE}/{@code
 * SETTLEMENT} row on the card's settlement-source account; a candidate <em>card credit</em> is a
 * positive, non-voided {@code SETTLEMENT} row on the card. Both must be in the card's currency
 * (cross-currency settlement - the payment itself in a different currency than the card - is not
 * built). A payment and a credit pair up when their amounts are exactly equal and their booking
 * dates are at most {@value #WINDOW_DAYS} days apart.
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
 * <p>Cost: a write costs a handful of queries however long the ledger is. Pairing looks only at the
 * card's few unmatched credits, each fetching just the payments of the same amount within {@value
 * #WINDOW_DAYS} days; the one-sided check is a single query that compares balances inside the
 * database, and for a write is limited to payments the new row can affect. Only an on-demand run, a
 * newly set settlement source or an undone match scans the full history.
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

  // A card bill paid from a current account is typed by whoever records it: WITHDRAWAL (money out),
  // EXPENSE (a manual "I paid my card bill") or SETTLEMENT. All three must be matchable, or the
  // payment counts as spending on top of the purchases it settles (double count). The type is
  // frozen (V21), so matching never re-types - it only flags the row an internal transfer.
  private static final Set<String> PAYMENT_TYPES = Set.of("WITHDRAWAL", "EXPENSE", "SETTLEMENT");

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

  // A date early enough to include every ledger row: a scan with no lower bound.
  private static final LocalDate FULL_HISTORY = LocalDate.of(1900, 1, 1);

  /**
   * Re-runs matching for every card a write to {@code written}, booked on {@code bookedOn}, can
   * affect: the card itself if it is one, and every card that names {@code written} as its
   * settlement source. Only payments the new row can influence are re-examined for a one-sided
   * candidate: a row booked on {@code bookedOn} changes the card's balance from that date on, and
   * can pair with a payment up to {@value #WINDOW_DAYS} days either side.
   */
  @Transactional
  public void detectAfterWrite(Account written, LocalDate bookedOn) {
    LocalDate since = bookedOn.minusDays(WINDOW_DAYS);
    cardsAffectedBy(written).forEach(cardId -> detectForCard(cardId, since));
  }

  /**
   * The cards whose matching a row on {@code written} can affect: the account itself if it is a
   * card, otherwise every card that names it as settlement source. A writer that must exclude
   * matching for these cards (US-07-02's removal) locks them first, in this order.
   */
  @Transactional(readOnly = true)
  public Set<UUID> cardsAffectedBy(Account written) {
    Set<UUID> cardIds = new LinkedHashSet<>();
    if (written.isHasStatementCycle()) {
      cardIds.add(written.getId());
    } else {
      accountCreditCardRepository
          .findBySettlementSourceAccountId(written.getId())
          .forEach(card -> cardIds.add(card.getAccountId()));
    }
    return cardIds;
  }

  /** Runs matching for one card over its whole ledger. */
  @Transactional
  public void detectForCard(UUID cardAccountId) {
    detectForCard(cardAccountId, FULL_HISTORY);
  }

  /**
   * Serialises everything that decides or applies a match for one card behind that card's account
   * row: two writes racing on the same card would each read the same "nothing proposed yet" state
   * and both insert the same proposal, and two members deciding competing proposals would each hold
   * one and wait for the other. Under READ COMMITTED the second transaction, once it gets the lock,
   * sees what the first committed and adds nothing.
   *
   * <p>Safe to take <em>after</em> inserting a ledger row: Hibernate emits {@code FOR NO KEY
   * UPDATE} for a pessimistic write on PostgreSQL, which - unlike {@code FOR UPDATE} - does not
   * conflict with the {@code FOR KEY SHARE} an insert into {@code transaction} takes on the card
   * row through its foreign key. So two concurrent inserts, each then taking this lock, queue
   * behind each other instead of deadlocking (verified: {@code
   * SettlementMatchControllerTest#concurrentWritesToOneCardAndItsSourceAccountAllSucceed}). A
   * change to a plain {@code FOR UPDATE} would break that.
   */
  @Transactional
  public void lockCard(UUID cardAccountId) {
    accountRepository.findByIdForUpdate(cardAccountId);
  }

  // Runs matching for one card. A card with no (usable) settlement source has nothing to do.
  // oneSidedSince bounds only the one-sided balance check; pairing is bounded by the card's
  // unmatched credits instead.
  private void detectForCard(UUID cardAccountId, LocalDate oneSidedSince) {
    lockCard(cardAccountId);
    AccountCreditCard card = accountCreditCardRepository.findById(cardAccountId).orElse(null);
    if (card == null || card.getSettlementSourceAccountId() == null) {
      return;
    }
    Account cardAccount = card.getAccount();
    Account source = accountRepository.findById(card.getSettlementSourceAccountId()).orElse(null);
    // Cross-currency settlement (the payment itself in a different currency than the card) would
    // need the payment converted at some rate; not built. Not to be confused with US-09-04's
    // foreign-currency card purchases, a different leg entirely.
    if (source == null || !source.getNativeCurrency().equals(cardAccount.getNativeCurrency())) {
      return;
    }
    String currency = cardAccount.getNativeCurrency();

    Map<UUID, List<SettlementMatch>> known =
        byPayment(settlementMatchRepository.findByCardAccountId(cardAccountId));
    List<Transaction> credits =
        transactionRepository.findUnmatchedCardCredits(cardAccountId, currency);

    // Pairing: each unmatched credit fetches only the payments of its own amount within the window.
    Map<UUID, Transaction> pairable = new LinkedHashMap<>();
    Map<UUID, List<Transaction>> creditsByPayment = new HashMap<>();
    Map<UUID, List<Transaction>> paymentsByCredit = new HashMap<>();
    for (Transaction credit : credits) {
      List<Transaction> fitting =
          transactionRepository.findPairablePayments(
              source.getId(),
              currency,
              PAYMENT_TYPES,
              credit.getAmount().negate(),
              credit.getBookingDate().minusDays(WINDOW_DAYS),
              credit.getBookingDate().plusDays(WINDOW_DAYS));
      for (Transaction payment : fitting) {
        if (isRejectedPair(known, payment, credit)) {
          continue;
        }
        pairable.putIfAbsent(payment.getId(), payment);
        creditsByPayment.computeIfAbsent(payment.getId(), k -> new ArrayList<>()).add(credit);
        paymentsByCredit.computeIfAbsent(credit.getId(), k -> new ArrayList<>()).add(payment);
      }
    }

    for (Transaction payment : pairable.values()) {
      List<Transaction> candidates = creditsByPayment.get(payment.getId());
      boolean unambiguous =
          candidates.size() == 1 && paymentsByCredit.get(candidates.get(0).getId()).size() == 1;
      if (unambiguous && !hasRejectedOneSided(known, payment)) {
        applyUnambiguousPair(cardAccount, payment, candidates.get(0), known);
      } else {
        // Ambiguous - or unambiguous, but a member has already said this payment is not a
        // settlement: either way the system does not decide, it proposes.
        proposePairs(cardAccount, payment, candidates, known);
      }
    }

    // One-sided: a payment with no credit to pair with that equals what the card owed that day.
    for (Transaction payment :
        transactionRepository.findPaymentsEqualToCardBalance(
            source.getId(), cardAccountId, currency, PAYMENT_TYPES, oneSidedSince)) {
      if (!creditsByPayment.containsKey(payment.getId())) {
        proposeOneSided(cardAccount, payment);
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
    // A payment can also sit in a proposed own-account transfer (US-10-01): that proposal lost.
    rejectCompetitors(match, OffsetDateTime.now(clock));
    applyFlags(match);
  }

  /**
   * Rejects, as decided by the system, every other PROPOSED match - card settlement or transfer -
   * that shares a leg with {@code match}, which is being confirmed: only one match may own a leg
   * (uq_settlement_match_confirmed_*), and a proposal left behind could never be confirmed. Every
   * path that confirms a match calls this; {@code match} must already have its id.
   */
  void rejectCompetitors(SettlementMatch match, OffsetDateTime now) {
    List<SettlementMatch> competitors =
        settlementMatchRepository.findCompetingProposals(
            match.getId(),
            match.getPaymentTransaction().getId(),
            match.getCardTransaction() == null ? null : match.getCardTransaction().getId());
    competitors.forEach(
        competitor -> {
          competitor.setStatus(SettlementMatchValues.REJECTED);
          competitor.setDecidedBy(null);
          competitor.setDecidedAt(now);
        });
    settlementMatchRepository.saveAllAndFlush(competitors);
  }

  private void proposePairs(
      Account cardAccount,
      Transaction payment,
      List<Transaction> candidates,
      Map<UUID, List<SettlementMatch>> known) {
    SettlementMatch oneSided = activeOneSided(known, payment);
    if (oneSided != null && SettlementMatchValues.CONFIRMED.equals(oneSided.getStatus())) {
      // A member already confirmed this payment on its own; equal credits cannot be told apart, so
      // it stays as confirmed rather than being replaced by a guess.
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

  // The query behind this only returns payments equal to the card's balance that no match of any
  // status has been made for, so there is nothing left to guard here.
  private void proposeOneSided(Account cardAccount, Transaction payment) {
    SettlementMatch proposal = newMatch(cardAccount, payment);
    proposal.setMatchBasis(SettlementMatchValues.BALANCE_EQUALS_PAYMENT);
    proposal.setStatus(SettlementMatchValues.PROPOSED);
    settlementMatchRepository.saveAndFlush(proposal);
  }

  private SettlementMatch newMatch(Account cardAccount, Transaction payment) {
    SettlementMatch match = new SettlementMatch();
    match.setWorkspace(cardAccount.getWorkspace());
    match.setCardAccount(cardAccount);
    match.setPaymentTransaction(payment);
    return match;
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

  /** Whether a member rejected this payment as a settlement altogether (no card leg involved). */
  private static boolean hasRejectedOneSided(
      Map<UUID, List<SettlementMatch>> known, Transaction payment) {
    return forPayment(known, payment).stream()
        .anyMatch(
            m ->
                m.getCardTransaction() == null
                    && SettlementMatchValues.REJECTED.equals(m.getStatus()));
  }
}
