package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * One resolved account's signed contribution to an institution summary
 * (US-04-03/FR-INS-SUM-001..003) - the minimal input {@code InstitutionSummaryService.sumByNature}
 * needs, deliberately independent of {@link InstitutionSummaryResponse.AccountLine}'s full shape so
 * the negative-net-value aggregation logic (FR-INS-SUM-003/C6) can be unit-tested directly against
 * synthetic contributions, without needing a real liability-nature account with a resolvable value
 * - which does not exist yet in this codebase (see {@link InstitutionSummaryResponse}'s own
 * Javadoc).
 *
 * @param nature {@code account.nature} - {@code "ASSET"} or {@code "LIABILITY"} (V4's generated
 *     column)
 * @param convertedValue the account's value in the summary's container currency, already
 *     FX-converted - a non-negative magnitude, same as {@link
 *     InstitutionSummaryResponse.AccountLine#convertedValue()}
 */
public record InstitutionSummaryContribution(String nature, BigDecimal convertedValue) {}
