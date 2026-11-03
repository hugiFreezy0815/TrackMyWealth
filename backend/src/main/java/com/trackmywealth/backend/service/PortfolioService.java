package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.PortfolioSummaryResponse;
import com.trackmywealth.backend.dto.PortfolioSummaryResponse.AccountContribution;
import com.trackmywealth.backend.dto.PortfolioType;
import com.trackmywealth.backend.dto.UpdatePortfolioRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.repository.PortfolioRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.validation.CurrencyCodes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-04-01/03/06: a workspace's own {@link Portfolio}s - created from an institution catalogue
 * entry or freely named (FR-INS-001/003), read, renamed and typed, and summed up.
 *
 * <p>RLS ({@code app.current_workspace_id}, set per-request by {@code
 * WorkspaceContextTransactionExecutionListener}) confines every read/write here to the caller's own
 * workspace; {@code JwtAuthenticationFilter} additionally refuses to authenticate a request at all
 * when the caller's linked {@code workspace_member} exists but isn't {@code ACTIVE}. Every method
 * additionally checks workspace membership through {@link AccessControlService}, so an
 * administrator who may not act as a member (FR-TEN-007) is refused here like anywhere else, and
 * every member has full access (US-03-06).
 *
 * <p>This class is the one place that reads {@code institution_type} ({@code ArchitectureTest}),
 * and only to describe the portfolio itself, never {@code account_type}: which account's value
 * comes from where is decided by {@link AccountValuationService} from capability flags, and which
 * section an account belongs to by {@link AccountService#portfolioPlacementsOf}.
 */
@Service
public class PortfolioService {

  // The institution_catalogue.country CHECK constraint (V3) currently allows only these two
  // values - this map covers exactly that, not a general-purpose country/currency registry.
  private static final Map<String, String> DEFAULT_CURRENCY_BY_COUNTRY =
      Map.of("CH", "CHF", "DE", "EUR");

  private static final String ACTIVE = "ACTIVE";
  private static final String ASSET = "ASSET";
  // Names the resource in a 412 VERSION_CONFLICT detail (VersionPreconditionService).
  private static final String VERSIONED_RESOURCE = "portfolio";

  private final WorkspaceAccessService workspaceAccessService;
  private final AccessControlService accessControlService;
  private final PortfolioLookupService portfolioLookupService;
  private final VersionPreconditionService versionPreconditionService;
  private final PortfolioRepository portfolioRepository;
  private final InstitutionCatalogueRepository institutionCatalogueRepository;
  private final AccountRepository accountRepository;
  private final AccountService accountService;
  private final AccountValuationService accountValuationService;
  private final BusinessDateService businessDateService;

  public PortfolioService(
      WorkspaceAccessService workspaceAccessService,
      AccessControlService accessControlService,
      PortfolioLookupService portfolioLookupService,
      VersionPreconditionService versionPreconditionService,
      PortfolioRepository portfolioRepository,
      InstitutionCatalogueRepository institutionCatalogueRepository,
      AccountRepository accountRepository,
      AccountService accountService,
      AccountValuationService accountValuationService,
      BusinessDateService businessDateService) {
    this.workspaceAccessService = workspaceAccessService;
    this.accessControlService = accessControlService;
    this.portfolioLookupService = portfolioLookupService;
    this.versionPreconditionService = versionPreconditionService;
    this.portfolioRepository = portfolioRepository;
    this.institutionCatalogueRepository = institutionCatalogueRepository;
    this.accountRepository = accountRepository;
    this.accountService = accountService;
    this.accountValuationService = accountValuationService;
    this.businessDateService = businessDateService;
  }

  @Transactional
  public PortfolioResponse createPortfolio(
      CreatePortfolioRequest request, AuthenticatedUserPrincipal actor) {
    // Only an active member creates portfolios, as for accounts: a principal without a
    // membership, or an administrator once the workspace has another member (FR-TEN-007, audited),
    // is refused with the same 404 as any other workspace-scoped request, before anything is
    // written.
    accessControlService.requireActingMember(actor);
    Workspace workspace =
        workspaceAccessService.requireWorkspace(actor.workspaceId(), "a portfolio");

    Portfolio portfolio = new Portfolio();
    portfolio.setWorkspace(workspace);
    if (request.catalogueInstitutionId() != null) {
      applyCatalogueEntry(portfolio, request);
    } else {
      applyCustomEntry(portfolio, request);
    }
    // saveAndFlush: the response carries the version the row was stored with (@Generated).
    portfolio = portfolioRepository.saveAndFlush(portfolio);
    return toResponse(portfolio);
  }

  @Transactional(readOnly = true)
  public PortfolioResponse getPortfolio(UUID portfolioId, AuthenticatedUserPrincipal actor) {
    Portfolio portfolio = portfolioLookupService.findPortfolioOrThrow(portfolioId, actor);
    accessControlService.requirePortfolioAccess(actor, portfolio);
    return toResponse(portfolio);
  }

  /**
   * US-04-06: full replacement of the portfolio's name, country, logo and type ({@link
   * UpdatePortfolioRequest}), under {@code If-Match} (ADR 0004). A type the member sets is stored
   * and kept as set (FR-INS-006); none clears it, and the type is suggested from the portfolio's
   * active accounts again ({@link #typeOf}). The Personal Assets default keeps {@code
   * PERSONAL_ASSETS}: setting a type on it is a 409, and no other portfolio can be given that type
   * ({@link PortfolioType#SELECTABLE_PATTERN}).
   */
  @Transactional
  public PortfolioResponse updatePortfolio(
      UUID portfolioId,
      UpdatePortfolioRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    Portfolio portfolio = portfolioLookupService.findPortfolioOrThrow(portfolioId, actor);
    accessControlService.requirePortfolioAccess(actor, portfolio);
    versionPreconditionService.requireCurrent(
        expectedVersion, portfolio.getVersion(), VERSIONED_RESOURCE);

    if (portfolio.isPersonalAssetsDefault() && request.portfolioType() != null) {
      // PO decision on #390: the Personal Assets default is always PERSONAL_ASSETS.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The type of the Personal Assets portfolio cannot be set; send portfolioType null.");
    }

    portfolio.setName(request.name());
    portfolio.setCountry(request.country());
    portfolio.setLogoUrl(request.logoUrl());
    if (request.portfolioType() == null) {
      portfolio.setPortfolioTypeOverride(null);
    } else {
      portfolio.setPortfolioTypeOverride(PortfolioType.valueOf(request.portfolioType()));
    }

    // flush, not a plain save: the response's version is the one the row was stored with.
    portfolio = portfolioRepository.saveAndFlush(portfolio);
    return toResponse(portfolio);
  }

  /**
   * US-04-03/FR-INS-SUM-001..004: total assets, liabilities and net value across every
   * currently-active account in the portfolio, in the portfolio currency - or, US-06-05, in {@code
   * requestedCurrency} when one is given (validated after the access check, like {@code
   * AccountValuationService#getBalance}). C7: a portfolio with zero accounts returns a valid,
   * all-zero, {@code complete} summary, not an error.
   *
   * <p>Each account's value comes from {@link AccountValuationService} - see its own Javadoc for
   * which account types have a value source today. An account with none contributes {@code
   * valueKnown = false} and is excluded from the totals, not counted as zero - {@link
   * PortfolioSummaryResponse#complete} makes that visible rather than silently understating the
   * totals (PR-011).
   */
  @Transactional(readOnly = true)
  public PortfolioSummaryResponse getSummary(
      UUID portfolioId, AuthenticatedUserPrincipal actor, String requestedCurrency) {
    Portfolio portfolio = portfolioLookupService.findPortfolioOrThrow(portfolioId, actor);
    accessControlService.requirePortfolioAccess(actor, portfolio);

    List<Account> accounts =
        accountRepository.findByPortfolioIdAndStatusOrderByCreatedAtAsc(portfolioId, ACTIVE);
    LocalDate asOf = businessDateService.today();
    String displayCurrency =
        CurrencyCodes.requestedOrDefault(requestedCurrency, portfolio.getPortfolioCurrency());
    List<AccountValuation> valuations =
        accountValuationService.valueAll(accounts, displayCurrency, asOf);

    BigDecimal totalAssets = BigDecimal.ZERO;
    BigDecimal totalLiabilities = BigDecimal.ZERO;
    boolean complete = true;
    List<AccountContribution> contributions = new ArrayList<>();
    // PR-011/FR-CON-007: an account's warning is shown at the portfolio's headline too.
    Set<String> warnings = new TreeSet<>();

    for (AccountValuation valuation : valuations) {
      AccountContribution contribution = toContribution(valuation);
      contributions.add(contribution);
      warnings.addAll(contribution.warnings());
      if (!contribution.valueKnown()) {
        complete = false;
      } else if (ASSET.equals(contribution.nature())) {
        totalAssets = totalAssets.add(contribution.value());
      } else {
        totalLiabilities = totalLiabilities.add(contribution.value());
      }
    }

    // FR-INS-SUM-001: never suppressed or clamped to zero - a portfolio mixing assets and
    // liabilities is expected to show a negative net value, not an error.
    BigDecimal netValue = totalAssets.subtract(totalLiabilities);
    return new PortfolioSummaryResponse(
        portfolioId,
        portfolio.getPortfolioCurrency(),
        displayCurrency,
        totalAssets,
        totalLiabilities,
        netValue,
        complete,
        List.copyOf(warnings),
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
        valuation.valueKnown(),
        valuation.warnings());
  }

  private void applyCatalogueEntry(Portfolio portfolio, CreatePortfolioRequest request) {
    InstitutionCatalogue catalogue =
        institutionCatalogueRepository
            .findById(request.catalogueInstitutionId())
            .filter(InstitutionCatalogue::isActive)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Catalogue institution not found."));

    portfolio.setCatalogueInstitution(catalogue);
    // FR-INS-001: the member may name the portfolio; the catalogue's name is the default.
    portfolio.setName(request.name() != null ? request.name() : catalogue.getName());
    portfolio.setCountry(catalogue.getCountry());
    portfolio.setInstitutionType(catalogue.getInstitutionType());
    portfolio.setIdentifier(catalogue.getIdentifier());
    portfolio.setLogoUrl(catalogue.getLogoUrl());

    String currency = request.portfolioCurrency();
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
                + "'; portfolioCurrency must be supplied explicitly.");
      }
    }
    portfolio.setPortfolioCurrency(currency);
  }

  private void applyCustomEntry(Portfolio portfolio, CreatePortfolioRequest request) {
    if (request.name() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "name is required for a portfolio without a catalogue entry.");
    }
    if (request.portfolioCurrency() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "portfolioCurrency is required for a portfolio without a catalogue entry.");
    }

    portfolio.setName(request.name());
    portfolio.setCountry(request.country());
    portfolio.setInstitutionType(
        request.institutionType() != null ? request.institutionType() : "OTHER");
    portfolio.setIdentifier(request.identifier());
    portfolio.setLogoUrl(request.logoUrl());
    portfolio.setPortfolioCurrency(request.portfolioCurrency());
  }

  /**
   * US-04-06/FR-INS-006: the portfolio's type, by the rule of {@link PortfolioType#of} - the one
   * the member set, else the one suggested from its active accounts' sections ({@link
   * AccountService#portfolioPlacementsOf}). Reads the accounts only when there is no type set.
   * Performs no access check.
   */
  private PortfolioType typeOf(Portfolio portfolio) {
    if (portfolio.getPortfolioTypeOverride() != null) {
      return portfolio.getPortfolioTypeOverride();
    }
    List<Account> active =
        accountRepository.findByPortfolioIdAndStatusOrderByCreatedAtAsc(portfolio.getId(), ACTIVE);
    return PortfolioType.suggestedFor(
        portfolio.isPersonalAssetsDefault(),
        accountService.portfolioPlacementsOf(active).values().stream()
            .map(PortfolioPlacement::section)
            .toList());
  }

  private PortfolioResponse toResponse(Portfolio portfolio) {
    return new PortfolioResponse(
        portfolio.getId(),
        portfolio.getCatalogueInstitution() != null
            ? portfolio.getCatalogueInstitution().getId()
            : null,
        portfolio.getName(),
        portfolio.getCountry(),
        portfolio.getInstitutionType(),
        portfolio.getIdentifier(),
        portfolio.getLogoUrl(),
        portfolio.getPortfolioCurrency(),
        typeOf(portfolio),
        portfolio.getPortfolioTypeOverride() != null,
        portfolio.isPersonalAssetsDefault(),
        portfolio.getStatus(),
        VersionPreconditionService.persistedVersion(portfolio.getVersion(), VERSIONED_RESOURCE));
  }
}
