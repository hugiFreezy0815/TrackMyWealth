package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.trackmywealth.backend.entity.AuthorizationDenialLog;
import com.trackmywealth.backend.repository.AuthorizationDenialLogRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** US-28-02: object-level denials are generic to callers and uniform in the audit trail. */
class AuthorizationDenialAuditServiceTest {

  @Test
  void denialRecordsOnlyTheRequestedIdentityAndReturnsTheGenericNotFound() {
    AuthorizationDenialLogRepository repository =
        org.mockito.Mockito.mock(AuthorizationDenialLogRepository.class);
    AuthorizationDenialAuditService service = new AuthorizationDenialAuditService(repository);
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    UUID requestedId = UUID.randomUUID();
    AuthenticatedUserPrincipal actor =
        new AuthenticatedUserPrincipal(userId, "STANDARD_USER", workspaceId, UUID.randomUUID());

    ResponseStatusException denial = service.denyAsNotFound(actor, "Account", requestedId);

    assertThat(denial.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(denial.getReason())
        .isEqualTo(AuthorizationDenialAuditService.GENERIC_NOT_FOUND_DETAIL);

    ArgumentCaptor<AuthorizationDenialLog> log =
        ArgumentCaptor.forClass(AuthorizationDenialLog.class);
    verify(repository).saveAndFlush(log.capture());
    assertThat(log.getValue().getPrincipalUserId()).isEqualTo(userId);
    assertThat(log.getValue().getRequestedEntityType()).isEqualTo("Account");
    assertThat(log.getValue().getRequestedEntityId()).isEqualTo(requestedId);
    assertThat(log.getValue().getReason()).isEqualTo("NOT_FOUND");
  }
}
