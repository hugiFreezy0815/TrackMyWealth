package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.IdempotencyClaim;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * US-29-17 (#629): ADR 0006's {@code Idempotency-Key} for a creating POST with a JSON body, on top
 * of {@link IdempotencyService} (#574). Without the header, or for an administrator without a
 * workspace (no key scope), the create runs as before.
 *
 * <p>With it, the key is {@linkplain IdempotencyService#claim claimed} first. A retry of a finished
 * request gets the stored 201 body again and creates nothing. Otherwise the create and {@linkplain
 * IdempotencyService#complete storing its response} run in one transaction, which the create
 * service's own {@code @Transactional} joins, so both commit together or not at all; a failed
 * create {@linkplain IdempotencyService#release releases} the key.
 *
 * <p>The request that must match is the operation's path parameters and its JSON body, compared as
 * the JSON the server reads them as.
 */
@Service
public class IdempotentCreateService {

  public static final String HEADER = IdempotencyService.HEADER;

  /** The header's OpenAPI description, shared by every operation that takes it. */
  public static final String HEADER_DESCRIPTION =
      "A fresh UUID for each create, the same UUID on every retry of it (ADR 0006). A retry of the"
          + " same request within 24 hours answers the first request's 201 again and creates"
          + " nothing.";

  private final IdempotencyService idempotencyService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transaction;

  public IdempotentCreateService(
      IdempotencyService idempotencyService,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.idempotencyService = idempotencyService;
    this.objectMapper = objectMapper;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * Runs {@code create} at most once per key.
   *
   * @param operation the OpenAPI operation id, part of the key's scope
   * @param idempotencyKey the header's value, or {@code null} when the request has none
   * @param request what makes two requests the same: the path parameters and the body
   * @param responseType the 201 body's type, to read a stored response back
   */
  public <T> T create(
      String operation,
      @Nullable String idempotencyKey,
      AuthenticatedUserPrincipal actor,
      Object request,
      Class<T> responseType,
      Supplier<T> create) {
    if (idempotencyKey == null || actor.workspaceId() == null) {
      return create.get();
    }
    IdempotencyClaim claim =
        idempotencyService.claim(
            operation,
            IdempotencyService.normalizedKey(idempotencyKey),
            IdempotencyService.fingerprint(objectMapper.writeValueAsBytes(request), operation),
            actor);
    if (claim.isReplay()) {
      return idempotencyService.replayed(claim, responseType);
    }
    try {
      return transaction.execute(
          status -> {
            T created = create.get();
            idempotencyService.complete(claim, HttpStatus.CREATED.value(), created);
            return created;
          });
    } catch (RuntimeException failure) {
      releaseAfterFailure(claim, failure);
      throw failure;
    }
  }

  // A release that fails too must not hide why the create failed; an unreleased claim is taken over
  // once it counts as abandoned (IdempotencyService).
  private void releaseAfterFailure(IdempotencyClaim claim, RuntimeException failure) {
    try {
      idempotencyService.release(claim);
    } catch (RuntimeException releaseFailure) {
      failure.addSuppressed(releaseFailure);
    }
  }
}
