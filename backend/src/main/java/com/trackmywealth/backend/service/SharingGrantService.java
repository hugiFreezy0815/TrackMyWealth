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
import com.trackmywealth.backend.repository.FinancialInstitutionRepository;
import com.trackmywealth.backend.repository.SharingGrantRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
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
 * what they cannot fully see") - both funnel through {@link #requireFullAccessToScope}, which calls
 * {@link AccessControlService}, the same mechanism that will enforce the grant once created, so
 * "can grant"/"can revoke" and "can access" can never drift apart. Rejected the same way any other
 * deny-by-default lookup in this codebase is: a {@code 404}, not a {@code 403} - indistinguishable
 * from the scope target not existing at all (FR-TEN-006).
 *
 * <p>{@link #revoke} locks the grant row for its read-check-write sequence ({@code
 * SharingGrantRepository#findByIdForUpdate}, the same {@code @Lock(PESSIMISTIC_WRITE)} pattern
 * {@code AccountRepository}/{@code AppUserRepository} already use) - without it, two concurrent
 * revokes of the same grant would both read {@code revokedAt == null}, both pass the conflict
 * check, and both succeed, instead of the second one correctly hitting {@code 409 CONFLICT} (the
 * same race {@code AccountOwnershipService}'s own {@code findByIdForUpdate} was added to close).
 *
 * <p>Relies on RLS for workspace isolation the same way {@code AccountOwnershipService} does:
 * {@code scopeAccountId}/{@code scopeInstitutionId}/{@code grantedToMemberId} are all looked up
 * through repositories RLS already confines to the caller's own workspace.
 *
 * <p><b>#122's bootstrap exception</b>: unlike an account (which always has an owner, {@code
 * AccountService#assignInitialOwnershipToCreator}), an institution or the workspace itself has no
 * ownership fallback - so once a second active member exists, the sole-active-member rule stops
 * applying and "can't share what you can't fully see" becomes permanently unsatisfiable for {@code
 * INSTITUTION}/{@code WORKSPACE} scope: no one, including the admin who created the institution,
 * could ever hold {@code FULL} access to grant from. {@link #tryBootstrapInstitution}/{@link
 * #tryBootstrapWorkspace} are the fix: the workspace's {@code SYSTEM_ADMINISTRATOR} may create the
 * <em>first</em> non-revoked grant of an {@code INSTITUTION}/{@code WORKSPACE} scope that currently
 * has none, without needing {@code FULL} access first. This is a deliberately narrow exception to
 * RULE-018/FR-USR-010/FR-TEN-007 (role confers administration rights only, never financial-data
 * access - see {@code AppUser}'s own Javadoc, and contrast {@code
 * WorkspaceMemberRepository#countByWorkspaceIdAndStatusAndDependentFalse}'s comment on why the
 * *sole-member* bootstrap is deliberately structural, not role-based, for exactly this reason): the
 * role only ever unlocks creating that one grant, never any financial-data access of its own, and
 * once any non-revoked grant of the scope exists this exception no longer applies to that scope -
 * though it re-arms if the workspace is later revoked back down to zero, so it can never durably
 * re-enter #122's original locked-out state.
 *
 * <p>{@link #revoke} gets the symmetric counterpart, {@link #isSoleRemainingGrant}: a {@code
 * SYSTEM_ADMINISTRATOR} may revoke a scope's <em>sole</em> non-revoked grant, <b>if they are the
 * one who granted it</b>, without {@code FULL} access either. Without this, a bootstrap grant made
 * to someone other than the administrator themselves (the ordinary case - bootstrapping is usually
 * done *for* a second member, not for oneself) would be permanent: the administrator granted it
 * without ever holding {@code FULL} access themselves, so they could never revoke a mistake, and no
 * one else could either (they are, by construction, the only one with any standing on that scope at
 * all). The "granted it themselves" check (added on review, #139) is what keeps this from becoming
 * "any {@code SYSTEM_ADMINISTRATOR} may strip a scope's last standing grant regardless of who
 * created it or how" - a different administrator, or a grant that reached "sole remaining" through
 * ordinary revocation of its siblings rather than ever being a bootstrap grant, still needs {@code
 * FULL} access like anything else.
 *
 * <p>Both bootstrap checks lock the scope's own row ({@code FinancialInstitutionRepository}/{@code
 * WorkspaceRepository} {@code findByIdForUpdate}, added on review, #139) before checking whether a
 * non-revoked grant already exists: without it, two concurrent {@link #grant} calls for the same
 * scope could each observe "none exist yet" and both bypass {@link #requireFullAccessToScope} - the
 * same class of check-then-act race {@link #revoke}'s own grant-row lock already closes for itself.
 * The role check runs before either the lock or the existence query (also #139), so the common
 * non-{@code SYSTEM_ADMINISTRATOR} caller pays for neither.
 */
@Service
public class SharingGrantService {

  // No shared role-constants class exists yet (each service that checks a role defines its own,
  // e.g. AdminUserService) - matching that convention rather than introducing one for a single use.
  private static final String SYSTEM_ADMINISTRATOR = "SYSTEM_ADMINISTRATOR";

  private final WorkspaceAccessService workspaceAccessService;
  private final AccessControlService accessControlService;
  private final AccountLookupService accountLookupService;
  private final InstitutionLookupService institutionLookupService;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final SharingGrantRepository sharingGrantRepository;
  private final FinancialInstitutionRepository financialInstitutionRepository;
  private final WorkspaceRepository workspaceRepository;

  public SharingGrantService(
      WorkspaceAccessService workspaceAccessService,
      AccessControlService accessControlService,
      AccountLookupService accountLookupService,
      InstitutionLookupService institutionLookupService,
      WorkspaceMemberRepository workspaceMemberRepository,
      SharingGrantRepository sharingGrantRepository,
      FinancialInstitutionRepository financialInstitutionRepository,
      WorkspaceRepository workspaceRepository) {
    this.workspaceAccessService = workspaceAccessService;
    this.accessControlService = accessControlService;
    this.accountLookupService = accountLookupService;
    this.institutionLookupService = institutionLookupService;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.sharingGrantRepository = sharingGrantRepository;
    this.financialInstitutionRepository = financialInstitutionRepository;
    this.workspaceRepository = workspaceRepository;
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
                    accessControlService.denyAsNotFound(
                        actor, "WorkspaceMember", request.grantedToMemberId()));

    SharingGrant grant = new SharingGrant();
    grant.setWorkspace(workspace);
    grant.setScopeType(request.scopeType());
    switch (request.scopeType()) {
      case ScopeTypeValues.ACCOUNT -> {
        Account account = accountLookupService.findAccountOrThrow(request.scopeAccountId(), actor);
        requireFullAccessToScope(actor, ScopeTypeValues.ACCOUNT, account, null, null);
        grant.setScopeAccount(account);
      }
      case ScopeTypeValues.INSTITUTION -> {
        FinancialInstitution institution =
            institutionLookupService.findInstitutionOrThrow(request.scopeInstitutionId(), actor);
        if (!tryBootstrapInstitution(actor, institution.getId())) {
          requireFullAccessToScope(
              actor, ScopeTypeValues.INSTITUTION, null, institution, null);
        }
        grant.setScopeInstitution(institution);
      }
      case ScopeTypeValues.WORKSPACE -> {
        if (!tryBootstrapWorkspace(actor)) {
          requireFullAccessToScope(
              actor, ScopeTypeValues.WORKSPACE, null, null, actor.workspaceId());
        }
      }
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
            .findByIdForUpdate(grantId)
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "SharingGrant", grantId));

    UUID actingMemberId = accessControlService.requireActingMember(actor);

    // Revoking requires the same FULL access the original grant did - not "only the original
    // granter may revoke": household membership changes over time (a granter could themselves be
    // deactivated later), and anyone who currently has FULL access to a scope is, by definition,
    // trusted to manage sharing for it. #122: unless the actor is the SYSTEM_ADMINISTRATOR who
    // granted this, and it is the scope's sole remaining grant - see this class's own Javadoc.
    if (!isSoleRemainingGrant(actor, actingMemberId, grant)) {
      requireFullAccessToScope(
          actor,
          grant.getScopeType(),
          grant.getScopeAccount(),
          grant.getScopeInstitution(),
          grant.getWorkspace().getId());
    }

    if (grant.getRevokedAt() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This grant has already been revoked.");
    }
    grant.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
    grant = sharingGrantRepository.saveAndFlush(grant);

    return toResponse(grant);
  }

  // Shared by grant() and revoke() - previously two independently-maintained copies of the same
  // scopeType -> AccessControlService dispatch, one per call site. account/institution/workspaceId
  // are only ever populated for the branch matching scopeType; the other two are null and unused
  // by design (a caller passes whichever one its own scope resolution already produced). Takes the
  // already-resolved acting member id, not the actor, so grant() (which already resolved it for
  // grantedByMemberId) doesn't pay for AccessControlService.requireActingMember's
  // AppUserRepository lookup a second time - revoke() resolves it once itself instead.
  private void requireFullAccessToScope(
      AuthenticatedUserPrincipal actor,
      String scopeType,
      Account account,
      FinancialInstitution institution,
      UUID workspaceId) {
    switch (scopeType) {
      case ScopeTypeValues.ACCOUNT ->
          accessControlService.requireAccountAccess(actor, account, AccessLevelValues.FULL);
      case ScopeTypeValues.INSTITUTION ->
          accessControlService.requireInstitutionAccess(
              actor, institution, AccessLevelValues.FULL);
      case ScopeTypeValues.WORKSPACE ->
          accessControlService.requireWorkspaceAccess(
              actor, workspaceId, AccessLevelValues.FULL);
      default -> throw new IllegalStateException("Unexpected scopeType: " + scopeType);
    }
  }

  // #122: see this class's own Javadoc for the full rationale. Role checked before either the lock
  // or the existence query (#139 review), so the common non-SYSTEM_ADMINISTRATOR caller pays for
  // neither. The lock (institution row) closes the check-then-insert race between two concurrent
  // bootstrap grant() calls for the same institution (#139 review) - held until this transaction
  // commits, same as revoke()'s own grant-row lock.
  private boolean tryBootstrapInstitution(AuthenticatedUserPrincipal actor, UUID institutionId) {
    if (!SYSTEM_ADMINISTRATOR.equals(actor.role())) {
      return false;
    }
    financialInstitutionRepository.findByIdForUpdate(institutionId);
    return !sharingGrantRepository.existsByScopeInstitutionIdAndRevokedAtIsNull(institutionId);
  }

  // Same as tryBootstrapInstitution, locking the workspace row instead.
  private boolean tryBootstrapWorkspace(AuthenticatedUserPrincipal actor) {
    if (!SYSTEM_ADMINISTRATOR.equals(actor.role())) {
      return false;
    }
    workspaceRepository.findByIdForUpdate(actor.workspaceId());
    return !sharingGrantRepository.existsByScopeTypeAndRevokedAtIsNull(ScopeTypeValues.WORKSPACE);
  }

  // revoke()'s counterpart to the tryBootstrap* methods: ACCOUNT scope never qualifies (it has
  // ownership as its own permanent fallback, so this exception has no reason to extend to it).
  // Requires the actor to be the SYSTEM_ADMINISTRATOR who granted this specific row (#139 review) -
  // without that check, any SYSTEM_ADMINISTRATOR could revoke any scope's last standing grant
  // regardless of who created it or how it came to be the sole one, which is a far broader power
  // than "undo my own bootstrap mistake". Only then does it check whether any *other* non-revoked
  // grant of the same scope exists besides this one.
  private boolean isSoleRemainingGrant(
      AuthenticatedUserPrincipal actor, UUID actingMemberId, SharingGrant grant) {
    if (!SYSTEM_ADMINISTRATOR.equals(actor.role())
        || !grant.getGrantedByMember().getId().equals(actingMemberId)) {
      return false;
    }
    boolean anotherExists =
        switch (grant.getScopeType()) {
          case ScopeTypeValues.INSTITUTION ->
              sharingGrantRepository.existsByScopeInstitutionIdAndRevokedAtIsNullAndIdNot(
                  grant.getScopeInstitution().getId(), grant.getId());
          case ScopeTypeValues.WORKSPACE ->
              sharingGrantRepository.existsByScopeTypeAndRevokedAtIsNullAndIdNot(
                  ScopeTypeValues.WORKSPACE, grant.getId());
          default -> true; // ACCOUNT: never eligible, regardless of how many grants exist
        };
    return !anotherExists;
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
