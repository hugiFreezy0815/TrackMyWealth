package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * The complete currently-effective ownership set of one account (US-03-02, FR-CNC-001/002).
 *
 * <p>The account is the aggregate concurrency owner: a PUT replaces this whole list, including the
 * valid empty-list case, so its version is available even when there is no ownership row to carry
 * one.
 */
public record AccountOwnershipSetResponse(
    UUID accountId, int version, List<AccountOwnershipResponse> owners) {

  public AccountOwnershipSetResponse {
    owners = List.copyOf(owners);
  }
}
