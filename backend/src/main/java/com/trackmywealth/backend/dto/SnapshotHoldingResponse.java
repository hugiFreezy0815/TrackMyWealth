package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** One reported position of a snapshot, with enough of the security to display it. */
public record SnapshotHoldingResponse(
    UUID id,
    UUID securityId,
    String isin,
    String securityDisplayName,
    BigDecimal quantity,
    BigDecimal reportedCostBasis,
    boolean costBasisIsEstimated) {}
