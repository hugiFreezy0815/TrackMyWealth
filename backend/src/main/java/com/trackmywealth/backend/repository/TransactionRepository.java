package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

  // The caller supplies the sort (TransactionService fixes it): a Pageable's own sort is client
  // input and must not decide which columns the query orders by.
  Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);

  // The idempotency lookup, backed by uq_transaction_external_id (account_id, source, external_id).
  Optional<Transaction> findByAccountIdAndSourceAndExternalId(
      UUID accountId, String source, String externalId);

  /**
   * The signed sum of every ledger row on {@code accountId} booked on or before {@code asOf}, in
   * the account's own currency - {@link Optional#empty()} when there is no such row at all, so a
   * caller can still tell "no history recorded" from "rows that net to exactly zero" and decide how
   * to treat it.
   *
   * <p>US-09-04: sums {@code amount * fxRateToAccountCurrency} (falling back to a bare {@code
   * amount} via {@code coalesce} for the common same-currency row, where the rate is {@code null}),
   * not a bare {@code sum(amount)} - a foreign-currency row's {@code amount} is in its own original
   * currency, never the account's, so summing it unconverted would silently mix currencies.
   *
   * <p>Deliberately <b>includes voided rows</b>. A void leaves the original in the ledger and adds
   * a reversing row of the opposite sign (FR-LIF-002: "both records remain in the ledger"), so the
   * pair nets to zero only if both are summed - filtering on {@code voided_at IS NULL} would drop
   * the original and count the reversal on its own, misstating the balance by the full amount.
   */
  @Query(
      "select sum(t.amount * coalesce(t.fxRateToAccountCurrency, 1)) from Transaction t"
          + " where t.account.id = :accountId and t.bookingDate <= :asOf")
  Optional<BigDecimal> sumAmountByAccountIdAsOf(
      @Param("accountId") UUID accountId, @Param("asOf") LocalDate asOf);

  /**
   * Payments that can pair with a card credit of {@code -amount} (US-09-02): the non-voided rows of
   * {@code types} on the card's settlement-source account, in {@code currency}, of exactly {@code
   * amount}, booked in [{@code from}, {@code to}] and not already half of a CONFIRMED
   * <em>pair</em>. Bounded by amount and date, so its cost follows the handful of unmatched credits
   * and not the account's whole history. A payment with only a CONFIRMED one-sided match stays a
   * candidate on purpose, so the card-side leg can complete it when it is recorded later
   * (FR-CF-005).
   */
  @Query(
      "select t from Transaction t where t.account.id = :accountId and t.amount = :amount"
          + " and t.bookingDate between :from and :to"
          + " and t.voidedAt is null and t.currency = :currency and t.transactionType in :types"
          + " and not exists (select 1 from SettlementMatch m where m.status = 'CONFIRMED'"
          + " and m.cardTransaction is not null and m.paymentTransaction = t)"
          + " order by t.bookingDate, t.createdAt, t.id")
  List<Transaction> findPairablePayments(
      @Param("accountId") UUID accountId,
      @Param("currency") String currency,
      @Param("types") Collection<String> types,
      @Param("amount") BigDecimal amount,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * One-sided settlement candidates (US-09-02, FR-CF-005): payments booked on or after {@code
   * since} that equal exactly what the card owed on their booking date and have no match of any
   * status for this card yet. The balance comparison runs in the database - one query, not one per
   * payment - and a payment with any earlier match row (proposed, confirmed or rejected) is never
   * returned, so a decision already made is not revisited. {@code since} bounds the scan for a
   * write, which can only change the outcome for payments booked on or after it.
   *
   * <p>The card's balance is the signed sum of its rows to that date, converted into the card's own
   * currency (US-09-04: {@code amount * fxRateToAccountCurrency}, same as {@link
   * #sumAmountByAccountIdAsOf}) - a payment is a candidate only in the card's currency itself
   * ({@code currency = :currency} above, unchanged), so this comparison is already apples-to-apples
   * once the card side is converted.
   */
  @Query(
      "select t from Transaction t where t.account.id = :sourceAccountId and t.amount < 0"
          + " and t.voidedAt is null and t.currency = :currency and t.transactionType in :types"
          + " and t.bookingDate >= :since"
          + " and not exists (select 1 from SettlementMatch m"
          + " where m.cardAccount.id = :cardAccountId and m.paymentTransaction = t)"
          + " and (select sum(c.amount * coalesce(c.fxRateToAccountCurrency, 1)) from Transaction c"
          + " where c.account.id = :cardAccountId and c.bookingDate <= t.bookingDate) = t.amount"
          + " order by t.bookingDate, t.createdAt, t.id")
  List<Transaction> findPaymentsEqualToCardBalance(
      @Param("sourceAccountId") UUID sourceAccountId,
      @Param("cardAccountId") UUID cardAccountId,
      @Param("currency") String currency,
      @Param("types") Collection<String> types,
      @Param("since") LocalDate since);

  /**
   * Card-side credits (positive, non-voided {@code SETTLEMENT} rows) not yet in a CONFIRMED match.
   */
  @Query(
      "select t from Transaction t where t.account.id = :accountId and t.amount > 0"
          + " and t.voidedAt is null and t.currency = :currency"
          + " and t.transactionType = 'SETTLEMENT'"
          + " and not exists (select 1 from SettlementMatch m where m.status = 'CONFIRMED'"
          + " and m.cardTransaction = t)"
          + " order by t.bookingDate, t.createdAt, t.id")
  List<Transaction> findUnmatchedCardCredits(
      @Param("accountId") UUID accountId, @Param("currency") String currency);

  /**
   * Signed sum per currency of {@code types} rows booked in [{@code from}, {@code to}] on {@code
   * accountIds}, leaving out internal transfers and any payment awaiting a settlement decision
   * (US-09-02: that amount is reported separately as pending review, never as an expense). Each
   * element is {@code [currency, sum]}.
   *
   * <p>Like the balance query, this includes voided rows: a void's reversing row nets against the
   * original only if both are summed. For the same reason a voided payment is never "awaiting a
   * decision" here - a proposal on a row that has since been voided is moot - so it stays in the
   * sum, where its reversing row cancels it, instead of being excluded while the reversal is
   * counted.
   */
  @Query(
      "select t.currency, sum(t.amount) from Transaction t where t.account.id in :accountIds"
          + " and t.transactionType in :types and t.internalTransfer = false"
          + " and t.bookingDate between :from and :to"
          + " and (t.voidedAt is not null or not exists (select 1 from SettlementMatch m"
          + " where m.status = 'PROPOSED' and m.paymentTransaction = t))"
          + " group by t.currency order by t.currency")
  List<Object[]> sumSpendingByCurrency(
      @Param("accountIds") Collection<UUID> accountIds,
      @Param("types") Collection<String> types,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * Signed sum per currency of payments that look like a card settlement but are not resolved: a
   * negative row typed {@code SETTLEMENT}, or one with a PROPOSED match, that is not yet flagged an
   * internal transfer (FR-CF-004/005 data-quality rule). A voided row is no longer unresolved, so
   * it is left out (its reversing row is positive and outside the sum anyway). Each element is
   * {@code [currency, sum]}.
   */
  @Query(
      "select t.currency, sum(t.amount) from Transaction t where t.account.id in :accountIds"
          + " and t.internalTransfer = false and t.amount < 0 and t.voidedAt is null"
          + " and t.bookingDate between :from and :to"
          + " and (t.transactionType = 'SETTLEMENT' or exists (select 1 from SettlementMatch m"
          + " where m.status = 'PROPOSED' and m.paymentTransaction = t))"
          + " group by t.currency order by t.currency")
  List<Object[]> sumUnresolvedSettlementsByCurrency(
      @Param("accountIds") Collection<UUID> accountIds,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);
}
