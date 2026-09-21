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
   * The signed sum of every ledger row on {@code accountId} booked on or before {@code asOf} -
   * {@link Optional#empty()} when there is no such row at all, so a caller can still tell "no
   * history recorded" from "rows that net to exactly zero" and decide how to treat it.
   *
   * <p>Sums {@code amount} without looking at each row's {@code currency}, which is sound only
   * while every row on a card is in the account's own currency (enforced on write until US-09-04
   * adds foreign-currency rows - see {@code AccountValuationService}).
   *
   * <p>Deliberately <b>includes voided rows</b>. A void leaves the original in the ledger and adds
   * a reversing row of the opposite sign (FR-LIF-002: "both records remain in the ledger"), so the
   * pair nets to zero only if both are summed - filtering on {@code voided_at IS NULL} would drop
   * the original and count the reversal on its own, misstating the balance by the full amount.
   */
  @Query(
      "select sum(t.amount) from Transaction t"
          + " where t.account.id = :accountId and t.bookingDate <= :asOf")
  Optional<BigDecimal> sumAmountByAccountIdAsOf(
      @Param("accountId") UUID accountId, @Param("asOf") LocalDate asOf);

  /**
   * Candidate settlement payments (US-09-02): the negative, non-voided rows of {@code types} on the
   * card's settlement-source account, in {@code currency}, that are not already half of a CONFIRMED
   * <em>pair</em>. A payment with only a CONFIRMED one-sided match stays a candidate on purpose, so
   * the card-side leg can complete it when it is recorded later (FR-CF-005).
   */
  @Query(
      "select t from Transaction t where t.account.id = :accountId and t.amount < 0"
          + " and t.voidedAt is null and t.currency = :currency and t.transactionType in :types"
          + " and not exists (select 1 from SettlementMatch m where m.status = 'CONFIRMED'"
          + " and m.cardTransaction is not null and m.paymentTransaction = t)"
          + " order by t.bookingDate, t.createdAt, t.id")
  List<Transaction> findSettlementPaymentCandidates(
      @Param("accountId") UUID accountId,
      @Param("currency") String currency,
      @Param("types") Collection<String> types);

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
   * original only if both are summed.
   */
  @Query(
      "select t.currency, sum(t.amount) from Transaction t where t.account.id in :accountIds"
          + " and t.transactionType in :types and t.internalTransfer = false"
          + " and t.bookingDate between :from and :to"
          + " and not exists (select 1 from SettlementMatch m where m.status = 'PROPOSED'"
          + " and m.paymentTransaction = t)"
          + " group by t.currency order by t.currency")
  List<Object[]> sumSpendingByCurrency(
      @Param("accountIds") Collection<UUID> accountIds,
      @Param("types") Collection<String> types,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * Signed sum per currency of payments that look like a card settlement but are not resolved: a
   * negative row typed {@code SETTLEMENT}, or one with a PROPOSED match, that is not yet flagged an
   * internal transfer (FR-CF-004/005 data-quality rule). Each element is {@code [currency, sum]}.
   */
  @Query(
      "select t.currency, sum(t.amount) from Transaction t where t.account.id in :accountIds"
          + " and t.internalTransfer = false and t.amount < 0"
          + " and t.bookingDate between :from and :to"
          + " and (t.transactionType = 'SETTLEMENT' or exists (select 1 from SettlementMatch m"
          + " where m.status = 'PROPOSED' and m.paymentTransaction = t))"
          + " group by t.currency order by t.currency")
  List<Object[]> sumUnresolvedSettlementsByCurrency(
      @Param("accountIds") Collection<UUID> accountIds,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);
}
