package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionSummaryResponse.AccountContribution;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * where is decided by {@link AccountValuationService} from capability flags, not by branching on
 * the type string.
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
  private final InstitutionLookupService institutionLookupService;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final InstitutionCatalogueRepository institutionCatalogueRepository;
  private final AccountRepository accountRepository;
  private final AccountValuationService accountValuationService;
  private final BusinessDateService businessDateService;

  public InstitutionService(
      WorkspaceAccessService workspaceAccessService,
      AccessControlService accessControlService,
      InstitutionLookupService institutionLookupService,
      FinancialInstitutionRepository financialInstitutionRepository,
      InstitutionCatalogueRepository institutionCatalogueRepository,
      AccountRepository accountRepository,
      AccountValuationService accountValuationService,
      BusinessDateService businessDateService) {
    this.workspaceAccessService = workspaceAccessService;
    this.accessControlService = accessControlService;
    this.institutionLookupService = institutionLookupService;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.institutionCatalogueRepository = institutionCatalogueRepository;
    this.accountRepository = accountRepository;
    this.accountValuationService = accountValuationService;
    this.businessDateService = businessDateService;
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
   * <p>Each account's value comes from {@link AccountValuationService} - see its own Javadoc for
   * which account types have a value source today. An account with none contributes {@code
   * valueKnown = false} and is excluded from the totals, not counted as zero - {@link
   * InstitutionSummaryResponse#complete} makes that visible rather than silently understating the
   * totals (PR-011).
   */
  @Transactional(readOnly = true)
  public InstitutionSummaryResponse getSummary(
      UUID institutionId, AuthenticatedUserPrincipal actor) {
    FinancialInstitution institution =
        institutionLookupService.findInstitutionOrThrow(institutionId, actor);
    accessControlService.requireInstitutionAccess(
        actor, institution, AccessLevelValues.BALANCE_ONLY);

    List<Account> accounts =
        accountRepository.findByFinancialInstitutionIdAndStatusOrderByCreatedAtAsc(
            institutionId, ACTIVE);
    LocalDate asOf = businessDateService.today();

    BigDecimal totalAssets = BigDecimal.ZERO;
    BigDecimal totalLiabilities = BigDecimal.ZERO;
    boolean complete = true;
    List<AccountContribution> contributions = new ArrayList<>();

    for (Account account : accounts) {
      AccountContribution contribution =
          toContribution(
              accountValuationService.valueIn(account, institution.getContainerCurrency(), asOf));
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

  // AccountValuation is the shared DM-17 result; AccountContribution is this endpoint's own
  // (FR-INS-SUM-002) wire shape, kept field-for-field so the response contract is unchanged.
  private static AccountContribution toContribution(AccountValuation valuation) {
    return new AccountContribution(
        valuation.accountId(),
        valuation.name(),
        valuation.nature(),
        valuation.nativeCurrency(),
        valuation.value(),
        valuation.conversionRate(),
        valuation.conversionRateDate(),
        valuation.conversionRateCarriedForward(),
        valuation.conversionRateStale(),
        valuation.valueKnown());
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
