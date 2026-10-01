package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.trackmywealth.backend.config.AuthorizationDenialAuditProperties;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-28-02 / #205: object-level denials stay generic while audit work is bounded and detached from
 * the request thread.
 */
class AuthorizationDenialAuditServiceTest {

  private static final UUID PRINCIPAL =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void denialRecordsOnlyRequestedIdentityAndReturnsGenericNotFound() {
    AuthorizationDenialAuditWriterService writer = mock(AuthorizationDenialAuditWriterService.class);
    AuthorizationDenialAuditService service =
        new AuthorizationDenialAuditService(Runnable::run, writer, properties(10));
    UUID requestedId = UUID.randomUUID();
    AuthenticatedUserPrincipal actor =
        new AuthenticatedUserPrincipal(
            PRINCIPAL, "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID());

    ResponseStatusException denial = service.denyAsNotFound(actor, "Account", requestedId);

    assertThat(denial.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(denial.getReason())
        .isEqualTo(AuthorizationDenialAuditService.GENERIC_NOT_FOUND_DETAIL);
    verify(writer).recordDenial(PRINCIPAL, "Account", requestedId);
  }

  @Test
  void onePrincipalGetsBoundedExactRowsThenOneSummaryWithoutChanging404() {
    AuthorizationDenialAuditWriterService writer = mock(AuthorizationDenialAuditWriterService.class);
    AuthorizationDenialAuditService service =
        new AuthorizationDenialAuditService(Runnable::run, writer, properties(2));

    for (int i = 0; i < 8; i++) {
      ResponseStatusException denial =
          service.denyAsNotFound(PRINCIPAL, "Account", UUID.randomUUID());

      assertThat(denial.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(denial.getReason())
          .isEqualTo(AuthorizationDenialAuditService.GENERIC_NOT_FOUND_DETAIL);
    }

    verify(writer, times(2)).recordDenial(eq(PRINCIPAL), eq("Account"), any(UUID.class));
    verify(writer).recordRateLimitedSummary(PRINCIPAL);
  }

  @Test
  void principalBudgetsAreIndependent() {
    AuthorizationDenialAuditWriterService writer = mock(AuthorizationDenialAuditWriterService.class);
    AuthorizationDenialAuditService service =
        new AuthorizationDenialAuditService(Runnable::run, writer, properties(1));
    UUID secondPrincipal = UUID.fromString("22222222-2222-2222-2222-222222222222");

    service.denyAsNotFound(PRINCIPAL, "Account", UUID.randomUUID());
    service.denyAsNotFound(secondPrincipal, "Account", UUID.randomUUID());

    verify(writer).recordDenial(eq(PRINCIPAL), eq("Account"), any(UUID.class));
    verify(writer).recordDenial(eq(secondPrincipal), eq("Account"), any(UUID.class));
    verify(writer, never()).recordRateLimitedSummary(any());
  }

  @Test
  void aFullAuditQueueNeverChangesThe404OrFallsBackToRequestThread() {
    AuthorizationDenialAuditWriterService writer = mock(AuthorizationDenialAuditWriterService.class);
    Executor rejectingExecutor =
        command -> {
          throw new RejectedExecutionException("full");
        };
    AuthorizationDenialAuditService service =
        new AuthorizationDenialAuditService(rejectingExecutor, writer, properties(10));

    ResponseStatusException denial =
        service.denyAsNotFound(PRINCIPAL, "Account", UUID.randomUUID());

    assertThat(denial.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(denial.getReason())
        .isEqualTo(AuthorizationDenialAuditService.GENERIC_NOT_FOUND_DETAIL);
    verify(writer, never()).recordDenial(any(), any(), any());
    verify(writer, never()).recordRateLimitedSummary(any());
  }

  private static AuthorizationDenialAuditProperties properties(int maxWrites) {
    return new AuthorizationDenialAuditProperties(
        maxWrites,
        Duration.ofMinutes(1),
        100,
        10,
        1,
        Duration.ofDays(90),
        Duration.ofHours(1));
  }
}
