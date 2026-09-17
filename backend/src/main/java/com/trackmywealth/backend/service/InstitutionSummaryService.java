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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * contribute a resolved value; every other account type appears in the drill-down unresolved.
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
  private static final int MONEY_SCALE = 4;

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

    List<AccountLine> lines = new ArrayList<>();
    List<InstitutionSummaryContribution> contributions = new ArrayList<>();
    boolean hasUnresolvedValues = false;
    boolean hasCarriedForwardFxRate = false;

    for (Account account :
        accountRepository.findByFinancialInstitutionIdAndStatus(institutionId, ACTIVE)) {
      if (!AccessLevelValues.NO_ACCESS.equals(
          accessControlService.accountAccessLevel(memberId, account))) {
        AccountLine line = toLine(account, containerCurrency, today);
        lines.add(line);
        if (line.valueResolvable()) {
          contributions.add(
              new InstitutionSummaryContribution(line.nature(), line.convertedValue()));
          hasCarriedForwardFxRate = hasCarriedForwardFxRate || line.carriedForward();
        } else {
          hasUnresolvedValues = true;
        }
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

  private AccountLine toLine(Account account, String containerCurrency, LocalDate asOf) {
    BigDecimal nativeValue = resolveNativeValue(account, asOf);
    if (nativeValue == null) {
      return new AccountLine(
          account.getId(),
          account.getName(),
          account.getNature(),
          account.getNativeCurrency(),
          null,
          null,
          null,
          null,
          false,
          false);
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

    CurrencyConversionResult conversion =
        fxRateService.getConversionRate(
            account.getNativeCurrency(), containerCurrency, asOf, FX_SOURCE);
    BigDecimal convertedValue =
        nativeValue.multiply(conversion.rate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new AccountLine(
        account.getId(),
        account.getName(),
        account.getNature(),
        account.getNativeCurrency(),
        nativeValue,
        convertedValue,
        conversion.rate(),
        conversion.rateDate(),
        conversion.carriedForward(),
        true);
  }

  // US-04-03's current scope limit (see InstitutionSummaryResponse's Javadoc): only manualValuation
  // (CUSTOM_ASSET-capability) accounts have any implemented value source. Branches on the
  // capability flag, never on accountType itself (ArchitectureTest's
  // only_account_service_branches_on_account_type rule forbids calling Account.getAccountType()
  // outside AccountService at all, not just switching on it).
  private BigDecimal resolveNativeValue(Account account, LocalDate asOf) {
    if (!account.isManualValuation()) {
      return null;
    }
    return customAssetValuationRepository
        .findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc(
            account.getId(), asOf)
        .map(CustomAssetValuation::getValue)
        .orElse(null);
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
