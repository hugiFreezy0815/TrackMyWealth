package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** US-11-06: a portfolio's one-month change supplied by the server in its own currency. */
public record PortfolioPeriodChange(
    String period, @Nullable BigDecimal amount, @Nullable BigDecimal percent) {}
