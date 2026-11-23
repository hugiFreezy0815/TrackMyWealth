package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.LoanRepaymentYearSum;
import com.trackmywealth.backend.entity.LoanPayment;
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
public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

  // A payment is in effect while one of its rows is: removing a payment soft-deletes (or, for an
  // imported row, voids) all of its rows together, and a restore brings them back. The record
  // itself stays (V87). deletedAt is spelled out although Transaction's @SQLRestriction hides
  // deleted rows already, so the rule does not rest on how a subquery applies it.
  String LOAN_ACCOUNT_ID = "loanAccountId";
  String PAYING_ACCOUNT_IDS = "payingAccountIds";

  String IN_EFFECT =
      " exists (select 1 from Transaction t"
          + " where t.id in (p.principalTransactionId, p.interestTransactionId)"
          + " and t.deletedAt is null and t.voidedAt is null)";

  /** The loan's payments still in effect, newest booking first, then newest entry. */
  @Query(
      "select p from LoanPayment p where p.loanAccount.id = :loanAccountId and"
          + IN_EFFECT
          + " order by p.bookingDate desc, p.createdAt desc")
  List<LoanPayment> findInEffectByLoanAccountId(@Param(LOAN_ACCOUNT_ID) UUID loanAccountId);

  /** Paged payment history, with the caller-visible paying accounts filtered before counting. */
  @Query(
      "select p from LoanPayment p where p.loanAccount.id = :loanAccountId"
          + " and p.payingAccount.id in :payingAccountIds and"
          + IN_EFFECT)
  Page<LoanPayment> findVisibleInEffect(
      @Param(LOAN_ACCOUNT_ID) UUID loanAccountId,
      @Param(PAYING_ACCOUNT_IDS) Collection<UUID> payingAccountIds,
      Pageable pageable);

  /**
   * #693: the loan's payments still in effect from the given paying accounts, summed per calendar
   * year of their booking date, newest year first - the same payments {@link #findVisibleInEffect}
   * lists.
   */
  @Query(
      "select new com.trackmywealth.backend.dto.LoanRepaymentYearSum("
          + "extract(year from p.bookingDate), sum(p.totalAmount), sum(p.principalAmount),"
          + " sum(p.interestAmount), count(p),"
          + " sum(case when p.splitEstimated = true then 1 else 0 end))"
          + " from LoanPayment p where p.loanAccount.id = :loanAccountId"
          + " and p.payingAccount.id in :payingAccountIds and"
          + IN_EFFECT
          + " group by extract(year from p.bookingDate)"
          + " order by extract(year from p.bookingDate) desc")
  List<LoanRepaymentYearSum> sumVisibleInEffectByYear(
      @Param(LOAN_ACCOUNT_ID) UUID loanAccountId,
      @Param(PAYING_ACCOUNT_IDS) Collection<UUID> payingAccountIds);

  /**
   * #337: the loan's payments still in effect booked in [{@code from}, {@code to}], earliest first,
   * which an imported loan statement may be about to book a second time.
   */
  @Query(
      "select p from LoanPayment p where p.loanAccount.id = :loanAccountId"
          + " and p.bookingDate between :from and :to and"
          + IN_EFFECT
          + " order by p.bookingDate, p.createdAt")
  List<LoanPayment> findInEffectByLoanAccountIdBookedBetween(
      @Param(LOAN_ACCOUNT_ID) UUID loanAccountId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /** Whether the payment's rows are still in effect, i.e. it was not removed. */
  @Query(
      "select case when count(p) > 0 then true else false end from LoanPayment p"
          + " where p.id = :id and"
          + IN_EFFECT)
  boolean isInEffect(@Param("id") UUID id);

  /** US-10-02: the payment an idempotency key already recorded on this loan, removed or not. */
  Optional<LoanPayment> findByLoanAccountIdAndExternalId(UUID loanAccountId, String externalId);
}
