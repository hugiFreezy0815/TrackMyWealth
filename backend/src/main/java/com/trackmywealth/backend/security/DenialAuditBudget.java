package com.trackmywealth.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per-principal budget for authorization-denial audit rows (#205).
 *
 * <p>Within one refill window a principal gets up to {@code maxWritesPerPrincipal} exact rows; the
 * first denial beyond that gets one summary row, and later ones get none until the window refills.
 * This bounds how far one signed-in caller can grow {@code authorization_denial_log}, while the
 * summary keeps the probing signal. The decision never changes the caller's response - only which
 * row, if any, is written.
 *
 * <p>Windows live in a bounded, expiring cache: at most {@code maxPrincipals} entries, each dropped
 * two windows after its last use. An evicted principal simply starts a fresh window.
 */
public final class DenialAuditBudget {

  /** What to write for one denial. */
  public enum Decision {
    /** Write the exact denial row. */
    EXACT,
    /** The budget just ran out: write one summary row instead. */
    SUMMARY,
    /** Budget and summary both used up in this window: write nothing. */
    SUPPRESS
  }

  private final Cache<UUID, Window> windows;
  private final int maxWritesPerPrincipal;
  private final long refillNanos;
  private final LongSupplier nanoClock;

  public DenialAuditBudget(
      int maxWritesPerPrincipal, Duration refillPeriod, int maxPrincipals, LongSupplier nanoClock) {
    this.maxWritesPerPrincipal = maxWritesPerPrincipal;
    this.refillNanos = refillPeriod.toNanos();
    this.nanoClock = nanoClock;
    this.windows =
        Caffeine.newBuilder()
            .maximumSize(maxPrincipals)
            .expireAfterAccess(refillPeriod.multipliedBy(2))
            .build();
  }

  public Decision decide(UUID principalUserId) {
    long now = nanoClock.getAsLong();
    return windows
        .get(principalUserId, ignored -> new Window(now))
        .decide(now, refillNanos, maxWritesPerPrincipal);
  }

  private static final class Window {

    private long startedAtNanos;
    private int exactWrites;
    private boolean summaryWritten;

    private Window(long startedAtNanos) {
      this.startedAtNanos = startedAtNanos;
    }

    private synchronized Decision decide(long nowNanos, long refillNanos, int maxExact) {
      if (nowNanos - startedAtNanos >= refillNanos) {
        startedAtNanos = nowNanos;
        exactWrites = 0;
        summaryWritten = false;
      }
      if (exactWrites < maxExact) {
        exactWrites++;
        return Decision.EXACT;
      }
      if (!summaryWritten) {
        summaryWritten = true;
        return Decision.SUMMARY;
      }
      return Decision.SUPPRESS;
    }
  }
}
