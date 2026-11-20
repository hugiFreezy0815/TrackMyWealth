package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.IdempotencyClaim;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** US-29-17 (#629): the wrapper's branches; the database behaviour is integration-tested. */
class IdempotentCreateServiceTest {

  private static final String CREATE_UUID = "0F8FAD5B-D9CB-469F-A165-70867728950E";
  private static final UUID WORKSPACE = UUID.randomUUID();

  private final IdempotencyService idempotency = mock(IdempotencyService.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final IdempotentCreateService service =
      new IdempotentCreateService(idempotency, objectMapper, transactions);
  private final AtomicInteger creates = new AtomicInteger();

  IdempotentCreateServiceTest() {
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
  }

  @Test
  void withoutAKeyTheCreateRunsAsBefore() {
    assertThat(create(null, member())).isEqualTo("created-1");
    verifyNoInteractions(idempotency);
  }

  @Test
  void anAdministratorWithoutAWorkspaceHasNoKeyScope() {
    AuthenticatedUserPrincipal administrator =
        new AuthenticatedUserPrincipal(UUID.randomUUID(), "SYSTEM_ADMINISTRATOR", null, null, "EN");

    assertThat(create(CREATE_UUID, administrator)).isEqualTo("created-1");
    verifyNoInteractions(idempotency);
  }

  @Test
  void aHeldClaimCreatesAndStoresTheResponseInTheSameTransaction() {
    IdempotencyClaim claim = IdempotencyClaim.held(UUID.randomUUID(), OffsetDateTime.now());
    when(idempotency.claim(eq("createThing"), anyString(), anyString(), any())).thenReturn(claim);

    assertThat(create(CREATE_UUID, member())).isEqualTo("created-1");

    // The key is stored normalized; the fingerprint covers the operation and the request.
    verify(idempotency)
        .claim(
            "createThing",
            CREATE_UUID.toLowerCase(Locale.ROOT),
            IdempotencyService.fingerprint(
                objectMapper.writeValueAsBytes(List.of(WORKSPACE, Map.of("name", "A"))),
                "createThing"),
            member());
    verify(idempotency).complete(claim, 201, "created-1");
    verify(transactions).commit(any());
    verify(idempotency, never()).release(any());
  }

  @Test
  void aReplayAnswersTheStoredResponseAndCreatesNothing() {
    IdempotencyClaim replay = IdempotencyClaim.replay(UUID.randomUUID(), "\"stored\"");
    when(idempotency.claim(any(), any(), any(), any())).thenReturn(replay);
    when(idempotency.replayed(replay, String.class)).thenReturn("stored");

    assertThat(create(CREATE_UUID, member())).isEqualTo("stored");
    assertThat(creates).hasValue(0);
    verifyNoInteractions(transactions);
  }

  @Test
  void aFailedCreateReleasesTheKeyAndRethrows() {
    IdempotencyClaim claim = IdempotencyClaim.held(UUID.randomUUID(), OffsetDateTime.now());
    when(idempotency.claim(any(), any(), any(), any())).thenReturn(claim);
    IllegalStateException failure = new IllegalStateException("create failed");

    assertThatThrownBy(() -> failingCreate(failure)).isSameAs(failure);

    verify(idempotency).release(claim);
    verify(idempotency, never()).complete(any(), anyInt(), any());
    verify(transactions).rollback(any());
  }

  @Test
  void aFailedReleaseDoesNotHideWhyTheCreateFailed() {
    IdempotencyClaim claim = IdempotencyClaim.held(UUID.randomUUID(), OffsetDateTime.now());
    when(idempotency.claim(any(), any(), any(), any())).thenReturn(claim);
    IllegalStateException releaseFailure = new IllegalStateException("release failed");
    doThrow(releaseFailure).when(idempotency).release(claim);
    IllegalStateException failure = new IllegalStateException("create failed");

    assertThatThrownBy(() -> failingCreate(failure))
        .isSameAs(failure)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(releaseFailure));
  }

  @Test
  void aKeyThatIsNoUuidIsRejectedBeforeAnyClaim() {
    assertThatThrownBy(() -> create("not-a-uuid", member())).isInstanceOf(ApiException.class);
    verifyNoInteractions(idempotency);
    assertThat(creates).hasValue(0);
  }

  private String create(String key, AuthenticatedUserPrincipal actor) {
    return service.create(
        "createThing",
        key,
        actor,
        List.of(WORKSPACE, Map.of("name", "A")),
        String.class,
        () -> "created-" + creates.incrementAndGet());
  }

  private String failingCreate(RuntimeException failure) {
    return service.create(
        "createThing",
        CREATE_UUID,
        member(),
        Map.of("name", "A"),
        String.class,
        () -> {
          throw failure;
        });
  }

  private static AuthenticatedUserPrincipal member() {
    return new AuthenticatedUserPrincipal(new UUID(0, 1), "STANDARD_USER", WORKSPACE, null, "EN");
  }
}
