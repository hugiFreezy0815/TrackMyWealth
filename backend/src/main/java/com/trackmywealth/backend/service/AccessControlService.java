package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.SharingGrant;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AccountOwnershipRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.SharingGrantRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-03/FR-TEN-002/008/009: the one place every workspace-scoped read/write consults {@code
 * account_ownership} + {@code sharing_grant} to decide whether the acting member may proceed (the
 * story's own "Authorization/privacy" line) - not a per-controller reimplementation. Deny is always
 * surfaced as {@link HttpStatus#NOT_FOUND}, identical to a genuinely nonexistent/cross-workspace id
 * (FR-TEN-006/US-28-02's existing convention), so a denied caller can never distinguish "doesn't
 * exist" from "exists but you can't see it".
 *
 * <p>An actor's effective access level to a resource ({@link #effectiveAccessLevel}, the single
 * core computation every {@code require*Access}/{@code *AccessLevel} method below funnels through),
 * strongest source wins:
 *
 * <ol>
 *   <li>The sole active member of a workspace has implicit {@code FULL} access to everything in it.
 *       With only one active member, no {@code sharing_grant} could possibly exist yet to grant
 *       them access to their own workspace's data - this is what makes the workspace usable
 *       immediately after {@code SetupService} bootstraps it, before any grant has ever been
 *       created. Deliberately structural, not role-based: {@link AppUser#getRole()} confers
 *       administration rights only, never financial-data access (see its own Javadoc) - a {@code
 *       SYSTEM_ADMINISTRATOR} in a multi-member workspace gets no special treatment here.
 *   <li>An account's currently-effective owner ({@code account_ownership}, {@link
 *       AccountOwnershipRepository}) has implicit {@code FULL} access to that one account without
 *       needing a {@code sharing_grant} row of their own - a grant is only needed to extend access
 *       to a non-owner. {@code AccountService.createAccount} gives the creator this immediately, so
 *       an account is never left ownerless in the first place (see its own Javadoc).
 *   <li>Otherwise, the highest {@code access_level} among every non-revoked {@code sharing_grant}
 *       applicable to the resource: an {@code ACCOUNT}-scope grant for that account, an {@code
 *       INSTITUTION}-scope grant for the account's institution, or a {@code WORKSPACE}-scope grant
 *       - broader scopes cascade down, matching the story's own "one account, one institution, or
 *       the whole workspace" description. No applicable grant means {@code NO_ACCESS} (FR-TEN-002:
 *       deny by default).
 * </ol>
 */
@Service
public class AccessControlService {

  private final AppUserRepository appUserRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AccountOwnershipRepository accountOwnershipRepository;
  private static final String ACCOUNT_ENTITY_TYPE = "Account";
  private static final String INSTITUTION_ENTITY_TYPE = "FinancialInstitution";
  private static final String WORKSPACE_ENTITY_TYPE = "Workspace";

  private final SharingGrantRepository sharingGrantRepository;
  private final AuthorizationDenialAuditService authorizationDenialAuditService;

  public AccessControlService(
      AppUserRepository appUserRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AccountOwnershipRepository accountOwnershipRepository,
      SharingGrantRepository sharingGrantRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService) {
    this.appUserRepository = appUserRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.accountOwnershipRepository = accountOwnershipRepository;
    this.sharingGrantRepository = sharingGrantRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
  }

  @Transactional(readOnly = true)
  public void requireAccountAccess(
      AuthenticatedUserPrincipal actor, Account account, String requiredLevel) {
    UUID memberId = requireActingMember(actor);
    if (!atLeast(accountAccessLevel(memberId, account), requiredLevel)) {
      throw denyAsNotFound(actor, ACCOUNT_ENTITY_TYPE, account.getId());
    }
  }

  // memberId overload: for a caller that has already resolved the acting member's id for its own
  // purposes (e.g. SharingGrantService.grant() needs it for grantedByMemberId regardless), so it
  // isn't forced to pay for requireActingMember's AppUserRepository lookup a second time just to
  // also gate the same request.
  @Transactional(readOnly = true)
  public void requireAccountAccess(UUID memberId, Account account, String requiredLevel) {
    if (!atLeast(accountAccessLevel(memberId, account), requiredLevel)) {
      throw denyMemberAsNotFound(memberId, ACCOUNT_ENTITY_TYPE, account.getId());
    }
  }

