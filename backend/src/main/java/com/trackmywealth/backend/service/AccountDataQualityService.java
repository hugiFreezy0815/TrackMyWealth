package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.DataQualityWarningValues;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.util.List;
import java.util.UUID;
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

  public AccountDataQualityService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
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
}
