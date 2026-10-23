package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.UpdateWorkspaceCurrencyRequest;
import com.trackmywealth.backend.dto.UpdateWorkspaceNameRequest;
import com.trackmywealth.backend.dto.WorkspaceResponse;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.FinancialAuditLogRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-06-05 and US-03-07: who may change the workspace currency and name, and what a rename writes.
 * A dependent has no login today, so the controller tests cannot reach that branch over HTTP; it is
 * checked here with the repositories mocked.
 */
class WorkspaceServiceTest {

  private static final UUID WORKSPACE = UUID.randomUUID();
  private static final UUID MEMBER = UUID.randomUUID();
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", WORKSPACE, UUID.randomUUID(), "EN");

  private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
  private final WorkspaceMemberRepository workspaceMemberRepository =
      mock(WorkspaceMemberRepository.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final FinancialAuditLogRepository financialAuditLogRepository =
      mock(FinancialAuditLogRepository.class);
  private final WorkspaceService service =
      new WorkspaceService(
          workspaceRepository,
          workspaceMemberRepository,
          accessControlService,
          new VersionPreconditionService(),
          financialAuditLogRepository);

  @Test
  void aRenameStoresTheTrimmedNameAndAuditsBothValues() {
    Workspace workspace = actingMembersWorkspace("Initial");
    when(workspaceRepository.saveAndFlush(workspace)).thenReturn(workspace);

    WorkspaceResponse response =
        service.updateName(new UpdateWorkspaceNameRequest("  Hugi  "), 3, ACTOR);

    assertThat(response.name()).isEqualTo("Hugi");
    assertThat(workspace.getName()).isEqualTo("Hugi");
    verify(financialAuditLogRepository)
        .recordWorkspaceNameChange(WORKSPACE, ACTOR.userId(), "Initial", "Hugi");
  }

  @Test
  void clearingTheNameAuditsTheOldNameAgainstNull() {
    Workspace workspace = actingMembersWorkspace("Initial");
    when(workspaceRepository.saveAndFlush(workspace)).thenReturn(workspace);

    WorkspaceResponse response = service.updateName(new UpdateWorkspaceNameRequest(" "), 3, ACTOR);

    assertThat(response.name()).isNull();
    verify(financialAuditLogRepository)
        .recordWorkspaceNameChange(WORKSPACE, ACTOR.userId(), "Initial", null);
  }

  // An unchanged name is no change: no write, no new version and no audit entry.
  @Test
  void anUnchangedNameWritesNothing() {
    actingMembersWorkspace("Hugi");

    WorkspaceResponse response =
        service.updateName(new UpdateWorkspaceNameRequest(" Hugi "), 3, ACTOR);

    assertThat(response.version()).isEqualTo(3);
    verify(workspaceRepository, never()).saveAndFlush(any());
    verifyNoInteractions(financialAuditLogRepository);
  }

  @Test
  void aStaleVersionChangesNothing() {
    Workspace workspace = actingMembersWorkspace("Initial");

    assertThatThrownBy(() -> service.updateName(new UpdateWorkspaceNameRequest("Hugi"), 2, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
    assertThat(workspace.getName()).isEqualTo("Initial");
    verify(workspaceRepository, never()).saveAndFlush(any());
    verifyNoInteractions(financialAuditLogRepository);
  }

  @Test
  void aDependentMemberCannotRenameTheWorkspace() {
    WorkspaceMember dependent = new WorkspaceMember();
    dependent.setDependent(true);
    when(accessControlService.requireActingMember(ACTOR)).thenReturn(MEMBER);
    when(workspaceMemberRepository.findById(MEMBER)).thenReturn(Optional.of(dependent));

    assertThatThrownBy(() -> service.updateName(new UpdateWorkspaceNameRequest("New"), null, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    verify(workspaceRepository, never()).findByIdForUpdate(any());
    verifyNoInteractions(financialAuditLogRepository);
  }

  // Reading the settings is not changing them: a dependent may read the workspace.
  @Test
  void aDependentMemberCanReadTheWorkspace() {
    Workspace workspace = workspace("Hugi");
    when(accessControlService.requireActingMember(ACTOR)).thenReturn(MEMBER);
    when(workspaceRepository.findById(WORKSPACE)).thenReturn(Optional.of(workspace));

    assertThat(service.getCurrent(ACTOR).name()).isEqualTo("Hugi");
    verifyNoInteractions(workspaceMemberRepository);
  }

  // Authorization comes before the version (ADR 0004): a dependent is refused with 403 even
  // without If-Match, and the workspace row is never locked or written.
  @Test
  void aDependentMemberCannotChangeTheWorkspaceCurrency() {
    WorkspaceMember dependent = new WorkspaceMember();
    dependent.setDependent(true);
    when(accessControlService.requireActingMember(ACTOR)).thenReturn(MEMBER);
    when(workspaceMemberRepository.findById(MEMBER)).thenReturn(Optional.of(dependent));

    assertThatThrownBy(
            () -> service.updateCurrency(new UpdateWorkspaceCurrencyRequest("USD"), null, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    verify(workspaceRepository, never()).findByIdForUpdate(any());
    verify(workspaceRepository, never()).saveAndFlush(any());
  }

  private Workspace actingMembersWorkspace(String name) {
    when(accessControlService.requireActingMember(ACTOR)).thenReturn(MEMBER);
    when(workspaceMemberRepository.findById(MEMBER)).thenReturn(Optional.of(new WorkspaceMember()));
    Workspace workspace = workspace(name);
    when(workspaceRepository.findByIdForUpdate(WORKSPACE)).thenReturn(Optional.of(workspace));
    return workspace;
  }

  private static Workspace workspace(String name) {
    Workspace workspace = new Workspace();
    workspace.setId(WORKSPACE);
    workspace.setName(name);
    workspace.setCurrency("CHF");
    ReflectionTestUtils.setField(workspace, "version", 3);
    return workspace;
  }
}
