package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.InstitutionNetTotals;
import com.trackmywealth.backend.dto.InstitutionSummaryContribution;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse.AccountLine;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.CustomAssetValuation;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-04-03: aggregates an institution's contributing accounts into total assets, liabilities and
 * net value in the container currency (FR-INS-006/FR-INS-SUM-001..004). See {@link
 * InstitutionSummaryResponse}'s own Javadoc for the current, deliberate scope limit - only {@code
 * CUSTOM_ASSET} accounts (the one account type with an implemented value source, US-05-05) ever
 * contribute a resolved value; every other account type appears in the drill-down unresolved. A
 * resolvable account whose native currency has no FX rate (direct or chained) into the container
 * currency yet is treated the same way - {@link AccountLine#nativeValue()} is still shown (we do
 * know it), but {@link AccountLine#valueResolvable()} is false and it is excluded from the totals,
 * never allowed to fail the whole request the way an uncaught 404 from {@link
 * FxRateService#getConversionRate} otherwise would.
 *
 * <p>Authorization is per-account, not a single institution-level gate: unlike {@code
 * AccountController}'s endpoints (gated by {@link AccessControlService#requireInstitutionAccess}
 * alone), an institution-scope or workspace-scope grant is not the only way to legitimately see
 * part of an institution's data here - a member who merely owns one account inside it (with no
 * broader grant) must still be able to request the summary and see at least that account, the same
 * as any other account-scoped read in this codebase. So this method never calls {@code
 * requireInstitutionAccess} at all: every contributing account is individually checked via {@link
 * AccessControlService#accountAccessLevel}, and one a caller cannot see (level below {@code
 * BALANCE_ONLY}) is silently dropped from both the totals and the drill-down list - the same
 * treatment as an unresolvable value, not a partial-denial error, since a summary is inherently a
 * "show me everything I can see" view rather than a single indivisible resource.
 *
 * <p>Valuations for every visible {@code manualValuation} account are fetched in one bulk query,
 * and each distinct native/container currency pair is resolved via {@link FxRateService} at most
 * once and reused across every account sharing it - one query per <em>distinct currency</em>
 * needing conversion, not one per account, since an institution can plausibly hold several accounts
 * in the same non-container currency.
 */
@Service
public class InstitutionSummaryService {

  // No FX-rate-writing job exists yet (EPIC 30) - every fx_rate row in any environment today was
  // inserted manually or by a test, the same scoping US-06-01 itself documents. "MANUAL" mirrors
  // CustomAssetValuation's own default source for the same reason: it is the only source that can
  // exist right now. Revisit once EPIC 30 introduces real, named providers.
  private static final String FX_SOURCE = "MANUAL";
  private static final String ACTIVE = "ACTIVE";
  private static final String ASSET = "ASSET";

  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final AccountRepository accountRepository;
  private final CustomAssetValuationRepository customAssetValuationRepository;
  private final AccessControlService accessControlService;
  private final FxRateService fxRateService;

  public InstitutionSummaryService(
      FinancialInstitutionRepository financialInstitutionRepository,
      AccountRepository accountRepository,
      CustomAssetValuationRepository customAssetValuationRepository,
      AccessControlService accessControlService,
      FxRateService fxRateService) {
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.accountRepository = accountRepository;
    this.customAssetValuationRepository = customAssetValuationRepository;
    this.accessControlService = accessControlService;
    this.fxRateService = fxRateService;
  }

