package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
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
}
