package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * One transaction of a rolled-back batch that someone had worked on, and how ({@code reasons}, the
 * criterion codes of {@link ImportRollbackValues}, at least one). Any one of them makes the
 * rollback a void (US-07-05).
 */
public record ImportRollbackModifiedResponse(UUID transactionId, List<String> reasons) {

  public ImportRollbackModifiedResponse {
    reasons = List.copyOf(reasons);
  }
}
