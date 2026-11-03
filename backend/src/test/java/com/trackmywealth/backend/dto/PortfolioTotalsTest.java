package com.trackmywealth.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * US-04-05/06: the totals the Portfolio View and the portfolio list share - signed sums of the
 * known values in two currencies, and a completeness and status that cover both.
 */
class PortfolioTotalsTest {

  @Test
  void noAccountsAreAZeroCompleteCurrentTotal() {
    PortfolioTotals totals = PortfolioTotals.of(List.of(), List.of());

    assertThat(totals.total()).isEqualByComparingTo("0");
    assertThat(totals.totalInWorkspaceCurrency()).isEqualByComparingTo("0");
    assertThat(totals.complete()).isTrue();
    assertThat(totals.valueStatus()).isEqualTo(ValueStatus.CURRENT);
  }

  @Test
  void aLiabilitySubtractsSoATotalMayBeNegative() {
    List<AccountValuation> values =
        List.of(
            known("ASSET", "500000", ValueBasisValues.LEDGER),
            known("LIABILITY", "600000", ValueBasisValues.LEDGER));

    PortfolioTotals totals = PortfolioTotals.of(values);

    assertThat(totals.total()).isEqualByComparingTo("-100000");
    assertThat(totals.totalInWorkspaceCurrency()).isEqualByComparingTo("-100000");
    assertThat(totals.complete()).isTrue();
  }

  // #381 review: a portfolio in the workspace's currency sums once, to the same totals as two
  // currencies holding the same values.
  @Test
  void oneCurrencyGivesTheSameTotalsAsTwoWithTheSameValues() {
    List<AccountValuation> values =
        List.of(
            known("ASSET", "1000", ValueBasisValues.LEDGER),
            known("LIABILITY", "200", ValueBasisValues.LEDGER_EMPTY),
            unknown());

    assertThat(PortfolioTotals.of(values)).isEqualTo(PortfolioTotals.of(values, values));
  }

  @Test
  void eachTotalIsSummedInItsOwnCurrency() {
    PortfolioTotals totals =
        PortfolioTotals.of(
            List.of(known("ASSET", "1180", ValueBasisValues.LEDGER)),
            List.of(known("ASSET", "1120", ValueBasisValues.LEDGER)));

    assertThat(totals.total()).isEqualByComparingTo("1180");
    assertThat(totals.totalInWorkspaceCurrency()).isEqualByComparingTo("1120");
  }

  @Test
  void aValueUnknownOnlyInTheWorkspaceCurrencyIsLeftOutThereAndMarksBothIncomplete() {
    PortfolioTotals totals =
        PortfolioTotals.of(
            List.of(
                known("ASSET", "1000", ValueBasisValues.LEDGER),
                known("ASSET", "50", ValueBasisValues.LEDGER)),
            List.of(unknown(), known("ASSET", "40", ValueBasisValues.LEDGER)));

    assertThat(totals.total()).isEqualByComparingTo("1050");
    // Left out, not counted as zero - and not hidden: the status and `complete` say so.
    assertThat(totals.totalInWorkspaceCurrency()).isEqualByComparingTo("40");
    assertThat(totals.complete()).isFalse();
    assertThat(totals.valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
  }

  @Test
  void theStatusIsTheWorstOfAllAccounts() {
    List<AccountValuation> values =
        List.of(
            known("ASSET", "1000", ValueBasisValues.LEDGER),
            known("LIABILITY", "400", ValueBasisValues.LEDGER_EMPTY));

    PortfolioTotals totals = PortfolioTotals.of(values, values);

    assertThat(totals.valueStatus()).isEqualTo(ValueStatus.ESTIMATED);
    assertThat(totals.complete()).isTrue();
    assertThat(totals.total()).isEqualByComparingTo("600");
  }

  private static AccountValuation known(String nature, String value, String basis) {
    return new AccountValuation(
        UUID.randomUUID(),
        "Account",
        nature,
        "CHF",
        "CHF",
        new BigDecimal(value),
        null,
        null,
        false,
        false,
        true,
        basis,
        null,
        false,
        List.of());
  }

  private static AccountValuation unknown() {
    return new AccountValuation(
        UUID.randomUUID(),
        "Account",
        "ASSET",
        "EUR",
        "CHF",
        null,
        null,
        null,
        false,
        false,
        false,
        null,
        null,
        false,
        List.of());
  }
}
