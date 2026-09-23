package com.trackmywealth.backend.dto;

import java.util.List;

/**
 * FR-SMD-011: which analysis-relevant master-data fields are still missing, so an analysis that
 * needs one can say so instead of silently leaving the position out (PR-011).
 */
public record SecurityCompleteness(boolean complete, List<String> missingFields) {

  public SecurityCompleteness {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as CashFlowResponse.
    missingFields = List.copyOf(missingFields);
  }
}
