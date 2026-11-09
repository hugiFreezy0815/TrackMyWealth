package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code GET /api/v1/accounts} (US-05-06, #380): an account of the workspace with its
 * current value.
 *
 * <p><b>Every amount here is signed</b>, as in {@link PortfolioViewResponse}: assets positive,
 * liabilities negative ({@link SignedAmounts}), so a client adds them up as they are. The figures
 * come from the same valuation as {@code /api/v1/net-worth} and the Portfolio View; {@code
 * balanceInWorkspaceCurrency} equals the account's net worth contribution (with the sign of its
 * {@code nature}) for the same date.
 *
 * @param version the account's version, for an {@code If-Match} on a later change
 * @param currency the account's own currency: its native currency, or a card's billing currency
 * @param archived whether the account is archived: such an account is listed only with {@code
 *     archived=true} and counts towards no total
 * @param hasTransactions whether the account keeps a transaction ledger (FR-ACC-010): only such an
 *     account takes a statement import, so a client lists only these as import targets
 * @param balance the account's signed value in {@code currency}; {@code null} only when it has no
 *     value even in its own currency. It may be set while {@code valueKnown} is {@code false}: an
 *     account whose value has no FX rate into the workspace's currency still shows its own balance
 * @param workspaceCurrency the workspace's currency (US-06-05), the one net worth is shown in
 * @param balanceInWorkspaceCurrency the same value in {@code workspaceCurrency}; {@code null} when
 *     {@code valueKnown} is {@code false}
 * @param valueKnown whether the account has a value in the workspace's currency, i.e. counts
 *     towards net worth; {@code valueStatus} describes that same value
 * @param valueStatus how far that value can be trusted ({@link ValueStatus}): {@code UNKNOWN}
 *     without one, {@code STALE} when its snapshot or rate is out of date
 * @param asOf the business date the value is taken on
 * @param valueSourceDate the date of the snapshot, valuation or loan statement {@code balance}
 *     rests on, so a client can show how old it is - set whenever {@code balance} is, even while
 *     {@code valueKnown} is {@code false} for want of an FX rate; {@code null} when the value is
 *     derived from the ledger or the loan terms
 * @param warnings the account's data-quality warnings ({@link DataQualityWarningValues}), sorted
 */
public record AccountListItemResponse(
    UUID id,
    int version,
    String name,
    String accountType,
    String nature,
    UUID portfolioId,
    String portfolioName,
    String currency,
    boolean archived,
    boolean hasTransactions,
    @Nullable BigDecimal balance,
    String workspaceCurrency,
    @Nullable BigDecimal balanceInWorkspaceCurrency,
    boolean valueKnown,
    ValueStatus valueStatus,
    LocalDate asOf,
    @Nullable LocalDate valueSourceDate,
    List<String> warnings) {

  public AccountListItemResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP).
    warnings = List.copyOf(warnings);
  }
}
