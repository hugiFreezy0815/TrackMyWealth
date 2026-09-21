package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SettlementMatch;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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

  // Same SELECT ... FOR UPDATE pattern as AccountRepository.findByIdForUpdate: the row a decision
  // reads is the row it then changes.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM SettlementMatch m WHERE m.id = :id")
  Optional<SettlementMatch> findByIdForUpdate(@Param("id") UUID id);

  // A scalar, not the entity: a decision must first learn WHICH card to serialise on, without
  // loading (and so caching, possibly stale) the match before that lock is held.
  @Query("SELECT m.cardAccount.id FROM SettlementMatch m WHERE m.id = :id")
  Optional<UUID> findCardAccountIdById(@Param("id") UUID id);

  // Every match ever made for a card, in any status: detection needs the rejected ones too, since a
  // rejected pair or payment must never be proposed again. The associations a response or the
  // detection reads are fetched in the same query, not one lazy load each. Newest first, with id as
  // the tie-breaker: created_at defaults to now(), the transaction's start, so every match one run
  // creates carries the same timestamp.
  @Query(
      "SELECT m FROM SettlementMatch m JOIN FETCH m.cardAccount JOIN FETCH m.paymentTransaction p"
          + " JOIN FETCH p.account LEFT JOIN FETCH m.cardTransaction"
          + " WHERE m.cardAccount.id = :cardAccountId ORDER BY m.createdAt DESC, m.id DESC")
  List<SettlementMatch> findByCardAccountId(@Param("cardAccountId") UUID cardAccountId);

  // The work queue: matches of one status whose card AND payment account are both in accountIds -
  // the accounts the caller may EDIT. Filtering here, before the page is cut, is what keeps another
  // member's matches from crowding the caller's own out of the page. RLS confines the rows to the
  // caller's workspace. accountIds must not be empty.
  @Query(
      "SELECT m FROM SettlementMatch m JOIN FETCH m.cardAccount JOIN FETCH m.paymentTransaction p"
          + " JOIN FETCH p.account LEFT JOIN FETCH m.cardTransaction"
          + " WHERE m.status = :status AND m.cardAccount.id IN :accountIds"
          + " AND p.account.id IN :accountIds ORDER BY m.createdAt DESC, m.id DESC")
  List<SettlementMatch> findActionable(
      @Param("status") String status,
      @Param("accountIds") Collection<UUID> accountIds,
      Pageable pageable);

  // The other PROPOSED matches that reference either leg, so confirming one can reject its
  // competitors. Not row-locked here: every decision on a card first takes that card's own row lock
  // (SettlementDetectionService#lockCard), which is what serialises two members confirming
  // competing proposals - all competitors share a payment or credit, so they share a card.
  @Query(
      "SELECT m FROM SettlementMatch m WHERE m.status = 'PROPOSED' AND m.id <> :excludeId"
          + " AND (m.paymentTransaction.id = :paymentId"
          + " OR (:cardTransactionId IS NOT NULL AND m.cardTransaction.id = :cardTransactionId))")
  List<SettlementMatch> findCompetingProposals(
      @Param("excludeId") UUID excludeId,
      @Param("paymentId") UUID paymentId,
      @Param("cardTransactionId") UUID cardTransactionId);
}
