package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse.AccountContribution;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountLoan;
import com.trackmywealth.backend.entity.AccountMortgage;
import com.trackmywealth.backend.entity.CustomAssetValuation;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountLoanRepository;
import com.trackmywealth.backend.repository.AccountMortgageRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-04-01: search the shared institution catalogue and create a workspace's own {@link
 * FinancialInstitution} container from a catalogue entry or as a custom one.
 *
 * <p>This is the first member-facing (non-admin) workspace-scoped write in the codebase. RLS
 * ({@code app.current_workspace_id}, set per-request by {@code
 * WorkspaceContextTransactionExecutionListener}) confines every read/write here to the caller's own
 * workspace; {@code JwtAuthenticationFilter} additionally refuses to authenticate a request at all
 * when the caller's linked {@code workspace_member} exists but isn't {@code ACTIVE} - so by the
 * time a request reaches this service, a workspace context is either absent entirely (a {@code
 * SYSTEM_ADMINISTRATOR} with no membership, handled by the {@code actorWorkspaceId == null} check
 * in {@link #createInstitution}) or genuinely active. {@link #getSummary} is the first method here
 * to gate on the fine-grained per-institution access level {@link AccessControlService} computes
 * (US-03-03, merged after this class's other methods were written) - {@code createInstitution}/
 * {@code searchCatalogue} still rely on RLS/workspace-existence alone, unchanged by this story.
 *
 * <p>US-04-03: {@link #getSummary} reads only {@code institution_type} to manage the institution
 * itself, never {@code account_type} directly (the sanctioned exception this class's own Javadoc
 * already claims, and the one {@code ArchitectureTest} enforces) - which account's value comes from
 * where is decided by capability flags ({@code Account#isManualValuation}, {@code
 * Account#isHasAmortisation}), not by branching on the type string.
 */
@Service
public class InstitutionService {

  // The institution_catalogue.country CHECK constraint (V3) currently allows only these two
  // values - this map covers exactly that, not a general-purpose country/currency registry.
  private static final Map<String, String> DEFAULT_CURRENCY_BY_COUNTRY =
      Map.of("CH", "CHF", "DE", "EUR");

  private static final String ACTIVE = "ACTIVE";
  private static final String ASSET = "ASSET";

  private final WorkspaceAccessService workspaceAccessService;
  private final AccessControlService accessControlService;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final InstitutionCatalogueRepository institutionCatalogueRepository;
  private final AccountRepository accountRepository;
  private final AccountMortgageRepository accountMortgageRepository;
  private final AccountLoanRepository accountLoanRepository;
  private final CustomAssetValuationRepository customAssetValuationRepository;
  private final FxRateService fxRateService;
  private final String fxDefaultSource;

  public InstitutionService(
      WorkspaceAccessService workspaceAccessService,
      AccessControlService accessControlService,
      FinancialInstitutionRepository financialInstitutionRepository,
      InstitutionCatalogueRepository institutionCatalogueRepository,
      AccountRepository accountRepository,
      AccountMortgageRepository accountMortgageRepository,
      AccountLoanRepository accountLoanRepository,
      CustomAssetValuationRepository customAssetValuationRepository,
      FxRateService fxRateService,
      @Value("${app.fx.default-source}") String fxDefaultSource) {
    this.workspaceAccessService = workspaceAccessService;
    this.accessControlService = accessControlService;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.institutionCatalogueRepository = institutionCatalogueRepository;
    this.accountRepository = accountRepository;
    this.accountMortgageRepository = accountMortgageRepository;
    this.accountLoanRepository = accountLoanRepository;
    this.customAssetValuationRepository = customAssetValuationRepository;
    this.fxRateService = fxRateService;
    this.fxDefaultSource = fxDefaultSource;
  }

  @Transactional(readOnly = true)
  public Page<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      String query, Pageable pageable) {
    Page<InstitutionCatalogue> page =
        (query == null || query.isBlank())
            ? institutionCatalogueRepository.findByActiveTrue(pageable)
            : institutionCatalogueRepository.findByActiveTrueAndNameContainingIgnoreCase(
                query, pageable);
    return page.map(this::toCatalogueSummary);
  }

  @Transactional
  public FinancialInstitutionSummaryResponse createInstitution(
      CreateFinancialInstitutionRequest request, UUID actorWorkspaceId) {
    // Reachable for a SYSTEM_ADMINISTRATOR with no linked workspace_member - see the class
    // Javadoc. Anyone with an active membership always has a non-null workspaceId by the time
    // JwtAuthenticationFilter authenticates the request.
    Workspace workspace =
        workspaceAccessService.requireWorkspace(actorWorkspaceId, "an institution");

    FinancialInstitution institution = new FinancialInstitution();
    institution.setWorkspace(workspace);
    if (request.catalogueInstitutionId() != null) {
      applyCatalogueEntry(institution, request);
    } else {
      applyCustomEntry(institution, request);
    }

    institution = financialInstitutionRepository.save(institution);
    return toSummary(institution);
  }

  /**
   * US-04-03/FR-INS-SUM-001..004: total assets, liabilities and net value across every
   * currently-active account under the institution, in the container currency. C7: an institution
   * with zero accounts returns a valid, all-zero, {@code complete} summary, not an error.
   *
   * <p>Only {@code CUSTOM_ASSET} (via {@link CustomAssetValuation}) and {@code MORTGAGE}/{@code
   * LOAN} (via their {@code original_principal} - a real stored number, but the loan's original
   * amount, not its current outstanding balance; no amortization tracking exists yet, EPIC 10) have
   * any value source in this codebase today. Every other account type contributes {@code valueKnown
   * = false} and is excluded from the totals, not counted as zero - {@link
   * InstitutionSummaryResponse#complete} makes that visible rather than silently understating the
   * totals (PR-011).
   */
  @Transactional(readOnly = true)
  public InstitutionSummaryResponse getSummary(
      UUID institutionId, AuthenticatedUserPrincipal actor) {
    FinancialInstitution institution =
        financialInstitutionRepository
            .findById(institutionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Institution not found."));
    accessControlService.requireInstitutionAccess(
        actor, institution, AccessLevelValues.BALANCE_ONLY);

    List<Account> accounts =
        accountRepository.findByFinancialInstitutionIdAndStatus(institutionId, ACTIVE);
    LocalDate asOf = LocalDate.now();

    BigDecimal totalAssets = BigDecimal.ZERO;
    BigDecimal totalLiabilities = BigDecimal.ZERO;
    boolean complete = true;
    List<AccountContribution> contributions = new ArrayList<>();

    for (Account account : accounts) {
      AccountContribution contribution =
          toContribution(account, institution.getContainerCurrency(), asOf);
      contributions.add(contribution);
      if (!contribution.valueKnown()) {
        complete = false;
      } else if (ASSET.equals(contribution.nature())) {
        totalAssets = totalAssets.add(contribution.valueInContainerCurrency());
      } else {
        totalLiabilities = totalLiabilities.add(contribution.valueInContainerCurrency());
      }
    }

    // FR-INS-SUM-001: never suppressed or clamped to zero - a container mixing assets and
    // liabilities is expected to show a negative net value, not an error.
    BigDecimal netValue = totalAssets.subtract(totalLiabilities);
    return new InstitutionSummaryResponse(
        institutionId,
        institution.getContainerCurrency(),
        totalAssets,
        totalLiabilities,
        netValue,
        complete,
        contributions);
  }

  private AccountContribution toContribution(
      Account account, String containerCurrency, LocalDate asOf) {
    Optional<BigDecimal> nativeValue = resolveNativeValue(account, asOf);
    if (nativeValue.isEmpty()) {
      return new AccountContribution(
          account.getId(),
          account.getName(),
          account.getNature(),
          account.getNativeCurrency(),
          null,
          null,
          null,
          false);
    }

    if (account.getNativeCurrency().equals(containerCurrency)) {
      return new AccountContribution(
          account.getId(),
          account.getName(),
          account.getNature(),
          account.getNativeCurrency(),
          nativeValue.get(),
          null,
          null,
          true);
    }

    // FR-CUR-011/US-06-03: a current holding's value converts at the valuation date (today), not
    // at any date tied to when the account or its value was originally recorded - see
    // docs/architecture/calculation-methodology.md.
    try {
      CurrencyConversionResult conversion =
          fxRateService.getConversionRate(
              account.getNativeCurrency(), containerCurrency, asOf, fxDefaultSource);
      BigDecimal convertedValue =
          fxRateService.convert(
              nativeValue.get(),
              account.getNativeCurrency(),
              containerCurrency,
              asOf,
              fxDefaultSource);
      return new AccountContribution(
          account.getId(),
          account.getName(),
          account.getNature(),
          account.getNativeCurrency(),
          convertedValue,
          conversion.rate(),
          asOf,
          true);
    } catch (ResponseStatusException e) {
      // PR-012: no FX rate available for this pair at all - degrade this one account to unknown
      // rather than failing the whole summary just because one foreign-currency account among
      // several has no stored rate.
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return new AccountContribution(
            account.getId(),
            account.getName(),
            account.getNature(),
            account.getNativeCurrency(),
            null,
            null,
            null,
            false);
      }
      throw e;
    }
  }

  // US-05-04/ArchitectureTest's only_account_service_branches_on_account_type: never reads
  // account.getAccountType() - which value source applies is decided by capability flags instead.
  // isHasAmortisation() is true for exactly MORTGAGE and LOAN (US-05-01), so exactly one of the
  // two lookups below ever finds a row; isManualValuation() is true for exactly CUSTOM_ASSET
  // (US-05-05).
  private Optional<BigDecimal> resolveNativeValue(Account account, LocalDate asOf) {
    if (account.isManualValuation()) {
      return customAssetValuationRepository
          .findFirstByAccountIdAndValuationDateLessThanEqualOrderByValuationDateDesc(
              account.getId(), asOf)
          .map(CustomAssetValuation::getValue);
    }
    if (account.isHasAmortisation()) {
      return accountMortgageRepository
          .findById(account.getId())
          .map(AccountMortgage::getOriginalPrincipal)
          .or(
              () ->
                  accountLoanRepository
                      .findById(account.getId())
                      .map(AccountLoan::getOriginalPrincipal));
    }
    return Optional.empty();
  }

  private void applyCatalogueEntry(
      FinancialInstitution institution, CreateFinancialInstitutionRequest request) {
    InstitutionCatalogue catalogue =
        institutionCatalogueRepository
            .findById(request.catalogueInstitutionId())
            .filter(InstitutionCatalogue::isActive)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Catalogue institution not found."));

    institution.setCatalogueInstitution(catalogue);
    institution.setName(catalogue.getName());
    institution.setCountry(catalogue.getCountry());
    institution.setInstitutionType(catalogue.getInstitutionType());
    institution.setIdentifier(catalogue.getIdentifier());
    institution.setLogoUrl(catalogue.getLogoUrl());

    String currency = request.containerCurrency();
    if (currency == null) {
      currency = DEFAULT_CURRENCY_BY_COUNTRY.get(catalogue.getCountry());
      if (currency == null) {
        // Not reachable while institution_catalogue.country is CHECK-constrained to CH/DE
        // (both covered above) - a defensive guard against that constraint ever widening
        // without this map being updated to match.
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "No default currency for country '"
                + catalogue.getCountry()
                + "'; containerCurrency must be supplied explicitly.");
      }
    }
    institution.setContainerCurrency(currency);
  }

  private void applyCustomEntry(
      FinancialInstitution institution, CreateFinancialInstitutionRequest request) {
    if (request.name() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "name is required for a custom institution.");
    }
    if (request.containerCurrency() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "containerCurrency is required for a custom institution.");
    }

    institution.setName(request.name());
    institution.setCountry(request.country());
    institution.setInstitutionType(
        request.institutionType() != null ? request.institutionType() : "OTHER");
    institution.setIdentifier(request.identifier());
    institution.setLogoUrl(request.logoUrl());
    institution.setContainerCurrency(request.containerCurrency());
  }

  private InstitutionCatalogueEntrySummaryResponse toCatalogueSummary(
      InstitutionCatalogue catalogue) {
    return new InstitutionCatalogueEntrySummaryResponse(
        catalogue.getId(),
        catalogue.getName(),
        catalogue.getCountry(),
        catalogue.getInstitutionType(),
        catalogue.getIdentifier(),
        catalogue.getLogoUrl());
  }

  private FinancialInstitutionSummaryResponse toSummary(FinancialInstitution institution) {
    return new FinancialInstitutionSummaryResponse(
        institution.getId(),
        institution.getCatalogueInstitution() != null
            ? institution.getCatalogueInstitution().getId()
            : null,
        institution.getName(),
        institution.getCountry(),
        institution.getInstitutionType(),
        institution.getIdentifier(),
        institution.getLogoUrl(),
        institution.getContainerCurrency(),
        institution.isPersonalAssetsDefault(),
        institution.getStatus());
  }
}
