package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.dto.WorkspaceMemberListItemResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-05: the service's own decisions on the member list - who may ask, the bound, what each row
 * shows. Workspace isolation and the query itself are proven against PostgreSQL in {@code
 * WorkspaceMemberControllerTest}.
 */
class WorkspaceMemberListServiceTest {

  private static final Limit BOUNDED = Limit.of(PagingValues.MAX_SIZE + 1);

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final WorkspaceMemberService service =
      new WorkspaceMemberService(
          users,
          members,
          mock(AuthorizationDenialAuditService.class),
          new VersionPreconditionService(),
          accessControlService);

  private final UUID workspaceId = UUID.randomUUID();
  private final AuthenticatedUserPrincipal actor =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", workspaceId, UUID.randomUUID(), "EN");

  @Test
  void listsEveryMemberWithItsLoginAndMembershipStatus() {
    WorkspaceMember active = member("Anna", "ACTIVE", 1);
    WorkspaceMember inactive = member("Ben", "INACTIVE", 2);
    AppUser annasLogin = login(active, "STANDARD_USER");
    // A disabled login does not make the membership inactive: `active` is the membership's.
    AppUser bensLogin = login(inactive, "STANDARD_USER");
    when(bensLogin.getStatus()).thenReturn("DISABLED");
    givenMembers(active, inactive);
    givenLogins(List.of(active, inactive), annasLogin, bensLogin);

    List<WorkspaceMemberListItemResponse> listed = service.listWorkspaceMembers(actor);

    assertThat(listed)
        .containsExactly(
            new WorkspaceMemberListItemResponse(
                active.getId(), annasLogin.getId(), "Anna", true, 1),
            new WorkspaceMemberListItemResponse(
                inactive.getId(), bensLogin.getId(), "Ben", false, 2));
  }

  @Test
  void aRefusedCallerGetsTheAccessDecisionAndNoMemberIsRead() {
    when(accessControlService.requireActingMember(actor))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));

    assertThatThrownBy(() -> service.listWorkspaceMembers(actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(members, never()).findMembersForListing(any(), any());
  }

  // FR-USR-07: the bootstrap membership passes requireActingMember, but the administrator is not
  // one of the workspace's people and is refused like any other caller it does not concern.
  @Test
  void theAdministratorIsRefusedAndNoMemberIsRead() {
    AuthenticatedUserPrincipal administrator =
        new AuthenticatedUserPrincipal(
            UUID.randomUUID(), "SYSTEM_ADMINISTRATOR", workspaceId, UUID.randomUUID(), "EN");
    when(accessControlService.denyAsNotFound(administrator, "Workspace", workspaceId))
        .thenReturn(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));

    assertThatThrownBy(() -> service.listWorkspaceMembers(administrator))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(members, never()).findMembersForListing(any(), any());
  }

  @Test
  void theAdministratorsBootstrapMembershipIsNotListed() {
    WorkspaceMember person = member("Anna", "ACTIVE", 1);
    WorkspaceMember administrator = member("admin", "ACTIVE", 1);
    givenMembers(person, administrator);
    givenLogins(
        List.of(person, administrator),
        login(person, "STANDARD_USER"),
        login(administrator, "SYSTEM_ADMINISTRATOR"));

    assertThat(service.listWorkspaceMembers(actor))
        .extracting(WorkspaceMemberListItemResponse::id)
        .containsExactly(person.getId());
  }

  // Until #389 every member has a login; a legacy member without one is still listed.
  @Test
  void aMemberWithoutALoginIsListedWithoutAUser() {
    WorkspaceMember person = member("Anna", "ACTIVE", 1);
    WorkspaceMember legacy = member("Child", "ACTIVE", 3);
    givenMembers(person, legacy);
    givenLogins(List.of(person, legacy), login(person, "STANDARD_USER"));

    List<WorkspaceMemberListItemResponse> listed = service.listWorkspaceMembers(actor);

    assertThat(listed).extracting(WorkspaceMemberListItemResponse::id).contains(legacy.getId());
    assertThat(listed.get(1).userId()).isNull();
  }

  @Test
  void aWorkspaceAboveTheLimitIsRefusedBeforeAnyLoginIsRead() {
    givenMembers(
        Collections.nCopies(PagingValues.MAX_SIZE + 1, member("Anna", "ACTIVE", 1))
            .toArray(WorkspaceMember[]::new));

    assertThatThrownBy(() -> service.listWorkspaceMembers(actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(users, never()).findUsersLinkedToMembers(any());
  }

  private void givenMembers(WorkspaceMember... listed) {
    when(members.findMembersForListing(workspaceId, BOUNDED)).thenReturn(List.of(listed));
  }

  private void givenLogins(List<WorkspaceMember> listed, AppUser... logins) {
    when(users.findUsersLinkedToMembers(listed.stream().map(WorkspaceMember::getId).toList()))
        .thenReturn(List.of(logins));
  }

  // Ids and versions are database-assigned, so the entities are stubbed rather than built.
  private static WorkspaceMember member(String displayName, String status, int version) {
    WorkspaceMember member = mock(WorkspaceMember.class);
    when(member.getId()).thenReturn(UUID.randomUUID());
    when(member.getDisplayName()).thenReturn(displayName);
    when(member.getStatus()).thenReturn(status);
    when(member.getVersion()).thenReturn(version);
    return member;
  }

  private static AppUser login(WorkspaceMember member, String role) {
    AppUser user = mock(AppUser.class);
    when(user.getId()).thenReturn(UUID.randomUUID());
    when(user.getWorkspaceMember()).thenReturn(member);
    when(user.getRole()).thenReturn(role);
    when(user.getStatus()).thenReturn("ACTIVE");
    return user;
  }
}
