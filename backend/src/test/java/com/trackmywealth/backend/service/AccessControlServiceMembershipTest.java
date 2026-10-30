package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-06/FR-TEN-008: membership of a workspace is the only access grant. The HTTP-level proof
 * with two real members, imports and cross-workspace ids is {@code
 * WorkspaceMembershipAccessIntegrationTest}; this pins the decision itself.
 */
class AccessControlServiceMembershipTest {

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
  private final AuthorizationDenialAuditService denials =
      mock(AuthorizationDenialAuditService.class);
  private final AccessControlService access = new AccessControlService(users, members, denials);

  private final UUID workspaceId = UUID.randomUUID();
  private final UUID otherWorkspaceId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final AuthenticatedUserPrincipal actor =
      new AuthenticatedUserPrincipal(userId, "STANDARD_USER", workspaceId, UUID.randomUUID(), "EN");
  private final WorkspaceMember member = mock(WorkspaceMember.class);

  @BeforeEach
  void anActiveMember() {
    Workspace workspace = workspace(workspaceId);
    when(member.getStatus()).thenReturn("ACTIVE");
    when(member.getWorkspace()).thenReturn(workspace);
    when(member.getId()).thenReturn(memberId);
    when(members.findById(memberId)).thenReturn(Optional.of(member));
    AppUser user = mock(AppUser.class);
    when(user.getWorkspaceMember()).thenReturn(member);
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(denials.denyAsNotFound(any(AuthenticatedUserPrincipal.class), any(), any()))
        .thenAnswer(invocation -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @Test
  void anActiveMemberMayUseEverythingInTheWorkspaceWithoutAnOwnerOrGrant() {
    assertThat(access.requireActingMember(actor)).isEqualTo(memberId);
    assertThatCode(() -> access.requireAccountAccess(actor, accountIn(workspaceId)))
        .doesNotThrowAnyException();
    assertThatCode(() -> access.requireInstitutionAccess(actor, institutionIn(workspaceId)))
        .doesNotThrowAnyException();
    assertThatCode(() -> access.requireWorkspaceAccess(actor, workspaceId))
        .doesNotThrowAnyException();
    verify(denials, never()).denyAsNotFound(any(AuthenticatedUserPrincipal.class), any(), any());
  }

  @Test
  void anotherWorkspacesResourceIsTheAuditedNotFound() {
    Account foreign = accountIn(otherWorkspaceId);

    assertNotFound(() -> access.requireAccountAccess(actor, foreign));
    assertNotFound(() -> access.requireInstitutionAccess(actor, institutionIn(otherWorkspaceId)));
    assertNotFound(() -> access.requireWorkspaceAccess(actor, otherWorkspaceId));
    verify(denials).denyAsNotFound(actor, "Account", foreign.getId());
    verify(denials).denyAsNotFound(actor, "Workspace", otherWorkspaceId);
  }

  @Test
  void anAggregationKeepsOnlyTheWorkspacesOwnAccounts() {
    Account own = accountIn(workspaceId);
    Account foreign = accountIn(otherWorkspaceId);

    assertThat(access.accountsWithAccess(memberId, List.of(own, foreign))).containsExactly(own);
  }

  @Test
  void aDeactivatedMemberIsRefusedEverywhere() {
    when(member.getStatus()).thenReturn("INACTIVE");

    assertNotFound(() -> access.requireActingMember(actor));
    assertNotFound(() -> access.requireAccountAccess(actor, accountIn(workspaceId)));
    assertThat(access.accountsWithAccess(memberId, List.of(accountIn(workspaceId)))).isEmpty();
  }

  @Test
  void aPrincipalWithoutAWorkspaceHasNoMembership() {
    AuthenticatedUserPrincipal administrator =
        new AuthenticatedUserPrincipal(
            UUID.randomUUID(), "SYSTEM_ADMINISTRATOR", null, UUID.randomUUID(), "EN");

    assertNotFound(() -> access.requireActingMember(administrator));
    assertNotFound(() -> access.requireAccountAccess(administrator, accountIn(workspaceId)));
    assertThat(access.accountsWithAccess(null, List.of(accountIn(workspaceId)))).isEmpty();
  }

  // FR-TEN-007, interim rule until FR-USR-013: an administrator's membership counts only while it
  // is the workspace's sole active member.
  @Test
  void anAdministratorWhoIsTheSoleActiveMemberActsAsAMember() {
    AuthenticatedUserPrincipal administrator = administratorLinkedToTheMember();
    when(members.countByWorkspaceIdAndStatus(workspaceId, "ACTIVE")).thenReturn(1L);

    assertThat(access.requireActingMember(administrator)).isEqualTo(memberId);
    assertThatCode(() -> access.requireAccountAccess(administrator, accountIn(workspaceId)))
        .doesNotThrowAnyException();
    verify(denials, never()).denyAsNotFound(any(AuthenticatedUserPrincipal.class), any(), any());
  }

  @Test
  void anAdministratorIsTheAuditedNotFoundOnceAnotherMemberIsActive() {
    AuthenticatedUserPrincipal administrator = administratorLinkedToTheMember();
    when(members.countByWorkspaceIdAndStatus(workspaceId, "ACTIVE")).thenReturn(2L);

    assertNotFound(() -> access.requireActingMember(administrator));
    assertNotFound(() -> access.requireAccountAccess(administrator, accountIn(workspaceId)));
    assertNotFound(() -> access.requireWorkspaceAccess(administrator, workspaceId));
    verify(denials, times(3)).denyAsNotFound(administrator, "Workspace", workspaceId);
  }

  // A Standard User never pays for the count: the rule is the administrator's alone.
  @Test
  void aStandardUserIsNeverCountedAgainstOtherMembers() {
    when(members.countByWorkspaceIdAndStatus(workspaceId, "ACTIVE")).thenReturn(2L);

    assertThat(access.requireActingMember(actor)).isEqualTo(memberId);
    verify(members, never()).countByWorkspaceIdAndStatus(any(), any());
  }

  @Test
  void aUserWithoutALinkedMemberHasNoMembership() {
    AppUser unlinked = mock(AppUser.class);
    when(users.findById(userId)).thenReturn(Optional.of(unlinked));

    assertNotFound(() -> access.requireActingMember(actor));
  }

  private static void assertNotFound(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private AuthenticatedUserPrincipal administratorLinkedToTheMember() {
    UUID administratorId = UUID.randomUUID();
    AppUser administrator = mock(AppUser.class);
    when(administrator.getWorkspaceMember()).thenReturn(member);
    when(administrator.getRole()).thenReturn("SYSTEM_ADMINISTRATOR");
    when(users.findById(administratorId)).thenReturn(Optional.of(administrator));
    return new AuthenticatedUserPrincipal(
        administratorId, "SYSTEM_ADMINISTRATOR", workspaceId, UUID.randomUUID(), "EN");
  }

  private static Workspace workspace(UUID id) {
    Workspace workspace = mock(Workspace.class);
    when(workspace.getId()).thenReturn(id);
    return workspace;
  }

  private static Account accountIn(UUID workspace) {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn(UUID.randomUUID());
    Workspace owning = workspace(workspace);
    when(account.getWorkspace()).thenReturn(owning);
    return account;
  }

  private static FinancialInstitution institutionIn(UUID workspace) {
    FinancialInstitution institution = mock(FinancialInstitution.class);
    when(institution.getId()).thenReturn(UUID.randomUUID());
    Workspace owning = workspace(workspace);
    when(institution.getWorkspace()).thenReturn(owning);
    return institution;
  }
}
