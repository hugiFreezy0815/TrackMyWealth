package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * US-07-35 (#382): the filters of the workspace-wide transaction list ({@code GET
 * /api/v1/transactions}). Every filter is optional and they combine with AND; a repeatable one
 * ({@code accountIds}, {@code portfolioIds}, {@code categoryIds}) matches any of its values.
 *
 * <ul>
 *   <li>{@code accountIds}, {@code portfolioIds} - an id of another workspace, or none at all,
 *       matches nothing: the list is empty exactly as for an unknown id (US-28-03).
 *   <li>{@code bookingDateFrom}, {@code bookingDateTo} - inclusive.
 *   <li>{@code uncategorized} - only rows in UNCATEGORIZED that still need a category (not voided,
 *       not a reversal): the actionable inbox of FR-CAT-013, across accounts.
 *   <li>{@code q} - contained in the description or the counterparty name, ignoring case and
 *       accents; {@code %} and {@code _} match themselves. Leading and trailing white space is
 *       ignored, and blank counts as not sent. It never matches across the end of the description
 *       and the start of the counterparty; control characters are rejected.
 *   <li>{@code amountMin}, {@code amountMax} - inclusive, signed, in the account's currency (the
 *       value {@code sort=amount} orders by), whatever the booking's currency.
 *   <li>{@code currency} - the booking's own currency.
 * </ul>
 */
public record TransactionSearchFilter(
    List<UUID> accountIds,
    List<UUID> portfolioIds,
    List<UUID> categoryIds,
    LocalDate bookingDateFrom,
    LocalDate bookingDateTo,
    boolean uncategorized,
    String q,
    BigDecimal amountMin,
    BigDecimal amountMax,
    String currency) {

  /** The most values one repeatable filter takes, so a request cannot send an unbounded IN list. */
  public static final int MAX_VALUES_PER_FILTER = 50;

  /** The longest {@code q}. */
  public static final int MAX_QUERY_LENGTH = 100;

  public TransactionSearchFilter {
    accountIds = Collections.unmodifiableList(new ArrayList<>(accountIds));
    portfolioIds = Collections.unmodifiableList(new ArrayList<>(portfolioIds));
    categoryIds = Collections.unmodifiableList(new ArrayList<>(categoryIds));
    q = RequestStrings.blankToNull(q == null ? null : q.strip());
  }
}
