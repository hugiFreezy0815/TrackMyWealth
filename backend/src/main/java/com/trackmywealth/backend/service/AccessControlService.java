package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-06/FR-TEN-008/FR-HOU-007: the one place every workspace-scoped read/write asks whether the
 * acting member may proceed. Membership is the only access grant: every {@code ACTIVE} member of a
 * workspace may see and change everything in it, and nobody else may see anything. There is no
 * account ownership, no per-account or per-scope grant and no access level (all withdrawn with
 * OPEN-021), so whatever one member creates or imports is there for every other member at once.
 *
 * <p>Deny is always surfaced as {@link HttpStatus#NOT_FOUND}, identical to a genuinely
 * nonexistent/cross-workspace id (FR-TEN-006/US-28-02), so a denied caller can never distinguish
 * "doesn't exist" from "exists but you can't see it". PostgreSQL RLS (V20) independently confines
 * every query to the caller's workspace; this class is the application-level half of the same
 * boundary, not a replacement for it.
 */
@Service
public class AccessControlService {

  private static final String ACTIVE = "ACTIVE";
  private static final String ACCOUNT_ENTITY_TYPE = "Account";
  private static final String INSTITUTION_ENTITY_TYPE = "FinancialInstitution";
  private static final String WORKSPACE_ENTITY_TYPE = "Workspace";

  private final AppUserRepository appUserRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AuthorizationDenialAuditService authorizationDenialAuditService;

  public AccessControlService(
      AppUserRepository appUserRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService) {
    this.appUserRepository = appUserRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
  }

  @Transactional(readOnly = true)
  public void requireAccountAccess(AuthenticatedUserPrincipal actor, Account account) {
    requireWorkspaceMembership(
        actor, account.getWorkspace().getId(), ACCOUNT_ENTITY_TYPE, account.getId());
  }

  // Non-throwing, bulk counterpart of requireAccountAccess, for an aggregation (e.g. net worth)
  // that must silently leave out the accounts the member cannot see rather than 404 the whole
  // request. One membership lookup for the whole collection, never one query per account.
  @Transactional(readOnly = true)
  public List<Account> accountsWithAccess(UUID memberId, Collection<Account> accounts) {
    UUID workspaceId = activeMemberWorkspaceId(memberId);
    if (workspaceId == null) {
      return List.of();
    }
    return accounts.stream()
        .filter(account -> workspaceId.equals(account.getWorkspace().getId()))
        .toList();
  }

  @Transactional(readOnly = true)
  public void requireInstitutionAccess(
      AuthenticatedUserPrincipal actor, FinancialInstitution institution) {
    requireWorkspaceMembership(
        actor, institution.getWorkspace().getId(), INSTITUTION_ENTITY_TYPE, institution.getId());
  }

  @Transactional(readOnly = true)
  public void requireWorkspaceAccess(AuthenticatedUserPrincipal actor, UUID workspaceId) {
    requireWorkspaceMembership(actor, workspaceId, WORKSPACE_ENTITY_TYPE, workspaceId);
  }

  // The single core check every require* method above funnels through: the actor is an ACTIVE
  // member of the resource's workspace. A caller without any membership gets requireActingMember's
  // own 404; a member of another workspace gets the audited 404 of the resource itself.
  private void requireWorkspaceMembership(
      AuthenticatedUserPrincipal actor, UUID workspaceId, String entityType, UUID entityId) {
    // requireActingMember has already loaded the member, so the lookup below is answered from the
    // persistence context, not by a second query.
    UUID memberWorkspaceId = activeMemberWorkspaceId(requireActingMember(actor));
    if (workspaceId == null || !workspaceId.equals(memberWorkspaceId)) {
      throw denyAsNotFound(actor, entityType, entityId);
    }
  }

  // The member's workspace while the membership is ACTIVE, null otherwise.
  private UUID activeMemberWorkspaceId(UUID memberId) {
    if (memberId == null) {
      return null;
    }
    return workspaceMemberRepository
        .findById(memberId)
        .filter(member -> ACTIVE.equals(member.getStatus()))
        .map(member -> member.getWorkspace().getId())
        .orElse(null);
  }

  // Shared by every controller/service that needs "who is making this request" as a
  // workspace_member id, not just the app_user id AuthenticatedUserPrincipal itself carries - see
  // its own Javadoc for why workspaceId (and by extension the linked member) is null for a
  // SYSTEM_ADMINISTRATOR with no workspace_member, a normal state that simply has no
  // workspace-scoped resource to ever legitimately request. JwtAuthenticationFilter already
  // refuses a token whose linked membership is not ACTIVE; the status is checked here as well so
  // a deactivated member is refused by every caller, not only by those behind the HTTP filter.
  @Transactional(readOnly = true)
  public UUID requireActingMember(AuthenticatedUserPrincipal actor) {
    if (actor.workspaceId() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }
    return appUserRepository
        .findById(actor.userId())
        .map(AppUser::getWorkspaceMember)
        .filter(member -> ACTIVE.equals(member.getStatus()))
        .map(WorkspaceMember::getId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
  }

  // #192: every require*Access gate takes the authenticated actor, never a bare member id, so the
  // audit row always names the principal straight from the request - there is no member-to-user
  // lookup that could fail or resolve ambiguously on a denial path.
  public ResponseStatusException denyAsNotFound(
      AuthenticatedUserPrincipal actor, String entityType, UUID entityId) {
    return authorizationDenialAuditService.denyAsNotFound(actor, entityType, entityId);
  }
}
