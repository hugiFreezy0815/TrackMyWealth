package com.trackmywealth.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US-04-06: one account's {@link ValueStatus}, and a total's as the worst of its parts. */
class ValueStatusTest {

  @Test
  void anExactFreshValueIsCurrent() {
    assertThat(ValueStatus.of(valuation(true, ValueBasisValues.LEDGER, false, List.of())))
        .isEqualTo(ValueStatus.CURRENT);
  }

  @Test
  void anApproximateValueIsEstimated() {
    assertThat(ValueStatus.of(valuation(true, ValueBasisValues.LEDGER_EMPTY, false, List.of())))
        .isEqualTo(ValueStatus.ESTIMATED);
    assertThat(
            ValueStatus.of(
                valuation(
                    true,
                    ValueBasisValues.LEDGER,
                    false,
                    List.of(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE))))
        .isEqualTo(ValueStatus.ESTIMATED);
  }

  @Test
  void aStaleRateOutranksAnApproximation() {
    assertThat(ValueStatus.of(valuation(true, ValueBasisValues.LEDGER_EMPTY, true, List.of())))
        .isEqualTo(ValueStatus.STALE);
  }

  @Test
  void anOpenReconciliationDifferenceOutranksAStaleRate() {
    assertThat(
            ValueStatus.of(
                valuation(
                    true,
                    ValueBasisValues.LEDGER,
                    true,
                    List.of(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE))))
        .isEqualTo(ValueStatus.UNRECONCILED);
  }

  @Test
  void noValueIsUnknownWhateverElseHolds() {
    assertThat(
            ValueStatus.of(
                valuation(
                    false,
                    null,
                    false,
                    List.of(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE))))
        .isEqualTo(ValueStatus.UNKNOWN);
  }

  @Test
  void aTotalTakesTheWorstStatusAndNothingIsCurrent() {
    assertThat(ValueStatus.worstOf(List.of())).isEqualTo(ValueStatus.CURRENT);
    assertThat(
            ValueStatus.worstOf(
                List.of(ValueStatus.CURRENT, ValueStatus.STALE, ValueStatus.ESTIMATED)))
        .isEqualTo(ValueStatus.STALE);
  }

  private static AccountValuation valuation(
      boolean known, String valueBasis, boolean rateStale, List<String> warnings) {
    return new AccountValuation(
        UUID.randomUUID(),
        "Account",
        "ASSET",
        "CHF",
        "CHF",
        known ? new BigDecimal("100.0000") : null,
        null,
        null,
        rateStale,
        rateStale,
        known,
        valueBasis,
        null,
        warnings);
  }
}
