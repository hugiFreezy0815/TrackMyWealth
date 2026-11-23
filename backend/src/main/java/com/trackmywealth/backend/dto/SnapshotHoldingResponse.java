package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One reported position of a snapshot, with enough of the security to display it. {@code
 * reportedPrice}, {@code reportedValue} and {@code reportedCurrency} come from the imported
 * statement or the manual snapshot request. Each field is {@code null} when no figure or currency
 * was supplied for it.
 */
public record SnapshotHoldingResponse(
    UUID id,
    UUID securityId,
    @Nullable String isin,
    String securityDisplayName,
    BigDecimal quantity,
    @Nullable BigDecimal reportedCostBasis,
    @Schema(description = "Currency of reportedCostBasis; null when unknown (#527).")
        @Nullable String reportedCostBasisCurrency,
    @Nullable BigDecimal costBasisInAccountCurrency,
    boolean costBasisIsEstimated,
    @Nullable BigDecimal reportedPrice,
    @Nullable BigDecimal reportedValue,
    @Nullable String reportedCurrency) {}
