package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.repository.TransferDetectionFxPendingRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for US-10-06's pure cross-currency tolerance rule and its configuration. {@code
 * TransferControllerTest} covers which rate the matcher picks against PostgreSQL.
 */
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

  // A zero tolerance is an exact conversion, to the last digit.
  @Test
  void aZeroToleranceNeedsTheExactConvertedAmount() {
    assertThat(matches("1000", "1040.00", "1.04", "0")).isTrue();
    assertThat(matches("1000", "1040.01", "1.04", "0")).isFalse();
  }

  @Test
  void aToleranceOutsideZeroToOneIsRejectedAtStartup() {
    assertThatThrownBy(() -> service("-0.01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.fx.transfer-match-tolerance");
    assertThatThrownBy(() -> service("1.01")).isInstanceOf(IllegalArgumentException.class);
    service("0");
    service("1");
  }

  private static TransferDetectionService service(String tolerance) {
    return new TransferDetectionService(
        mock(TransactionRepository.class),
        mock(SettlementMatchRepository.class),
        mock(SettlementDetectionService.class),
        mock(WorkspaceRepository.class),
        mock(FxRateService.class),
        mock(TransferDetectionFxPendingRepository.class),
        Clock.systemUTC(),
        "MANUAL",
        new BigDecimal(tolerance));
  }

  private static boolean matches(String debit, String credit, String rate, String tolerance) {
    return TransferDetectionService.amountsWithinTolerance(
        new BigDecimal(debit),
        new BigDecimal(credit),
        new BigDecimal(rate),
        new BigDecimal(tolerance));
  }
}