  @Transactional(readOnly = true)
  public InstitutionSummaryResponse getSummary(
      UUID institutionId, AuthenticatedUserPrincipal actor) {
    // RLS already confines this to the caller's own workspace - a cross-workspace id simply isn't
    // found, degrading safely to 404 (same pattern as every other lookup in this codebase).
    FinancialInstitution institution =
        financialInstitutionRepository
            .findById(institutionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Financial institution not found."));
    UUID memberId = accessControlService.requireActingMember(actor);
    String containerCurrency = institution.getContainerCurrency();
    LocalDate today = LocalDate.now();

    List<Account> visibleAccounts = new ArrayList<>();
    for (Account account :
        accountRepository.findByFinancialInstitutionIdAndStatus(institutionId, ACTIVE)) {
      if (!AccessLevelValues.NO_ACCESS.equals(
          accessControlService.accountAccessLevel(memberId, account))) {
        visibleAccounts.add(account);
      }
    }

    Map<UUID, BigDecimal> nativeValuesByAccountId = bulkResolveNativeValues(visibleAccounts, today);
    Map<String, Optional<CurrencyConversionResult>> conversionsByNativeCurrency =
        bulkResolveConversions(visibleAccounts, nativeValuesByAccountId, containerCurrency, today);

    List<AccountLine> lines = new ArrayList<>();
    List<InstitutionSummaryContribution> contributions = new ArrayList<>();
    boolean hasUnresolvedValues = false;
    boolean hasCarriedForwardFxRate = false;

    for (Account account : visibleAccounts) {
      AccountLine line =
          toLine(
              account,
              containerCurrency,
              nativeValuesByAccountId.get(account.getId()),
              conversionsByNativeCurrency);
      lines.add(line);
      if (line.valueResolvable()) {
        contributions.add(new InstitutionSummaryContribution(line.nature(), line.convertedValue()));
        hasCarriedForwardFxRate = hasCarriedForwardFxRate || line.carriedForward();
      } else {
        hasUnresolvedValues = true;
      }
    }

    InstitutionNetTotals totals = sumByNature(contributions);
    return new InstitutionSummaryResponse(
        institutionId,
        containerCurrency,
        totals.totalAssets(),
        totals.totalLiabilities(),
        totals.netValue(),
        hasUnresolvedValues,
        hasCarriedForwardFxRate,
        lines);
  }

  // One bulk query instead of one per account (see class Javadoc). US-04-03's current scope limit
  // (see InstitutionSummaryResponse's Javadoc): only manualValuation (CUSTOM_ASSET-capability)
  // accounts have any implemented value source. Branches on the capability flag, never on
  // accountType itself (ArchitectureTest's only_account_service_branches_on_account_type rule
  // forbids calling Account.getAccountType() outside AccountService at all, not just switching on
  // it).
  private Map<UUID, BigDecimal> bulkResolveNativeValues(List<Account> accounts, LocalDate asOf) {
    List<UUID> manualValuationAccountIds =
        accounts.stream().filter(Account::isManualValuation).map(Account::getId).toList();
    if (manualValuationAccountIds.isEmpty()) {
      return Map.of();
    }

    Map<UUID, BigDecimal> nativeValuesByAccountId = new HashMap<>();
    for (CustomAssetValuation valuation :
        customAssetValuationRepository
            .findByAccountIdInAndValuationDateLessThanEqualOrderByValuationDateDesc(
                manualValuationAccountIds, asOf)) {
      // See the repository method's own Javadoc: the global valuationDate-DESC ordering
      // guarantees the first row seen for a given accountId is already its latest one.
      nativeValuesByAccountId.putIfAbsent(valuation.getAccount().getId(), valuation.getValue());
    }
    return nativeValuesByAccountId;
  }

  // Resolves at most one CurrencyConversionResult per distinct native currency actually needing
  // conversion (native currency present, resolved, and different from the container currency) -
  // reused across every account sharing that currency, rather than one FxRateService call per
  // account. A missing rate (404) is recorded as Optional.empty() here, not thrown: toLine() below
  // must be able to render an unresolved line for it rather than the whole request failing.
  private Map<String, Optional<CurrencyConversionResult>> bulkResolveConversions(
      List<Account> accounts,
      Map<UUID, BigDecimal> nativeValuesByAccountId,
      String containerCurrency,
      LocalDate asOf) {
    Map<String, Optional<CurrencyConversionResult>> conversionsByNativeCurrency = new HashMap<>();
    for (Account account : accounts) {
      String nativeCurrency = account.getNativeCurrency();
      if (nativeValuesByAccountId.containsKey(account.getId())
          && !nativeCurrency.equals(containerCurrency)) {
        conversionsByNativeCurrency.computeIfAbsent(
            nativeCurrency,
            currency ->
                fxRateService.tryGetConversionRate(currency, containerCurrency, asOf, FX_SOURCE));
      }
    }
    return conversionsByNativeCurrency;
  }

  private AccountLine toLine(
      Account account,
      String containerCurrency,
      BigDecimal nativeValue,
      Map<String, Optional<CurrencyConversionResult>> conversionsByNativeCurrency) {
    if (nativeValue == null) {
      return unresolvedLine(account, null);
    }

    if (account.getNativeCurrency().equals(containerCurrency)) {
      // No conversion needed - and none attempted, so there is genuinely no rate to report
      // (FR-INS-SUM-004 is about a rate that was actually applied).
      return new AccountLine(
          account.getId(),
          account.getName(),
          account.getNature(),
          account.getNativeCurrency(),
          nativeValue,
          nativeValue,
          null,
          null,
          false,
          true);
    }

    Optional<CurrencyConversionResult> conversion =
        conversionsByNativeCurrency.get(account.getNativeCurrency());
    if (conversion == null || conversion.isEmpty()) {
      // The value itself is known - just not convertible to the container currency right now
      // (see class Javadoc) - so the native figure is still worth showing.
      return unresolvedLine(account, nativeValue);
    }

    CurrencyConversionResult rate = conversion.get();
    BigDecimal convertedValue =
        nativeValue
            .multiply(rate.rate())
            .setScale(FxRateService.MONEY_SCALE, FxRateService.MONEY_ROUNDING);
    return new AccountLine(
        account.getId(),
        account.getName(),
        account.getNature(),
        account.getNativeCurrency(),
        nativeValue,
        convertedValue,
        rate.rate(),
        rate.rateDate(),
        rate.carriedForward(),
        true);
  }

  private AccountLine unresolvedLine(Account account, BigDecimal nativeValue) {
    return new AccountLine(
        account.getId(),
        account.getName(),
        account.getNature(),
        account.getNativeCurrency(),
        nativeValue,
        null,
        null,
        null,
        false,
        false);
  }

  // Package-private and dependency-free (no Spring, no DB) specifically so the negative-net-value
  // case (FR-INS-SUM-003/C6) can be unit-tested directly against synthetic contributions, without
  // needing a real liability-nature account with a resolvable value - which does not exist yet in
  // this codebase (see InstitutionSummaryResponse's Javadoc).
  static InstitutionNetTotals sumByNature(List<InstitutionSummaryContribution> contributions) {
    BigDecimal totalAssets = BigDecimal.ZERO;
    BigDecimal totalLiabilities = BigDecimal.ZERO;
    for (InstitutionSummaryContribution contribution : contributions) {
      if (ASSET.equals(contribution.nature())) {
        totalAssets = totalAssets.add(contribution.convertedValue());
      } else {
        totalLiabilities = totalLiabilities.add(contribution.convertedValue());
      }
    }
    return new InstitutionNetTotals(
        totalAssets, totalLiabilities, totalAssets.subtract(totalLiabilities));
  }
}
