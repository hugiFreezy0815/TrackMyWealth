package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.LoanPayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

  // A payment is in effect while one of its rows is: removing a payment soft-deletes (or, for an
  // imported row, voids) all of its rows together, and a restore brings them back. The record
  // itself stays (V87). deletedAt is spelled out although Transaction's @SQLRestriction hides
  // deleted rows already, so the rule does not rest on how a subquery applies it.
  String IN_EFFECT =
      " exists (select 1 from Transaction t"
          + " where t.id in (p.principalTransactionId, p.interestTransactionId)"
          + " and t.deletedAt is null and t.voidedAt is null)";

  /** The loan's payments still in effect, newest booking first, then newest entry. */
  @Query(
      "select p from LoanPayment p where p.loanAccount.id = :loanAccountId and"
          + IN_EFFECT
          + " order by p.bookingDate desc, p.createdAt desc")
  List<LoanPayment> findInEffectByLoanAccountId(@Param("loanAccountId") UUID loanAccountId);

  /** Whether the payment's rows are still in effect, i.e. it was not removed. */
  @Query(
      "select case when count(p) > 0 then true else false end from LoanPayment p"
          + " where p.id = :id and"
          + IN_EFFECT)
  boolean isInEffect(@Param("id") UUID id);

  /** US-10-02: the payment an idempotency key already recorded on this loan, removed or not. */
  Optional<LoanPayment> findByLoanAccountIdAndExternalId(UUID loanAccountId, String externalId);
}
