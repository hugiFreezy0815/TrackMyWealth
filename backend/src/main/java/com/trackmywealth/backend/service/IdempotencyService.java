package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.IdempotencyClaim;
import com.trackmywealth.backend.entity.IdempotencyKey;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.IdempotencyKeyRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent creates (US-29-14 #574, ADR 0006): a creating POST that carries an {@code
 * Idempotency-Key} first {@linkplain #claim claims} it, then creates its resource and {@linkplain
 * #complete stores the response} in the same transaction, or {@linkplain #release releases} the key
 * when it fails. A retry with the same key and the same request gets the stored response again
 * ({@link IdempotencyClaim#isReplay()}) and creates nothing.
 *
 * <p>The key is scoped to the user, the workspace and the operation; the request fingerprint must
 * match (else 422 {@code IDEMPOTENCY_KEY_MISMATCH}); a claim still running answers 409 {@code
 * IDEMPOTENCY_KEY_IN_USE}. A key is kept {@link #RETENTION}; afterwards it is a new request. A
 * claim left {@code IN_PROGRESS} longer than {@link #ABANDONED_AFTER} belongs to a request whose
 * server stopped mid-way: its transaction rolled back, nothing was created, and the next request
 * with the key takes it over. Only successful responses are stored: a failed create created
 * nothing.
 */
@Service
public class IdempotencyService {

  public static final String HEADER = "Idempotency-Key";
  public static final Duration RETENTION = Duration.ofHours(24);
  static final Duration ABANDONED_AFTER = Duration.ofMinutes(15);
  // The ADR asks clients for a UUID: one fixed, bounded shape, compared case-insensitively.
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final long IN_USE_RETRY_AFTER_SECONDS = 2;
  private static final int PURGE_BATCH = 1000;
  private static final int ONE_ROW = 1;
  private static final char SEPARATOR = '\u001f';
  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyService.class);

  private final IdempotencyKeyRepository repository;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final TransactionTemplate ownTransaction;

  public IdempotencyService(
      IdempotencyKeyRepository repository,
      ObjectMapper objectMapper,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * The header's value as stored: a UUID in lower case.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} for anything else
   */
  public static String normalizedKey(String header) {
    String value = header == null ? "" : header.strip();
    if (!UUID_SHAPE.matcher(value).matches()) {
      throw new ApiException(ProblemCode.VALIDATION_FAILED, HEADER + " must be a UUID.");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  /**
   * A SHA-256 over what makes two requests the same: {@code parts} in order (a {@code null} part
   * differs from every value) and the request's file content.
   */
  public static String fingerprint(byte[] content, Object... parts) {
    StringBuilder canonical = new StringBuilder();
    for (Object part : parts) {
      canonical.append(part == null ? "\u0000" : part.toString()).append(SEPARATOR);
    }
    MessageDigest digest = sha256();
    digest.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
    digest.update(content);
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Claims {@code key} for this request in a transaction of its own, committed before the create
   * starts, so a concurrent request with the same key sees it.
   *
   * @throws ApiException 422 {@code IDEMPOTENCY_KEY_MISMATCH} when the key was used for another
   *     request; 409 {@code IDEMPOTENCY_KEY_IN_USE} (with {@code Retry-After}) while the first
   *     request with it still runs
   */
  public IdempotencyClaim claim(
      String operation, String key, String fingerprint, AuthenticatedUserPrincipal actor) {
    return ownTransaction.execute(
        status -> {
          OffsetDateTime now = OffsetDateTime.now(clock);
          OffsetDateTime expiresAt = now.plus(RETENTION);
          int inserted =
              repository.insertIfAbsent(
                  actor.workspaceId(), actor.userId(), operation, key, fingerprint, now, expiresAt);
          IdempotencyKey row =
              repository
                  .lockByScope(actor.workspaceId(), actor.userId(), operation, key)
                  .orElseThrow(() -> new IllegalStateException("Claimed key is not visible."));
          if (inserted == ONE_ROW) {
            return IdempotencyClaim.held(row.getId());
          }
          boolean expired = !row.getExpiresAt().isAfter(now);
          boolean abandoned =
              IdempotencyKey.IN_PROGRESS.equals(row.getStatus())
                  && !row.getCreatedAt().plus(ABANDONED_AFTER).isAfter(now);
          if (expired || abandoned) {
            repository.reclaim(row.getId(), fingerprint, now, expiresAt);
            return IdempotencyClaim.held(row.getId());
          }
          if (!row.getRequestFingerprint().equals(fingerprint)) {
            throw new ApiException(
                ProblemCode.IDEMPOTENCY_KEY_MISMATCH,
                HEADER + " was already used for a different request.");
          }
          if (IdempotencyKey.IN_PROGRESS.equals(row.getStatus())) {
            throw new ApiException(
                    ProblemCode.IDEMPOTENCY_KEY_IN_USE,
                    "A request with this " + HEADER + " is still running.")
                .withRetryAfter(IN_USE_RETRY_AFTER_SECONDS);
          }
          return IdempotencyClaim.replay(row.getId(), row.getResponseBody());
        });
  }

  /**
   * Stores the response of the request that holds {@code claim}. Runs inside the transaction that
   * creates the resource, so both commit together or not at all.
   *
   * @throws IllegalStateException when the claim is no longer held, which rolls the create back
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(IdempotencyClaim claim, int responseStatus, Object response) {
    String body = objectMapper.writeValueAsString(response);
    if (repository.complete(claim.id(), (short) responseStatus, body) != ONE_ROW) {
      throw new IllegalStateException("The idempotency claim is no longer held.");
    }
  }

  /** Gives the key up after a failed create, so a retry runs as a new request. */
  public void release(IdempotencyClaim claim) {
    ownTransaction.executeWithoutResult(status -> repository.release(claim.id()));
  }

  /** The stored response of a {@linkplain IdempotencyClaim#isReplay() replayed} claim. */
  public <T> T replayed(IdempotencyClaim claim, Class<T> type) {
    if (!claim.isReplay()) {
      throw new IllegalStateException("The claim has no stored response.");
    }
    return objectMapper.readValue(claim.replayBody(), type);
  }

  /** Deletes expired keys in short batches; harmless when several instances run it at once. */
  @Scheduled(
      fixedDelayString = "${app.idempotency.cleanup-interval:PT1H}",
      initialDelayString = "${app.idempotency.cleanup-interval:PT1H}")
  public void deleteExpiredKeys() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    long deleted = 0;
    int batchDeleted;
    do {
      Integer result = ownTransaction.execute(status -> repository.purgeExpired(now, PURGE_BATCH));
      batchDeleted = result == null ? 0 : result;
      deleted += batchDeleted;
    } while (batchDeleted == PURGE_BATCH);
    if (deleted > 0) {
      LOG.info("Deleted {} expired idempotency key(s)", deleted);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