  // Non-throwing, bulk counterpart of requireAccountAccess, for an aggregation (e.g. net worth)
  // that
  // must silently leave out the accounts the member cannot see rather than 404 the whole request.
  // Bulk on purpose: the sole-active-member rule is a per-workspace count, identical for every
  // account in that workspace, so it is resolved once per workspace here instead of once per
  // account.
  @Transactional(readOnly = true)
  public List<Account> accountsWithAccess(
      UUID memberId, Collection<Account> accounts, String requiredLevel) {
    Map<UUID, Boolean> soleActiveMemberByWorkspace = new HashMap<>();
    return accounts.stream()
        .filter(
            account ->
                atLeast(
                    effectiveAccessLevel(
                        memberId,
                        soleActiveMemberByWorkspace.computeIfAbsent(
                            account.getWorkspace().getId(), this::isSoleActiveMember),
                        account.getId(),
                        account.getFinancialInstitution().getId(),
                        true),
                    requiredLevel))
        .toList();
  }

  @Transactional(readOnly = true)
  public void requireInstitutionAccess(
      AuthenticatedUserPrincipal actor, FinancialInstitution institution, String requiredLevel) {
    UUID memberId = requireActingMember(actor);
    if (!atLeast(institutionAccessLevel(memberId, institution), requiredLevel)) {
      throw denyAsNotFound(actor, INSTITUTION_ENTITY_TYPE, institution.getId());
    }
  }

  @Transactional(readOnly = true)
  public void requireInstitutionAccess(
      UUID memberId, FinancialInstitution institution, String requiredLevel) {
    if (!atLeast(institutionAccessLevel(memberId, institution), requiredLevel)) {
      throw denyMemberAsNotFound(memberId, INSTITUTION_ENTITY_TYPE, institution.getId());
    }
  }

  @Transactional(readOnly = true)
  public void requireWorkspaceAccess(
      AuthenticatedUserPrincipal actor, UUID workspaceId, String requiredLevel) {
    UUID memberId = requireActingMember(actor);
    if (!atLeast(workspaceAccessLevel(memberId, workspaceId), requiredLevel)) {
      throw denyAsNotFound(actor, WORKSPACE_ENTITY_TYPE, workspaceId);
    }
  }

  @Transactional(readOnly = true)
  public void requireWorkspaceAccess(UUID memberId, UUID workspaceId, String requiredLevel) {
    if (!atLeast(workspaceAccessLevel(memberId, workspaceId), requiredLevel)) {
      throw denyMemberAsNotFound(memberId, WORKSPACE_ENTITY_TYPE, workspaceId);
    }
  }

  // Public, level-returning counterparts of the require* methods above - for a future caller that
  // needs to know what an actor can do (e.g. to render available actions in a UI) rather than gate
  // one specific action, without re-deriving the sole-member/ownership/grants logic itself. Each
  // has the same actor/memberId overload pair as its require* counterpart, for the same reason.
  @Transactional(readOnly = true)
  public String accountAccessLevel(AuthenticatedUserPrincipal actor, Account account) {
    return accountAccessLevel(requireActingMember(actor), account);
  }

  @Transactional(readOnly = true)
  public String accountAccessLevel(UUID memberId, Account account) {
    return effectiveAccessLevel(
        memberId,
        account.getWorkspace().getId(),
        account.getId(),
        account.getFinancialInstitution().getId(),
        true);
  }

  @Transactional(readOnly = true)
  public String institutionAccessLevel(
      AuthenticatedUserPrincipal actor, FinancialInstitution institution) {
    return institutionAccessLevel(requireActingMember(actor), institution);
  }

  @Transactional(readOnly = true)
  public String institutionAccessLevel(UUID memberId, FinancialInstitution institution) {
    return effectiveAccessLevel(
        memberId, institution.getWorkspace().getId(), null, institution.getId(), false);
  }

  @Transactional(readOnly = true)
  public String workspaceAccessLevel(AuthenticatedUserPrincipal actor, UUID workspaceId) {
    return workspaceAccessLevel(requireActingMember(actor), workspaceId);
  }

  @Transactional(readOnly = true)
  public String workspaceAccessLevel(UUID memberId, UUID workspaceId) {
    return effectiveAccessLevel(memberId, workspaceId, null, null, false);
  }

