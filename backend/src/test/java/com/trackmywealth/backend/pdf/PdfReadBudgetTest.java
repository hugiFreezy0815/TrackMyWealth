package com.trackmywealth.backend.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The drawing budget of one PDF read; its use on real PDFs is in PdfImportReaderServiceTest. */
class PdfReadBudgetTest {

  @Test
  void theLastAllowedOperationRunsAndNoneAfterIt() {
    PdfReadBudget budget = new PdfReadBudget(3, Duration.ofMinutes(1));
    List<Boolean> allowed = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      allowed.add(budget.allowOperation());
    }

    assertThat(allowed).containsExactly(true, true, true, false, false);
    assertThatThrownBy(budget::requireWithinLimits)
        .isInstanceOf(PdfLimitException.class)
        .hasMessageContaining("draws more than a statement needs");
  }

  @Test
  void aReadPastItsTimeIsRefusedAndStopsDrawing() {
    PdfReadBudget budget = new PdfReadBudget(1_000_000, Duration.ZERO);
    boolean allowed = true;
    // The clock is read every 1,024 operations.
    for (int i = 0; i < 1024 && allowed; i++) {
      allowed = budget.allowOperation();
    }

    assertThat(allowed).isFalse();
    assertThatThrownBy(budget::requireWithinLimits)
        .isInstanceOf(PdfLimitException.class)
        .hasMessageContaining("takes longer than a statement may");
  }

  @Test
  void theFirstRefusalIsTheOneReported() {
    PdfReadBudget budget = new PdfReadBudget(1, Duration.ofMinutes(1));
    budget.refuse(new PdfLimitException("first"));
    budget.refuse(new PdfLimitException("second"));

    assertThat(budget.allowOperation()).isFalse();
    assertThatThrownBy(budget::requireWithinLimits).hasMessage("first");
  }

  @Test
  void withinItsLimitsAReadPasses() throws PdfLimitException {
    PdfReadBudget budget = new PdfReadBudget(10, Duration.ofMinutes(1));
    assertThat(budget.allowOperation()).isTrue();

    budget.requireWithinLimits();
  }
}
