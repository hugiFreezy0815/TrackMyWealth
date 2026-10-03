package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.ValueBasisValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-09-01/FR-CC-003: the thin, partial workspace net worth that makes "a card's outstanding
 * balance is subtracted, never added" observable. It is the {@code assets - liabilities} rule over
 * whatever {@link AccountValuationService} can value today - US-11-01 owns the full consolidated
 * figure (owner/asset-class scopes, historical series, data-quality banner) and supersedes this.
 *
 * <p>Signs come from each account's {@code nature} (the DB {@code GENERATED ALWAYS AS} column,
 * DB-12), never from application-side type logic: an {@code ASSET}'s value adds, a {@code
 * LIABILITY}'s value subtracts. Only accounts the caller may see at {@code BALANCE_ONLY} or above
 * take part (US-03-03) - an aggregation must not leak another member's private account through its
 * total.
 */
@Service
public class NetWorthService {

  private static final String ACTIVE = "ACTIVE";
  private static final String ASSET = "ASSET";
  private static final String LIABILITY = "LIABILITY";

  private final AccessControlService accessControlService;
  private final AppUserRepository appUserRepository;
  private final AccountRepository accountRepository;
  private final AccountValuationService accountValuationService;
  private final BusinessDateService businessDateService;

  public NetWorthService(
      AccessControlService accessControlService,
      AppUserRepository appUserRepository,
      AccountRepository accountRepository,
      AccountValuationService accountValuationService,
      BusinessDateService businessDateService) {
    this.accessControlService = accessControlService;
    this.appUserRepository = appUserRepository;
    this.accountRepository = accountRepository;
    this.accountValuationService = accountValuationService;
    this.businessDateService = businessDateService;
  }

  // Access is resolved in bulk (the per-workspace sole-member count once, not per account) and each
  // distinct foreign currency's FX rate once. What remains per account is its ownership/grant
  // lookup and its value-source query - fine at this project's household scale (self-hosted, tens
  // of accounts); revisit if a workspace ever holds enough accounts for it to show in a profile.
  @Transactional(readOnly = true)
  public NetWorthResponse getNetWorth(AuthenticatedUserPrincipal actor) {
    // 404 for a caller with no workspace membership (a SYSTEM_ADMINISTRATOR with no linked
    // member), same as every other workspace-scoped read.
    UUID memberId = accessControlService.requireActingMember(actor);
    String reportingCurrency =
        appUserRepository
            .findById(actor.userId())
            .map(AppUser::getReportingCurrency)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
    LocalDate asOf = businessDateService.today();

    List<Account> visible =
        accessControlService.accountsWithAccess(
            memberId,
            accountRepository.findByWorkspaceIdAndStatusOrderByCreatedAtAsc(
                actor.workspaceId(), ACTIVE),
            AccessLevelValues.BALANCE_ONLY);
    List<AccountValuation> valuations =
        accountValuationService.valueAll(visible, reportingCurrency, asOf);

    BigDecimal totalAssets = BigDecimal.ZERO;
    BigDecimal totalLiabilities = BigDecimal.ZERO;
    boolean complete = true;
    boolean approximate = false;
    // PR-011/FR-CON-007: an account's warning is shown at the headline too, known value or not.
    Set<String> warnings = new TreeSet<>();

    for (AccountValuation valuation : valuations) {
      warnings.addAll(valuation.warnings());
      if (!valuation.valueKnown()) {
        complete = false;
        continue;
      }
      approximate |= ValueBasisValues.isApproximate(valuation.valueBasis());
      // Explicit on both natures: one this code has never heard of must fail loudly rather than
      // be silently counted as a liability.
      if (ASSET.equals(valuation.nature())) {
        totalAssets = totalAssets.add(valuation.value());
      } else if (LIABILITY.equals(valuation.nature())) {
        totalLiabilities = totalLiabilities.add(valuation.value());
      } else {
        throw new IllegalStateException("Unexpected account nature '" + valuation.nature() + "'");
      }
    }

    return new NetWorthResponse(
        reportingCurrency,
        asOf,
        totalAssets,
        totalLiabilities,
        totalAssets.subtract(totalLiabilities),
        complete,
        approximate,
        List.copyOf(warnings),
        valuations);
  }
}
