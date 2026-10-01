package com.trackmywealth.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.security.DenialAuditBudget.Decision;
import com.trackmywealth.backend.security.DenialAuditBudget.Kind;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** #205: the per-principal audit budget, on a clock the test controls. */
class DenialAuditBudgetTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final AtomicLong now = new AtomicLong();
  private final Map<UUID, Integer> reported = new ConcurrentHashMap<>();
  private final DenialAuditBudget budget =
      new DenialAuditBudget(2, WINDOW, 100, now::get, reported::put, Runnable::run);
  private final UUID principal = UUID.randomUUID();

  @Test
  void theBudgetIsExactRowsThenOneSummaryThenNothing() {
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.EXACT, 0));
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.EXACT, 0));
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.SUMMARY, 0));
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.SUPPRESS, 0));
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.SUPPRESS, 0));
  }

  @Test
  void theBudgetRefillsOnlyOnceTheWindowHasPassed() {
    exhaust(principal);

    now.addAndGet(WINDOW.toNanos() - 1);
    assertThat(budget.decide(principal).kind())
        .as("still the same window")
        .isEqualTo(Kind.SUPPRESS);

    now.addAndGet(1);
    assertThat(budget.decide(principal).kind()).as("a new window").isEqualTo(Kind.EXACT);
    assertThat(budget.decide(principal).kind()).isEqualTo(Kind.EXACT);
    assertThat(budget.decide(principal).kind())
        .as("the summary is available again in the new window")
        .isEqualTo(Kind.SUMMARY);
  }

  @Test
  void theFirstDenialAfterTheWindowClosesCarriesItsSuppressedCountOnce() {
    exhaust(principal);
    budget.decide(principal);
    budget.decide(principal);
    budget.decide(principal);

    now.addAndGet(WINDOW.toNanos());
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.EXACT, 3));
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.EXACT, 0));
  }

  @Test
  void aWindowThatSuppressedNothingCarriesNoCount() {
    budget.decide(principal);
    budget.decide(principal);
    budget.decide(principal); // the summary, but nothing suppressed

    now.addAndGet(WINDOW.toNanos());
    assertThat(budget.decide(principal)).isEqualTo(new Decision(Kind.EXACT, 0));
  }

  @Test
  void anEvictedWindowHandsItsSuppressedCountToTheListener() {
    exhaust(principal);
    budget.decide(principal);
    budget.decide(principal);

    now.addAndGet(WINDOW.multipliedBy(2).toNanos() + 1);
    budget.cleanUp();

    assertThat(reported).containsExactly(Map.entry(principal, 2));
    assertThat(budget.decide(principal))
        .as("an evicted principal starts a fresh window with nothing left to carry")
        .isEqualTo(new Decision(Kind.EXACT, 0));
  }

  @Test
  void shutdownReportsPendingCountsExactlyOnce() {
    UUID quiet = UUID.randomUUID();
    exhaust(principal);
    budget.decide(principal);
    budget.decide(quiet);

    budget.reportUnreportedSuppressions();
    assertThat(reported).containsExactly(Map.entry(principal, 1));

    reported.clear();
    budget.reportUnreportedSuppressions();
    assertThat(reported).as("a count is never reported twice").isEmpty();
  }

  @Test
  void eachPrincipalHasItsOwnBudget() {
    exhaust(principal);

    assertThat(budget.decide(UUID.randomUUID()).kind()).isEqualTo(Kind.EXACT);
  }

  /** Two exact rows and the summary: the next denial in this window is suppressed. */
  private void exhaust(UUID who) {
    budget.decide(who);
    budget.decide(who);
    budget.decide(who);
  }
}
