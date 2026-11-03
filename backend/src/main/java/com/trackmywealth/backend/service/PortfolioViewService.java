package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountPositionCount;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.MultiCurrencyValuations;
import com.trackmywealth.backend.dto.PortfolioAccountDetails;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.PortfolioType;
import com.trackmywealth.backend.dto.PortfolioViewResponse;
import com.trackmywealth.backend.dto.PortfolioViewResponse.AccountRow;
import com.trackmywealth.backend.dto.PortfolioViewResponse.Section;
import com.trackmywealth.backend.dto.SignedAmounts;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.SnapshotHoldingRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-04-06/FR-INS-013: the Portfolio View - one portfolio tab with its total and a section per kind
 * of account, for the getquin-style overview (#377).
 *
 * <p>No new aggregation path: every figure is an {@link AccountValuation} from {@link
 * AccountValuationService#valueAllIn}, the calculation the portfolio summary and net worth use:
 * each account's value is resolved once and converted into the portfolio's currency, the
 * workspace's and its own. Which section an account belongs to is {@link
 * AccountService#portfolioPlacementsOf}'s decision; this class only reads capability flags.
 *
 * <p>The number of queries does not grow with the number of accounts beyond what {@code valueAll}
 * itself runs per account: placements, positions, card and mortgage details are each one query for
 * the whole portfolio.
 */
@Service
public class PortfolioViewService {

  private static final String ACTIVE = "ACTIVE";

  private final AccessControlService accessControlService;
  private final PortfolioLookupService portfolioLookupService;
  private final AccountRepository accountRepository;
  private final AccountService accountService;
  private final AccountValuationService accountValuationService;
  private final BusinessDateService businessDateService;
  private final SnapshotHoldingRepository snapshotHoldingRepository;
  private final AccountCreditCardRepository accountCreditCardRepository;
  private final AccountMortgageRepository accountMortgageRepository;
  private final CardStatementService cardStatementService;

  public PortfolioViewService(
      AccessControlService accessControlService,
      PortfolioLookupService portfolioLookupService,
      AccountRepository accountRepository,
      AccountService accountService,
      AccountValuationService accountValuationService,
      BusinessDateService businessDateService,
      SnapshotHoldingRepository snapshotHoldingRepository,
      AccountCreditCardRepository accountCreditCardRepository,
      AccountMortgageRepository accountMortgageRepository,
      CardStatementService cardStatementService) {
    this.accessControlService = accessControlService;
    this.portfolioLookupService = portfolioLookupService;
    this.accountRepository = accountRepository;
    this.accountService = accountService;
    this.accountValuationService = accountValuationService;
    this.businessDateService = businessDateService;
    this.snapshotHoldingRepository = snapshotHoldingRepository;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.accountMortgageRepository = accountMortgageRepository;
    this.cardStatementService = cardStatementService;
  }

  @Transactional(readOnly = true)
  public PortfolioViewResponse getView(UUID portfolioId, AuthenticatedUserPrincipal actor) {
    Portfolio portfolio = portfolioLookupService.findPortfolioOrThrow(portfolioId, actor);
    accessControlService.requirePortfolioAccess(actor, portfolio);

    List<Account> accounts =
        accountRepository.findByPortfolioIdAndStatusOrderByCreatedAtAsc(portfolioId, ACTIVE);
    LocalDate asOf = businessDateService.today();
    String portfolioCurrency = portfolio.getPortfolioCurrency();
    String workspaceCurrency = portfolio.getWorkspace().getCurrency();

    MultiCurrencyValuations valuations =
        accountValuationService.valueAllIn(
            accounts, Stream.of(portfolioCurrency, workspaceCurrency).distinct().toList(), asOf);
    Map<UUID, AccountValuation> inPortfolio = byAccount(valuations.in(portfolioCurrency));
    Map<UUID, AccountValuation> inWorkspace = byAccount(valuations.in(workspaceCurrency));
    Map<UUID, AccountValuation> inOwn = byAccount(valuations.inOwnCurrency());
    Map<UUID, PortfolioPlacement> placements = accountService.portfolioPlacementsOf(accounts);
    PortfolioAccountDetails details = details(accounts, placements);

    Map<PortfolioSection, List<Account>> bySection = new EnumMap<>(PortfolioSection.class);
    for (Account account : accounts) {
      bySection
          .computeIfAbsent(placements.get(account.getId()).section(), key -> new ArrayList<>())
          .add(account);
    }

    List<Section> sections = new ArrayList<>();
    for (Map.Entry<PortfolioSection, List<Account>> entry : bySection.entrySet()) {
      List<AccountRow> rows = new ArrayList<>();
      for (Account account : entry.getValue()) {
        UUID id = account.getId();
        rows.add(toRow(account, placements.get(id), inPortfolio.get(id), inOwn.get(id), details));
      }
      List<AccountValuation> sectionInPortfolio = valuationsOf(entry.getValue(), inPortfolio);
      List<AccountValuation> sectionInWorkspace = valuationsOf(entry.getValue(), inWorkspace);
      sections.add(
          new Section(
              entry.getKey(),
              signedSum(sectionInPortfolio),
              signedSum(sectionInWorkspace),
              allKnown(sectionInPortfolio) && allKnown(sectionInWorkspace),
              worstStatus(sectionInPortfolio, sectionInWorkspace),
              rows));
    }

    List<AccountValuation> allInPortfolio = valuationsOf(accounts, inPortfolio);
    List<AccountValuation> allInWorkspace = valuationsOf(accounts, inWorkspace);
    // PR-011/FR-CON-007: an account's warning is shown at the portfolio's headline too, from both
    // totals - a rate missing only into the workspace currency explains why that total is
    // incomplete.
    Set<String> warnings = new TreeSet<>();
    allInPortfolio.forEach(valuation -> warnings.addAll(valuation.warnings()));
    allInWorkspace.forEach(valuation -> warnings.addAll(valuation.warnings()));

    return new PortfolioViewResponse(
        portfolio.getId(),
        portfolio.getName(),
        // From the placements already at hand, not by reading the accounts a second time.
        PortfolioType.of(
            portfolio.getPortfolioTypeOverride(),
            portfolio.isPersonalAssetsDefault(),
            placements.values().stream().map(PortfolioPlacement::section).toList()),
        portfolio.getPortfolioTypeOverride() != null,
        portfolio.isPersonalAssetsDefault(),
        portfolioCurrency,
        workspaceCurrency,
        asOf,
        signedSum(allInPortfolio),
        signedSum(allInWorkspace),
        allKnown(allInPortfolio) && allKnown(allInWorkspace),
        worstStatus(allInPortfolio, allInWorkspace),
        List.copyOf(warnings),
        sections);
  }

  private static AccountRow toRow(
      Account account,
      PortfolioPlacement placement,
      AccountValuation portfolioValue,
      AccountValuation ownValue,
      PortfolioAccountDetails details) {
    UUID id = account.getId();
    boolean holdsPositions = account.isHoldsPositions();
    Integer positionsCount = holdsPositions ? details.positionsCount().get(id) : null;
    LocalDate positionsAsOf = holdsPositions ? details.positionsAsOf().get(id) : null;
    return new AccountRow(
        id,
        account.getName(),
        placement.accountType(),
        portfolioValue.nature(),
        ownValue.currency(),
        SignedAmounts.of(ownValue),
        SignedAmounts.of(portfolioValue),
        portfolioValue.valueKnown(),
        ValueStatus.of(portfolioValue),
        portfolioValue.valueSourceDate(),
        portfolioValue.warnings(),
        positionsCount,
        positionsAsOf,
        // EPIC 15: no performance calculation exists yet (PO decision on #390).
        null,
        details.dueDates().get(id),
        details.linkedProperties().get(id));
  }

  private PortfolioAccountDetails details(
      List<Account> accounts, Map<UUID, PortfolioPlacement> placements) {
    List<UUID> withPositions =
        accounts.stream().filter(Account::isHoldsPositions).map(Account::getId).toList();
    Map<UUID, Integer> positionsCount = new HashMap<>();
    Map<UUID, LocalDate> positionsAsOf = new HashMap<>();
    if (!withPositions.isEmpty()) {
      for (AccountPositionCount count :
          snapshotHoldingRepository.countPositionsInNewestHoldingsSnapshot(withPositions)) {
        positionsCount.put(count.getAccountId(), Math.toIntExact(count.getPositions()));
        positionsAsOf.put(count.getAccountId(), count.getPositionsAsOf());
      }
    }

    List<UUID> cards =
        accounts.stream().filter(Account::isHasStatementCycle).map(Account::getId).toList();
    Map<UUID, LocalDate> dueDates = new HashMap<>();
    if (!cards.isEmpty()) {
      for (AccountCreditCard card : accountCreditCardRepository.findAllById(cards)) {
        cardStatementService
            .currentDueDate(card)
            .ifPresent(dueDate -> dueDates.put(card.getAccountId(), dueDate));
      }
    }

    List<UUID> propertyAndMortgage =
        idsInSection(accounts, placements, PortfolioSection.PROPERTY_AND_MORTGAGE);
    Map<UUID, UUID> linkedProperties = new HashMap<>();
    if (!propertyAndMortgage.isEmpty()) {
      for (AccountMortgage mortgage : accountMortgageRepository.findAllById(propertyAndMortgage)) {
        if (mortgage.getLinkedAssetAccountId() != null) {
          linkedProperties.put(mortgage.getAccountId(), mortgage.getLinkedAssetAccountId());
        }
      }
    }
    return new PortfolioAccountDetails(positionsCount, positionsAsOf, dueDates, linkedProperties);
  }

  private static List<UUID> idsInSection(
      Collection<Account> accounts,
      Map<UUID, PortfolioPlacement> placements,
      PortfolioSection section) {
    return accounts.stream()
        .map(Account::getId)
        .filter(id -> placements.get(id).section() == section)
        .toList();
  }

  private static Map<UUID, AccountValuation> byAccount(List<AccountValuation> valuations) {
    Map<UUID, AccountValuation> byAccount = new HashMap<>();
    for (AccountValuation valuation : valuations) {
      byAccount.put(valuation.accountId(), valuation);
    }
    return byAccount;
  }

  private static List<AccountValuation> valuationsOf(
      List<Account> accounts, Map<UUID, AccountValuation> byAccount) {
    return accounts.stream().map(account -> byAccount.get(account.getId())).toList();
  }

  // The signed sum of the known values; an unknown one is left out, not counted as zero (PR-011).
  private static BigDecimal signedSum(List<AccountValuation> valuations) {
    BigDecimal sum = BigDecimal.ZERO;
    for (AccountValuation valuation : valuations) {
      if (valuation.valueKnown()) {
        sum = sum.add(SignedAmounts.of(valuation));
      }
    }
    return sum;
  }

  private static boolean allKnown(List<AccountValuation> valuations) {
    return valuations.stream().allMatch(AccountValuation::valueKnown);
  }

  // The worst status across both totals, so it agrees with `complete`: an account whose value is
  // known in the portfolio currency but not in the workspace's (no rate) is left out of the
  // workspace total, and the status must say so too.
  private static ValueStatus worstStatus(
      List<AccountValuation> inPortfolio, List<AccountValuation> inWorkspace) {
    List<ValueStatus> statuses = new ArrayList<>();
    inPortfolio.forEach(valuation -> statuses.add(ValueStatus.of(valuation)));
    inWorkspace.forEach(valuation -> statuses.add(ValueStatus.of(valuation)));
    return ValueStatus.worstOf(statuses);
  }
}
