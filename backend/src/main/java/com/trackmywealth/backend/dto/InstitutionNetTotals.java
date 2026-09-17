package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * Result of {@code InstitutionSummaryService.sumByNature} (US-04-03/FR-INS-SUM-001..003) - see
 * {@link InstitutionSummaryResponse}'s own {@code totalAssets}/{@code totalLiabilities}/{@code
 * netValue} Javadoc for the exact sign convention.
 */
public record InstitutionNetTotals(
    BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal netValue) {}
