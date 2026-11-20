package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * #607: one account value a period return reads - the scope's start or end value, or a flow into or
 * out of the scope on its day (an account entering tracking, or a lifecycle or portfolio change).
 */
public record ReturnTerm(UUID accountId, LocalDate date, Kind kind) {

  /** Where the value goes in the Modified Dietz formula. */
  public enum Kind {
    START,
    END,
    FLOW_IN,
    FLOW_OUT
  }
}
