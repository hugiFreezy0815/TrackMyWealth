package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.repository.PortfolioRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** A failed provisioning step must not leave the caller in the new tenant's RLS context. */
class WorkspaceProvisioningServiceTest {

  private static final String PREVIOUS = "previous-workspace";

  private final EntityManager entityManager = mock(EntityManager.class);
  private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
  private final List<String> contexts = new ArrayList<>();
  private final WorkspaceProvisioningService service =
      new WorkspaceProvisioningService(
          entityManager,
          workspaces,
          mock(WorkspaceMemberRepository.class),
          mock(PortfolioRepository.class));

  private boolean restoreFails;

  @BeforeEach
  void stubContextQueries() {
    Query read = mock(Query.class);
    when(read.getSingleResult()).thenReturn(PREVIOUS);
    when(entityManager.createNativeQuery(
            eq("SELECT COALESCE(current_setting('app.current_workspace_id', true), '')")))
        .thenReturn(read);

    Query set = mock(Query.class);
    when(entityManager.createNativeQuery(
            eq("SELECT set_config('app.current_workspace_id', :workspaceId, true)")))
        .thenReturn(set);
    when(set.setParameter(eq("workspaceId"), anyString()))
        .thenAnswer(
            invocation -> {
              contexts.add(invocation.getArgument(1));
              return set;
            });
    when(set.getSingleResult())
        .thenAnswer(
            invocation -> {
              if (restoreFails && contexts.size() > 1) {
                throw new PersistenceException("current transaction is aborted");
              }
              return "";
            });
  }

  @Test
  void aFailedWriteRestoresThePreviousContext() {
    DataIntegrityViolationException failure = new DataIntegrityViolationException("duplicate");
    when(workspaces.saveAndFlush(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.create("a@example.com", "Home", "CHF")).isSameAs(failure);

    assertThat(contexts).hasSize(2).last().isEqualTo(PREVIOUS);
  }

  @Test
  void aFailedRestoreIsAttachedToTheOriginalFailure() {
    restoreFails = true;
    DataIntegrityViolationException failure = new DataIntegrityViolationException("duplicate");
    when(workspaces.saveAndFlush(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.create("a@example.com", "Home", "CHF"))
        .isSameAs(failure)
        .satisfies(
            thrown ->
                assertThat(thrown.getSuppressed())
                    .singleElement()
                    .isInstanceOf(PersistenceException.class));
  }
}
