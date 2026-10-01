package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.trackmywealth.backend.repository.AuthorizationDenialAuditWriteRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.security.DenialAuditBudget;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-28-02 / #205: every denial is answered with the same generic 404, and the audit row the budget
 * calls for is written before that answer - synchronously, never dropped.
 */
class AuthorizationDenialAuditServiceTest {

  private static final int BUDGET = 3;
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final AtomicLong now = new AtomicLong();
  private final AuthorizationDenialAuditWriteRepository writeRepository =
      mock(AuthorizationDenialAuditWriteRepository.class);
  private final AuthorizationDenialAuditService service =
      new AuthorizationDenialAuditService(
          writeRepository,
          new DenialAuditBudget(
              BUDGET,
              WINDOW,
              100,
              now::get,
              (principal, suppressed) ->
                  AuthorizationDenialAuditService.recordUnreportedSuppressions(
                      writeRepository, principal, suppressed),
              Runnable::run));

  @Test
  void denialRecordsOnlyTheRequestedIdentityAndReturnsTheGenericNotFound() {
    UUID userId = UUID.randomUUID();
    UUID requestedId = UUID.randomUUID();
    AuthenticatedUserPrincipal actor =
        new AuthenticatedUserPrincipal(
            userId, "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

    ResponseStatusException denial = service.denyAsNotFound(actor, "Account", requestedId);

    assertGenericNotFound(denial);
    verify(writeRepository).insert(userId, "Account", requestedId, "NOT_FOUND", null);
    verifyNoMoreInteractions(writeRepository);
  }

  @Test
  void onePrincipalGetsTheExactBudgetThenOneSummaryThenNothingWithTheSame404() {
    UUID userId = UUID.randomUUID();

    for (int i = 0; i < BUDGET + 5; i++) {
      assertGenericNotFound(service.denyAsNotFound(userId, "Account", UUID.randomUUID()));
    }

    InOrder writes = inOrder(writeRepository);
    writes
        .verify(writeRepository, times(BUDGET))
        .insert(eq(userId), eq("Account"), any(UUID.class), eq("NOT_FOUND"), isNull());
    writes
        .verify(writeRepository)
        .insert(eq(userId), eq("AuthorizationDenial"), isNull(), eq("RATE_LIMITED"), isNull());
    verifyNoMoreInteractions(writeRepository);
  }

  @Test
  void theFirstDenialAfterTheWindowClosesWritesItsSuppressedCountBeforeItsOwnRow() {
    UUID userId = UUID.randomUUID();
    for (int i = 0; i < BUDGET + 1 + 4; i++) {
      service.denyAsNotFound(userId, "Account", UUID.randomUUID());
    }

    now.addAndGet(WINDOW.toNanos());
    UUID requestedId = UUID.randomUUID();
    assertGenericNotFound(service.denyAsNotFound(userId, "Account", requestedId));

    InOrder writes = inOrder(writeRepository);
    writes.verify(writeRepository).insert(userId, "AuthorizationDenial", null, "RATE_LIMITED", 4);
    writes.verify(writeRepository).insert(userId, "Account", requestedId, "NOT_FOUND", null);
  }

  @Test
  void shutdownWritesTheSuppressedCountsNoLaterDenialWillCarry() {
    UUID userId = UUID.randomUUID();
    for (int i = 0; i < BUDGET + 1 + 2; i++) {
      service.denyAsNotFound(userId, "Account", UUID.randomUUID());
    }

    service.recordUnreportedSuppressionsOnShutdown();

    verify(writeRepository).insert(userId, "AuthorizationDenial", null, "RATE_LIMITED", 2);
  }

  @Test
  void aSuppressedCountThatCannotBeWrittenOffTheRequestPathIsLoggedNotThrown() {
    doThrow(new DataAccessResourceFailureException("database down"))
        .when(writeRepository)
        .insert(any(), any(), any(), any(), any());

    assertThatCode(
            () ->
                AuthorizationDenialAuditService.recordUnreportedSuppressions(
                    writeRepository, UUID.randomUUID(), 7))
        .doesNotThrowAnyException();
  }

  @Test
  void principalBudgetsAreIndependent() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    for (int i = 0; i < BUDGET + 1; i++) {
      service.denyAsNotFound(first, "Account", UUID.randomUUID());
    }
    service.denyAsNotFound(second, "Account", UUID.randomUUID());

    verify(writeRepository)
        .insert(eq(second), eq("Account"), any(UUID.class), eq("NOT_FOUND"), isNull());
  }

  // "Never lost" (#205 decision): an audit row that cannot be written fails the request rather
  // than being silently skipped. The failure is the same whether the id exists or not, so it
  // reveals nothing.
  @Test
  void anAuditRowThatCannotBeWrittenFailsTheRequestInsteadOfBeingLost() {
    DataAccessResourceFailureException auditDown =
        new DataAccessResourceFailureException("audit pool timed out");
    doThrow(auditDown).when(writeRepository).insert(any(), any(), any(), any(), any());

    assertThatThrownBy(
            () -> service.denyAsNotFound(UUID.randomUUID(), "Account", UUID.randomUUID()))
        .isSameAs(auditDown);
  }

  private static void assertGenericNotFound(ResponseStatusException denial) {
    assertThat(denial.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(denial.getReason())
        .isEqualTo(AuthorizationDenialAuditService.GENERIC_NOT_FOUND_DETAIL);
  }
}
