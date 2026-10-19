package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * US-07-31 (#366): one position a statement contributes to an imported snapshot - its resolved
 * security, its quantity, and the price, value and currency the statement reports.
 */
public record ImportSnapshotHolding(
    UUID securityId, BigDecimal quantity, BigDecimal price, BigDecimal value, String currency) {}
