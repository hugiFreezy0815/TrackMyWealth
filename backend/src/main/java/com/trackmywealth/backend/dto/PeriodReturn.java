package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * #607: one scope's period return in percent, two decimals, and the date it is measured from. Both
 * are null when nothing in the scope is tracked during the period; {@code percent} alone is null
 * when the return cannot be calculated (an unknown tracked value or flow, no elapsed time, or a
 * denominator of 0 or less).
 */
public record PeriodReturn(@Nullable BigDecimal percent, @Nullable LocalDate from) {

  public static final PeriodReturn NONE = new PeriodReturn(null, null);
}
