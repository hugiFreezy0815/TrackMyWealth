package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Cash in the currency reported by the statement. A missing field is a 400 like any other; an
 * unknown ISO 4217 code or an amount beyond the money scale is a 422 from {@code
 * AccountSnapshotService} (#614).
 */
public record SnapshotCashBalance(@NotNull String currency, @NotNull BigDecimal amount) {}
