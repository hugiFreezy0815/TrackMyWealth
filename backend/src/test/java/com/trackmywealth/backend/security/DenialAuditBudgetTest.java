package com.trackmywealth.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.security.DenialAuditBudget.Decision;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** #205: the per-principal audit budget, on a clock the test controls. */
class DenialAuditBudgetTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final AtomicLong now = new AtomicLong();
  private final DenialAuditBudget budget = new DenialAuditBudget(2, WINDOW, 100, now::get);
  private final UUID principal = UUID.randomUUID();

  @Test
  void theBudgetIsExactRowsThenOneSummaryThenNothing() {
    assertThat(budget.decide(principal)).isEqualTo(Decision.EXACT);
    assertThat(budget.decide(principal)).isEqualTo(Decision.EXACT);
    assertThat(budget.decide(principal)).isEqualTo(Decision.SUMMARY);
    assertThat(budget.decide(principal)).isEqualTo(Decision.SUPPRESS);
    assertThat(budget.decide(principal)).isEqualTo(Decision.SUPPRESS);
  }

  @Test
  void theBudgetRefillsOnlyOnceTheWindowHasPassed() {
    budget.decide(principal);
    budget.decide(principal);
    budget.decide(principal);

    now.addAndGet(WINDOW.toNanos() - 1);
    assertThat(budget.decide(principal)).as("still the same window").isEqualTo(Decision.SUPPRESS);

    now.addAndGet(1);
    assertThat(budget.decide(principal)).as("a new window").isEqualTo(Decision.EXACT);
    assertThat(budget.decide(principal)).isEqualTo(Decision.EXACT);
    assertThat(budget.decide(principal))
        .as("the summary is available again in the new window")
        .isEqualTo(Decision.SUMMARY);
  }

  @Test
  void eachPrincipalHasItsOwnBudget() {
    budget.decide(principal);
    budget.decide(principal);
    budget.decide(principal);

    assertThat(budget.decide(UUID.randomUUID())).isEqualTo(Decision.EXACT);
  }
}
