package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.InstitutionNetTotals;
import com.trackmywealth.backend.dto.InstitutionSummaryContribution;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * US-04-03's DoD requires proving the mixed asset/liability negative-total scenario
 * (FR-INS-SUM-003/C6: "Given a Sparkasse container with a EUR 2,000 current account and a EUR
 * 300,000 mortgage... net value shows EUR -298,000"). No liability-nature account type has an
 * implemented value source yet (see {@code InstitutionSummaryResponse}'s Javadoc), so this proves
 * the sign-aggregation math itself directly against synthetic contributions, deliberately without
 * Spring/a database - {@link InstitutionSummaryService#sumByNature} takes exactly the same {@link
 * InstitutionSummaryContribution} shape {@link InstitutionSummaryService#getSummary} builds from
 * real (today, CUSTOM_ASSET-only) accounts, so this is the same code path, just fed synthetic input
 * instead of account-resolved input. The end-to-end scenario with a real liability account becomes
 * a follow-up once EPIC 07/12/14/15/16 give MORTGAGE/LOAN/etc. a resolvable value.
 */
class InstitutionSummaryServiceAggregationTest {

  @Test
  void mixedAssetAndLiabilityProducesTheCorrectNegativeNetValue() {
    InstitutionNetTotals totals =
        InstitutionSummaryService.sumByNature(
            List.of(
                new InstitutionSummaryContribution("ASSET", new BigDecimal("2000.00")),
                new InstitutionSummaryContribution("LIABILITY", new BigDecimal("300000.00"))));

    assertThat(totals.totalAssets()).isEqualByComparingTo("2000.00");
    assertThat(totals.totalLiabilities()).isEqualByComparingTo("300000.00");
    assertThat(totals.netValue()).isEqualByComparingTo("-298000.00");
  }

  @Test
  void allAssetsProduceAPositiveNetValue() {
    InstitutionNetTotals totals =
        InstitutionSummaryService.sumByNature(
            List.of(
                new InstitutionSummaryContribution("ASSET", new BigDecimal("1000")),
                new InstitutionSummaryContribution("ASSET", new BigDecimal("500"))));

    assertThat(totals.totalLiabilities()).isEqualByComparingTo("0");
    assertThat(totals.netValue()).isEqualByComparingTo("1500");
  }

  @Test
  void noContributionsProduceAValidZeroSummaryNotAnError() {
    // C7: an empty container must show a valid, empty summary.
    InstitutionNetTotals totals = InstitutionSummaryService.sumByNature(List.of());

    assertThat(totals.totalAssets()).isEqualByComparingTo("0");
    assertThat(totals.totalLiabilities()).isEqualByComparingTo("0");
    assertThat(totals.netValue()).isEqualByComparingTo("0");
  }
}
