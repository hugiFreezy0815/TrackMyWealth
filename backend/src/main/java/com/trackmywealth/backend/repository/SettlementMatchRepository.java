package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.SettlementMatch;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
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

  // A card leg that exists but is soft-deleted (US-07-02) joins as null: such a match is left out
  // rather than read as one-sided, or loaded with a leg Hibernate cannot find.
  String HIDDEN_LEG_EXCLUDED = " AND (m.cardTransactionId IS NULL OR c.id IS NOT NULL)";

  // Same SELECT ... FOR UPDATE pattern as AccountRepository.findByIdForUpdate: the row a decision
  // reads is the row it then changes.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM SettlementMatch m WHERE m.id = :id")
  Optional<SettlementMatch> findByIdForUpdate(@Param("id") UUID id);

  /**
   * US-10-01: every transfer match, any status, one of whose legs is among {@code ids} - detection
   * needs the rejected ones too, since a rejected pair is never proposed again. {@code ids} must
   * not be empty.
   */
  @Query(
      "SELECT m FROM SettlementMatch m WHERE m.matchKind = 'TRANSFER'"
          + " AND (m.paymentTransaction.id IN :ids OR m.cardTransaction.id IN :ids)")
  List<SettlementMatch> findTransferMatchesTouching(@Param("ids") Collection<UUID> ids);

  /** US-07-02: every match, any status, one of whose legs is the given transaction. */
  @Query(
      "SELECT m FROM SettlementMatch m"
          + " WHERE m.paymentTransaction.id = :id OR m.cardTransaction.id = :id")
  List<SettlementMatch> findByTransactionId(@Param("id") UUID id);

  // A scalar, not the entity: a decision must first learn WHICH card to serialise on, without
  // loading (and so caching, possibly stale) the match before that lock is held. Empty while a leg
  // is soft-deleted (US-07-02): such a match is not actionable, and loading it would fail.
  @Query(
      "SELECT m.cardAccount.id FROM SettlementMatch m JOIN m.paymentTransaction p"
          + " LEFT JOIN m.cardTransaction c WHERE m.id = :id"
          + HIDDEN_LEG_EXCLUDED)
  Optional<UUID> findCardAccountIdById(@Param("id") UUID id);

  // Every match ever made for a card, in any status: detection needs the rejected ones too, since a
  // rejected pair or payment must never be proposed again. The associations a response or the
  // detection reads are fetched in the same query, not one lazy load each. Newest first, with id as
  // the tie-breaker: created_at defaults to now(), the transaction's start, so every match one run
  // creates carries the same timestamp.
  //
  // US-07-02: a rejected match outlives a soft delete of either leg, so a restore does not forget
  // the member's decision. While a leg is deleted the match is left out (HIDDEN_LEG_EXCLUDED); the
  // inner join already drops a deleted payment.
  @Query(
      "SELECT m FROM SettlementMatch m JOIN FETCH m.cardAccount JOIN FETCH m.paymentTransaction p"
          + " JOIN FETCH p.account LEFT JOIN FETCH m.cardTransaction c"
          + " WHERE m.cardAccount.id = :cardAccountId AND m.matchKind = 'CARD_SETTLEMENT'"
          + HIDDEN_LEG_EXCLUDED
          + " ORDER BY m.createdAt DESC, m.id DESC")
  List<SettlementMatch> findByCardAccountId(@Param("cardAccountId") UUID cardAccountId);

  // US-09-03: whether a statement period is paid needs only the CONFIRMED matches whose payment
  // could plausibly belong to it - far cheaper than findByCardAccountId's whole history (any
  // status, all time) when a card has years of settlements behind it. Only the payment leg is
  // fetched, the one CardStatementService reads.
  @Query(
      "SELECT m FROM SettlementMatch m JOIN FETCH m.paymentTransaction p"
          + " WHERE m.cardAccount.id = :cardAccountId AND m.status = 'CONFIRMED'"
          + " AND m.matchKind = 'CARD_SETTLEMENT'"
          + " AND p.bookingDate BETWEEN :from AND :to")
  List<SettlementMatch> findConfirmedByCardAccountIdAndPaymentBookingDateBetween(
      @Param("cardAccountId") UUID cardAccountId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  // The work queue: matches of one status whose card AND payment account are both in accountIds -
  // the accounts the caller may EDIT. Filtering here, before the page is cut, is what keeps another
  // member's matches from crowding the caller's own out of the page. RLS confines the rows to the
  // caller's workspace. accountIds must not be empty.
  @Query(
      "SELECT m FROM SettlementMatch m JOIN FETCH m.cardAccount JOIN FETCH m.paymentTransaction p"
          + " JOIN FETCH p.account LEFT JOIN FETCH m.cardTransaction c"
          + " WHERE m.status = :status AND m.cardAccount.id IN :accountIds"
          + " AND p.account.id IN :accountIds"
          + HIDDEN_LEG_EXCLUDED
          + " ORDER BY m.createdAt DESC, m.id DESC")
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
