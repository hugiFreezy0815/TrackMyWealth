package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-10-01/FR-CF-001/005, DM-05: recognises the two legs of a transfer between two of a workspace's
 * own accounts when they were recorded separately (an import, or two manual one-sided entries), so
 * neither counts as spending or income. A manual transfer recorded with its counterparty is written
 * as a linked pair to begin with (TransactionService) and never reaches this matcher.
 *
 * <h2>What matches</h2>
 *
 * A <em>debit</em> is a negative {@code TRANSFER}/{@code WITHDRAWAL}/{@code EXPENSE}, a
 * <em>credit</em> a positive {@code TRANSFER}/{@code DEPOSIT}/{@code INCOME}, on two different
 * accounts of the workspace, in the same currency, of exactly opposite amounts, booked at most
 * {@value #WINDOW_DAYS} days apart. Cross-currency legs are not matched (a later story, #181). Card
 * accounts take no part: a card is settled through {@link SettlementDetectionService}.
 *
 * <ul>
 *   <li><b>Applied automatically</b> - only an unambiguous pair (each leg has exactly one
 *       candidate) whose legs are both typed {@code TRANSFER}: {@code CONFIRMED}, decided by the
 *       system.
 *   <li><b>Proposed</b> - every other pair, including an unambiguous {@code WITHDRAWAL}/{@code
 *       DEPOSIT} one: a salary can equal a transfer by coincidence, so a member decides.
 * </ul>
 *
 * <p>Matches reuse {@code settlement_match} (kind {@code TRANSFER}, V41): the debit is the
 * "payment" leg, the credit the "card" leg and its account the "card" account, so confirming,
 * rejecting and dissolving work exactly as for a card settlement. A rejected pair is never proposed
 * again. A match never re-types a leg; it only sets {@code is_internal_transfer} and {@code
 * counterparty_account_id}.
 *
 * <p>Idempotent and resumable like card matching: a run only adds what is missing. Runs as the
 * system; each run holds the workspace row lock, so two writes racing in one workspace neither
 * propose the same pair twice nor both claim one leg.
 */
@Service
public class TransferDetectionService {

  /** Booking dates further apart than this never pair. */
  static final int WINDOW_DAYS = 5;

  static final Set<String> DEBIT_TYPES = Set.of("TRANSFER", "WITHDRAWAL", "EXPENSE");
  static final Set<String> CREDIT_TYPES = Set.of("TRANSFER", "DEPOSIT", "INCOME");
  private static final String TRANSFER = "TRANSFER";

  private final TransactionRepository transactionRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final WorkspaceRepository workspaceRepository;
  private final Clock clock;

  public TransferDetectionService(
      TransactionRepository transactionRepository,
      SettlementMatchRepository settlementMatchRepository,
      SettlementDetectionService settlementDetectionService,
      WorkspaceRepository workspaceRepository,
      Clock clock) {
    this.transactionRepository = transactionRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.workspaceRepository = workspaceRepository;
    this.clock = clock;
  }

  /**
   * Re-runs matching around a row just written to {@code written} on {@code bookedOn}: every
   * candidate within twice the window, since pairing the new row can also make an earlier pair
   * ambiguous. A card account has nothing to do here.
   */
  @Transactional
  public void detectAfterWrite(Account written, LocalDate bookedOn) {
    if (written.isHasStatementCycle()) {
      return;
    }
    detectAround(written.getWorkspace().getId(), bookedOn);
  }

  /** Runs matching for one workspace's candidates booked around {@code date}. */
  @Transactional
  public void detectAround(UUID workspaceId, LocalDate date) {
    workspaceRepository.findByIdForUpdate(workspaceId);
    List<Transaction> candidates =
        transactionRepository.findTransferCandidates(
            workspaceId,
            date.minusDays(2L * WINDOW_DAYS),
            date.plusDays(2L * WINDOW_DAYS),
            DEBIT_TYPES,
            CREDIT_TYPES);
    List<Transaction> debits = new ArrayList<>();
    List<Transaction> credits = new ArrayList<>();
    for (Transaction candidate : candidates) {
      (candidate.getAmount().signum() < 0 ? debits : credits).add(candidate);
    }
    if (debits.isEmpty() || credits.isEmpty()) {
      return;
    }
    List<SettlementMatch> known =
        settlementMatchRepository.findTransferMatchesTouching(
            candidates.stream().map(Transaction::getId).toList());

    Map<UUID, List<Transaction>> creditsByDebit = new LinkedHashMap<>();
    Map<UUID, List<Transaction>> debitsByCredit = new HashMap<>();
    for (Transaction debit : debits) {
      for (Transaction credit : credits) {
        if (pairs(debit, credit) && !isRejected(known, debit, credit)) {
          creditsByDebit.computeIfAbsent(debit.getId(), k -> new ArrayList<>()).add(credit);
          debitsByCredit.computeIfAbsent(credit.getId(), k -> new ArrayList<>()).add(debit);
        }
      }
    }

    Map<UUID, Transaction> debitsById = new HashMap<>();
    debits.forEach(debit -> debitsById.put(debit.getId(), debit));
    for (Map.Entry<UUID, List<Transaction>> entry : creditsByDebit.entrySet()) {
      Transaction debit = debitsById.get(entry.getKey());
      List<Transaction> fitting = entry.getValue();
      Transaction only = fitting.size() == 1 ? fitting.get(0) : null;
      boolean unambiguous = only != null && debitsByCredit.get(only.getId()).size() == 1;
      if (unambiguous
          && TRANSFER.equals(debit.getTransactionType())
          && TRANSFER.equals(only.getTransactionType())
          && existing(known, debit, only) == null) {
        confirm(debit, only);
      } else {
        for (Transaction credit : fitting) {
          if (existing(known, debit, credit) == null) {
            propose(debit, credit);
          }
        }
      }
    }
  }

  private static boolean pairs(Transaction debit, Transaction credit) {
    return !debit.getAccount().getId().equals(credit.getAccount().getId())
        && debit.getCurrency().equals(credit.getCurrency())
        && debit.getAmount().negate().compareTo(credit.getAmount()) == 0
        && Math.abs(ChronoUnit.DAYS.between(debit.getBookingDate(), credit.getBookingDate()))
            <= WINDOW_DAYS;
  }

  private static SettlementMatch existing(
      List<SettlementMatch> known, Transaction debit, Transaction credit) {
    for (SettlementMatch match : known) {
      if (match.getPaymentTransaction().getId().equals(debit.getId())
          && match.getCardTransaction() != null
          && match.getCardTransaction().getId().equals(credit.getId())) {
        return match;
      }
    }
    return null;
  }

  private static boolean isRejected(
      List<SettlementMatch> known, Transaction debit, Transaction credit) {
    SettlementMatch match = existing(known, debit, credit);
    return match != null && SettlementMatchValues.REJECTED.equals(match.getStatus());
  }

  // Decided by the system: an exact, unique pair of legs both typed TRANSFER.
  private void confirm(Transaction debit, Transaction credit) {
    SettlementMatch match = newMatch(debit, credit);
    match.setStatus(SettlementMatchValues.CONFIRMED);
    match.setDecidedAt(OffsetDateTime.now(clock));
    settlementMatchRepository.saveAndFlush(match);
    settlementDetectionService.applyFlags(match);
  }

  private void propose(Transaction debit, Transaction credit) {
    SettlementMatch match = newMatch(debit, credit);
    match.setStatus(SettlementMatchValues.PROPOSED);
    settlementMatchRepository.saveAndFlush(match);
  }

  private static SettlementMatch newMatch(Transaction debit, Transaction credit) {
    SettlementMatch match = new SettlementMatch();
    match.setWorkspace(debit.getWorkspace());
    match.setMatchKind(SettlementMatchValues.TRANSFER);
    match.setMatchBasis(SettlementMatchValues.LEG_PAIR);
    match.setPaymentTransaction(debit);
    match.setCardTransaction(credit);
    match.setCardAccount(Objects.requireNonNull(credit.getAccount()));
    return match;
  }
}
