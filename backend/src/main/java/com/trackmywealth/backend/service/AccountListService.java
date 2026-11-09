package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountListFilter;
import com.trackmywealth.backend.dto.AccountListItemResponse;
import com.trackmywealth.backend.dto.AccountTypeValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.MultiCurrencyValuations;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.SignedAmounts;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-05-06 (#380): every account of the workspace with its current value, for the app to show and
 * navigate them. Every active member sees all of them (US-03-06); RLS keeps workspaces apart.
 *
 * <p>No second calculation path: each value is an {@link AccountValuation} from {@link
 * AccountValuationService#valueAllIn}, the one net worth and the Portfolio View use, in the
 * workspace's currency and in the account's own. The list is unpaged by design (ADR 0006's bounded
 * lists, #373): a workspace holds tens of accounts, not thousands. The number of queries does not
 * grow with the number of accounts: the accounts with their portfolios, their placements and each
 * kind of value source are one query each.
 */
@Service
public class AccountListService {

  // Oldest first, as in the Portfolio View; the id makes the order unique.
  private static final Sort LIST_ORDER = Sort.by("createdAt", "id");
  private static final String ARCHIVED = "ARCHIVED";

  private final AccessControlService accessControlService;
  private final AccountRepository accountRepository;
  private final AccountService accountService;
  private final AccountValuationService accountValuationService;
  private final BusinessDateService businessDateService;

  public AccountListService(
      AccessControlService accessControlService,
      AccountRepository accountRepository,
      AccountService accountService,
      AccountValuationService accountValuationService,
      BusinessDateService businessDateService) {
    this.accessControlService = accessControlService;
    this.accountRepository = accountRepository;
    this.accountService = accountService;
    this.accountValuationService = accountValuationService;
    this.businessDateService = businessDateService;
  }

  @Transactional(readOnly = true)
  public List<AccountListItemResponse> listAccounts(
      AccountListFilter filter, AuthenticatedUserPrincipal actor) {
    // Authorization first: a non-member gets the same 404 whatever filters it sends.
    accessControlService.requireWorkspaceAccess(actor, actor.workspaceId());
    requireValid(filter);
    List<Account> accounts =
        accountRepository.findAll(
            AccountRepository.listedBy(actor.workspaceId(), filter), LIST_ORDER);
    if (accounts.isEmpty()) {
      return List.of();
    }

    // Fetched with the accounts, the same workspace for all of them.
    String workspaceCurrency = accounts.get(0).getWorkspace().getCurrency();
    LocalDate asOf = businessDateService.today();
    MultiCurrencyValuations valuations =
        accountValuationService.valueAllIn(accounts, List.of(workspaceCurrency), asOf);
    List<AccountValuation> inWorkspace = valuations.in(workspaceCurrency);
    List<AccountValuation> inOwn = valuations.inOwnCurrency();
    Map<UUID, PortfolioPlacement> placements = accountService.portfolioPlacementsOf(accounts);

    List<AccountListItemResponse> rows = new ArrayList<>(accounts.size());
    // The valuations come back in the order the accounts were given.
    for (int i = 0; i < accounts.size(); i++) {
      Account account = accounts.get(i);
      rows.add(
          toRow(
              account,
              placements.get(account.getId()),
              inWorkspace.get(i),
              inOwn.get(i),
              workspaceCurrency,
              asOf));
    }
    return rows;
  }

  // A filter that cannot match anything by construction is the client's mistake, said as a 400
  // rather than answered with an empty list that would look like "no such accounts".
  static void requireValid(AccountListFilter filter) {
    if (filter.accountType() != null && !AccountTypeValues.isKnown(filter.accountType())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "accountType must be one of the account types.");
    }
  }

  private static AccountListItemResponse toRow(
      Account account,
      PortfolioPlacement placement,
      AccountValuation inWorkspace,
      AccountValuation inOwn,
      String workspaceCurrency,
      LocalDate asOf) {
    return new AccountListItemResponse(
        account.getId(),
        account.getVersion(),
        account.getName(),
        placement.accountType(),
        inWorkspace.nature(),
        account.getPortfolio().getId(),
        account.getPortfolio().getName(),
        inOwn.currency(),
        ARCHIVED.equals(account.getStatus()),
        account.isHasTransactions(),
        SignedAmounts.of(inOwn),
        workspaceCurrency,
        SignedAmounts.of(inWorkspace),
        inWorkspace.valueKnown(),
        ValueStatus.of(inWorkspace),
        asOf,
        // The value's own date, which converting it does not change: set as long as balance is,
        // even when no FX rate gives it a value in the workspace's currency.
        inOwn.valueSourceDate(),
        inWorkspace.warnings().stream().sorted().toList());
  }
}
