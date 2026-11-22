package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * US-11-06: a portfolio's one-month change supplied by the server in its own currency, from {@code
 * from} to today. {@code percent} is the wealth change (#477). {@code returnPercent} (#645) is the
 * portfolio's period return, as its history response's {@code change.returnPercent} (#607): null
 * when unknown, measured from {@code returnFrom}, the period start or the portfolio's earliest
 * tracked day when that is later; {@code returnFrom} is null when nothing in it is tracked.
 */
public record PortfolioPeriodChange(
    String period,
    LocalDate from,
    @Nullable BigDecimal amount,
    @Nullable BigDecimal percent,
    @Nullable BigDecimal returnPercent,
    @Nullable LocalDate returnFrom) {}
