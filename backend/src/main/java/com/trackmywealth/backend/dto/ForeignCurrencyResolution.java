package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * The resolved (rate, estimated) pair for a foreign-currency card purchase - the intermediate
 * {@code TransactionService} resolves before saving the row (US-09-04/PR-011). Internal to
 * recording: not part of any API response. It lives here rather than as a nested type of the
 * service because {@code ArchitectureTest} requires every class in {@code ..service..} to be a
 * {@code @Service} - same reasoning as {@link NativeAccountValue}.
 */
public record ForeignCurrencyResolution(BigDecimal rate, boolean estimated) {}