  // The single core computation every method above funnels through (first extracted after
  // requireAccountAccess/requireInstitutionAccess/requireWorkspaceAccess had each independently
  // reimplemented this same sole-member -> ownership -> grants sequence, differing only in which
  // repository supplied the ownership short-circuit and which id fed findApplicableGrants).
  // checkOwnership is false for institution/workspace scope, which have no ownership concept of
  // their own; accountId/institutionId are simply left null for whichever scope doesn't apply -
  // findApplicableGrants already treats a null id as "this clause never matches" (see its own
  // Javadoc), so no separate query shape is needed per scope.
  private String effectiveAccessLevel(
      UUID memberId, UUID workspaceId, UUID accountId, UUID institutionId, boolean checkOwnership) {
    return effectiveAccessLevel(
        memberId, isSoleActiveMember(workspaceId), accountId, institutionId, checkOwnership);
  }

  // The same computation with the (per-workspace) sole-active-member answer already in hand, for a
  // bulk caller that would otherwise re-run the same count for every resource.
  private String effectiveAccessLevel(
      UUID memberId,
      boolean soleActiveMember,
      UUID accountId,
      UUID institutionId,
      boolean checkOwnership) {
    boolean implicitFull =
        soleActiveMember
            || (checkOwnership
                && accountOwnershipRepository
                    .existsByAccountIdAndWorkspaceMemberIdAndEffectiveToIsNull(
                        accountId, memberId));
    return implicitFull
        ? AccessLevelValues.FULL
        : maxGrantedLevel(
            sharingGrantRepository.findApplicableGrants(memberId, accountId, institutionId));
  }

  // Shared by every controller/service that needs "who is making this request" as a
  // workspace_member id, not just the app_user id AuthenticatedUserPrincipal itself carries - see
  // its own Javadoc for why workspaceId (and by extension the linked member) is null for a
  // SYSTEM_ADMINISTRATOR with no workspace_member, a normal state that simply has no
  // workspace-scoped resource to ever legitimately request.
  @Transactional(readOnly = true)
  public UUID requireActingMember(AuthenticatedUserPrincipal actor) {
    UUID memberId =
        actor.workspaceId() == null
            ? null
            : appUserRepository
                .findById(actor.userId())
                .map(AppUser::getWorkspaceMember)
                .map(WorkspaceMember::getId)
                .orElse(null);
    if (memberId == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }
    return memberId;
  }

  // The caller's own membership is already known-ACTIVE by this point: JwtAuthenticationFilter
  // refuses to authenticate a request whose linked workspace_member exists but isn't ACTIVE (see
  // AccountService's own Javadoc for the same reliance) - so "exactly one ACTIVE, non-dependent
  // member in this workspace" already implies that one member is the caller, with no separate
  // equality check (and no memberId parameter) needed here. Excludes dependent members
  // (WorkspaceMember's own Javadoc: "a person represented financially; may have no login") - a
  // dependent can never authenticate, so counting one toward this rule would strip the one real
  // login user of their implicit FULL access the moment a dependent is added, even though no
  // second person can actually use the workspace.
  private boolean isSoleActiveMember(UUID workspaceId) {
    return workspaceMemberRepository.countByWorkspaceIdAndStatusAndDependentFalse(
            workspaceId, "ACTIVE")
        == 1;
  }

  private String maxGrantedLevel(List<SharingGrant> grants) {
    return grants.stream()
        .map(SharingGrant::getAccessLevel)
        .map(AccessLevelValues.Rank::valueOf)
        .max(Comparator.naturalOrder())
        .map(Enum::name)
        .orElse(AccessLevelValues.NO_ACCESS);
  }

  private static boolean atLeast(String actualLevel, String requiredLevel) {
    return AccessLevelValues.Rank.valueOf(actualLevel)
            .compareTo(AccessLevelValues.Rank.valueOf(requiredLevel))
        >= 0;
  }

  public ResponseStatusException denyAsNotFound(
      AuthenticatedUserPrincipal actor, String entityType, UUID entityId) {
    return authorizationDenialAuditService.denyAsNotFound(actor, entityType, entityId);
  }

  private ResponseStatusException denyMemberAsNotFound(
      UUID memberId, String entityType, UUID entityId) {
    UUID principalUserId =
        appUserRepository.findUserIdByWorkspaceMemberId(memberId).orElse(null);
    return authorizationDenialAuditService.denyAsNotFound(
        principalUserId, entityType, entityId);
  }
}
