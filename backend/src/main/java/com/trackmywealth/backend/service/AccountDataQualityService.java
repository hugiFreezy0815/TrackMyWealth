package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data-quality warnings an account carries ({@link DataQualityWarningValues}), shown on the
 * account itself and, through its valuation, at every consolidated headline that includes it
 * (PR-011, FR-CON-007). One place, so the account and its figures never disagree.
 *
 * <p><b>Performs no access check</b>, like {@link AccountValuationService#valueIn}: the caller has
 * already cleared the actor for the account.
 */
@Service
public class AccountDataQualityService {

  private final TransactionRepository transactionRepository;
  private final AccountSnapshotRepository accountSnapshotRepository;
  private final ReconciliationResultRepository reconciliationResultRepository;

  public AccountDataQualityService(
      TransactionRepository transactionRepository,
      AccountSnapshotRepository accountSnapshotRepository,
      ReconciliationResultRepository reconciliationResultRepository) {
    this.transactionRepository = transactionRepository;
    this.accountSnapshotRepository = accountSnapshotRepository;
    this.reconciliationResultRepository = reconciliationResultRepository;
  }

  /** The account's warnings, empty when it has none. */
  @Transactional(readOnly = true)
  public List<String> warningsFor(UUID accountId) {
    List<String> warnings = new ArrayList<>();
    // US-25-04: rows before the opening balance were acknowledged (or added later) and are left
    // out of the value - never silently, so the account says so for as long as they exist.
    if (transactionRepository.existsLiveBookedBeforeOpeningBalance(accountId)) {
      warnings.add(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
    }
    if (reconciliationResultRepository.existsByAccountIdAndStatusAndAffectedSecurityIdIsNull(
        accountId, ReconciliationResultValues.OPEN)) {
      warnings.add(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
    }
    return List.copyOf(warnings);
  }

  /**
   * {@link #warningsFor(UUID)} for a caller that already holds the account's opening balance date
   * ({@code null} when it has none) - a valuation, which needs the opening balance anyway. Saves a
   * query per account: none at all without an opening balance or without a ledger.
   */
  @Transactional(readOnly = true)
  public List<String> warningsFor(Account account, LocalDate openingBalanceDate) {
    List<String> warnings = new ArrayList<>();
    if (openingBalanceDate != null
        && account.isHasTransactions()
        && transactionRepository.existsLiveBookedBefore(account.getId(), openingBalanceDate)) {
      warnings.add(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
    }
    if (reconciliationResultRepository.existsByAccountIdAndStatusAndAffectedSecurityIdIsNull(
        account.getId(), ReconciliationResultValues.OPEN)) {
      warnings.add(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
    }
    return List.copyOf(warnings);
  }

  /**
   * {@link #warningsFor(Account, LocalDate)} for a whole batch of accounts (a net worth, an
   * institution summary) in one query instead of one per account. {@code openingBalanceDates} holds
   * the date of each account that has an opening balance; an account missing from the result has no
   * warnings.
   */
  @Transactional(readOnly = true)
  public Map<UUID, List<String>> warningsForAll(
      Collection<Account> accounts, Map<UUID, LocalDate> openingBalanceDates) {
    Map<UUID, List<String>> warnings = new HashMap<>();
    List<UUID> openingCandidates =
        accounts.stream()
            .filter(Account::isHasTransactions)
            .map(Account::getId)
            .filter(openingBalanceDates::containsKey)
            .toList();
    if (!openingCandidates.isEmpty()) {
      for (UUID accountId :
          transactionRepository.findAccountIdsWithLiveRowsBeforeOpeningBalance(openingCandidates)) {
        warnings
            .computeIfAbsent(accountId, ignored -> new ArrayList<>())
            .add(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
      }
    }

    List<UUID> accountIds = accounts.stream().map(Account::getId).distinct().toList();
    if (!accountIds.isEmpty()) {
      for (UUID accountId :
          reconciliationResultRepository.findAccountIdsWithCashResultInStatus(
              accountIds, ReconciliationResultValues.OPEN)) {
        warnings
            .computeIfAbsent(accountId, ignored -> new ArrayList<>())
            .add(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE);
      }
    }

    return warnings.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  /**
   * The opening balance date of each of {@code accountIds} that has one - one query for a whole
   * page of transactions, for {@link #transactionWarnings}.
   */
  @Transactional(readOnly = true)
  public Map<UUID, LocalDate> openingBalanceDates(Collection<UUID> accountIds) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    return accountSnapshotRepository.findByAccountIdInAndOpeningBalanceTrue(accountIds).stream()
        .collect(
            Collectors.toMap(
                snapshot -> snapshot.getAccount().getId(), AccountSnapshot::getSnapshotDate));
  }

  /**
   * One transaction's warnings: {@code BOOKED_BEFORE_OPENING_BALANCE} for a live row booked before
   * its account's opening balance ({@code openingBalanceDates}, from {@link #openingBalanceDates}).
   * A removed row (voided, a reversal, soft-deleted) counts nowhere anyway and carries none. A row
   * on the opening date is taken as contained in the balance by convention
   * (calculation-methodology.md) and is not flagged.
   */
  public static List<String> transactionWarnings(
      Transaction transaction, Map<UUID, LocalDate> openingBalanceDates) {
    LocalDate openingDate = openingBalanceDates.get(transaction.getAccount().getId());
    boolean live =
        transaction.getVoidedAt() == null
            && !transaction.isReversal()
            && transaction.getDeletedAt() == null;
    if (openingDate == null || !live || !transaction.getBookingDate().isBefore(openingDate)) {
      return List.of();
    }
    return List.of(DataQualityWarningValues.BOOKED_BEFORE_OPENING_BALANCE);
  }
}
