package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A preview's duplicate row and the ledger row that holds its booking when the commit runs (#287):
 * the row it points at, or what replaced that row since - a correction's replacement, a restored
 * void's copy. A query projection of {@code ImportRowRawRepository}; internal, not part of any API
 * response. It lives here because {@code ArchitectureTest} allows only repositories in {@code
 * ..repository..}.
 */
public interface ImportDuplicateHold {
  UUID getRowId();

  UUID getTransactionId();
}
