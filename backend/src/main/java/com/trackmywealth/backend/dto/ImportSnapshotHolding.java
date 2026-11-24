package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * US-07-31 (#366): one position a statement contributes to an imported snapshot - its resolved
 * security, its quantity, and the price, value and currency the statement reports. US-15-08: {@code
 * costBasis} is its total cost in {@code currency}, null when the statement prints none.
 */
public record ImportSnapshotHolding(
    UUID securityId,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal value,
    String currency,
    @Nullable BigDecimal costBasis) {}
