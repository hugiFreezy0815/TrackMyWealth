package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One step of {@code FxRateService}'s rate resolution (#223): a stored rate, the inverse of one, or
 * a chain of two, with the date of the oldest stored rate it was computed from, and whether it went
 * through another currency - a chain, or a cross rate the import derived (V54). Internal to that
 * service; it lives in {@code dto} only because ArchUnit's {@code services_are_named_consistently}
 * admits nothing but {@code @Service} classes in {@code ..service..} (same as {@link
 * FxRateLookupResult}).
 */
public record ResolvedFxRate(BigDecimal rate, LocalDate rateDate, boolean derived) {}
