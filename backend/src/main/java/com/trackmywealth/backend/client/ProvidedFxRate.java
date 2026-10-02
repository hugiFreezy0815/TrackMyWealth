package com.trackmywealth.backend.client;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One published rate: an amount in {@code baseCurrency} times {@code rate} is the amount in {@code
 * quoteCurrency}, the same direction as {@code fx_rate}.
 */
public record ProvidedFxRate(
    String baseCurrency, String quoteCurrency, LocalDate rateDate, BigDecimal rate) {}
