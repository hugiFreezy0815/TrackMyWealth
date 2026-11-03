package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/portfolios/{portfolioId}/view} (US-04-06, FR-INS-013): one
 * portfolio tab, broken down into a section per kind of account.
 *
 * <p><b>Every amount here is signed</b>: assets positive, liabilities negative, so a client adds
 * them up as they are and a portfolio holding a property with a larger mortgage shows a negative
 * total (FR-INS-SUM-001/003). This differs from {@link PortfolioSummaryResponse}, which keeps the
 * {@link AccountValuation} convention of a magnitude plus a {@code nature}. The figures come from
 * the same valuation as that summary and as net worth; nothing is calculated a second way.
 *
 * <p>Like the summary, a total sums only the accounts whose value is known; {@code complete} is
 * {@code false} and {@code valueStatus} {@link ValueStatus#UNKNOWN} when one is left out.
 *
 * @param portfolioCurrency the currency of {@code total} and of each section's {@code subtotal}
 * @param workspaceCurrency the workspace's currency (US-06-05), the one net worth is shown in
 * @param asOf the business date every value is taken on
 * @param valueStatus the worst {@link ValueStatus} of the portfolio's accounts, in either total's
 *     currency - like {@code complete}, it covers both totals
 * @param warnings every data-quality warning one of the accounts carries in either total's
 *     currency, each once and sorted
 * @param sections only the sections holding at least one active account, in {@link
 *     PortfolioSection} order
 */
public record PortfolioViewResponse(
    UUID portfolioId,
    String name,
    PortfolioType portfolioType,
    boolean portfolioTypeOverridden,
    boolean personalAssetsDefault,
    String portfolioCurrency,
    String workspaceCurrency,
    LocalDate asOf,
    BigDecimal total,
    BigDecimal totalInWorkspaceCurrency,
    boolean complete,
    ValueStatus valueStatus,
    List<String> warnings,
    List<Section> sections) {

  public PortfolioViewResponse {
    // Defensive/immutable copies (SpotBugs EI_EXPOSE_REP).
    warnings = List.copyOf(warnings);
    sections = List.copyOf(sections);
  }

  /**
   * One kind of account within the portfolio.
   *
   * @param subtotal the signed sum of the section's known values, in the portfolio's currency; the
   *     subtotals add up to the portfolio's {@code total}
   * @param valueStatus the worst {@link ValueStatus} of the section's accounts, in either total's
   *     currency - like {@code complete}, it covers both subtotals
   * @param accounts the section's active accounts, oldest first
   */
  public record Section(
      PortfolioSection section,
      BigDecimal subtotal,
      BigDecimal subtotalInWorkspaceCurrency,
      boolean complete,
      ValueStatus valueStatus,
      List<AccountRow> accounts) {

    public Section {
      accounts = List.copyOf(accounts);
    }
  }

  /**
   * One account of a section. The last five fields apply to some kinds of account only and are
   * {@code null} for every other.
   *
   * @param currency the account's own currency: its native currency, or a card's billing currency
   * @param balance the account's signed value in {@code currency}; {@code null} only when it has no
   *     value even in its own currency. It may be set while {@code valueKnown} is {@code false}: an
   *     account whose value has no FX rate into the portfolio's currency still shows its own
   *     balance
   * @param valueInPortfolioCurrency the same value in the portfolio's currency; {@code null} when
   *     {@code valueKnown} is {@code false}
   * @param valueKnown whether the account has a value in the portfolio's currency, i.e. counts
   *     towards the section's {@code subtotal}; {@code valueStatus} describes that same value
   * @param valueSourceDate the date of the snapshot or valuation the value rests on, {@code null}
   *     when it is derived from the ledger or the loan terms ({@link AccountValuation})
   * @param positionsCount for an account holding positions: the number of positions in its newest
   *     holdings snapshot; {@code null} when it has none yet
   * @param positionsAsOf the date of that snapshot, so a client can show how old the count is: a
   *     later snapshot without holdings (a balance-only statement) does not replace it
   * @param totalReturn for an account holding positions: its return. Always {@code null} until
   *     performance is calculated (EPIC 15, PO decision on #390); part of the contract already so a
   *     client can show it once it is
   * @param dueDate for a credit card: the due date of its current statement; {@code null} while its
   *     statement cycle is not configured
   * @param linkedPropertyAccountId for a mortgage: the property account it finances, if linked
   */
  public record AccountRow(
      UUID accountId,
      String name,
      String accountType,
      String nature,
      String currency,
      BigDecimal balance,
      BigDecimal valueInPortfolioCurrency,
      boolean valueKnown,
      ValueStatus valueStatus,
      LocalDate valueSourceDate,
      List<String> warnings,
      Integer positionsCount,
      LocalDate positionsAsOf,
      BigDecimal totalReturn,
      LocalDate dueDate,
      UUID linkedPropertyAccountId) {

    public AccountRow {
      warnings = List.copyOf(warnings);
    }
  }
}
