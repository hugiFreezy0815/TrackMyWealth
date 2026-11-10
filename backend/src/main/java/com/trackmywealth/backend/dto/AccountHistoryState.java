package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Effective membership and lifecycle event; verified=false for a pre-tracking baseline. */
public record AccountHistoryState(
    UUID accountId,
    UUID portfolioId,
    String status,
    OffsetDateTime effectiveAt,
    boolean verified) {}
