package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One reported position of a snapshot, with enough of the security to display it. {@code
 * reportedPrice}, {@code reportedValue} and {@code reportedCurrency} are what an imported statement
 * printed for it (#273), {@code null} for a holding entered by hand.
 */
public record SnapshotHoldingResponse(
    UUID id,
    UUID securityId,
    String isin,
    String securityDisplayName,
    BigDecimal quantity,
    BigDecimal reportedCostBasis,
    boolean costBasisIsEstimated,
    BigDecimal reportedPrice,
    BigDecimal reportedValue,
    String reportedCurrency) {}
