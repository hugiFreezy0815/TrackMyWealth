package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateSharingGrantRequest;
import com.trackmywealth.backend.dto.ScopeTypeValues;
import com.trackmywealth.backend.dto.SharingGrantResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.SharingGrant;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.SharingGrantRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-03: grant and revoke {@code sharing_grant} rows (FR-TEN-002/008/009). The granter ({@code
 * grantedByMemberId}) is always the authenticated caller's own {@code workspace_member}, resolved
 * server-side via {@link AccessControlService#requireActingMember} - never a request field, so a
 * caller can never grant "as" someone else.
 *
 * <p>Granting (or revoking) access to a scope requires the caller to themselves currently have
 * {@code FULL} access to that same scope (the story's own error/edge case: "a member cannot share
 * what they cannot fully see") - checked via {@link AccessControlService}, the same mechanism that
 * will enforce the grant once created, so "can grant" and "can access" can never drift apart.
 * Rejected the same way any other deny-by-default lookup in this codebase is: a {@code 404}, not a
 * {@code 403} - indistinguishable from the scope target not existing at all (FR-TEN-006).
 *
 * <p>Relies on RLS for workspace isolation the same way {@code AccountOwnershipService} does:
 * {@code scopeAccountId}/{@code scopeInstitutionId}/{@code grantedToMemberId} are all looked up
 * through repositories RLS already confines to the caller's own workspace.
 */
@Service
public class SharingGrantService {

  private final WorkspaceAccessService workspaceAccessService;
  private final AccessControlService accessControlService;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AccountRepository accountRepository;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final SharingGrantRepository sharingGrantRepository;

  public SharingGrantService(
      WorkspaceAccessService workspaceAccessService,
      AccessControlService accessControlService,
      WorkspaceMemberRepository workspaceMemberRepository,
      AccountRepository accountRepository,
      FinancialInstitutionRepository financialInstitutionRepository,
      SharingGrantRepository sharingGrantRepository) {
    this.workspaceAccessService = workspaceAccessService;
    this.accessControlService = accessControlService;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.accountRepository = accountRepository;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.sharingGrantRepository = sharingGrantRepository;
  }

  @Transactional
  public SharingGrantResponse grant(
      CreateSharingGrantRequest request, AuthenticatedUserPrincipal actor) {
    requireExactlyOneScopeTarget(request);
    Workspace workspace =
        workspaceAccessService.requireWorkspace(actor.workspaceId(), "a sharing grant");
    UUID granterMemberId = accessControlService.requireActingMember(actor);
    WorkspaceMember grantedToMember =
        workspaceMemberRepository
            .findById(request.grantedToMemberId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Workspace member not found."));

    SharingGrant grant = new SharingGrant();
    grant.setWorkspace(workspace);
    grant.setScopeType(request.scopeType());
    switch (request.scopeType()) {
      case ScopeTypeValues.ACCOUNT -> {
        Account account = findAccountOrThrow(request.scopeAccountId());
        accessControlService.requireAccountAccess(actor, account, AccessLevelValues.FULL);
        grant.setScopeAccount(account);
      }
      case ScopeTypeValues.INSTITUTION -> {
        FinancialInstitution institution = findInstitutionOrThrow(request.scopeInstitutionId());
        accessControlService.requireInstitutionAccess(actor, institution, AccessLevelValues.FULL);
        grant.setScopeInstitution(institution);
      }
      case ScopeTypeValues.WORKSPACE ->
          accessControlService.requireWorkspaceAccess(
              actor, actor.workspaceId(), AccessLevelValues.FULL);
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Unsupported scopeType: " + request.scopeType());
    }

    grant.setGrantedToMember(grantedToMember);
    grant.setAccessLevel(request.accessLevel());
    grant.setGrantedByMember(workspaceMemberRepository.getReferenceById(granterMemberId));
    grant = sharingGrantRepository.saveAndFlush(grant);

    return toResponse(grant);
  }

  @Transactional
  public SharingGrantResponse revoke(UUID grantId, AuthenticatedUserPrincipal actor) {
    SharingGrant grant =
        sharingGrantRepository
            .findById(grantId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grant not found."));

    // Revoking requires the same FULL access the original grant did - not "only the original
    // granter may revoke": household membership changes over time (a granter could themselves be
    // deactivated later), and anyone who currently has FULL access to a scope is, by definition,
    // trusted to manage sharing for it.
    requireFullAccessToGrantScope(grant, actor);

    if (grant.getRevokedAt() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This grant has already been revoked.");
    }
    grant.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
    grant = sharingGrantRepository.saveAndFlush(grant);

    return toResponse(grant);
  }

  private void requireFullAccessToGrantScope(SharingGrant grant, AuthenticatedUserPrincipal actor) {
    switch (grant.getScopeType()) {
      case ScopeTypeValues.ACCOUNT ->
          accessControlService.requireAccountAccess(
              actor, grant.getScopeAccount(), AccessLevelValues.FULL);
      case ScopeTypeValues.INSTITUTION ->
          accessControlService.requireInstitutionAccess(
              actor, grant.getScopeInstitution(), AccessLevelValues.FULL);
      case ScopeTypeValues.WORKSPACE ->
          accessControlService.requireWorkspaceAccess(
              actor, grant.getWorkspace().getId(), AccessLevelValues.FULL);
      default ->
          throw new IllegalStateException(
              "Unexpected persisted scopeType: " + grant.getScopeType());
    }
  }

  // Mirrors V6's own CHECK constraint (scope_type paired with exactly the matching scope_*_id) -
  // validated here, before any lookup, for the same clean 400 AccountOwnershipService's
  // requireNoDuplicateMembers gives a malformed request, rather than surfacing the DB's own
  // constraint violation as a translated 409.
  private void requireExactlyOneScopeTarget(CreateSharingGrantRequest request) {
    boolean valid =
        switch (request.scopeType()) {
          case ScopeTypeValues.ACCOUNT ->
              request.scopeAccountId() != null && request.scopeInstitutionId() == null;
          case ScopeTypeValues.INSTITUTION ->
              request.scopeInstitutionId() != null && request.scopeAccountId() == null;
          case ScopeTypeValues.WORKSPACE ->
              request.scopeAccountId() == null && request.scopeInstitutionId() == null;
          default -> false;
        };
    if (!valid) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "scopeType "
              + request.scopeType()
              + " requires exactly the matching scope id field to be set (ACCOUNT ->"
              + " scopeAccountId, INSTITUTION -> scopeInstitutionId, WORKSPACE -> neither).");
    }
  }

  private Account findAccountOrThrow(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
  }

  private FinancialInstitution findInstitutionOrThrow(UUID institutionId) {
    return financialInstitutionRepository
        .findById(institutionId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Financial institution not found."));
  }

  private SharingGrantResponse toResponse(SharingGrant grant) {
    return new SharingGrantResponse(
        grant.getId(),
        grant.getWorkspace().getId(),
        grant.getGrantedToMember().getId(),
        grant.getScopeType(),
        grant.getScopeAccount() != null ? grant.getScopeAccount().getId() : null,
        grant.getScopeInstitution() != null ? grant.getScopeInstitution().getId() : null,
        grant.getAccessLevel(),
        grant.getGrantedByMember().getId(),
        grant.getGrantedAt(),
        grant.getRevokedAt());
  }
}
