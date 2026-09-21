package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

  private final AccessControlService accessControlService;
  private final AppUserRepository appUserRepository;
  private final AccountRepository accountRepository;
  private final AccountValuationService accountValuationService;

  public NetWorthService(
      AccessControlService accessControlService,
      AppUserRepository appUserRepository,
      AccountRepository accountRepository,
      AccountValuationService accountValuationService) {
    this.accessControlService = accessControlService;
    this.appUserRepository = appUserRepository;
    this.accountRepository = accountRepository;
    this.accountValuationService = accountValuationService;
  }

  // One access-level lookup per account: fine at this project's household scale (self-hosted, tens
  // of accounts). Batch it if a workspace ever holds enough accounts for this to show in a profile.
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
    LocalDate asOf = LocalDate.now();

    BigDecimal totalAssets = BigDecimal.ZERO;
    BigDecimal totalLiabilities = BigDecimal.ZERO;
    boolean complete = true;
    List<AccountValuation> valuations = new ArrayList<>();

    for (Account account :
        accountRepository.findByWorkspaceIdAndStatusOrderByCreatedAtAsc(
            actor.workspaceId(), ACTIVE)) {
      if (!accessControlService.hasAccountAccess(
          memberId, account, AccessLevelValues.BALANCE_ONLY)) {
        continue;
      }
      AccountValuation valuation =
          accountValuationService.valueIn(account, reportingCurrency, asOf);
      valuations.add(valuation);
      if (!valuation.valueKnown()) {
        complete = false;
      } else if (ASSET.equals(valuation.nature())) {
        totalAssets = totalAssets.add(valuation.value());
      } else {
        totalLiabilities = totalLiabilities.add(valuation.value());
      }
    }

    return new NetWorthResponse(
        reportingCurrency,
        asOf,
        totalAssets,
        totalLiabilities,
        totalAssets.subtract(totalLiabilities),
        complete,
        valuations);
  }
}
