package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceMemberListServiceTest {

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
  private final WorkspaceMemberService service =
      new WorkspaceMemberService(
          users,
          members,
          mock(AuthorizationDenialAuditService.class),
          new VersionPreconditionService());

  private final UUID workspaceId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();

  @Test
  void listsLinkedUsersAndPreservesInactiveMembers() {
    Workspace workspace = mock(Workspace.class);
    when(workspace.getId()).thenReturn(workspaceId);
    WorkspaceMember current = member(memberId, workspace, "ACTIVE", "A", 1);
    WorkspaceMember inactive = member(UUID.randomUUID(), workspace, "INACTIVE", "B", 2);
    AppUser currentUser = user(userId, current, "ACTIVE");
    AppUser disabledUser = user(UUID.randomUUID(), inactive, "DISABLED");
    when(users.findWorkspaceMemberId(userId)).thenReturn(Optional.of(memberId));
    when(members.findById(memberId)).thenReturn(Optional.of(current));
    when(members.findMembersForListing(workspaceId, PageRequest.of(0, 201)))
        .thenReturn(List.of(current, inactive));
    when(users.findUsersLinkedToMembers(List.of(memberId, inactive.getId())))
        .thenReturn(List.of(currentUser, disabledUser));

    var listed = service.listWorkspaceMembers(actor("STANDARD_USER", workspaceId));
    assertThat(listed).hasSize(2);
    assertThat(listed.get(0).active()).isTrue();
    assertThat(listed.get(0).userId()).isEqualTo(userId);
    assertThat(listed.get(1).active()).isFalse();
    assertThat(listed.get(1).version()).isEqualTo(2);
  }

  @Test
  void administratorCannotReadMembershipListEvenWithWorkspaceLink() {
    assertThatThrownBy(() -> service.listWorkspaceMembers(actor("SYSTEM_ADMINISTRATOR", workspaceId)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void refusesMismatchedWorkspaceWithoutListingMembers() {
    Workspace realWorkspace = mock(Workspace.class);
    when(realWorkspace.getId()).thenReturn(UUID.randomUUID());
    WorkspaceMember current = member(memberId, realWorkspace, "ACTIVE", "A", 1);
    when(users.findWorkspaceMemberId(userId)).thenReturn(Optional.of(memberId));
    when(members.findById(memberId)).thenReturn(Optional.of(current));

    assertThatThrownBy(() -> service.listWorkspaceMembers(actor("STANDARD_USER", workspaceId)))
        .isInstanceOf(ResponseStatusException.class);
    verify(members, never()).findMembersForListing(any(), any());
  }

  @Test
  void refusesInactiveCaller() {
    Workspace workspace = mock(Workspace.class);
    when(workspace.getId()).thenReturn(workspaceId);
    WorkspaceMember current = member(memberId, workspace, "INACTIVE", "A", 1);
    when(users.findWorkspaceMemberId(userId)).thenReturn(Optional.of(memberId));
    when(members.findById(memberId)).thenReturn(Optional.of(current));

    assertThatThrownBy(() -> service.listWorkspaceMembers(actor("STANDARD_USER", workspaceId)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void noLinkedUserIsShownAsLegacyMemberWithoutExposingAnEmail() {
    Workspace workspace = mock(Workspace.class);
    when(workspace.getId()).thenReturn(workspaceId);
    WorkspaceMember current = member(memberId, workspace, "ACTIVE", "Current", 1);
    WorkspaceMember legacy = member(UUID.randomUUID(), workspace, "ACTIVE", "Legacy", 2);
    when(users.findWorkspaceMemberId(userId)).thenReturn(Optional.of(memberId));
    when(members.findById(memberId)).thenReturn(Optional.of(current));
    when(members.findMembersForListing(workspaceId, PageRequest.of(0, 201)))
        .thenReturn(List.of(current, legacy));
    when(users.findUsersLinkedToMembers(List.of(memberId, legacy.getId())))
        .thenReturn(List.of(user(userId, current, "ACTIVE")));
    var listed = service.listWorkspaceMembers(actor("STANDARD_USER", workspaceId));
    assertThat(listed).extracting(item -> item.id()).containsExactly(memberId, legacy.getId());
    assertThat(listed.get(1).userId()).isNull();
    assertThat(listed.get(1).active()).isTrue();
  }

  @Test
  void oversizedListIsStoppedBeforeLookingUpUsers() {
    Workspace workspace = mock(Workspace.class);
    when(workspace.getId()).thenReturn(workspaceId);
    WorkspaceMember current = member(memberId, workspace, "ACTIVE", "Current", 1);
    when(users.findWorkspaceMemberId(userId)).thenReturn(Optional.of(memberId));
    when(members.findById(memberId)).thenReturn(Optional.of(current));
    when(members.findMembersForListing(workspaceId, PageRequest.of(0, 201)))
        .thenReturn(java.util.Collections.nCopies(201, current));
    assertThatThrownBy(() -> service.listWorkspaceMembers(actor("STANDARD_USER", workspaceId)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(users, never()).findUsersLinkedToMembers(any());
  }

  @Test
  void emptyListDoesNotQueryUsers() {
    Workspace workspace = mock(Workspace.class);
    when(workspace.getId()).thenReturn(workspaceId);
    WorkspaceMember current = member(memberId, workspace, "ACTIVE", "Current", 1);
    when(users.findWorkspaceMemberId(userId)).thenReturn(Optional.of(memberId));
    when(members.findById(memberId)).thenReturn(Optional.of(current));
    when(members.findMembersForListing(workspaceId, PageRequest.of(0, 201)))
        .thenReturn(List.of());
    assertThat(service.listWorkspaceMembers(actor("STANDARD_USER", workspaceId))).isEmpty();
    verify(users, never()).findUsersLinkedToMembers(any());
  }

  @Test
  void administratorLinkedMemberIsNotExposed() {
    Workspace workspace = mock(Workspace.class);
    when(workspace.getId()).thenReturn(workspaceId);
    WorkspaceMember current = member(memberId, workspace, "ACTIVE", "Current", 1);
    WorkspaceMember adminMember = member(UUID.randomUUID(), workspace, "ACTIVE", "Admin", 2);
    AppUser adminUser = user(UUID.randomUUID(), adminMember, "ACTIVE");
    when(adminUser.getRole()).thenReturn("SYSTEM_ADMINISTRATOR");
    when(users.findWorkspaceMemberId(userId)).thenReturn(Optional.of(memberId));
    when(members.findById(memberId)).thenReturn(Optional.of(current));
    when(members.findMembersForListing(workspaceId, PageRequest.of(0, 201)))
        .thenReturn(List.of(current, adminMember));
    when(users.findUsersLinkedToMembers(List.of(memberId, adminMember.getId())))
        .thenReturn(List.of(user(userId, current, "ACTIVE"), adminUser));
    var listed = service.listWorkspaceMembers(actor("STANDARD_USER", workspaceId));
    assertThat(listed).extracting(item -> item.id()).containsExactly(memberId);
  }

  private AuthenticatedUserPrincipal actor(String role, UUID workspace) {
    return new AuthenticatedUserPrincipal(userId, role, workspace, UUID.randomUUID(), "EN");
  }

  private WorkspaceMember member(UUID id, Workspace workspace, String status, String name, int version) {
    WorkspaceMember result = mock(WorkspaceMember.class);
    when(result.getId()).thenReturn(id);
    when(result.getWorkspace()).thenReturn(workspace);
    when(result.getStatus()).thenReturn(status);
    when(result.getDisplayName()).thenReturn(name);
    when(result.getVersion()).thenReturn(version);
    return result;
  }

  private AppUser user(UUID id, WorkspaceMember member, String status) {
    AppUser result = mock(AppUser.class);
    when(result.getId()).thenReturn(id);
    when(result.getWorkspaceMember()).thenReturn(member);
    when(result.getStatus()).thenReturn(status);
    when(result.getRole()).thenReturn("STANDARD_USER");
    return result;
  }
}
