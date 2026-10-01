package com.trackmywealth.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.ObjIntConsumer;

/**
 * Per-principal budget for authorization-denial audit rows (#205).
 *
 * <p>Within one refill window a principal gets up to {@code maxWritesPerPrincipal} exact rows; the
 * first denial beyond that gets one summary row, and later ones get none until the window refills.
 * This bounds how far one signed-in caller can grow {@code authorization_denial_log}, while the
 * summary keeps the probing signal. The decision never changes the caller's response - only which
 * row, if any, is written.
 *
 * <p>The denials answered without a row are counted, so their magnitude isn't lost: the principal's
 * first denial after the window closes carries the count ({@link Decision#closedWindowSuppressed}).
 * A principal who never comes back has the count handed to {@code unreportedSuppressions} when its
 * window is evicted, or on shutdown via {@link #reportUnreportedSuppressions()}.
 *
 * <p>Windows live in a bounded, expiring cache: at most {@code maxPrincipals} entries, each dropped
 * two windows after its last use. An evicted principal simply starts a fresh window. The cache is
 * per application instance, so with several instances each bound applies once per instance.
 */
public final class DenialAuditBudget {

  /** Which row one denial gets. */
  public enum Kind {
    /** Write the exact denial row. */
    EXACT,
    /** The budget just ran out: write one summary row instead. */
    SUMMARY,
    /** Budget and summary both used up in this window: write nothing. */
    SUPPRESS
  }

  /**
   * What to write for one denial.
   *
   * @param kind the row this denial gets
   * @param closedWindowSuppressed how many denials the principal's previous window suppressed, if
   *     this denial is the first since it closed; otherwise 0. Above 0, write that window's closing
   *     summary as well.
   */
  public record Decision(Kind kind, int closedWindowSuppressed) {}

  private final Cache<UUID, Window> windows;
  private final int maxWritesPerPrincipal;
  private final long refillNanos;
  private final LongSupplier nanoClock;
  private final ObjIntConsumer<UUID> unreportedSuppressions;

  /**
   * @param unreportedSuppressions receives a principal's suppressed count when no later denial of
   *     its own will carry it; runs on {@code listenerExecutor}
   */
  public DenialAuditBudget(
      int maxWritesPerPrincipal,
      Duration refillPeriod,
      int maxPrincipals,
      LongSupplier nanoClock,
      ObjIntConsumer<UUID> unreportedSuppressions,
      Executor listenerExecutor) {
    this.maxWritesPerPrincipal = maxWritesPerPrincipal;
    this.refillNanos = refillPeriod.toNanos();
    this.nanoClock = nanoClock;
    this.unreportedSuppressions = unreportedSuppressions;
    this.windows =
        Caffeine.newBuilder()
            .maximumSize(maxPrincipals)
            .expireAfterAccess(refillPeriod.multipliedBy(2))
            .ticker(nanoClock::getAsLong)
            .executor(listenerExecutor)
            .<UUID, Window>removalListener(
                (principal, window, cause) -> {
                  if (cause.wasEvicted() && principal != null && window != null) {
                    report(principal, window);
                  }
                })
            .build();
  }

  public Decision decide(UUID principalUserId) {
    long now = nanoClock.getAsLong();
    return windows
        .get(principalUserId, ignored -> new Window(now))
        .decide(now, refillNanos, maxWritesPerPrincipal);
  }

  /** Hands every count no later denial has carried yet to {@code unreportedSuppressions}. */
  public void reportUnreportedSuppressions() {
    windows.asMap().forEach(this::report);
  }

  /** Runs pending evictions now (and so their reports); for tests on a controlled clock. */
  void cleanUp() {
    windows.cleanUp();
  }

  private void report(UUID principal, Window window) {
    int suppressed = window.drainSuppressed();
    if (suppressed > 0) {
      unreportedSuppressions.accept(principal, suppressed);
    }
  }

  private static final class Window {

    private long startedAtNanos;
    private int exactWrites;
    private boolean summaryWritten;
    private int suppressed;

    private Window(long startedAtNanos) {
      this.startedAtNanos = startedAtNanos;
    }

    private synchronized Decision decide(long nowNanos, long refillNanos, int maxExact) {
      int closedWindowSuppressed = 0;
      if (nowNanos - startedAtNanos >= refillNanos) {
        closedWindowSuppressed = suppressed;
        startedAtNanos = nowNanos;
        exactWrites = 0;
        summaryWritten = false;
        suppressed = 0;
      }
      if (exactWrites < maxExact) {
        exactWrites++;
        return new Decision(Kind.EXACT, closedWindowSuppressed);
      }
      if (!summaryWritten) {
        summaryWritten = true;
        return new Decision(Kind.SUMMARY, closedWindowSuppressed);
      }
      suppressed++;
      return new Decision(Kind.SUPPRESS, closedWindowSuppressed);
    }

    private synchronized int drainSuppressed() {
      int drained = suppressed;
      suppressed = 0;
      return drained;
    }
  }
}
