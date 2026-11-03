package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * The signed form of a valuation, shared by every response that shows signed amounts (the Portfolio
 * View, the account list): assets positive, liabilities negative, so a client adds them up as they
 * are. An {@link AccountValuation} itself keeps a magnitude plus a {@code nature}.
 */
public final class SignedAmounts {

  private static final String LIABILITY = "LIABILITY";

  private SignedAmounts() {}

  /** The valuation's value with the sign of its nature; {@code null} when it is unknown. */
  public static BigDecimal of(AccountValuation valuation) {
    if (!valuation.valueKnown()) {
      return null;
    }
    return LIABILITY.equals(valuation.nature()) ? valuation.value().negate() : valuation.value();
  }
}
