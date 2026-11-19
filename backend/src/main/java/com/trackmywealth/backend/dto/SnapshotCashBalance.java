package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Cash in the currency reported by the statement. */
public record SnapshotCashBalance(@NotNull String currency, @NotNull BigDecimal amount) {}
