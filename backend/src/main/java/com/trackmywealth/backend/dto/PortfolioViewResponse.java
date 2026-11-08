package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
 * @param institutionName the catalogue institution's name, or {@code null} for a portfolio not
 *     linked to the catalogue (#482)
 * @param logoUrl the portfolio's logo, as in the portfolio list
 * @param conversionRate one unit of {@code portfolioCurrency} in {@code workspaceCurrency}, for the
 *     header's FX hint (#482). It is the rate an account held in the portfolio's currency is
 *     converted with, so for such accounts it is the one behind {@code totalInWorkspaceCurrency};
 *     an account in a third currency is converted with its own rate (never chained through this
 *     one). {@code null} when the currencies are equal or no rate resolves
 * @param conversionRateDate the date the rate was requested for ({@code asOf}, FR-CUR-011's
 *     valuation-date convention, as in {@link PortfolioSummaryResponse}); see {@code
 *     conversionRateCarriedForward} for an older stored rate. {@code null} with {@code
 *     conversionRate}
 * @param conversionRateCarriedForward {@code true} when the rate was carried forward from an
 *     earlier date (FR-CUR-012/PR-011); always {@code false} without {@code conversionRate}
 * @param conversionRateStale {@code true} when that earlier rate is older than {@code
 *     app.fx.stale-after} (NFR-CON-003/PR-011); the hint must be marked as such. Implies {@code
 *     conversionRateCarriedForward}
 * @param lastImport the newest still-committed batch among the portfolio's active accounts; {@code
 *     null} without one
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
    List<Section> sections,
    @Nullable String institutionName,
    @Nullable String logoUrl,
    @Nullable BigDecimal conversionRate,
    @Nullable LocalDate conversionRateDate,
    boolean conversionRateCarriedForward,
    boolean conversionRateStale,
    @Nullable LastImport lastImport) {

  public PortfolioViewResponse {
    // Defensive/immutable copies (SpotBugs EI_EXPOSE_REP).
    warnings = List.copyOf(warnings);
    sections = List.copyOf(sections);
  }

  /**
   * The newest committed import for an active account in the portfolio; rolled-back batches are
   * excluded.
   *
   * @param uploadedAt when the file was uploaded
   * @param committedAt when it was imported (committed): the "last import" date the footer shows
   *     and the order the newest batch is chosen by
   * @param fileFormat the public import vocabulary, as in {@link TransactionImportBatchResponse}:
   *     both PDF reader kinds are {@code PDF}, a template-less statement document {@code CAMT}
   * @param transactionCount the rows actually imported, not duplicates or rejected rows
   */
  public record LastImport(
      OffsetDateTime uploadedAt,
      OffsetDateTime committedAt,
      @Schema(
              allowableValues = {
                TransactionImportBatchResponse.FORMAT_CSV,
                TransactionImportBatchResponse.FORMAT_SPREADSHEET,
                TransactionImportBatchResponse.FORMAT_PDF,
                TransactionImportBatchResponse.FORMAT_CAMT
              })
          String fileFormat,
      int transactionCount) {}

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
   * @param valueSourceDate the date of the snapshot, valuation or loan statement the value rests
   *     on, {@code null} when it is derived from the ledger alone or the loan terms ({@link
   *     AccountValuation})
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
   * @param maskedIdentifier the stored server-masked IBAN or card number; never a raw identifier
   * @param reconciliationStatus the account-level status from {@link ReconciliationStatusValues}
   * @param statementDate the card's most recently closed statement date, if configured
   */
  public record AccountRow(
      UUID accountId,
      String name,
      String accountType,
      String nature,
      String currency,
      @Nullable BigDecimal balance,
      @Nullable BigDecimal valueInPortfolioCurrency,
      boolean valueKnown,
      ValueStatus valueStatus,
      @Nullable LocalDate valueSourceDate,
      List<String> warnings,
      @Nullable Integer positionsCount,
      @Nullable LocalDate positionsAsOf,
      @Nullable BigDecimal totalReturn,
      @Nullable LocalDate dueDate,
      @Nullable UUID linkedPropertyAccountId,
      @Nullable String maskedIdentifier,
      String reconciliationStatus,
      @Nullable LocalDate statementDate) {

    public AccountRow {
      warnings = List.copyOf(warnings);
    }
  }
}
