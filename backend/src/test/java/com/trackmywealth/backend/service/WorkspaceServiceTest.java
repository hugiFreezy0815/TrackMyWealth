package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.UpdateWorkspaceCurrencyRequest;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-06-05: who may change the workspace currency. A dependent has no login today, so {@code
 * WorkspaceCurrencyControllerTest} cannot reach this branch over HTTP; it is checked here with the
 * repositories mocked.
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
  private final WorkspaceService service =
      new WorkspaceService(
          workspaceRepository,
          workspaceMemberRepository,
          accessControlService,
          new VersionPreconditionService());

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
}
