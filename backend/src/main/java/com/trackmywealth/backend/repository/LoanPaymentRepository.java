package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.LoanPayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

  /** Newest booking first, then newest entry. */
  List<LoanPayment> findByLoanAccountIdOrderByBookingDateDescCreatedAtDesc(UUID loanAccountId);

  /** US-10-02: the payment an idempotency key already recorded on this loan. */
  Optional<LoanPayment> findByLoanAccountIdAndExternalId(UUID loanAccountId, String externalId);
}
