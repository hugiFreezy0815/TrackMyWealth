package com.trackmywealth.backend.dto;

import java.util.Collection;

/**
 * How far a shown value can be trusted (PR-011, FR-CON-007), one word a client can render as a
 * badge. Declared from the best to the worst; a total takes the worst status of what it sums.
 *
 * <p>Derived from the {@link AccountValuation} every figure already comes from, so no second
 * calculation path exists: {@code UNKNOWN} when it has no value, {@code UNRECONCILED} while a
 * reconciliation difference is open, {@code STALE} when a carried-forward FX rate is older than
 * {@code app.fx.stale-after}, {@code ESTIMATED} when the value rests on an approximation ({@link
 * ValueBasisValues#isApproximate}) or leaves out transactions booked before the opening balance.
 */
public enum ValueStatus {
  CURRENT,
  ESTIMATED,
  STALE,
  UNRECONCILED,
  UNKNOWN;

  /** The status of one account's value. */
  public static ValueStatus of(AccountValuation valuation) {
    if (!valuation.valueKnown()) {
      return UNKNOWN;
    }
    if (valuation.warnings().contains(DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE)) {
      return UNRECONCILED;
    }
    if (valuation.conversionRateStale()) {
      return STALE;
    }
    if (ValueBasisValues.isApproximate(valuation.valueBasis())
        || valuation
            .warnings()
            .contains(DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE)) {
      return ESTIMATED;
    }
    return CURRENT;
  }

  /** The worst of {@code statuses}; {@link #CURRENT} for none, e.g. an empty portfolio. */
  public static ValueStatus worstOf(Collection<ValueStatus> statuses) {
    ValueStatus worst = CURRENT;
    for (ValueStatus status : statuses) {
      if (status.compareTo(worst) > 0) {
        worst = status;
      }
    }
    return worst;
  }
}
