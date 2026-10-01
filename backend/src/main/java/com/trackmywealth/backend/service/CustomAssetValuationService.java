package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CustomAssetValuationResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.CustomAssetValuation;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-05-05: dated manual valuations for {@code CUSTOM_ASSET} accounts (FR-NW-003). {@code
 * account_type} is validated only by V26/V27's DB trigger, translated by {@code
 * GlobalExceptionHandler}, the same "let the DB enforce the invariant" pattern {@code
 * AccountService} relies on for {@code account_type}/{@code native_currency} immutability. Currency
 * isn't client input at all - {@link #recordValuation} derives it from the account's own {@code
 * native_currency} - so the matching DB trigger only ever fires as defense-in-depth.
 *
 * <p>Net worth itself - reflecting a valuation in current totals, or in a historical net-worth
 * chart - is out of scope: no net-worth/reporting feature exists anywhere in this codebase yet (a
 * gap this story shares with US-05-03/#68). {@link #getValuationAsOf} is this story's complete
 * answer to "which valuation applies as of a given date" - the query a future net-worth feature
 * would call - verified directly here rather than through a reporting endpoint that doesn't exist,
 * so it deliberately has no {@link AccessControlService} check of its own yet either.
 *
 * <p>US-03-03 follow-up: {@link #recordValuation}/{@link #listValuations} are now gated via {@code
 * AccessControlService} ({@code EDIT}/{@code READ} respectively) - a dollar-valued valuation is
 * exactly the kind of account-scoped financial data the story's own "visibility is never an
 * implicit merge of another member's private data" line is about, the same as any other
 * account-scoped read/write.
 */
@Service
public class CustomAssetValuationService {

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final CustomAssetValuationRepository customAssetValuationRepository;

  public CustomAssetValuationService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      CustomAssetValuationRepository customAssetValuationRepository) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.customAssetValuationRepository = customAssetValuationRepository;
  }

  @Transactional
  public CustomAssetValuationResponse recordValuation(
      UUID accountId, CreateCustomAssetValuationRequest request, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);

    CustomAssetValuation valuation = new CustomAssetValuation();
    valuation.setAccount(account);
    valuation.setValuationDate(request.valuationDate());
    valuation.setValue(request.value());
    // Not client-supplied: a valuation's currency is the account's own native_currency, not an
    // independent choice (see CreateCustomAssetValuationRequest's Javadoc). V26/V27's DB trigger
    // still guards this as defense-in-depth, matching how this codebase treats every other
    // DB-enforced invariant the application also gets right on its own.
    valuation.setCurrency(account.getNativeCurrency());
    // flush, not a plain save: forces the INSERT (and any trigger rejection, or the UNIQUE(
    // account_id, valuation_date) violation for a duplicate date) to happen here, inside this
    // method, rather than deferred to end-of-transaction commit - same reasoning as
    // AccountService's own saveAndFlush calls.
    valuation = customAssetValuationRepository.saveAndFlush(valuation);

    return toResponse(valuation);
  }

  @Transactional(readOnly = true)
  public List<CustomAssetValuationResponse> listValuations(
      UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
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
    accountLookupService.findAccountOrThrow(accountId);
    return customAssetValuationRepository
        .findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc(
            accountId, asOfDate)
        .map(this::toResponse);
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
