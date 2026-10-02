package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import java.math.BigDecimal;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
 * accounts of the workspace and booked at most {@value #WINDOW_DAYS} days apart. Same-currency
 * amounts must be exactly opposite. US-10-06/#181 also accepts cross-currency amounts whose credit
 * is within the configured FX tolerance of the debit converted at the debit booking date. A missing
 * rate means no match; cross-currency pairs are always proposed for a member to confirm. Card
 * accounts take no part: a card is settled through {@link SettlementDetectionService}.
 *
 * <ul>
 *   <li><b>Applied automatically</b> - only an unambiguous <em>same-currency</em> pair (each leg
 *       has exactly one candidate) whose legs are both typed {@code TRANSFER}: {@code CONFIRMED},
 *       decided by the system.
 *   <li><b>Proposed</b> - every other pair, including every cross-currency pair and an unambiguous
 *       {@code WITHDRAWAL}/{@code DEPOSIT} one: FX tolerance and salary/transfer coincidences both
 *       need a member's decision.
 * </ul>
 *
 * <p>Matches reuse {@code settlement_match} (kind {@code TRANSFER}, V41): the debit is the
 * "payment" leg, the credit the "card" leg and its account the "card" account, so confirming,
 * rejecting and dissolving work exactly as for a card settlement. A rejected pair is never proposed
 * again. A match never re-types a leg; it only sets {@code is_internal_transfer} and {@code
 * counterparty_account_id}.
 *
 * <p>Confirming a pair, by the system or a member, rejects every other proposal - of either kind -
 * that shares one of its legs ({@link SettlementDetectionService#rejectCompetitors}).
 *
 * <p>Idempotent and resumable like card matching: a run only adds what is missing. Runs as the
 * system; each run holds a transaction-scoped advisory lock per workspace (not the workspace row,
 * which sharing grants lock for their own purpose), so two writes racing in one workspace neither
 * propose the same pair twice nor both claim one leg.
 *
 * <p>A run around a date decides only pairs with a leg booked within {@value #WINDOW_DAYS} days of
 * it - the pairs a change on that date can create or make ambiguous - and loads every candidate
 * within three windows, so each of those pairs is judged with all of its competitors in view.
 */
@Service
public class TransferDetectionService {

  /** Booking dates further apart than this never pair. */
  static final int WINDOW_DAYS = 5;

  static final Set<String> DEBIT_TYPES = Set.of("TRANSFER", "WITHDRAWAL", "EXPENSE");
  static final Set<String> CREDIT_TYPES = Set.of("TRANSFER", "DEPOSIT", "INCOME");
  private static final String TRANSFER = "TRANSFER";
  // Decided pairs have a leg within one window of the run's date, so the other leg is within two;
  // their competitors pair with a leg, so they are within three.
  private static final int LOADED_WINDOWS = 3;
  private static final String LOCK_PREFIX = "transfer-detection:";

  private final TransactionRepository transactionRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final WorkspaceRepository workspaceRepository;
  private final FxRateService fxRateService;
  private final Clock clock;
  private final String fxDefaultSource;
  private final BigDecimal crossCurrencyTolerance;

  public TransferDetectionService(
      TransactionRepository transactionRepository,
      SettlementMatchRepository settlementMatchRepository,
      SettlementDetectionService settlementDetectionService,
      WorkspaceRepository workspaceRepository,
      FxRateService fxRateService,
      Clock clock,
      @Value("${app.fx.default-source}") String fxDefaultSource,
      @Value("${app.fx.transfer-match-tolerance}") BigDecimal crossCurrencyTolerance) {
    this.transactionRepository = transactionRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.workspaceRepository = workspaceRepository;
    this.fxRateService = fxRateService;
    this.clock = clock;
    this.fxDefaultSource = fxDefaultSource;
    if (crossCurrencyTolerance.signum() < 0 || crossCurrencyTolerance.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("app.fx.transfer-match-tolerance must be between 0 and 1.");
    }
    this.crossCurrencyTolerance = crossCurrencyTolerance;
  }

  /**
   * Re-runs matching around a row just written to {@code written} on {@code bookedOn}. A card
   * account has nothing to do here.
   */
  @Transactional
  public void detectAfterWrite(Account written, LocalDate bookedOn) {
    if (written.isHasStatementCycle()) {
      return;
    }
    detectAround(written.getWorkspace().getId(), bookedOn);
  }

  /**
   * Runs matching for the pairs of one workspace with a leg booked within {@value #WINDOW_DAYS}
   * days of {@code date}.
   */
  @Transactional
  public void detectAround(UUID workspaceId, LocalDate date) {
    workspaceRepository.lockAdvisory(LOCK_PREFIX + workspaceId);
    List<Transaction> candidates =
        transactionRepository.findTransferCandidates(
            workspaceId,
            date.minusDays((long) LOADED_WINDOWS * WINDOW_DAYS),
            date.plusDays((long) LOADED_WINDOWS * WINDOW_DAYS),
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
    Map<FxLookupKey, Optional<CurrencyConversionResult>> fxRates = new HashMap<>();
    for (Transaction debit : debits) {
      for (Transaction credit : credits) {
        if (pairs(debit, credit, fxRates) && !isRejected(known, debit, credit)) {
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
          && debit.getCurrency().equals(only.getCurrency())
          && TRANSFER.equals(debit.getTransactionType())
          && TRANSFER.equals(only.getTransactionType())
          && existing(known, debit, only) == null) {
        if (inScope(date, debit, only)) {
          confirm(debit, only);
        }
      } else {
        for (Transaction credit : fitting) {
          if (inScope(date, debit, credit) && existing(known, debit, credit) == null) {
            propose(debit, credit);
          }
        }
      }
    }
  }

  // A pair this run may decide: one of its legs is within a window of the run's date. Further out,
  // a competitor could lie beyond what was loaded.
  private static boolean inScope(LocalDate date, Transaction debit, Transaction credit) {
    return withinWindow(date, debit.getBookingDate())
        || withinWindow(date, credit.getBookingDate());
  }

  private static boolean withinWindow(LocalDate a, LocalDate b) {
    return Math.abs(ChronoUnit.DAYS.between(a, b)) <= WINDOW_DAYS;
  }

  private boolean pairs(
      Transaction debit,
      Transaction credit,
      Map<FxLookupKey, Optional<CurrencyConversionResult>> fxRates) {
    if (debit.getAccount().getId().equals(credit.getAccount().getId())
        || !withinWindow(debit.getBookingDate(), credit.getBookingDate())) {
      return false;
    }
    if (debit.getCurrency().equals(credit.getCurrency())) {
      return debit.getAmount().negate().compareTo(credit.getAmount()) == 0;
    }

    FxLookupKey key =
        new FxLookupKey(debit.getCurrency(), credit.getCurrency(), debit.getBookingDate());
    Optional<CurrencyConversionResult> conversion =
        fxRates.computeIfAbsent(
            key,
            ignored ->
                fxRateService.tryGetConversionRate(
                    key.baseCurrency(), key.quoteCurrency(), key.date(), fxDefaultSource));
    return conversion.isPresent()
        && amountsWithinTolerance(
            debit.getAmount().negate(),
            credit.getAmount(),
            conversion.get().rate(),
            crossCurrencyTolerance);
  }

  /**
   * US-10-06: relative FX difference without division or intermediate rounding. Since transfer legs
   * are non-zero and FX rates are positive, {@code |actual - expected| <= expected * tolerance} is
   * exactly the configured {@code |actual - expected| / expected <= tolerance} rule.
   */
  static boolean amountsWithinTolerance(
      BigDecimal debitMagnitude,
      BigDecimal actualCredit,
      BigDecimal rate,
      BigDecimal tolerance) {
    BigDecimal expectedCredit = debitMagnitude.multiply(rate);
    return actualCredit.subtract(expectedCredit).abs().compareTo(expectedCredit.multiply(tolerance))
        <= 0;
  }

  private record FxLookupKey(String baseCurrency, String quoteCurrency, LocalDate date) {}

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
    settlementDetectionService.rejectCompetitors(match, match.getDecidedAt());
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
