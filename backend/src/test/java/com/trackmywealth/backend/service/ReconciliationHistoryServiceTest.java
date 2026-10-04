package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-25-03: a decision answers a result id the member may not use with the audited 404 before it
 * takes any lock, without loading the result - its version is read only under the locks.
 */
class ReconciliationHistoryServiceTest {

  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final ReconciliationResultRepository resultRepository =
      mock(ReconciliationResultRepository.class);
  private final ReconciliationHistoryService service =
      new ReconciliationHistoryService(
          mock(AccountLookupService.class),
          accessControlService,
          mock(AccountSnapshotRepository.class),
          resultRepository);

  private Account account;

  @BeforeEach
  void setUp() {
    account = new Account();
    ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
  }

  @Test
  void anotherAccountsOrAMissingResultIsTheAuditedNotFound() {
    UUID resultId = UUID.randomUUID();
    ResponseStatusException audited = new ResponseStatusException(HttpStatus.NOT_FOUND);
    when(accessControlService.denyAsNotFound(ACTOR, "Reconciliation result", resultId))
        .thenReturn(audited);

    assertThatThrownBy(() -> service.requireResultExists(account, resultId, ACTOR))
        .isSameAs(audited);
    verify(resultRepository, never()).findById(any());
  }

  @Test
  void theAccountsOwnResultPassesWithoutBeingLoaded() {
    UUID resultId = UUID.randomUUID();
    when(resultRepository.existsByIdAndAccountIdAndAffectedSecurityIdIsNull(
            resultId, account.getId()))
        .thenReturn(true);

    assertThatCode(() -> service.requireResultExists(account, resultId, ACTOR))
        .doesNotThrowAnyException();
    verify(resultRepository, never()).findById(any());
    verify(accessControlService, never()).denyAsNotFound(any(), any(), any());
  }
}
