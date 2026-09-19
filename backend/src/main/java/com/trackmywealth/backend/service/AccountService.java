package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.ReassignAccountInstitutionRequest;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.AccountCustomAsset;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AccountOwnership;
import com.trackmywealth.backend.entity.AccountPension;
import com.trackmywealth.backend.entity.AccountSecurities;
import com.trackmywealth.backend.entity.AccountVestedBenefits;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.AccountCustomAssetRepository;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountOwnershipRepository;
import com.trackmywealth.backend.repository.AccountPensionRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.AccountSecuritiesRepository;
import com.trackmywealth.backend.repository.AccountVestedBenefitsRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-05-01: create an account of any supported type under a container, applying the correct {@code
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
 * <p>Like {@code InstitutionService}, relies on RLS for workspace isolation and on {@code
 * JwtAuthenticationFilter} having already refused to authenticate a request whose linked {@code
 * workspace_member} exists but isn't {@code ACTIVE} - no local "active member" check is needed here
 * either.
 *
 * <p>US-03-03 follow-up: {@link #createAccount} now gives the creating member immediate {@code
 * FULL} ownership of the new account (an {@code account_ownership} row, share 1.0) in the same
 * transaction - closing the gap {@link AccessControlService}'s own Javadoc used to document as a
 * "known interaction, not a bug": an account left unowned became inaccessible to everyone,
 * including its own creator, the moment the workspace gained a second active member. This is a
 * default, not a guarantee - a later full-replacement call to {@code AccountOwnershipController}'s
 * {@code PUT .../ownership} can still reassign or remove it, same as for any other account.
 *
 * <p>US-04-04: {@link #reassignInstitution} moves an account between institution containers - see
 * its own Javadoc.
 */
@Service
public class AccountService {

  // Named once and reused everywhere below - PMD's AvoidDuplicateLiterals flags the same string
  // literal appearing 4+ times in one file.
  private static final String MORTGAGE = "MORTGAGE";
  private static final String LOAN = "LOAN";
  private static final String PENSION = "PENSION";
  private static final String CUSTOM_ASSET = "CUSTOM_ASSET";
  private static final String ACTIVE = "ACTIVE";
  private static final String ARCHIVED = "ARCHIVED";
  private static final String DELETED = "DELETED";

  // FR-LIF-006: archived accounts are restorable through the interface for 30 days; thereafter
  // they remain in the data but are no longer user-restorable.
  private static final int RESTORE_WINDOW_DAYS = 30;

  // FR-ACC-030: occupational-by-definition pension schemes (CH Pillar 2 / DE bAV) - not a
  // per-account choice the way holdsPositions is, since occupational-ness is inherent to which
  // scheme is being described, not to the provider.
  private static final Set<String> OCCUPATIONAL_PENSION_SCHEMES =
      Set.of("CH_PILLAR_2_VESTED_BENEFITS", "DE_BAV");

  private final WorkspaceAccessService workspaceAccessService;
  private final AccessControlService accessControlService;
  private final AccountLookupService accountLookupService;
  private final InstitutionLookupService institutionLookupService;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final AccountRepository accountRepository;
  private final AccountOwnershipRepository accountOwnershipRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AccountSecuritiesRepository accountSecuritiesRepository;
  private final AccountCreditCardRepository accountCreditCardRepository;
  private final AccountMortgageRepository accountMortgageRepository;
  private final AccountLoanRepository accountLoanRepository;
  private final AccountPensionRepository accountPensionRepository;
  private final AccountVestedBenefitsRepository accountVestedBenefitsRepository;
  private final AccountCustomAssetRepository accountCustomAssetRepository;

  public AccountService(
      WorkspaceAccessService workspaceAccessService,
      AccessControlService accessControlService,
      AccountLookupService accountLookupService,
      InstitutionLookupService institutionLookupService,
      FinancialInstitutionRepository financialInstitutionRepository,
      AccountRepository accountRepository,
      AccountOwnershipRepository accountOwnershipRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AccountSecuritiesRepository accountSecuritiesRepository,
      AccountCreditCardRepository accountCreditCardRepository,
      AccountMortgageRepository accountMortgageRepository,
      AccountLoanRepository accountLoanRepository,
      AccountPensionRepository accountPensionRepository,
      AccountVestedBenefitsRepository accountVestedBenefitsRepository,
      AccountCustomAssetRepository accountCustomAssetRepository) {
    this.workspaceAccessService = workspaceAccessService;
    this.accessControlService = accessControlService;
    this.accountLookupService = accountLookupService;
    this.institutionLookupService = institutionLookupService;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.accountRepository = accountRepository;
    this.accountOwnershipRepository = accountOwnershipRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.accountSecuritiesRepository = accountSecuritiesRepository;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.accountMortgageRepository = accountMortgageRepository;
    this.accountLoanRepository = accountLoanRepository;
    this.accountPensionRepository = accountPensionRepository;
    this.accountVestedBenefitsRepository = accountVestedBenefitsRepository;
    this.accountCustomAssetRepository = accountCustomAssetRepository;
  }

  @Transactional
  public AccountSummaryResponse createAccount(
      CreateAccountRequest request, AuthenticatedUserPrincipal actor) {
    // Validated first, before any DB write (or the institution lookup below) - none of these
    // checks need anything from either, so a request that's going to be rejected fails fast
    // instead of paying for an institution SELECT and an account INSERT+refresh it will never
    // keep.
    validateExtensionFields(request);
    Workspace workspace =
        workspaceAccessService.requireWorkspace(actor.workspaceId(), "an account");
    FinancialInstitution institution =
        resolveInstitution(request.financialInstitutionId(), actor.workspaceId());

    Account account = new Account();
    account.setWorkspace(workspace);
    account.setFinancialInstitution(institution);
    account.setName(request.name());
    account.setAccountType(request.accountType());
    account.setNativeCurrency(request.nativeCurrency());
    applyCapabilityDefaults(account, request);
    // saveAndFlush, not save: `nature` is a Postgres GENERATED ALWAYS AS (...) STORED column
    // (V4), only computed once the INSERT statement actually executes - a plain save() defers
    // that to end-of-transaction, so toSummary() below would read the field's still-null
    // in-memory value. Flushing forces the INSERT now, which is also what makes Hibernate's
    // @Generated support issue its post-insert refresh SELECT.
    account = accountRepository.saveAndFlush(account);

    createExtensionRowIfNeeded(account, request);
    assignInitialOwnershipToCreator(account, actor);

    return toSummary(account);
  }

  // US-03-03: see this class's own Javadoc for why this exists - share 1.0, effective today,
  // mirrors AccountOwnershipController's own "assign full ownership to oneself" shape.
  private void assignInitialOwnershipToCreator(Account account, AuthenticatedUserPrincipal actor) {
    UUID creatorMemberId = accessControlService.requireActingMember(actor);
    AccountOwnership ownership = new AccountOwnership();
    ownership.setAccount(account);
    ownership.setWorkspaceMember(workspaceMemberRepository.getReferenceById(creatorMemberId));
    ownership.setOwnershipShare(BigDecimal.ONE);
    ownership.setEffectiveFrom(LocalDate.now());
    accountOwnershipRepository.saveAndFlush(ownership);
  }

  // US-03-03: read-only access, gated at BALANCE_ONLY - not READ. AccountSummaryResponse carries
  // no monetary figures yet (no balance/valuation field exists anywhere in this codebase - that's
  // EPIC 11/16), so a BALANCE_ONLY grant would otherwise be indistinguishable from NO_ACCESS: both
  // returned 404 here, since READ was the floor. Gating at BALANCE_ONLY - the weakest tier above
  // NO_ACCESS - makes the level meaningful today (its holder can at least see this basic summary)
  // without granting it anything READ-specific doesn't already cover, since there's no
  // finer-grained data on this endpoint yet to withhold from a BALANCE_ONLY caller; a future
  // endpoint that actually exposes transaction-level detail is where the two levels would first
  // diverge in practice.
  @Transactional(readOnly = true)
  public AccountSummaryResponse getAccount(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.BALANCE_ONLY);
    return toSummary(account);
  }

  // US-05-02: full-replacement update over the account's ordinary mutable attributes (see
  // UpdateAccountRequest's own Javadoc for exactly which fields, and why financialInstitutionId/
  // status/the capability flags are deliberately excluded - each has its own story). accountType
  // and nativeCurrency are written unconditionally too, same as every other field here: when the
  // caller's value matches what's already persisted this is a no-op UPDATE, and when it doesn't,
  // V4's trg_account_type_immutable / V24's trg_account_currency_immutable reject it at the DB
  // level, translated to a clean 409 by GlobalExceptionHandler - the same "let the DB enforce the
  // invariant, translate its rejection" pattern createAccount's own extension-row triggers rely on.
  //
  // US-03-03: gated at EDIT - the AC's "B can view it but not edit it" half. Checked after
  // findAccountOrThrow (so a nonexistent/cross-workspace id still degrades to the same 404 it
  // always has) but before any field is mutated.
  @Transactional
  public AccountSummaryResponse updateAccount(
      UUID accountId, UpdateAccountRequest request, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);

    account.setName(request.name());
    account.setAccountType(request.accountType());
    account.setNativeCurrency(request.nativeCurrency());
    account.setIdentifierMasked(request.identifierMasked());
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
  public AccountSummaryResponse archiveAccount(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
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
    account = accountRepository.saveAndFlush(account);

    return toSummary(account);
  }

  // US-05-03/FR-LIF-006: the 30-day restore window is enforced here, not just left to the UI to
  // stop offering the button - a caller that goes straight to the API after the window has closed
  // must be rejected the same structured way the UI-hidden path would have been, rather than
  // silently succeeding forever.
  @Transactional
  public AccountSummaryResponse restoreAccount(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
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

    return toSummary(account);
  }

  // US-04-04/FR-INS-009/C2: moves the account to a different container - the FK update is the
  // entire change. Nothing else about the account is touched, and nothing links
  // transactions/positions/snapshots to an institution rather than to the account itself, so they
  // stay attached to account.id with no separate migration step; InstitutionService.getSummary is
  // computed live from current account state on every read, so both institutions' summaries are
  // correct "on the next read" purely as a consequence of this update - no cache to invalidate.
  //
  // Gated at EDIT on both the account and the destination institution (the story's own
  // "Authorization/privacy" line - reassignment is as sensitive as editing either side of it).
  // Cross-workspace reassignment is already excluded by RLS alone (resolveInstitution's own
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
  // account's mismodelled institution is still worth fixing.
  //
  // Currently the only account write with this guard (updateAccount, ownership and valuations
  // don't check) - deliberately not centralised yet, since nothing can produce a DELETED account
  // until account soft-delete exists; enforcing it across every write in one shared place is
  // tracked under EPIC 31 in docs/user-stories/BACKLOG-remaining-epics.md.
  @Transactional
  public AccountSummaryResponse reassignInstitution(
      UUID accountId, ReassignAccountInstitutionRequest request, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    if (DELETED.equals(account.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "A deleted account cannot be reassigned to a different institution.");
    }

    FinancialInstitution destination =
        institutionLookupService.findInstitutionOrThrow(request.financialInstitutionId());
    accessControlService.requireInstitutionAccess(actor, destination, AccessLevelValues.EDIT);

    if (!account.getWorkspace().getId().equals(destination.getWorkspace().getId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Financial institution not found.");
    }

    account.setFinancialInstitution(destination);
    account = accountRepository.saveAndFlush(account);

    return toSummary(account);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  // C2/FR-INS-011: every account has exactly one container; when the caller hasn't chosen one,
  // fall back to the workspace's own default Personal Assets container (guaranteed to exist by
  // V19's workspace_create_personal_assets_container trigger). When an id IS supplied, RLS's
  // tenant_isolation_read policy already confines the SELECT to the caller's own workspace - a
  // cross-workspace id simply isn't found, degrading safely to 404 rather than needing a
  // separate equality check (same reasoning as AdminUserService/InstitutionService) - delegated to
  // InstitutionLookupService, shared with reassignInstitution/SharingGrantService.
  private FinancialInstitution resolveInstitution(UUID financialInstitutionId, UUID workspaceId) {
    if (financialInstitutionId != null) {
      return institutionLookupService.findInstitutionOrThrow(financialInstitutionId);
    }
    return financialInstitutionRepository
        .findByWorkspaceIdAndPersonalAssetsDefaultTrue(workspaceId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "Workspace has no Personal Assets container."));
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
      case "SECURITIES" -> {
        holdsPositions = true;
        hasTransactions = true;
      }
      case "MANAGED_MANDATE" -> {
        holdsPositions = true;
        hasTransactions = true;
        discretionary = true;
      }
      case PENSION -> {
        holdsPositions = false;
        hasTransactions = true;
        hasContributionLimit = true;
      }
      case "VESTED_BENEFITS" -> {
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
      case "SECURITIES", "MANAGED_MANDATE" -> {
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
      case "VESTED_BENEFITS" -> {
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
        account.getFinancialInstitution().getId(),
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
        account.getStatus(),
        account.getArchivedAt());
  }
}
