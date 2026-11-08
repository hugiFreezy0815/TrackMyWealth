package com.trackmywealth.backend.dto;

import java.util.Map;
import java.util.UUID;

/** Immutable, page-wide enrichment lookup; never one query per transaction. */
public record TransactionReadContext(
    Map<UUID, TransactionReadAccountDetails> accounts,
    Map<UUID, TransactionImportBatchResponse> imports) {

  public static final TransactionReadContext EMPTY = new TransactionReadContext(Map.of(), Map.of());

  public TransactionReadContext {
    accounts = Map.copyOf(accounts);
    imports = Map.copyOf(imports);
  }
}
