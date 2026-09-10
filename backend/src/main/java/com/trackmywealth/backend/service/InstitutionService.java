package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CreateFinancialInstitutionRequest;
import com.trackmywealth.backend.dto.FinancialInstitutionSummaryResponse;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
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
 * <p>This is the first member-facing (non-admin) workspace-scoped write in the codebase - RLS
 * ({@code app.current_workspace_id}, set per-request by {@code
 * WorkspaceContextTransactionExecutionListener}) already confines every read/write here to the
 * caller's own workspace, but says nothing about whether the caller's own {@code workspace_member}
 * row has itself been deactivated. {@link #assertActiveMember} is the baseline check this
 * establishes for every workspace-scoped write to follow: RLS plus "the caller is still an active
 * member of this workspace." Fine-grained per-account/per-institution access levels (FR-HOU-004's
 * {@code sharing_grant}) have no Java implementation yet (EPIC-03's US-03-03 is still schema-only)
 * - this story deliberately does not attempt to enforce that layer.
 */
@Service
public class InstitutionService {

  private static final String ACTIVE = "ACTIVE";

  // The institution_catalogue.country CHECK constraint (V3) currently allows only these two
  // values - this map covers exactly that, not a general-purpose country/currency registry.
  private static final Map<String, String> DEFAULT_CURRENCY_BY_COUNTRY =
      Map.of("CH", "CHF", "DE", "EUR");

  private final AppUserRepository appUserRepository;
  private final WorkspaceRepository workspaceRepository;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final InstitutionCatalogueRepository institutionCatalogueRepository;

  public InstitutionService(
      AppUserRepository appUserRepository,
      WorkspaceRepository workspaceRepository,
      FinancialInstitutionRepository financialInstitutionRepository,
      InstitutionCatalogueRepository institutionCatalogueRepository) {
    this.appUserRepository = appUserRepository;
    this.workspaceRepository = workspaceRepository;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.institutionCatalogueRepository = institutionCatalogueRepository;
  }

  @Transactional(readOnly = true)
  public Page<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      UUID actorUserId, String query, Pageable pageable) {
    assertActiveMember(actorUserId);
    Page<InstitutionCatalogue> page =
        (query == null || query.isBlank())
            ? institutionCatalogueRepository.findByActiveTrue(pageable)
            : institutionCatalogueRepository.findByActiveTrueAndNameContainingIgnoreCase(
                query, pageable);
    return page.map(this::toCatalogueSummary);
  }

  @Transactional
  public FinancialInstitutionSummaryResponse createInstitution(
      CreateFinancialInstitutionRequest request, UUID actorUserId, UUID actorWorkspaceId) {
    assertActiveMember(actorUserId);
    if (actorWorkspaceId == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Caller has no workspace of their own to create an institution in.");
    }
    // A reference, not a fetch: RLS's tenant_isolation_read policy already confines a real SELECT
    // to app.current_workspace_id, which JwtAuthenticationFilter set to this exact id when it
    // authenticated this request - re-fetching it here would be redundant, not more correct (same
    // reasoning as AdminUserService.createUser).
    Workspace workspace = workspaceRepository.getReferenceById(actorWorkspaceId);

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
    if (request.name() == null || request.name().isBlank()) {
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

  // US-04-01's baseline authorization check - see the class Javadoc. Empty when the caller has
  // no linked workspace_member at all (e.g. a SYSTEM_ADMINISTRATOR with no membership).
  private void assertActiveMember(UUID actorUserId) {
    String status = appUserRepository.findWorkspaceMemberStatus(actorUserId).orElse(null);
    if (!ACTIVE.equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Caller has no active workspace membership.");
    }
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
