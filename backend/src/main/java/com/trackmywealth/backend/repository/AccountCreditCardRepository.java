package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AccountCreditCard;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountCreditCardRepository extends JpaRepository<AccountCreditCard, UUID> {

  // US-09-02: every card that expects its statement to be paid from this account - the cards whose
  // settlement matching a new payment on it can change.
  List<AccountCreditCard> findBySettlementSourceAccountId(UUID settlementSourceAccountId);
}
