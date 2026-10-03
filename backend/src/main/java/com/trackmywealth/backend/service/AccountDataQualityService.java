package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.time.LocalDate;
import java.util.Collection;
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

  public AccountDataQualityService(
      TransactionRepository transactionRepository,
      AccountSnapshotRepository accountSnapshotRepository) {
    this.transactionRepository = transactionRepository;
    this.accountSnapshotRepository = accountSnapshotRepository;
  }

  /** The account's warnings, empty when it has none. */
  @Transactional(readOnly = true)
  public List<String> warningsFor(UUID accountId) {
    // US-25-04: rows before the opening balance were acknowledged (or added later) and are left
    // out of the value - never silently, so the account says so for as long as they exist.
    if (transactionRepository.existsLiveBookedBeforeOpeningBalance(accountId)) {
      return List.of(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
    }
    return List.of();
  }

  /**
   * {@link #warningsFor(UUID)} for a caller that already holds the account's opening balance date
   * ({@code null} when it has none) - a valuation, which needs the opening balance anyway. Saves a
   * query per account: none at all without an opening balance or without a ledger.
   */
  @Transactional(readOnly = true)
  public List<String> warningsFor(Account account, LocalDate openingBalanceDate) {
    if (openingBalanceDate == null
        || !account.isHasTransactions()
        || !transactionRepository.existsLiveBookedBefore(account.getId(), openingBalanceDate)) {
      return List.of();
    }
    return List.of(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE);
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
    List<UUID> candidates =
        accounts.stream()
            .filter(Account::isHasTransactions)
            .map(Account::getId)
            .filter(openingBalanceDates::containsKey)
            .toList();
    if (candidates.isEmpty()) {
      return Map.of();
    }
    return transactionRepository.findAccountIdsWithLiveRowsBeforeOpeningBalance(candidates).stream()
        .distinct()
        .collect(
            Collectors.toMap(
                accountId -> accountId,
                accountId ->
                    List.of(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE)));
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
