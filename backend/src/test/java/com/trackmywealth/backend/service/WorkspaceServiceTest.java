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
import com.trackmywealth.backend.repository.FinancialAuditLogRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-07 and US-06-05: what a rename or currency change writes to the audit, and what a stale or
 * unchanged request leaves alone.
 */
class WorkspaceServiceTest {

  private static final UUID WORKSPACE = UUID.randomUUID();
  private static final UUID MEMBER = UUID.randomUUID();
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", WORKSPACE, UUID.randomUUID(), "EN");

  private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final FinancialAuditLogRepository financialAuditLogRepository =
      mock(FinancialAuditLogRepository.class);
  private final WorkspaceService service =
      new WorkspaceService(
          workspaceRepository,
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
  void aCurrencyChangeAuditsBothValues() {
    Workspace workspace = actingMembersWorkspace("Hugi");
    when(workspaceRepository.saveAndFlush(workspace)).thenReturn(workspace);

    WorkspaceResponse response =
        service.updateCurrency(new UpdateWorkspaceCurrencyRequest("USD"), 3, ACTOR);

    assertThat(response.currency()).isEqualTo("USD");
    verify(financialAuditLogRepository)
        .recordWorkspaceCurrencyChange(WORKSPACE, ACTOR.userId(), "CHF", "USD");
  }

  @Test
  void anUnchangedCurrencyWritesNothing() {
    actingMembersWorkspace("Hugi");

    WorkspaceResponse response =
        service.updateCurrency(new UpdateWorkspaceCurrencyRequest("CHF"), 3, ACTOR);

    assertThat(response.version()).isEqualTo(3);
    verify(workspaceRepository, never()).saveAndFlush(any());
    verifyNoInteractions(financialAuditLogRepository);
  }

  private Workspace actingMembersWorkspace(String name) {
    when(accessControlService.requireActingMember(ACTOR)).thenReturn(MEMBER);
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
