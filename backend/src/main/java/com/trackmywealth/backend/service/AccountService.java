package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.AccountCustomAsset;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.AccountPension;
import com.trackmywealth.backend.entity.AccountSecurities;
import com.trackmywealth.backend.entity.AccountVestedBenefits;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.AccountCustomAssetRepository;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountPensionRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.AccountSecuritiesRepository;
import com.trackmywealth.backend.repository.AccountVestedBenefitsRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
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
 * #applyCapabilityDefaults}, {@link #createExtensionRowIfNeeded}) - every other layer of the app
 * (consolidation, allocation, net worth, navigation) must branch only on the capability flags this
 * method computes, never on {@code accountType} again (DM-17, US-05-04).
 *
 * <p>Like {@code InstitutionService}, relies on RLS for workspace isolation and on {@code
 * JwtAuthenticationFilter} having already refused to authenticate a request whose linked {@code
 * workspace_member} exists but isn't {@code ACTIVE} - no local "active member" check is needed here
 * either.
 */
@Service
public class AccountService {

  // Named once and reused everywhere below - PMD's AvoidDuplicateLiterals flags the same string
  // literal appearing 4+ times in one file.
  private static final String MORTGAGE = "MORTGAGE";

  private final WorkspaceRepository workspaceRepository;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final AccountRepository accountRepository;
  private final AccountSecuritiesRepository accountSecuritiesRepository;
  private final AccountCreditCardRepository accountCreditCardRepository;
  private final AccountMortgageRepository accountMortgageRepository;
  private final AccountLoanRepository accountLoanRepository;
  private final AccountPensionRepository accountPensionRepository;
  private final AccountVestedBenefitsRepository accountVestedBenefitsRepository;
  private final AccountCustomAssetRepository accountCustomAssetRepository;

  public AccountService(
      WorkspaceRepository workspaceRepository,
      FinancialInstitutionRepository financialInstitutionRepository,
      AccountRepository accountRepository,
      AccountSecuritiesRepository accountSecuritiesRepository,
      AccountCreditCardRepository accountCreditCardRepository,
      AccountMortgageRepository accountMortgageRepository,
      AccountLoanRepository accountLoanRepository,
      AccountPensionRepository accountPensionRepository,
      AccountVestedBenefitsRepository accountVestedBenefitsRepository,
      AccountCustomAssetRepository accountCustomAssetRepository) {
    this.workspaceRepository = workspaceRepository;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.accountRepository = accountRepository;
    this.accountSecuritiesRepository = accountSecuritiesRepository;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.accountMortgageRepository = accountMortgageRepository;
    this.accountLoanRepository = accountLoanRepository;
    this.accountPensionRepository = accountPensionRepository;
    this.accountVestedBenefitsRepository = accountVestedBenefitsRepository;
    this.accountCustomAssetRepository = accountCustomAssetRepository;
  }

  @Transactional
  public AccountSummaryResponse createAccount(CreateAccountRequest request, UUID actorWorkspaceId) {
    // Reachable for a SYSTEM_ADMINISTRATOR with no linked workspace_member (see
    // InstitutionService's identical guard/comment for the full reasoning).
    if (actorWorkspaceId == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Caller has no workspace of their own to create an account in.");
    }
    Workspace workspace = workspaceRepository.getReferenceById(actorWorkspaceId);
    FinancialInstitution institution =
        resolveInstitution(request.financialInstitutionId(), actorWorkspaceId);

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

    return toSummary(account);
  }

  // C2/FR-INS-011: every account has exactly one container; when the caller hasn't chosen one,
  // fall back to the workspace's own default Personal Assets container (guaranteed to exist by
  // V19's workspace_create_personal_assets_container trigger). When an id IS supplied, RLS's
  // tenant_isolation_read policy already confines the SELECT to the caller's own workspace - a
  // cross-workspace id simply isn't found, degrading safely to 404 rather than needing a
  // separate equality check (same reasoning as AdminUserService/InstitutionService).
  private FinancialInstitution resolveInstitution(UUID financialInstitutionId, UUID workspaceId) {
    if (financialInstitutionId != null) {
      return financialInstitutionRepository
          .findById(financialInstitutionId)
          .orElseThrow(
              () ->
                  new ResponseStatusException(
                      HttpStatus.NOT_FOUND, "Financial institution not found."));
    }
    return financialInstitutionRepository
        .findByWorkspaceIdAndPersonalAssetsDefaultTrue(workspaceId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "Workspace has no Personal Assets container."));
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
      case "PENSION" -> {
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
      case MORTGAGE, "LOAN" -> {
        holdsPositions = false;
        hasTransactions = true;
        hasAmortisation = true;
      }
      case "CRYPTO" -> {
        holdsPositions = true;
        hasTransactions = true;
      }
      case "CUSTOM_ASSET" -> {
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
        requireForType(request.originalPrincipal(), "originalPrincipal", MORTGAGE);
        requireForType(request.interestRatePercent(), "interestRatePercent", MORTGAGE);
        AccountMortgage extension = new AccountMortgage();
        extension.setAccount(account);
        extension.setOriginalPrincipal(request.originalPrincipal());
        extension.setInterestRatePercent(request.interestRatePercent());
        accountMortgageRepository.save(extension);
      }
      case "LOAN" -> {
        requireForType(request.originalPrincipal(), "originalPrincipal", "LOAN");
        AccountLoan extension = new AccountLoan();
        extension.setAccount(account);
        extension.setOriginalPrincipal(request.originalPrincipal());
        extension.setInterestRatePercent(request.interestRatePercent());
        accountLoanRepository.save(extension);
      }
      case "PENSION" -> {
        requireForType(request.pensionScheme(), "pensionScheme", "PENSION");
        AccountPension extension = new AccountPension();
        extension.setAccount(account);
        extension.setPensionScheme(request.pensionScheme());
        accountPensionRepository.save(extension);
      }
      case "VESTED_BENEFITS" -> {
        AccountVestedBenefits extension = new AccountVestedBenefits();
        extension.setAccount(account);
        accountVestedBenefitsRepository.save(extension);
      }
      case "CUSTOM_ASSET" -> {
        requireForType(request.customAssetType(), "customAssetType", "CUSTOM_ASSET");
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
        account.getStatus());
  }
}
