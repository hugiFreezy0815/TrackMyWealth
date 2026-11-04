package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One reported position of a snapshot, with enough of the security to display it. {@code
 * reportedPrice}, {@code reportedValue} and {@code reportedCurrency} are what an imported statement
 * printed for it (#273), {@code null} for a holding entered by hand.
 */
public record SnapshotHoldingResponse(
    UUID id,
    UUID securityId,
    @Nullable String isin,
    String securityDisplayName,
    BigDecimal quantity,
    @Nullable BigDecimal reportedCostBasis,
    boolean costBasisIsEstimated,
    @Nullable BigDecimal reportedPrice,
    @Nullable BigDecimal reportedValue,
    @Nullable String reportedCurrency) {}
