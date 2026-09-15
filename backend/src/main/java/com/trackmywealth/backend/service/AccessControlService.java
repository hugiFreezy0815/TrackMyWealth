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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-03/FR-TEN-002/008/009: the one place every workspace-scoped read/write consults {@code
 * account_ownership} + {@code sharing_grant} to decide whether the acting member may proceed (the
 * story's own "Authorization/privacy" line) - not a per-controller reimplementation. Deny is always
 * surfaced as {@link HttpStatus#NOT_FOUND}, identical to a genuinely nonexistent/ cross-workspace
 * id (FR-TEN-006/US-28-02's existing convention), so a denied caller can never distinguish "doesn't
 * exist" from "exists but you can't see it".
 *
 * <p>An actor's effective access level to a resource, strongest source wins:
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
 *       to a non-owner.
 *   <li>Otherwise, the highest {@code access_level} among every non-revoked {@code sharing_grant}
 *       applicable to the resource: an {@code ACCOUNT}-scope grant for that account, an {@code
 *       INSTITUTION}-scope grant for the account's institution, or a {@code WORKSPACE}-scope grant
 *       - broader scopes cascade down, matching the story's own "one account, one institution, or
 *       the whole workspace" description. No applicable grant means {@code NO_ACCESS} (FR-TEN-002:
 *       deny by default).
 * </ol>
 *
 * <p><b>Known interaction, not a bug:</b> {@code AccountService.createAccount} does not itself
 * create an {@code account_ownership} row (that's a separate call to {@code
 * AccountOwnershipController}, US-03-02), so an account created while its creator was the sole
 * active member - then never explicitly owned - becomes inaccessible to everyone, including its own
 * creator, the moment a second member joins the workspace: the sole-member rule stops applying and
 * no ownership/grant exists yet to replace it. This is the same "can't share what you can't fully
 * see" rule applied consistently, including to this story's own bootstrap admin - the fix is for
 * the workspace to assign ownership (or a grant) before or immediately after inviting a second
 * member, not a gap in this class.
 */
@Service
public class AccessControlService {

  private final AppUserRepository appUserRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AccountOwnershipRepository accountOwnershipRepository;
  private final SharingGrantRepository sharingGrantRepository;

  public AccessControlService(
      AppUserRepository appUserRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AccountOwnershipRepository accountOwnershipRepository,
      SharingGrantRepository sharingGrantRepository) {
    this.appUserRepository = appUserRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.accountOwnershipRepository = accountOwnershipRepository;
    this.sharingGrantRepository = sharingGrantRepository;
  }

  @Transactional(readOnly = true)
  public void requireAccountAccess(
      AuthenticatedUserPrincipal actor, Account account, String requiredLevel) {
    UUID memberId = requireActingMember(actor);
    boolean isSoleMemberOrOwner =
        isSoleActiveMember(account.getWorkspace().getId())
            || accountOwnershipRepository.existsByAccountIdAndWorkspaceMemberIdAndEffectiveToIsNull(
                account.getId(), memberId);
    String level =
        isSoleMemberOrOwner
            ? AccessLevelValues.FULL
            : maxGrantedLevel(
                sharingGrantRepository.findApplicableGrants(
                    memberId, account.getId(), account.getFinancialInstitution().getId()));
    denyUnless(atLeast(level, requiredLevel), "Account not found.");
  }

  @Transactional(readOnly = true)
  public void requireInstitutionAccess(
      AuthenticatedUserPrincipal actor, FinancialInstitution institution, String requiredLevel) {
    UUID memberId = requireActingMember(actor);
    String level =
        isSoleActiveMember(institution.getWorkspace().getId())
            ? AccessLevelValues.FULL
            : maxGrantedLevel(
                sharingGrantRepository.findApplicableGrants(memberId, null, institution.getId()));
    denyUnless(atLeast(level, requiredLevel), "Financial institution not found.");
  }

  @Transactional(readOnly = true)
  public void requireWorkspaceAccess(
      AuthenticatedUserPrincipal actor, UUID workspaceId, String requiredLevel) {
    UUID memberId = requireActingMember(actor);
    String level =
        isSoleActiveMember(workspaceId)
            ? AccessLevelValues.FULL
            : maxGrantedLevel(sharingGrantRepository.findApplicableGrants(memberId, null, null));
    denyUnless(atLeast(level, requiredLevel), "Workspace not found.");
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
  // AccountService's own Javadoc for the same reliance) - so "exactly one ACTIVE member in this
  // workspace" already implies that one member is the caller, with no separate equality check
  // (and no memberId parameter) needed here.
  private boolean isSoleActiveMember(UUID workspaceId) {
    return workspaceMemberRepository.countByWorkspaceIdAndStatus(workspaceId, "ACTIVE") == 1;
  }

  private String maxGrantedLevel(List<SharingGrant> grants) {
    return grants.stream()
        .map(SharingGrant::getAccessLevel)
        .max(Comparator.comparingInt(AccessLevelValues.ORDER::indexOf))
        .orElse(AccessLevelValues.NO_ACCESS);
  }

  private static boolean atLeast(String actualLevel, String requiredLevel) {
    return AccessLevelValues.ORDER.indexOf(actualLevel)
        >= AccessLevelValues.ORDER.indexOf(requiredLevel);
  }

  private static void denyUnless(boolean allowed, String notFoundMessage) {
    if (!allowed) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage);
    }
  }
}
