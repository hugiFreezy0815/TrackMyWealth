package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionRemovalValues;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-02/FR-LIF-002/002a/002b/003/006/007: removing a transaction without ever mutating a
 * financial field (RULE-024). The system decides how, from the row's provenance - the member only
 * says "delete" (FR-LIF-003):
 *
 * <ul>
 *   <li><b>T1, a manually entered row</b> ({@code source = MANUAL}): soft delete. It disappears
 *       from every view and figure (the entity's {@code @SQLRestriction}), is restorable for
 *       {@value #RESTORE_WINDOW_DAYS} days ({@link #restore}), and is never purged (V39, issue
 *       #143).
 *   <li><b>T2, an imported row</b>: void. The original is marked voided with a required reason and
 *       stays listed as voided; a reversing row of the same type with every amount and the quantity
 *       negated is added, dated to the void, linked by {@code replaces_transaction_id}. Balances
 *       sum both (they net to zero from the void on); cash-flow and category figures leave both
 *       out. Restoring a void is a later story.
 * </ul>
 *
 * <p>Nothing goes silently (FR-LIF-007): a card purchase's linked FEE row is removed with it, and
 * every settlement match the removal breaks is dissolved - a confirmed match's other leg becomes an
 * ordinary payment or credit again. The response names every row affected. Only open matches
 * (proposed, confirmed) are removed: a rejected one stays, beside a voided row as history and
 * beside a soft-deleted one so that a restore cannot re-propose or auto-confirm the pair the member
 * rejected. Match queries leave a match out while one of its legs is deleted.
 *
 * <p>Needs EDIT on the account, the same as recording there. T3 - a reconciled row whose void
 * reopens its reconciliation - arrives with reconciliation itself (US-25-02).
 */
@Service
public class TransactionRemovalService {

  static final int RESTORE_WINDOW_DAYS = 30;
  private static final int MAX_REASON_LENGTH = 500;
  private static final String NOT_FOUND = "Not found.";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final TransferDetectionService transferDetectionService;
  private final TransactionService transactionService;
  private final BusinessDateService businessDateService;
  private final Clock clock;
  private final VersionPreconditionService versionPreconditionService;

  public TransactionRemovalService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      SettlementMatchRepository settlementMatchRepository,
      SettlementDetectionService settlementDetectionService,
      TransferDetectionService transferDetectionService,
      TransactionService transactionService,
      BusinessDateService businessDateService,
      Clock clock,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.transferDetectionService = transferDetectionService;
    this.transactionService = transactionService;
    this.businessDateService = businessDateService;
    this.clock = clock;
    this.versionPreconditionService = versionPreconditionService;
  }

  /**
   * Removes the transaction the way its provenance requires. {@code reason} is required for a void
   * (422 without it) and ignored for a soft delete; each row's {@code removal} tells a client in
   * advance which applies.
   */
  @Transactional
  public TransactionRemovalResponse remove(
      UUID accountId,
      UUID transactionId,
      String reason,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    // Cards before rows, the order settlement matching takes them, so a concurrent match decision
    // and this removal queue behind each other instead of deadlocking.
    lockCards(account, transactionId);
    Transaction original =
        transactionRepository
            .findByIdForUpdate(transactionId)
            .filter(row -> row.getAccount().getId().equals(account.getId()))
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "Transaction", transactionId));
    String removal = TransactionService.removalOf(original);
    if (removal == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          original.isReversal()
              ? "A reversing entry cannot be removed on its own; it goes with its original."
              : "This transaction is already voided.");
    }
    boolean softDelete = TransactionRemovalValues.SOFT_DELETE.equals(removal);
    String voidReason = softDelete ? null : requireReason(reason);

    List<Transaction> affected = new ArrayList<>();
    affected.add(original);
    // A card purchase's disclosed FX fee is part of it (US-09-04); a fee removed on its own before
    // is no longer found (voided, or hidden once soft-deleted).
    transactionRepository
        .findByRelatedTransactionId(original.getId())
        .filter(fee -> fee.getVoidedAt() == null)
        .ifPresent(affected::add);
    // US-10-01: a two-sided transfer is one entry - removing its incoming leg takes the outgoing
    // one too (the other direction is the lookup just above).
    if (isTransferLeg(original) && original.getRelatedTransactionId() != null) {
      transactionRepository
          .findByIdForUpdate(original.getRelatedTransactionId())
          .filter(debit -> debit.getVoidedAt() == null)
          .ifPresent(affected::add);
    }
    requireEditOnOtherAccounts(affected, account, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, original.getVersion(), TransactionService.VERSIONED_RESOURCE);

    OffsetDateTime now = OffsetDateTime.now(clock);
    SortedSet<UUID> unmatched = new TreeSet<>();
    List<Transaction> reversals = new ArrayList<>();
    for (Transaction row : affected) {
      dissolveMatches(row, unmatched);
      if (softDelete) {
        row.setDeletedAt(now);
        row.setDeletedBy(actor.userId());
        transactionRepository.saveAndFlush(row);
      } else {
        row.setVoidedAt(now);
        row.setVoidedBy(actor.userId());
        row.setVoidReason(voidReason);
        transactionRepository.saveAndFlush(row);
        reversals.add(transactionRepository.saveAndFlush(reversalOf(row, actor.userId())));
      }
    }
    affected.forEach(row -> unmatched.remove(row.getId()));
    return new TransactionRemovalResponse(
        removal,
        VersionPreconditionService.persistedVersion(
            original.getVersion(), TransactionService.VERSIONED_RESOURCE),
        transactionService.toResponses(affected),
        transactionService.toResponses(reversals),
        List.copyOf(unmatched));
  }

  /**
   * FR-LIF-006: brings back a soft-deleted row, and the FEE row deleted with it, within {@value
   * #RESTORE_WINDOW_DAYS} days of its deletion. Settlement matching then runs again for the
   * account, since a restored payment or credit may pair again. A void is not restored here (later
   * story).
   */
  @Transactional
  public TransactionRemovalResponse restore(
      UUID accountId,
      UUID transactionId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    lockCards(account, transactionId);
    Transaction deleted =
        transactionRepository
            .findByIdIncludingDeletedForUpdate(transactionId)
            .filter(row -> row.getAccount().getId().equals(account.getId()))
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "Transaction", transactionId));
    if (deleted.getDeletedAt() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This transaction is not deleted.");
    }
    if (deleted.getDeletedAt().isBefore(restoreWindowStart())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This transaction was deleted more than "
              + RESTORE_WINDOW_DAYS
              + " days ago and can no longer be restored.");
    }
    List<Transaction> restored = new ArrayList<>();
    restored.add(deleted);
    // Only a fee deleted in the same step: one the member deleted separately stays deleted.
    for (Transaction fee : transactionRepository.findDeletedFeeRowsForUpdate(deleted.getId())) {
      if (fee.getDeletedAt().isEqual(deleted.getDeletedAt())) {
        restored.add(fee);
      }
    }
    // US-10-01: the outgoing leg of a two-sided transfer comes back with its incoming leg.
    if (isTransferLeg(deleted) && deleted.getRelatedTransactionId() != null) {
      transactionRepository
          .findByIdIncludingDeletedForUpdate(deleted.getRelatedTransactionId())
          .filter(debit -> debit.getDeletedAt() != null)
          .filter(debit -> debit.getDeletedAt().isEqual(deleted.getDeletedAt()))
          .ifPresent(restored::add);
    }
    requireEditOnOtherAccounts(restored, account, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, deleted.getVersion(), TransactionService.VERSIONED_RESOURCE);
    LocalDate earliest = deleted.getBookingDate();
    for (Transaction row : restored) {
      row.setDeletedAt(null);
      row.setDeletedBy(null);
      transactionRepository.saveAndFlush(row);
    }
    settlementDetectionService.detectAfterWrite(account, earliest);
    transferDetectionService.detectAfterWrite(account, earliest);
    return new TransactionRemovalResponse(
        TransactionRemovalValues.SOFT_DELETE,
        VersionPreconditionService.persistedVersion(
            deleted.getVersion(), TransactionService.VERSIONED_RESOURCE),
        transactionService.toResponses(restored),
        List.of(),
        List.of());
  }

  /** The account's soft-deleted rows still restorable, most recently deleted first. */
  @Transactional(readOnly = true)
  public List<TransactionResponse> listRestorable(
      UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    return transactionService.toResponses(
        transactionRepository.findDeletedByAccountIdSince(accountId, restoreWindowStart()));
  }

  private static boolean isTransferLeg(Transaction row) {
    return TransferRecordingService.TRANSFER_TYPES.contains(row.getTransactionType());
  }

  // The other leg of a two-sided transfer sits on another account, which the member must be able to
  // edit too - the same rule as recording it.
  private void requireEditOnOtherAccounts(
      List<Transaction> rows, Account account, AuthenticatedUserPrincipal actor) {
    for (Transaction row : rows) {
      if (!row.getAccount().getId().equals(account.getId())) {
        accessControlService.requireAccountAccess(actor, row.getAccount(), AccessLevelValues.EDIT);
      }
    }
  }

  private Account requireEditable(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    return account;
  }

  // Every card whose matching the row can take part in, not only those with a match on it yet: a
  // detection run already reading the row as an unmatched candidate holds its card's lock, and must
  // finish (or wait) before the row is hidden or voided, or it would link a match to a removed row.
  // Plus the cards of any existing match, which a former settlement source may still have. Sorted,
  // so two removals take them in the same order.
  private void lockCards(Account account, UUID transactionId) {
    SortedSet<UUID> cards = new TreeSet<>(settlementDetectionService.cardsAffectedBy(account));
    settlementMatchRepository
        .findByTransactionId(transactionId)
        .forEach(match -> cards.add(match.getCardAccount().getId()));
    cards.forEach(settlementDetectionService::lockCard);
  }

  // A confirmed match flagged both legs an internal transfer; dissolving it makes the other leg an
  // ordinary payment or credit again (SettlementMatchService#reject does the same). A rejected
  // match is a member's decision and is kept whichever way the row goes (see the class comment).
  private void dissolveMatches(Transaction row, Set<UUID> unmatched) {
    for (SettlementMatch match : settlementMatchRepository.findByTransactionId(row.getId())) {
      if (SettlementMatchValues.REJECTED.equals(match.getStatus())) {
        continue;
      }
      if (SettlementMatchValues.CONFIRMED.equals(match.getStatus())) {
        settlementDetectionService.clearFlags(match);
      }
      addOtherLeg(match, row, unmatched);
      settlementMatchRepository.delete(match);
    }
    settlementMatchRepository.flush();
  }

  private static void addOtherLeg(SettlementMatch match, Transaction row, Set<UUID> into) {
    Transaction payment = match.getPaymentTransaction();
    Transaction card = match.getCardTransaction();
    Transaction other = row.getId().equals(payment.getId()) ? card : payment;
    if (other != null) {
      into.add(other.getId());
    }
  }

  /**
   * FR-LIF-002: the same type, account, currency, rate and security, with the amount and every
   * signed figure negated - so a position's quantity sum and an account's balance both net to zero
   * - dated to the void, or to the original's own date when that lies later (V36 keeps a trade date
   * on or before its booking date). No category (US-08-01): it is excluded from figures.
   */
  private Transaction reversalOf(Transaction original, UUID actorUserId) {
    Transaction reversal = new Transaction();
    reversal.setWorkspace(original.getWorkspace());
    reversal.setAccount(original.getAccount());
    reversal.setTransactionType(original.getTransactionType());
    LocalDate today = businessDateService.today();
    reversal.setBookingDate(
        original.getBookingDate().isAfter(today) ? original.getBookingDate() : today);
    reversal.setAmount(original.getAmount().negate());
    reversal.setCurrency(original.getCurrency());
    reversal.setFxRateToAccountCurrency(original.getFxRateToAccountCurrency());
    reversal.setFxRateDate(original.getFxRateDate());
    reversal.setFxRateEstimated(original.isFxRateEstimated());
    reversal.setMerchantDescription(original.getMerchantDescription());
    reversal.setSecurityId(original.getSecurityId());
    reversal.setQuantity(negate(original.getQuantity()));
    reversal.setUnitPrice(original.getUnitPrice());
    reversal.setFeeAmount(original.getFeeAmount());
    reversal.setTradeDate(original.getTradeDate());
    reversal.setSettlementDate(original.getSettlementDate());
    reversal.setGrossAmount(negate(original.getGrossAmount()));
    reversal.setTaxWithheldAmount(negate(original.getTaxWithheldAmount()));
    reversal.setNetAmount(negate(original.getNetAmount()));
    // The reversal carries the original's provenance: an imported row's correction stays T2.
    reversal.setSource(original.getSource());
    reversal.setReplacesTransactionId(original.getId());
    reversal.setCreatedBy(actorUserId);
    return reversal;
  }

  private static BigDecimal negate(BigDecimal value) {
    return value == null ? null : value.negate();
  }

  private static String requireReason(String reason) {
    String trimmed = reason == null ? "" : reason.strip();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "An imported transaction is voided, not deleted: give a reason (FR-LIF-002).");
    }
    if (trimmed.length() > MAX_REASON_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "reason must be at most " + MAX_REASON_LENGTH + " characters.");
    }
    return trimmed;
  }

  private OffsetDateTime restoreWindowStart() {
    return OffsetDateTime.now(clock).minus(Duration.ofDays(RESTORE_WINDOW_DAYS));
  }
}
