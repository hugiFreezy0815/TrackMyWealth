package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.FuzzyCategoryCandidate;
import com.trackmywealth.backend.entity.Transaction;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

  String ACCOUNT_ID = "accountId";
  String ACCOUNT_IDS = "accountIds";
  String FROM = "from";
  String TYPES = "types";
  String BOOKED_BETWEEN = " and t.bookingDate between :from and :to";
  String IN_LEDGER_ORDER = " order by t.bookingDate, t.createdAt, t.id";
  String PER_DATE_CURRENCY =
      " group by t.bookingDate, t.currency order by t.bookingDate, t.currency";

  // US-07-02: neither a voided original nor its reversing row - the pair nets to zero in a
  // balance, but must not count as spending or pair with a settlement.
  String NOT_VOIDED_OR_REVERSAL = " and t.voidedAt is null and t.replacesTransactionId is null";

  // The caller supplies the sort (TransactionService fixes it): a Pageable's own sort is client
  // input and must not decide which columns the query orders by.
  Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);

  // FR-CAT-013: the account's rows in one category - with UNCATEGORIZED, the actionable list.
  /**
   * US-08-02: the next page of rows an automatic re-run of one workspace's categorization considers
   * - non-voided, of the given types, after {@code (afterCreatedAt, afterId)} in creation order -
   * locked FOR UPDATE until the re-run's transaction ends. The lock is what lets the re-run check
   * for a member's override and then write without the override slipping in between: an override
   * locks the same row first ({@link #findByIdForUpdate}). Keyset paging keeps each page's id list
   * (and the override check's IN list) small, whatever the workspace's size.
   */
  @Query(
      value =
          "SELECT * FROM transaction t WHERE t.workspace_id = :workspaceId"
              + " AND t.voided_at IS NULL AND t.deleted_at IS NULL"
              + " AND t.replaces_transaction_id IS NULL"
              + " AND t.transaction_type IN (:transactionTypes)"
              + " AND (t.created_at, t.id) > (:afterCreatedAt, :afterId)"
              + " ORDER BY t.created_at, t.id LIMIT :limit FOR UPDATE",
      nativeQuery = true)
  List<Transaction> lockRecategorizationPage(
      @Param("workspaceId") UUID workspaceId,
      @Param("transactionTypes") Collection<String> transactionTypes,
      @Param("afterCreatedAt") OffsetDateTime afterCreatedAt,
      @Param("afterId") UUID afterId,
      @Param("limit") int limit);

  /**
   * One row, locked FOR UPDATE until the caller's transaction ends - for a member's category
   * override or reset (US-08-02), so it and an automatic re-run of the same row run one after the
   * other.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM Transaction t WHERE t.id = :id")
  Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);

  // FR-CAT-013: the account's rows in one category - with UNCATEGORIZED, the actionable list. A
  // voided row or a reversal is not actionable (US-07-02).
  Page<Transaction> findByAccountIdAndCategoryIdAndVoidedAtIsNullAndReplacesTransactionIdIsNull(
      UUID accountId, UUID categoryId, Pageable pageable);

  /**
   * Sets pg_trgm's threshold for the {@code %} operator in {@link #findFuzzyCandidates} for the
   * current transaction only ({@code is_local = true}): a session-wide setting would outlive the
   * request on a pooled connection.
   */
  @Query(
      value = "SELECT set_config('pg_trgm.similarity_threshold', :threshold, true)",
      nativeQuery = true)
  String setSimilarityThresholdForTransaction(@Param("threshold") String threshold);

  /**
   * US-08-01 FALLBACK_MATCH: the workspace's earlier, non-voided rows whose merchant description is
   * trigram-similar (pg_trgm, V10's GIN index) to {@code merchant} at or above {@code threshold},
   * and whose current category was assigned by a rule or a user - never by a shipped code or an
   * earlier fuzzy guess, so a guess cannot teach the next one. Most similar first, then most
   * recent. The caller still checks the brand and whether the category is assignable, hence {@code
   * limit}. Call {@link #setSimilarityThresholdForTransaction} with the same threshold first, in
   * the same transaction: the {@code %} filter reads it.
   */
  @Query(
      value =
          "SELECT t.category_id AS categoryId, t.merchant_description AS merchantDescription,"
              + " CAST(similarity(t.merchant_description, :merchant) AS NUMERIC(4, 3))"
              + " AS similarity"
              + " FROM transaction t"
              + " WHERE t.workspace_id = :workspaceId AND t.id <> :excludedId"
              + " AND t.voided_at IS NULL AND t.deleted_at IS NULL AND t.category_id IS NOT NULL"
              + " AND t.merchant_description IS NOT NULL"
              // % is what the GIN trigram index serves (similarity() alone is a full scan of the
              // workspace's history); the explicit >= keeps the exact configured threshold.
              + " AND t.merchant_description % :merchant"
              + " AND similarity(t.merchant_description, :merchant) >= :threshold"
              // The latest log row counts only while it still describes the current category: a
              // reset to automatic that landed in UNCATEGORIZED writes no row of its own
              // (US-08-02).
              + " AND (SELECT CASE WHEN l.category_id = t.category_id THEN l.assigned_by END"
              + " FROM transaction_categorization_log l WHERE l.transaction_id = t.id"
              + " ORDER BY l.assigned_at DESC, l.id DESC LIMIT 1) IN ('RULE', 'USER')"
              + " ORDER BY similarity(t.merchant_description, :merchant) DESC, t.created_at DESC,"
              + " t.id DESC LIMIT :limit",
      nativeQuery = true)
  List<FuzzyCategoryCandidate> findFuzzyCandidates(
      @Param("workspaceId") UUID workspaceId,
      @Param("excludedId") UUID excludedId,
      @Param("merchant") String merchant,
      @Param("threshold") double threshold,
      @Param("limit") int limit);

  // The idempotency lookup, backed by uq_transaction_external_id (account_id, source, external_id).
  // Native, so it also finds a soft-deleted row: its key stays taken (V39 keeps the row).
  @Query(
      value =
          "SELECT * FROM transaction WHERE account_id = :accountId AND source = :source"
              + " AND external_id = :externalId",
      nativeQuery = true)
  Optional<Transaction> findByAccountIdAndSourceAndExternalId(
      @Param(ACCOUNT_ID) UUID accountId,
      @Param("source") String source,
      @Param("externalId") String externalId);

  /**
   * US-07-02: one row whatever its state, soft-deleted included (native, so the entity's
   * restriction does not apply), locked FOR UPDATE - for restoring it.
   */
  @Query(value = "SELECT * FROM transaction WHERE id = :id FOR UPDATE", nativeQuery = true)
  Optional<Transaction> findByIdIncludingDeletedForUpdate(@Param("id") UUID id);

  /**
   * US-07-02/07-07 (FR-LIF-006): the account's rows still restorable - soft-deleted or voided at or
   * after {@code since} - most recently removed first. A void already restored (a row restores it,
   * V50) or any row since corrected (a replacement corrects it, V49) can no longer be restored and
   * is left out.
   */
  @Query(
      value =
          "SELECT * FROM transaction t WHERE t.account_id = :accountId AND ("
              + " (t.deleted_at IS NOT NULL AND t.deleted_at >= :since)"
              + " OR (t.voided_at IS NOT NULL AND t.voided_at >= :since"
              + " AND NOT EXISTS (SELECT 1 FROM transaction r"
              + " WHERE r.restores_transaction_id = t.id)))"
              + " AND NOT EXISTS (SELECT 1 FROM transaction c"
              + " WHERE c.corrects_transaction_id = t.id)"
              + " ORDER BY COALESCE(t.deleted_at, t.voided_at) DESC, t.id DESC",
      nativeQuery = true)
  List<Transaction> findRestorableByAccountIdSince(
      @Param(ACCOUNT_ID) UUID accountId, @Param("since") OffsetDateTime since);

  /**
   * US-07-07: the rows voided together with {@code headId} - a card purchase's FEE row or a
   * two-sided transfer's incoming leg, both linked by {@code related_transaction_id} - locked so
   * the group is restored atomically.
   */
  @Query(
      value =
          "SELECT * FROM transaction WHERE related_transaction_id = :headId"
              + " AND voided_at IS NOT NULL FOR UPDATE",
      nativeQuery = true)
  List<Transaction> findVoidedDependantsForUpdate(@Param("headId") UUID headId);

  /**
   * US-07-07: the other account of a removed transfer leg on {@code accountId}, read without a
   * lock, so a restore can lock that account's cards before it locks any row.
   */
  @Query(
      value =
          "SELECT counterparty_account_id FROM transaction"
              + " WHERE id = :id AND account_id = :accountId"
              + " AND transaction_type IN (:transferTypes)",
      nativeQuery = true)
  Optional<UUID> findTransferCounterpartyAccountId(
      @Param("id") UUID id,
      @Param(ACCOUNT_ID) UUID accountId,
      @Param("transferTypes") Collection<String> transferTypes);

  /** US-07-07: the row that restored {@code voidedId}'s void, if any (at most one, V50). */
  Optional<Transaction> findByRestoresTransactionId(UUID voidedId);

  /** US-07-02: the soft-deleted FEE row of a purchase, restored together with it. */
  @Query(
      value =
          "SELECT * FROM transaction WHERE related_transaction_id = :purchaseId"
              + " AND deleted_at IS NOT NULL FOR UPDATE",
      nativeQuery = true)
  List<Transaction> findDeletedFeeRowsForUpdate(@Param("purchaseId") UUID purchaseId);

  // US-09-04: the FEE row (if any) a foreign-currency purchase's replay check compares feeAmount
  // against - at most one exists per purchase (TransactionService only ever creates one).
  Optional<Transaction> findByRelatedTransactionId(UUID relatedTransactionId);

  /**
   * The signed sum of every ledger row on {@code accountId} booked on or before {@code asOf}, in
   * the account's own currency - {@link Optional#empty()} when there is no such row at all, so a
   * caller can still tell "no history recorded" from "rows that net to exactly zero" and decide how
   * to treat it.
   *
   * <p>US-09-04: sums {@code amount * fxRateToAccountCurrency} (falling back to a bare {@code
   * amount} via {@code coalesce} for the common same-currency row, where the rate is {@code null}),
   * not a bare {@code sum(amount)} - a foreign-currency row's {@code amount} is in its own original
   * currency, never the account's, so summing it unconverted would silently mix currencies.
   *
   * <p>Leaves out <b>a voided original and its reversal together</b>, so history reads as restated.
   * Both stay in the ledger (FR-LIF-002) and net to zero, but on different dates: the original on
   * its booking date, the reversal on the void's. A correction replacement (US-07-06) or a restore
   * copy (US-07-07) re-enters the transaction on the original booking date, so summing the pair as
   * well would count it twice on every date between the booking and the void. Dropping only the
   * original would count the reversal alone; dropping both changes no current balance. The pair
   * counts as zero rather than being filtered out, so an account whose rows are all voided still
   * has history (a measured zero, not "no rows").
   */
  @Query(
      "select sum(case when t.voidedAt is null and t.replacesTransactionId is null"
          + " then t.amount * coalesce(t.fxRateToAccountCurrency, 1) else 0 end)"
          + " from Transaction t where t.account.id = :accountId and t.bookingDate <= :asOf")
  Optional<BigDecimal> sumAmountByAccountIdAsOf(
      @Param(ACCOUNT_ID) UUID accountId, @Param("asOf") LocalDate asOf);

  /**
   * US-10-01: the workspace's rows booked in [{@code from}, {@code to}] that could be one leg of an
   * own-account transfer still to be matched - a debit of {@code debitTypes} or a credit of {@code
   * creditTypes}, not voided or reversing, not on a card (cards settle through US-09-02's
   * matching), not already linked to a counterpart account, and not already in a confirmed match or
   * an open card settlement. A leg confirmed as a transfer to an untracked account is flagged but
   * has no counterpart account, so it stays a candidate for a later counterpart.
   */
  @Query(
      "select t from Transaction t join fetch t.account a where t.workspace.id = :workspaceId"
          + BOOKED_BETWEEN
          + NOT_VOIDED_OR_REVERSAL
          + " and a.hasStatementCycle = false and t.counterpartyAccountId is null"
          + " and ((t.amount < 0 and t.transactionType in :debitTypes)"
          + " or (t.amount > 0 and t.transactionType in :creditTypes))"
          + " and not exists (select 1 from SettlementMatch m where (m.status = 'CONFIRMED'"
          + " or (m.status = 'PROPOSED' and m.matchKind = 'CARD_SETTLEMENT'))"
          + " and (m.paymentTransaction = t or m.cardTransaction = t))"
          + IN_LEDGER_ORDER)
  List<Transaction> findTransferCandidates(
      @Param("workspaceId") UUID workspaceId,
      @Param(FROM) LocalDate from,
      @Param("to") LocalDate to,
      @Param("debitTypes") Collection<String> debitTypes,
      @Param("creditTypes") Collection<String> creditTypes);

  /**
   * Payments that can pair with a card credit of {@code -amount} (US-09-02): the non-voided rows of
   * {@code types} on the card's settlement-source account, in {@code currency}, of exactly {@code
   * amount}, booked in [{@code from}, {@code to}] and not already half of a CONFIRMED
   * <em>pair</em>. Bounded by amount and date, so its cost follows the handful of unmatched credits
   * and not the account's whole history. A payment with only a CONFIRMED one-sided match stays a
   * candidate on purpose, so the card-side leg can complete it when it is recorded later
   * (FR-CF-005).
   */
  @Query(
      "select t from Transaction t where t.account.id = :accountId and t.amount = :amount"
          + BOOKED_BETWEEN
          + NOT_VOIDED_OR_REVERSAL
          + " and t.currency = :currency and t.transactionType in :types"
          + " and not exists (select 1 from SettlementMatch m where m.status = 'CONFIRMED'"
          + " and m.cardTransaction is not null and m.paymentTransaction = t)"
          + IN_LEDGER_ORDER)
  List<Transaction> findPairablePayments(
      @Param(ACCOUNT_ID) UUID accountId,
      @Param("currency") String currency,
      @Param(TYPES) Collection<String> types,
      @Param("amount") BigDecimal amount,
      @Param(FROM) LocalDate from,
      @Param("to") LocalDate to);

  /**
   * One-sided settlement candidates (US-09-02, FR-CF-005): payments booked on or after {@code
   * since} that equal exactly what the card owed on their booking date and have no match of any
   * status for this card yet. The balance comparison runs in the database - one query, not one per
   * payment - and a payment with any earlier match row (proposed, confirmed or rejected) is never
   * returned, so a decision already made is not revisited. {@code since} bounds the scan for a
   * write, which can only change the outcome for payments booked on or after it.
   *
   * <p>The card's balance is the signed sum of its rows to that date, converted into the card's own
   * currency (US-09-04: {@code amount * fxRateToAccountCurrency}, same as {@link
   * #sumAmountByAccountIdAsOf}) - a payment is a candidate only in the card's currency itself
   * ({@code currency = :currency} above, unchanged), so this comparison is already apples-to-apples
   * once the card side is converted. A voided row and its reversal are left out, as there.
   */
  @Query(
      "select t from Transaction t where t.account.id = :sourceAccountId and t.amount < 0"
          + NOT_VOIDED_OR_REVERSAL
          + " and t.currency = :currency and t.transactionType in :types"
          + " and t.bookingDate >= :since"
          + " and not exists (select 1 from SettlementMatch m"
          + " where m.cardAccount.id = :cardAccountId and m.paymentTransaction = t)"
          + " and (select sum(c.amount * coalesce(c.fxRateToAccountCurrency, 1)) from Transaction c"
          + " where c.account.id = :cardAccountId and c.bookingDate <= t.bookingDate"
          + " and c.voidedAt is null and c.replacesTransactionId is null) = t.amount"
          + IN_LEDGER_ORDER)
  List<Transaction> findPaymentsEqualToCardBalance(
      @Param("sourceAccountId") UUID sourceAccountId,
      @Param("cardAccountId") UUID cardAccountId,
      @Param("currency") String currency,
      @Param(TYPES) Collection<String> types,
      @Param("since") LocalDate since);

  /**
   * Card-side credits (positive, non-voided {@code SETTLEMENT} rows) not yet in a CONFIRMED match.
   */
  @Query(
      "select t from Transaction t where t.account.id = :accountId and t.amount > 0"
          + NOT_VOIDED_OR_REVERSAL
          + " and t.currency = :currency and t.transactionType = 'SETTLEMENT'"
          + " and not exists (select 1 from SettlementMatch m where m.status = 'CONFIRMED'"
          + " and m.cardTransaction = t)"
          + IN_LEDGER_ORDER)
  List<Transaction> findUnmatchedCardCredits(
      @Param(ACCOUNT_ID) UUID accountId, @Param("currency") String currency);

  /**
   * Signed sum per booking date and currency of {@code types} rows booked in [{@code from}, {@code
   * to}] on {@code accountIds}, leaving out internal transfers and any payment awaiting a
   * settlement decision (US-09-02: that amount is reported separately as pending review, never as
   * an expense). Each element is {@code [bookingDate, currency, sum]}.
   *
   * <p>US-07-02: unlike the balance query, a voided original and its reversing row are both left
   * out (decision on issue #143): the reversal is dated to the void, so summing the pair would move
   * the expense from its own month into the void's month as a negative spend. Soft-deleted rows are
   * gone through the entity's restriction.
   *
   * <p>US-06-05: grouped by booking date as well as currency, so a converted cash flow uses each
   * day's own FX rate without loading individual ledger rows; this and the three queries below
   * serve both the original-currency and the converted view.
   */
  @Query(
      "select t.bookingDate, t.currency, sum(t.amount) from Transaction t"
          + " where t.account.id in :accountIds"
          + " and t.transactionType in :types and t.internalTransfer = false"
          + BOOKED_BETWEEN
          + NOT_VOIDED_OR_REVERSAL
          + " and not exists (select 1 from SettlementMatch m"
          + " where m.status = 'PROPOSED' and m.paymentTransaction = t)"
          + PER_DATE_CURRENCY)
  List<Object[]> sumSpendingByDateAndCurrency(
      @Param(ACCOUNT_IDS) Collection<UUID> accountIds,
      @Param(TYPES) Collection<String> types,
      @Param(FROM) LocalDate from,
      @Param("to") LocalDate to);

  /**
   * US-10-01/FR-CF-004/005: sum per booking date and currency, as positive magnitudes, of
   * everything in [{@code from}, {@code to}] that awaits a member's decision before it can count as
   * spending, income or a transfer: a negative {@code SETTLEMENT}, or a payment of any open
   * proposal (card settlement or transfer); an unlinked {@code TRANSFER}/{@code
   * PENSION_CONTRIBUTION} leg; and the credit leg of a proposed transfer pair (any type, so an
   * {@code INCOME} kept out of income is not lost). A credit leg is left out only while one of its
   * proposals' debits already stands for the pair in this very figure - booked in the same range,
   * on an account in {@code accountIds}, and itself unresolved - so a pair is counted once, and a
   * pair split across two months or across accounts the caller cannot all see is still counted.
   * Internal transfers, voided rows and reversals are resolved and left out. Each element is {@code
   * [bookingDate, currency, sum]}.
   */
  @Query(
      "select t.bookingDate, t.currency, sum(abs(t.amount)) from Transaction t"
          + " where t.account.id in :accountIds and t.internalTransfer = false"
          + BOOKED_BETWEEN
          + NOT_VOIDED_OR_REVERSAL
          + " and ((t.amount < 0 and (t.transactionType = 'SETTLEMENT' or exists (select 1 from"
          + " SettlementMatch m where m.status = 'PROPOSED' and m.paymentTransaction = t)))"
          + " or ((t.transactionType in ('TRANSFER', 'PENSION_CONTRIBUTION')"
          + " or exists (select 1 from SettlementMatch m where m.status = 'PROPOSED'"
          + " and m.matchKind = 'TRANSFER' and m.cardTransaction = t))"
          + " and not exists (select 1 from SettlementMatch m join m.paymentTransaction d"
          + " where m.status = 'PROPOSED' and m.matchKind = 'TRANSFER' and m.cardTransaction = t"
          + " and d.account.id in :accountIds and d.bookingDate between :from and :to"
          + " and d.internalTransfer = false)))"
          + PER_DATE_CURRENCY)
  List<Object[]> sumPendingReviewByDateAndCurrency(
      @Param(ACCOUNT_IDS) Collection<UUID> accountIds,
      @Param(FROM) LocalDate from,
      @Param("to") LocalDate to);

  /**
   * US-10-01: signed income per booking date and currency - {@code types} rows in [{@code from},
   * {@code to}] that are not an internal transfer and not the incoming leg of a transfer pair still
   * awaiting a decision (that pair is pending review). Each element is {@code [bookingDate,
   * currency, sum]}.
   */
  @Query(
      "select t.bookingDate, t.currency, sum(t.amount) from Transaction t"
          + " where t.account.id in :accountIds"
          + " and t.transactionType in :types and t.internalTransfer = false"
          + BOOKED_BETWEEN
          + NOT_VOIDED_OR_REVERSAL
          + " and not exists (select 1 from SettlementMatch m where m.status = 'PROPOSED'"
          + " and m.matchKind = 'TRANSFER' and m.cardTransaction = t)"
          + PER_DATE_CURRENCY)
  List<Object[]> sumIncomeByDateAndCurrency(
      @Param(ACCOUNT_IDS) Collection<UUID> accountIds,
      @Param(TYPES) Collection<String> types,
      @Param(FROM) LocalDate from,
      @Param("to") LocalDate to);

  /**
   * US-10-01/FR-CF-003: per booking date and currency, the incoming legs of linked internal
   * transfers in [{@code from}, {@code to}] whose own account's {@code counts_as_saving} is {@code
   * intoSaving} and whose counterparty account's is the opposite - money moved into saving ({@code
   * true}) or back out of it ({@code false}). A transfer between two alike accounts, or to an
   * untracked one, is in neither. Each element is {@code [bookingDate, currency, sum]}.
   */
  @Query(
      "select t.bookingDate, t.currency, sum(t.amount) from Transaction t"
          + " where t.account.id in :accountIds"
          + " and t.internalTransfer = true and t.amount > 0 and t.counterpartyAccountId is not null"
          + BOOKED_BETWEEN
          + NOT_VOIDED_OR_REVERSAL
          + " and t.account.countsAsSaving = :intoSaving"
          + " and exists (select 1 from Account c where c.id = t.counterpartyAccountId"
          + " and c.countsAsSaving <> :intoSaving)"
          + PER_DATE_CURRENCY)
  List<Object[]> sumSavingMovementsByDateAndCurrency(
      @Param(ACCOUNT_IDS) Collection<UUID> accountIds,
      @Param("intoSaving") boolean intoSaving,
      @Param(FROM) LocalDate from,
      @Param("to") LocalDate to);

  /**
   * US-07-06: whether any of these rows has been corrected, i.e. a replacement points at it via
   * {@code corrects_transaction_id}. Native, so a replacement deleted since still counts: restoring
   * a corrected original next to its replacement would count the transaction twice, and the
   * original's one replacement slot (uq_transaction_correction) stays taken either way.
   */
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM transaction"
              + " WHERE corrects_transaction_id IN (:transactionIds))",
      nativeQuery = true)
  boolean existsCorrectionOfAny(@Param("transactionIds") Collection<UUID> transactionIds);
}
