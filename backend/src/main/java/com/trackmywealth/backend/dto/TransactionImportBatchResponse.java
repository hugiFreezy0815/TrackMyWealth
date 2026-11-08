package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Provenance of a transaction imported from a statement, without exposing raw source data. */
public record TransactionImportBatchResponse(
    UUID id, String fileFormat, OffsetDateTime uploadedAt) {}
