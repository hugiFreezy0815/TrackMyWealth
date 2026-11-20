package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * #607: the dated value sources that can start an account's tracked history - its earliest snapshot
 * with a balance (the opening balance included), its opening balance, its earliest holdings
 * statement and its earliest manual valuation - and whether a loan or mortgage has an original
 * principal to be valued from. Which of them applies is decided from the capability flags, as for
 * the value itself ({@code AccountValuationService}).
 */
public record TrackingStartSources(
    UUID accountId,
    @Nullable LocalDate firstBalance,
    @Nullable LocalDate openingBalance,
    @Nullable LocalDate firstHoldings,
    @Nullable LocalDate firstValuation,
    boolean hasOriginalPrincipal) {}
