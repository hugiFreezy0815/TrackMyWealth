package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CustomAssetValuationResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.CustomAssetValuation;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-05-05: dated manual valuations for {@code CUSTOM_ASSET} accounts (FR-NW-003). {@code
 * account_type} and currency matching are enforced by V26's DB triggers, translated by {@code
 * GlobalExceptionHandler}, the same "let the DB enforce the invariant" pattern {@code
 * AccountService} relies on for {@code account_type}/{@code native_currency} immutability - neither
 * is re-checked here.
 *
 * <p>Net worth itself - reflecting a valuation in current totals, or in a historical net-worth
 * chart - is out of scope: no net-worth/reporting feature exists anywhere in this codebase yet (a
 * gap this story shares with US-05-03/#68). {@link #getValuationAsOf} is this story's complete
 * answer to "which valuation applies as of a given date" - the query a future net-worth feature
 * would call - verified directly here rather than through a reporting endpoint that doesn't exist.
 */
@Service
public class CustomAssetValuationService {

  private final AccountRepository accountRepository;
  private final CustomAssetValuationRepository customAssetValuationRepository;

  public CustomAssetValuationService(
      AccountRepository accountRepository,
      CustomAssetValuationRepository customAssetValuationRepository) {
    this.accountRepository = accountRepository;
    this.customAssetValuationRepository = customAssetValuationRepository;
  }

  @Transactional
  public CustomAssetValuationResponse recordValuation(
      UUID accountId, CreateCustomAssetValuationRequest request) {
    Account account = findAccountOrThrow(accountId);

    CustomAssetValuation valuation = new CustomAssetValuation();
    valuation.setAccount(account);
    valuation.setValuationDate(request.valuationDate());
    valuation.setValue(request.value());
    valuation.setCurrency(request.currency());
    // flush, not a plain save: forces the INSERT (and any trigger rejection, or the UNIQUE(
    // account_id, valuation_date) violation for a duplicate date) to happen here, inside this
    // method, rather than deferred to end-of-transaction commit - same reasoning as
    // AccountService's own saveAndFlush calls.
    valuation = customAssetValuationRepository.saveAndFlush(valuation);

    return toResponse(valuation);
  }

  @Transactional(readOnly = true)
  public List<CustomAssetValuationResponse> listValuations(UUID accountId) {
    findAccountOrThrow(accountId);
    return customAssetValuationRepository
        .findByAccountIdOrderByValuationDateDesc(accountId)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  // FR-NW-003 AC #2/PR-011: the valuation current for asOfDate is the latest one dated on or
  // before it - never an interpolation between two known points. Empty, not zero, when no
  // valuation exists on or before asOfDate (PR-011/data-quality behaviour: an account with no
  // valuation must be shown as unknown, never as EUR 0 net worth).
  @Transactional(readOnly = true)
  public Optional<CustomAssetValuationResponse> getValuationAsOf(
      UUID accountId, LocalDate asOfDate) {
    findAccountOrThrow(accountId);
    return customAssetValuationRepository
        .findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc(
            accountId, asOfDate)
        .map(this::toResponse);
  }

  private Account findAccountOrThrow(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
  }

  private CustomAssetValuationResponse toResponse(CustomAssetValuation valuation) {
    return new CustomAssetValuationResponse(
        valuation.getId(),
        valuation.getAccount().getId(),
        valuation.getValuationDate(),
        valuation.getValue(),
        valuation.getCurrency(),
        valuation.getSource(),
        valuation.getCreatedAt());
  }
}
