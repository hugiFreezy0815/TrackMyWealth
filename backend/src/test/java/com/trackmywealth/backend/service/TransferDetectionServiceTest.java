package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests for US-10-06's pure cross-currency tolerance rule. */
class TransferDetectionServiceTest {

  @Test
  void exactConvertedAmountMatches() {
    assertThat(matches("1000", "1040", "1.04", "0.02")).isTrue();
  }

  @Test
  void toleranceBoundaryIsInclusive() {
    assertThat(matches("1000", "1060.8", "1.04", "0.02")).isTrue();
    assertThat(matches("1000", "1019.2", "1.04", "0.02")).isTrue();
  }

  @Test
  void amountOutsideToleranceDoesNotMatch() {
    assertThat(matches("1000", "1060.8001", "1.04", "0.02")).isFalse();
    assertThat(matches("1000", "1019.1999", "1.04", "0.02")).isFalse();
  }

  private static boolean matches(String debit, String credit, String rate, String tolerance) {
    return TransferDetectionService.amountsWithinTolerance(
        new BigDecimal(debit),
        new BigDecimal(credit),
        new BigDecimal(rate),
        new BigDecimal(tolerance));
  }
}
