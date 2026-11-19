package com.trackmywealth.backend.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of presenting an {@code Idempotency-Key} (#574): either this request now holds the
 * key ({@code replayBody == null}) and must complete or release it, or the key already completed
 * the same request and {@code replayBody} is the stored response to answer with again.
 */
public record IdempotencyClaim(UUID id, @Nullable String replayBody) {

  public static IdempotencyClaim held(UUID id) {
    return new IdempotencyClaim(id, null);
  }

  public static IdempotencyClaim replay(UUID id, String body) {
    return new IdempotencyClaim(id, body);
  }

  public boolean isReplay() {
    return replayBody != null;
  }
}
