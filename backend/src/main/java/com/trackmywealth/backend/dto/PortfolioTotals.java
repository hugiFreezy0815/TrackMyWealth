package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The totals of a group of accounts - a portfolio or one of its sections - in two currencies at
 * once: the portfolio's own and the workspace's (US-04-06, US-04-05). The one calculation the
 * Portfolio View and the portfolio list share, so a tab and its row in the list can never show
 * different money.
 *
 * <p>Each total is the signed sum ({@link SignedAmounts}) of the values known in its currency; an
 * unknown one is left out, not counted as zero (PR-011), and {@code complete} and {@code
 * valueStatus} say so. Both cover both totals: an account valued in the portfolio's currency but
 * without an FX rate into the workspace's is missing from the second total, and the status must not
 * hide that.
 *
 * @param total the signed sum in the portfolio's currency
 * @param totalInWorkspaceCurrency the signed sum in the workspace's currency, the one net worth is
 *     shown in
 * @param complete whether every account has a value in both currencies
 * @param valueStatus the worst {@link ValueStatus} of the accounts in either currency; {@link
 *     ValueStatus#CURRENT} for no accounts at all
 */
public record PortfolioTotals(
    BigDecimal total,
    BigDecimal totalInWorkspaceCurrency,
    boolean complete,
    ValueStatus valueStatus) {

  /**
   * The totals of the same accounts valued in the portfolio's currency ({@code inPortfolio}) and in
   * the workspace's ({@code inWorkspace}). When both currencies are the same, the caller may pass
   * the same list twice.
   */
  public static PortfolioTotals of(
      List<AccountValuation> inPortfolio, List<AccountValuation> inWorkspace) {
    List<ValueStatus> statuses = new ArrayList<>(inPortfolio.size() + inWorkspace.size());
    inPortfolio.forEach(valuation -> statuses.add(ValueStatus.of(valuation)));
    inWorkspace.forEach(valuation -> statuses.add(ValueStatus.of(valuation)));
    return new PortfolioTotals(
        signedSum(inPortfolio),
        signedSum(inWorkspace),
        allKnown(inPortfolio) && allKnown(inWorkspace),
        ValueStatus.worstOf(statuses));
  }

  private static BigDecimal signedSum(List<AccountValuation> valuations) {
    BigDecimal sum = BigDecimal.ZERO;
    for (AccountValuation valuation : valuations) {
      if (valuation.valueKnown()) {
        sum = sum.add(SignedAmounts.of(valuation));
      }
    }
    return sum;
  }

  private static boolean allKnown(List<AccountValuation> valuations) {
    return valuations.stream().allMatch(AccountValuation::valueKnown);
  }
}
