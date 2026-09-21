package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SettlementMatch;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SettlementMatchRepository extends JpaRepository<SettlementMatch, UUID> {

  // Same SELECT ... FOR UPDATE pattern as AccountRepository.findByIdForUpdate: two members
  // deciding the same match at once must serialise, not both read PROPOSED and both apply.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM SettlementMatch m WHERE m.id = :id")
  Optional<SettlementMatch> findByIdForUpdate(@Param("id") UUID id);

  // Every match ever made for a card, in any status: detection needs the rejected ones too, since a
  // rejected pair or payment must never be proposed again.
  List<SettlementMatch> findByCardAccountId(UUID cardAccountId);

  // The caller bounds the page (SettlementMatchService fixes it); RLS confines the rows to the
  // caller's workspace.
  List<SettlementMatch> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

  // Locks every PROPOSED match that references either leg, so confirming one can reject its
  // competitors atomically.
  @Query(
      "SELECT m FROM SettlementMatch m WHERE m.status = 'PROPOSED' AND m.id <> :excludeId"
          + " AND (m.paymentTransaction.id = :paymentId"
          + " OR (:cardTransactionId IS NOT NULL AND m.cardTransaction.id = :cardTransactionId))")
  List<SettlementMatch> findCompetingProposals(
      @Param("excludeId") UUID excludeId,
      @Param("paymentId") UUID paymentId,
      @Param("cardTransactionId") UUID cardTransactionId);
}
