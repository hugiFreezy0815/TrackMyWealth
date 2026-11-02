package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.PortfolioSection;
import com.trackmywealth.backend.dto.PortfolioType;
import com.trackmywealth.backend.dto.ReassignAccountPortfolioRequest;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.AccountCustomAsset;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AccountPension;
import com.trackmywealth.backend.entity.AccountSecurities;
import com.trackmywealth.backend.entity.AccountVestedBenefits;
import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.AccountCustomAssetRepository;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountPensionRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.AccountSecuritiesRepository;
import com.trackmywealth.backend.repository.AccountVestedBenefitsRepository;
import com.trackmywealth.backend.repository.PortfolioRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-05-01: create an account of any supported type in a portfolio, applying the correct {@code
 * account_type}-driven capability defaults and (where the type has one) the matching extension
 * table row - both in the same transaction.
 *
 * <p>This is the one place {@code accountType} itself is allowed to drive behaviour ({@link
 * #applyCapabilityDefaults}, {@link #validateExtensionFields}, {@link #createExtensionRowIfNeeded}
 * - kept as three passes over the same 11-value switch rather than one, since validation must run
 * before {@link Account} is persisted but extension-row creation needs the persisted account's id;
 * if a 12th account type is ever added, all three, plus {@link CreateAccountRequest}'s own {@code
 * accountType} pattern, need updating together) - every other layer of the app (consolidation,
 * allocation, net worth, navigation) must branch only on the capability flags this method computes,
 * never on {@code accountType} again (DM-17, US-05-04).
 *
 * <p>Like {@code PortfolioService}, relies on RLS for workspace isolation and on {@code
 * JwtAuthenticationFilter} having already refused to authenticate a request whose linked {@code
 * workspace_member} exists but isn't {@code ACTIVE} - no local "active member" check is needed here
 * either.
 *
 * <p>US-03-06: an account belongs to its workspace, not to a member - every active member of the
 * workspace can see and change it (FR-HOU-007), and creating one records no owner.
 *
 * <p>US-04-04: {@link #reassignPortfolio} moves an account between portfolios - see its own
 * Javadoc.
 */
@Service
public class AccountService {

  // Named once and reused everywhere below - PMD's AvoidDuplicateLiterals flags the same string
  // literal appearing 4+ times in one file.
  private static final String MORTGAGE = "MORTGAGE";
  private static final String LOAN = "LOAN";
  private static final String PENSION = "PENSION";
  private static final String CUSTOM_ASSET = "CUSTOM_ASSET";
  private static final String SAVINGS = "SAVINGS";
  private static final String SECURITIES = "SECURITIES";
  private static final String MANAGED_MANDATE = "MANAGED_MANDATE";
  private static final String VESTED_BENEFITS = "VESTED_BENEFITS";
  // US-07-22: what the bank calls the pocket; the member may rename it.
  private static final String SAVINGS_POCKET_NAME_SUFFIX = " \u2013 Sparkonto";
  private static final String ACTIVE = "ACTIVE";
  // US-10-01: the types whose accounts count as saving by default (the type -> capability mapping
  // lives in this service, like every other capability default).
  private static final Set<String> SAVING_ACCOUNT_TYPES =
      Set.of("SAVINGS", SECURITIES, MANAGED_MANDATE, "CRYPTO", PENSION, VESTED_BENEFITS);
  private static final String ARCHIVED = "ARCHIVED";
  private static final String DELETED = "DELETED";
  // Names the resource in a 412 VERSION_CONFLICT detail (VersionPreconditionService).
  private static final String VERSIONED_RESOURCE = "account";

  // FR-LIF-006: archived accounts are restorable through the interface for 30 days; thereafter
  // they remain in the data but are no longer user-restorable.
  private static final int RESTORE_WINDOW_DAYS = 30;

  // FR-ACC-030: occupational-by-definition pension schemes (CH Pillar 2 / DE bAV) - not a
  // per-account choice the way holdsPositions is, since occupational-ness is inherent to which
  // scheme is being described, not to the provider.
  private static final Set<String> OCCUPATIONAL_PENSION_SCHEMES =
      Set.of("CH_PILLAR_2_VESTED_BENEFITS", "DE_BAV");

  // US-04-06: the Portfolio View section of every account type that needs nothing else to decide
  // it (PO decision on #390, 2026-11-02). PENSION and CUSTOM_ASSET depend on their extension row,
  // see sectionOf.
  private static final Map<String, PortfolioSection> SECTION_BY_ACCOUNT_TYPE =
      Map.of(
          "CASH",
          PortfolioSection.CASH_AND_SAVINGS,
          SAVINGS,
          PortfolioSection.CASH_AND_SAVINGS,
          SECURITIES,
          PortfolioSection.DEPOT,
          MANAGED_MANDATE,
          PortfolioSection.DEPOT,
          "CRYPTO",
          PortfolioSection.CRYPTO,
          "CREDIT_CARD",
          PortfolioSection.CARDS,
          VESTED_BENEFITS,
          PortfolioSection.PENSION,
          MORTGAGE,
          PortfolioSection.PROPERTY_AND_MORTGAGE,
          LOAN,
          PortfolioSection.OTHER);
  private static final String PILLAR_3A_SCHEME = "CH_PILLAR_3A";
  private static final String REAL_ESTATE = "REAL_ESTATE";

  private final WorkspaceAccessService workspaceAccessService;
  private final AccessControlService accessControlService;
  private final AccountLookupService accountLookupService;
  private final VersionPreconditionService versionPreconditionService;
  private final PortfolioLookupService portfolioLookupService;
  private final PortfolioRepository portfolioRepository;
  private final AccountRepository accountRepository;
  private final AccountSecuritiesRepository accountSecuritiesRepository;
  private final AccountCreditCardRepository accountCreditCardRepository;
  private final AccountMortgageRepository accountMortgageRepository;
  private final AccountLoanRepository accountLoanRepository;
  private final AccountPensionRepository accountPensionRepository;
  private final AccountVestedBenefitsRepository accountVestedBenefitsRepository;
  private final AccountCustomAssetRepository accountCustomAssetRepository;
  private final AccountDataQualityService accountDataQualityService;
  private final ReconciliationService reconciliationService;

  public AccountService(
      WorkspaceAccessService workspaceAccessService,
      AccessControlService accessControlService,
      AccountLookupService accountLookupService,
      VersionPreconditionService versionPreconditionService,
      PortfolioLookupService portfolioLookupService,
      PortfolioRepository portfolioRepository,
      AccountRepository accountRepository,
      AccountSecuritiesRepository accountSecuritiesRepository,
      AccountCreditCardRepository accountCreditCardRepository,
      AccountMortgageRepository accountMortgageRepository,
      AccountLoanRepository accountLoanRepository,
      AccountPensionRepository accountPensionRepository,
      AccountVestedBenefitsRepository accountVestedBenefitsRepository,
      AccountCustomAssetRepository accountCustomAssetRepository,
      AccountDataQualityService accountDataQualityService,
      ReconciliationService reconciliationService) {
    this.workspaceAccessService = workspaceAccessService;
    this.accessControlService = accessControlService;
    this.accountLookupService = accountLookupService;
    this.versionPreconditionService = versionPreconditionService;
    this.portfolioLookupService = portfolioLookupService;
    this.portfolioRepository = portfolioRepository;
    this.accountRepository = accountRepository;
    this.accountSecuritiesRepository = accountSecuritiesRepository;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.accountMortgageRepository = accountMortgageRepository;
    this.accountLoanRepository = accountLoanRepository;
    this.accountPensionRepository = accountPensionRepository;
    this.accountVestedBenefitsRepository = accountVestedBenefitsRepository;
    this.accountCustomAssetRepository = accountCustomAssetRepository;
    this.accountDataQualityService = accountDataQualityService;
    this.reconciliationService = reconciliationService;
  }

  @Transactional
  public AccountSummaryResponse createAccount(
      CreateAccountRequest request, AuthenticatedUserPrincipal actor) {
    // Validated first, before any DB write (or the portfolio lookup below) - none of these
    // checks need anything from either, so a request that's going to be rejected fails fast
    // instead of paying for a portfolio SELECT and an account INSERT+refresh it will never
    // keep.
    validateExtensionFields(request);
    // Only an active member creates accounts: a principal without a membership is refused here
    // with the same 404 as any other workspace-scoped request, before anything is written.
    accessControlService.requireActingMember(actor);
    Workspace workspace =
        workspaceAccessService.requireWorkspace(actor.workspaceId(), "an account");
    Portfolio portfolio = resolvePortfolio(request.portfolioId(), actor);

    Account account = insertAccount(workspace, portfolio, request);

    return toSummary(account);
  }

  // The account of a validated request, with its capability defaults and extension row - shared
  // by createAccount and createSavingsPocket.
  private Account insertAccount(
      Workspace workspace, Portfolio portfolio, CreateAccountRequest request) {
    Account account = new Account();
    account.setWorkspace(workspace);
    account.setPortfolio(portfolio);
    account.setName(request.name());
    account.setAccountType(request.accountType());
    account.setNativeCurrency(request.nativeCurrency());
    applyCapabilityDefaults(account, request);
    // saveAndFlush, not save: `nature` is a Postgres GENERATED ALWAYS AS (...) STORED column
    // (V4), only computed once the INSERT statement actually executes - a plain save() defers
    // that to end-of-transaction, so toSummary() would read the field's still-null in-memory
    // value. Flushing forces the INSERT now, which is also what makes Hibernate's @Generated
    // support issue its post-insert refresh SELECT.
    account = accountRepository.saveAndFlush(account);
    createExtensionRowIfNeeded(account, request);
    // After the extension row: a pension's section depends on its scheme.
    refreshPortfolioTypes(List.of(portfolio));
    return account;
  }

  /**
   * US-07-22 (#325): creates the savings pocket of {@code parent} - a {@code SAVINGS} account in
   * {@code currency} in the same portfolio, named after it - and links it as the parent's pocket.
   * The import commit that first books a pocket move calls this, in its own transaction, under the
   * parent's import lock, so a parent never gains two pockets. The link is written on its own, not
   * by saving {@code parent}: the entity was loaded before the import lock and may be stale.
   */
  @Transactional
  public Account createSavingsPocket(Account parent, String currency) {
    CreateAccountRequest request =
        new CreateAccountRequest(
            parent.getPortfolio().getId(),
            parent.getName() + SAVINGS_POCKET_NAME_SUFFIX,
            SAVINGS,
            currency,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    validateExtensionFields(request);
    Account pocket = insertAccount(parent.getWorkspace(), parent.getPortfolio(), request);

    accountRepository.linkSavingsPocket(parent.getId(), pocket.getId());
    return pocket;
  }

  @Transactional(readOnly = true)
  public AccountSummaryResponse getAccount(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    return toSummary(account);
  }

  // US-05-02: full-replacement update over the account's ordinary mutable attributes (see
  // UpdateAccountRequest's own Javadoc for exactly which fields, and why portfolioId/
  // status/the capability flags are deliberately excluded - each has its own story). accountType
  // and nativeCurrency are written unconditionally too, same as every other field here: when the
  // caller's value matches what's already persisted this is a no-op UPDATE, and when it doesn't,
  // V4's trg_account_type_immutable / V24's trg_account_currency_immutable reject it at the DB
  // level, translated to a clean 409 by GlobalExceptionHandler - the same "let the DB enforce the
  // invariant, translate its rejection" pattern createAccount's own extension-row triggers rely on.
  //
  // US-03-06: any member of the account's workspace may change it. Checked after
  // findAccountOrThrow (so a nonexistent/cross-workspace id still degrades to the same 404 it
  // always has) but before any field is mutated.
  @Transactional
  public AccountSummaryResponse updateAccount(
      UUID accountId,
      UpdateAccountRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    versionPreconditionService.requireCurrent(
        expectedVersion, account.getVersion(), VERSIONED_RESOURCE);

    account.setName(request.name());
    account.setAccountType(request.accountType());
    account.setNativeCurrency(request.nativeCurrency());
    account.setJurisdiction(request.jurisdiction());
    account.setOpenedAt(request.openedAt());
    account.setClosedAt(request.closedAt());
    // flush, not a plain save: forces the UPDATE (and any trigger rejection) to happen here,
    // inside this method, rather than deferred to end-of-transaction commit.
    account = accountRepository.saveAndFlush(account);

    return toSummary(account);
  }

  // US-05-03/FR-STA-001: ACTIVE -> ARCHIVED is the only transition out of ACTIVE this method
  // allows; archiving an already-ARCHIVED account is a transition FR-STA-001's state table doesn't
  // list, so it's rejected the same way an out-of-table transition anywhere else in this codebase
  // is (see e.g. V4/V24's immutability triggers) - a structured 409, not a silent no-op.
  @Transactional
  public AccountSummaryResponse archiveAccount(
      UUID accountId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    versionPreconditionService.requireCurrent(
        expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    // Checking specifically for "not ACTIVE" rather than "already ARCHIVED": status also admits
    // DELETED (V4's own CHECK constraint), which FR-STA-001 defines as terminal - reachable from
    // ACTIVE only, and reachable from nowhere once there. An "already ARCHIVED" check alone would
    // let this method resurrect a DELETED account by silently accepting a DELETED -> ARCHIVED
    // transition FR-STA-001 doesn't list.
    if (!ACTIVE.equals(account.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only an active account can be archived.");
    }

    account.setStatus(ARCHIVED);
    account.setArchivedAt(now());
    // US-07-10: no import file is matched to an archived account, so it gives up its identifier
    // (hash and masked value together, chk_account_identifier_masked): a successor account can
    // register it. A restored account registers it again.
    account.setIdentifierHash(null);
    account.setIdentifierMasked(null);
    account.setContractIdentifierHash(null);
    account.setContractIdentifierMasked(null);
    account = accountRepository.saveAndFlush(account);
    refreshPortfolioTypes(List.of(account.getPortfolio()));

    return toSummary(account);
  }

  // US-05-03/FR-LIF-006: the 30-day restore window is enforced here, not just left to the UI to
  // stop offering the button - a caller that goes straight to the API after the window has closed
  // must be rejected the same structured way the UI-hidden path would have been, rather than
  // silently succeeding forever.
  @Transactional
  public AccountSummaryResponse restoreAccount(
      UUID accountId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    versionPreconditionService.requireCurrent(
        expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    if (!ARCHIVED.equals(account.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not archived.");
    }
    // isBefore, not isBefore-or-equal: FR-LIF-006 says "restorable for 30 days", read as
    // inclusive of the 30th day itself - the cutoff is the instant *after* archivedAt + 30 days,
    // not that instant itself.
    if (account.getArchivedAt().isBefore(now().minusDays(RESTORE_WINDOW_DAYS))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This account was archived more than 30 days ago (FR-LIF-006) and can no longer be"
              + " restored.");
    }

    account.setStatus(ACTIVE);
    account.setArchivedAt(null);
    account = accountRepository.saveAndFlush(account);
    refreshPortfolioTypes(List.of(account.getPortfolio()));

    return toSummary(account);
  }

  // US-04-04/FR-INS-009/C2: moves the account to a different portfolio - the FK update is the
  // entire change. Nothing else about the account is touched, and nothing links
  // transactions/positions/snapshots to a portfolio rather than to the account itself, so they
  // stay attached to account.id with no separate migration step; PortfolioService.getSummary is
  // computed live from current account state on every read, so both portfolios' summaries are
  // correct "on the next read" purely as a consequence of this update - no cache to invalidate.
  //
  // Checked on both the account and the destination portfolio (the story's own
  // "Authorization/privacy" line - reassignment is as sensitive as editing either side of it).
  // Cross-workspace reassignment is already excluded by RLS alone (resolvePortfolio's own
  // comment: a cross-workspace id simply isn't found) since both lookups run under the same
  // request's app.current_workspace_id - but the story's error/edge-case text asks for this
  // explicitly as a named service-layer check, not just implicit isolation, so it's asserted here
  // too as deliberate defense-in-depth, one layer beyond this codebase's usual "RLS is sufficient"
  // convention for cross-tenant integrity.
  //
  // FR-STA-001: DELETED is terminal - reachable from ACTIVE only, reachable from nowhere once
  // there (the same principle archiveAccount's own status check documents) - so a DELETED account
  // is rejected here, the one status this method doesn't otherwise care about. ACTIVE and ARCHIVED
  // are both allowed: unlike archive/restore, this isn't a lifecycle transition, so an archived
  // account's mismodelled portfolio is still worth fixing.
  //
  // Currently the only account write with this guard (updateAccount and valuations don't
  // check) - deliberately not centralised yet, since nothing can produce a DELETED account
  // until account soft-delete exists; enforcing it across every write in one shared place is
  // tracked under EPIC 31 in docs/user-stories/BACKLOG-remaining-epics.md.
  @Transactional
  public AccountSummaryResponse reassignPortfolio(
      UUID accountId,
      ReassignAccountPortfolioRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    versionPreconditionService.requireCurrent(
        expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    if (DELETED.equals(account.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A deleted account cannot be moved to a different portfolio.");
    }

    Portfolio destination =
        portfolioLookupService.findPortfolioOrThrow(request.portfolioId(), actor);
    accessControlService.requirePortfolioAccess(actor, destination);

    if (!account.getWorkspace().getId().equals(destination.getWorkspace().getId())) {
      throw accessControlService.denyAsNotFound(actor, "Portfolio", request.portfolioId());
    }

    Portfolio source = account.getPortfolio();
    account.setPortfolio(destination);
    account = accountRepository.saveAndFlush(account);
    refreshPortfolioTypes(List.of(source, destination));

    return toSummary(account);
  }

  /**
   * US-04-06/FR-INS-013: the Portfolio View placement of each of {@code accounts}, by account id:
   * its type, for the client to label it, and its section. A Pillar 3a is a {@code PENSION} account
   * with scheme {@code CH_PILLAR_3A}, property a {@code CUSTOM_ASSET} of type {@code REAL_ESTATE};
   * every other type has a fixed section. This is the one place outside the capability flags that
   * reads {@code account_type} (US-05-04), so the view and the suggested portfolio type never
   * branch on it themselves.
   *
   * <p>Two queries at most, however many accounts: the pension and custom-asset extension rows of
   * the accounts that need one. Performs no access check - pass only accounts the caller may see.
   */
  @Transactional(readOnly = true)
  public Map<UUID, PortfolioPlacement> portfolioPlacementsOf(Collection<Account> accounts) {
    List<UUID> pensionIds = idsOfType(accounts, PENSION);
    List<UUID> customAssetIds = idsOfType(accounts, CUSTOM_ASSET);
    Set<UUID> pillar3a =
        pensionIds.isEmpty()
            ? Set.of()
            : accountPensionRepository.findAllById(pensionIds).stream()
                .filter(pension -> PILLAR_3A_SCHEME.equals(pension.getPensionScheme()))
                .map(AccountPension::getAccountId)
                .collect(Collectors.toUnmodifiableSet());
    Set<UUID> realEstate =
        customAssetIds.isEmpty()
            ? Set.of()
            : accountCustomAssetRepository.findAllById(customAssetIds).stream()
                .filter(asset -> REAL_ESTATE.equals(asset.getCustomAssetType()))
                .map(AccountCustomAsset::getAccountId)
                .collect(Collectors.toUnmodifiableSet());

    Map<UUID, PortfolioPlacement> placements = new HashMap<>();
    for (Account account : accounts) {
      placements.put(
          account.getId(),
          new PortfolioPlacement(
              account.getAccountType(), sectionOf(account, pillar3a, realEstate)));
    }
    return placements;
  }

  /**
   * US-04-06/FR-INS-006: recomputes the suggested type of each of {@code portfolios} from its
   * active accounts ({@link PortfolioType#suggestedFor}) and stores it unless the member has set
   * one. Runs inside the caller's transaction, after an account was added, moved, archived or
   * restored.
   *
   * <p>Each portfolio is locked first ({@link PortfolioRepository#lockForTypeRefresh}), so its
   * accounts are read only once no other refresh of it is in flight: two members adding accounts to
   * one portfolio at the same time both end up counted. Portfolios are locked in id order, so two
   * moves in opposite directions cannot deadlock.
   */
  @Transactional
  public void refreshPortfolioTypes(Collection<Portfolio> portfolios) {
    Map<UUID, Portfolio> byId = new TreeMap<>();
    for (Portfolio portfolio : portfolios) {
      byId.put(portfolio.getId(), portfolio);
    }
    for (Portfolio portfolio : byId.values()) {
      portfolioRepository.lockForTypeRefresh(portfolio.getId());
      List<Account> active =
          accountRepository.findByPortfolioIdAndStatusOrderByCreatedAtAsc(
              portfolio.getId(), ACTIVE);
      PortfolioType suggested = suggestedTypeOf(portfolio, active);
      portfolioRepository.applySuggestedType(portfolio.getId(), suggested.name());
    }
  }

  /**
   * US-04-06: the type {@code portfolio} would be suggested with {@code activeAccounts} as its
   * active accounts ({@link PortfolioType#suggestedFor}). Performs no access check.
   */
  @Transactional(readOnly = true)
  public PortfolioType suggestedTypeOf(Portfolio portfolio, Collection<Account> activeAccounts) {
    return PortfolioType.suggestedFor(
        portfolio.isPersonalAssetsDefault(),
        portfolioPlacementsOf(activeAccounts).values().stream()
            .map(PortfolioPlacement::section)
            .toList());
  }

  private static List<UUID> idsOfType(Collection<Account> accounts, String accountType) {
    return accounts.stream()
        .filter(account -> accountType.equals(account.getAccountType()))
        .map(Account::getId)
        .toList();
  }

  private static PortfolioSection sectionOf(
      Account account, Set<UUID> pillar3a, Set<UUID> realEstate) {
    String accountType = account.getAccountType();
    if (PENSION.equals(accountType)) {
      return pillar3a.contains(account.getId())
          ? PortfolioSection.PILLAR_3A
          : PortfolioSection.PENSION;
    }
    if (CUSTOM_ASSET.equals(accountType)) {
      return realEstate.contains(account.getId())
          ? PortfolioSection.PROPERTY_AND_MORTGAGE
          : PortfolioSection.OTHER;
    }
    return SECTION_BY_ACCOUNT_TYPE.getOrDefault(accountType, PortfolioSection.OTHER);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  // C2/FR-INS-011: every account has exactly one portfolio; when the caller hasn't chosen one,
  // fall back to the workspace's own default Personal Assets portfolio (guaranteed to exist by
  // V19's workspace_create_personal_assets_container trigger). When an id IS supplied, RLS's
  // tenant_isolation_read policy already confines the SELECT to the caller's own workspace - a
  // cross-workspace id simply isn't found, degrading safely to 404 rather than needing a
  // separate equality check (same reasoning as AdminUserService/PortfolioService) - delegated to
  // PortfolioLookupService, shared with reassignPortfolio.
  private Portfolio resolvePortfolio(UUID portfolioId, AuthenticatedUserPrincipal actor) {
    if (portfolioId != null) {
      return portfolioLookupService.findPortfolioOrThrow(portfolioId, actor);
    }
    return portfolioRepository
        .findByWorkspaceIdAndPersonalAssetsDefaultTrue(actor.workspaceId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "Workspace has no Personal Assets portfolio."));
  }

  // Extension-table-required fields that have no type-wide default (see CreateAccountRequest's
  // Javadoc for why these four, specifically, can't just be left null the way e.g.
  // account_securities' columns can). Runs before anything is persisted - see createAccount's own
  // comment on why this ordering matters.
  private void validateExtensionFields(CreateAccountRequest request) {
    switch (request.accountType()) {
      case MORTGAGE -> {
        requireForType(request.originalPrincipal(), "originalPrincipal", MORTGAGE);
        requireForType(request.interestRatePercent(), "interestRatePercent", MORTGAGE);
      }
      case LOAN -> requireForType(request.originalPrincipal(), "originalPrincipal", LOAN);
      case PENSION -> requireForType(request.pensionScheme(), "pensionScheme", PENSION);
      case CUSTOM_ASSET ->
          requireForType(request.customAssetType(), "customAssetType", CUSTOM_ASSET);
      default -> {
        // Every other type either has no extension table or has one with no NOT NULL columns
        // lacking a default (account_securities, account_vested_benefits) - nothing to validate.
      }
    }
  }

  // FR-ACC-010/011/012: the account_type -> capability-flag mapping. Two of these (SECURITIES,
  // CASH) come directly from the story's own acceptance criteria; the rest are this service's own
  // considered defaults, since neither the schema nor the docs enumerate them - each is driven by
  // the presence/shape of that type's extension table (V5) and the `nature` column (V4).
  // holdsPositions is the one flag every type also accepts an explicit override for afterward
  // (PENSION is the case that actually needs it - see the class/DTO Javadoc - but the mechanism
  // isn't type-restricted, since a capability flag is a declared property, not implied by type).
  private void applyCapabilityDefaults(Account account, CreateAccountRequest request) {
    boolean holdsPositions;
    boolean hasTransactions;
    boolean hasStatementCycle = false;
    boolean hasAmortisation = false;
    boolean hasContributionLimit = false;
    boolean discretionary = false;
    boolean manualValuation = false;

    switch (request.accountType()) {
      case "CASH", "SAVINGS" -> {
        holdsPositions = false;
        hasTransactions = true;
      }
      case SECURITIES -> {
        holdsPositions = true;
        hasTransactions = true;
      }
      case MANAGED_MANDATE -> {
        holdsPositions = true;
        hasTransactions = true;
        discretionary = true;
      }
      case PENSION -> {
        holdsPositions = false;
        hasTransactions = true;
        hasContributionLimit = true;
      }
      case VESTED_BENEFITS -> {
        holdsPositions = false;
        hasTransactions = false;
      }
      case "CREDIT_CARD" -> {
        holdsPositions = false;
        hasTransactions = true;
        hasStatementCycle = true;
      }
      case MORTGAGE, LOAN -> {
        holdsPositions = false;
        hasTransactions = true;
        hasAmortisation = true;
      }
      case "CRYPTO" -> {
        holdsPositions = true;
        hasTransactions = true;
      }
      case CUSTOM_ASSET -> {
        holdsPositions = false;
        hasTransactions = false;
        manualValuation = true;
      }
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Unsupported account type: " + request.accountType());
    }

    if (request.holdsPositions() != null) {
      holdsPositions = request.holdsPositions();
    }
    // US-10-01/FR-CF-003: where money moved in is saving or investing rather than a neutral
    // transfer between current accounts. A default the member may override either way.
    boolean countsAsSaving =
        request.countsAsSaving() != null
            ? request.countsAsSaving()
            : SAVING_ACCOUNT_TYPES.contains(request.accountType());
    account.setCountsAsSaving(countsAsSaving);

    account.setHoldsPositions(holdsPositions);
    account.setHasTransactions(hasTransactions);
    account.setHasStatementCycle(hasStatementCycle);
    account.setHasAmortisation(hasAmortisation);
    account.setHasContributionLimit(hasContributionLimit);
    account.setDiscretionary(discretionary);
    account.setManualValuation(manualValuation);
  }

  // DB-09/DB-10: one extension table per subtype that has distinct, constrained attributes -
  // CASH/SAVINGS/CRYPTO get none (V5's own comment). trg_extension_type_guard (V5, V23) enforces
  // the account_type/extension-table pairing at the DB level regardless of what this method does.
  // Required-field checks already happened in validateExtensionFields, before account was ever
  // persisted - none are repeated here.
  private void createExtensionRowIfNeeded(Account account, CreateAccountRequest request) {
    switch (account.getAccountType()) {
      case SECURITIES, MANAGED_MANDATE -> {
        AccountSecurities extension = new AccountSecurities();
        extension.setAccount(account);
        accountSecuritiesRepository.save(extension);
      }
      case "CREDIT_CARD" -> {
        AccountCreditCard extension = new AccountCreditCard();
        extension.setAccount(account);
        // A sensible default, not an invented figure (unlike originalPrincipal etc. below) - the
        // card's own native currency is a reasonable default billing currency.
        extension.setBillingCurrency(
            request.billingCurrency() != null
                ? request.billingCurrency()
                : account.getNativeCurrency());
        accountCreditCardRepository.save(extension);
      }
      case MORTGAGE -> {
        AccountMortgage extension = new AccountMortgage();
        extension.setAccount(account);
        extension.setOriginalPrincipal(request.originalPrincipal());
        extension.setInterestRatePercent(request.interestRatePercent());
        accountMortgageRepository.save(extension);
      }
      case LOAN -> {
        AccountLoan extension = new AccountLoan();
        extension.setAccount(account);
        extension.setOriginalPrincipal(request.originalPrincipal());
        extension.setInterestRatePercent(request.interestRatePercent());
        accountLoanRepository.save(extension);
      }
      case PENSION -> {
        AccountPension extension = new AccountPension();
        extension.setAccount(account);
        extension.setPensionScheme(request.pensionScheme());
        extension.setOccupational(OCCUPATIONAL_PENSION_SCHEMES.contains(request.pensionScheme()));
        accountPensionRepository.save(extension);
      }
      case VESTED_BENEFITS -> {
        AccountVestedBenefits extension = new AccountVestedBenefits();
        extension.setAccount(account);
        accountVestedBenefitsRepository.save(extension);
      }
      case CUSTOM_ASSET -> {
        AccountCustomAsset extension = new AccountCustomAsset();
        extension.setAccount(account);
        extension.setCustomAssetType(request.customAssetType());
        accountCustomAssetRepository.save(extension);
      }
      default -> {
        // CASH, SAVINGS, CRYPTO: no extension table.
      }
    }
  }

  private void requireForType(Object value, String fieldName, String accountType) {
    if (value == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " is required for " + accountType + " accounts.");
    }
  }

  private AccountSummaryResponse toSummary(Account account) {
    return new AccountSummaryResponse(
        account.getId(),
        account.getPortfolio().getId(),
        account.getName(),
        account.getAccountType(),
        account.getNativeCurrency(),
        account.getNature(),
        account.isHoldsPositions(),
        account.isHasTransactions(),
        account.isHasStatementCycle(),
        account.isHasAmortisation(),
        account.isHasContributionLimit(),
        account.isDiscretionary(),
        account.isManualValuation(),
        account.isCountsAsSaving(),
        account.getSavingsPocketAccountId(),
        account.getStatus(),
        account.getArchivedAt(),
        VersionPreconditionService.persistedVersion(account.getVersion(), VERSIONED_RESOURCE),
        // PR-011/FR-CON-007: the warning is shown on the account, not only on its figures.
        accountDataQualityService.warningsFor(account.getId()),
        reconciliationService.status(account));
  }
}
