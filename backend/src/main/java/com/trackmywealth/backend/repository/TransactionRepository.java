package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

  List<Transaction> findByAccountIdOrderByBookingDateDescCreatedAtDesc(UUID accountId);

  /**
   * The signed sum of every ledger row on {@code accountId} booked on or before {@code asOf} -
   * {@link Optional#empty()} when there is no such row at all, so a caller can still tell "no
   * history recorded" from "rows that net to exactly zero" and decide how to treat it.
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
