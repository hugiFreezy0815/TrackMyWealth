package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionCorrectionResponse;
import com.trackmywealth.backend.dto.TransactionRemovalValues;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UpdateTransactionRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-06/FR-LIF-004/RULE-024: editing a transaction. Description and notes are annotations and
 * change in place. Every other field feeds a figure and is frozen once written ({@code
 * trg_transaction_append_only}), so changing one is a correction: the original is removed the way
 * its provenance requires ({@link TransactionRemovalService} - soft delete for a manual row, void
 * plus reversing row for an imported one) and a replacement with the corrected values is recorded
 * ({@link TransactionService#recordReplacement}), all in one transaction. History keeps both: the
 * replacement points back at the original through {@code corrects_transaction_id}.
 *
 * <p>The system decides which of the two an edit is by comparing it with the stored row
 * (FR-LIF-002b); the member only says what the transaction should look like.
 *
 * <p>Not corrected here yet: a row linked to another one - a two-sided transfer's leg, a card
 * purchase with its disclosed FEE row, or that FEE row itself (409). Correcting one would have to
 * re-create its partner too (US-07-08, #216); until then such a row is deleted and recorded again.
 * A settlement match on the original is dissolved, as for a removal, and detection then runs for
 * the replacement.
 *
 * <p>Needs EDIT on the account, and on the target account when the correction moves the row.
 */
@Service
public class TransactionCorrectionService {

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final TransactionRemovalService transactionRemovalService;
  private final TransactionService transactionService;
  private final VersionPreconditionService versionPreconditionService;

  public TransactionCorrectionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      TransactionRemovalService transactionRemovalService,
      TransactionService transactionService,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.transactionRemovalService = transactionRemovalService;
    this.transactionService = transactionService;
    this.versionPreconditionService = versionPreconditionService;
  }

  /**
   * Applies the edit in place or as a correction, see the class comment. {@code request.reason()}
   * is required when the correction voids an imported row (422 without it) and ignored otherwise.
   */
  @Transactional
  public TransactionCorrectionResponse update(
      UUID accountId,
      UUID transactionId,
      UpdateTransactionRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    boolean moves = !account.getId().equals(request.accountId());
    Account target = moves ? requireEditable(request.accountId(), actor) : account;
    // Cards before rows, as for a removal (TransactionRemovalService#lockCards), so matching on
    // either account and this edit queue behind each other instead of deadlocking.
    transactionRemovalService.lockCards(
        moves ? List.of(account, target) : List.of(account), transactionId);
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
              ? "A reversing entry cannot be edited; it follows its voided original."
              : "A voided transaction cannot be edited.");
    }
    versionPreconditionService.requireCurrent(
        expectedVersion, original.getVersion(), TransactionService.VERSIONED_RESOURCE);

    if (!changesFinancialFields(original, target, request)) {
      original.setMerchantDescription(request.merchantDescription());
      original.setNotes(request.notes());
      transactionRepository.saveAndFlush(original);
      TransactionResponse updated = transactionService.toResponses(List.of(original)).get(0);
      return new TransactionCorrectionResponse(
          null, updated.version(), updated, List.of(), List.of(), List.of());
    }

    requireUnlinked(original);
    String voidReason =
        TransactionRemovalValues.VOID.equals(removal)
            ? TransactionRemovalService.requireReason(request.reason())
            : null;
    SortedSet<UUID> unmatched = new TreeSet<>();
    List<Transaction> reversals =
        transactionRemovalService.removeRows(List.of(original), voidReason, actor, unmatched);
    TransactionResponse replacement =
        transactionService.recordReplacement(
            target, replacementRequest(original, request), original, actor);
    return new TransactionCorrectionResponse(
        removal,
        replacement.version(),
        replacement,
        transactionService.toResponses(List.of(original)),
        transactionService.toResponses(reversals),
        List.copyOf(unmatched));
  }

  private Account requireEditable(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    return account;
  }

  // Exactly the fields trg_transaction_append_only freezes, as the request can express them (a
  // dividend's net amount follows from its amount).
  static boolean changesFinancialFields(
      Transaction original, Account target, UpdateTransactionRequest request) {
    return !original.getAccount().getId().equals(target.getId())
        || !original.getBookingDate().equals(request.bookingDate())
        || original.getAmount().compareTo(request.amount()) != 0
        || !original.getCurrency().equals(request.currency())
        || changesFxRate(original, request)
        || !TransactionService.sameDecimal(original.getFeeAmount(), request.feeAmount())
        || !Objects.equals(original.getSecurityId(), request.securityId())
        || !TransactionService.sameDecimal(original.getQuantity(), request.quantity())
        || !TransactionService.sameDecimal(original.getUnitPrice(), request.unitPrice())
        || !Objects.equals(original.getTradeDate(), request.tradeDate())
        || !Objects.equals(original.getSettlementDate(), request.settlementDate())
        || !TransactionService.sameDecimal(original.getGrossAmount(), request.grossAmount())
        || !TransactionService.sameDecimal(
            original.getTaxWithheldAmount(), request.taxWithheldAmount());
  }

  // Only a rate the request states can differ; stating none leaves it to be resolved.
  private static boolean changesFxRate(Transaction original, UpdateTransactionRequest request) {
    BigDecimal requested;
    if (request.fxRateToAccountCurrency() != null) {
      requested = request.fxRateToAccountCurrency();
    } else if (request.billedAmount() != null && request.amount().signum() != 0) {
      requested = TransactionService.rateFromBilledAmount(request.billedAmount(), request.amount());
    } else {
      // A billedAmount against a zero amount implies no rate; recording rejects it with its reason.
      return request.billedAmount() != null;
    }
    return !TransactionService.sameDecimal(original.getFxRateToAccountCurrency(), requested);
  }

  private void requireUnlinked(Transaction original) {
    boolean linked =
        TransferRecordingService.TRANSFER_TYPES.contains(original.getTransactionType())
            || original.getRelatedTransactionId() != null
            || transactionRepository
                .findByRelatedTransactionId(original.getId())
                .filter(fee -> fee.getVoidedAt() == null)
                .isPresent();
    if (linked) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This transaction is linked to another one (a transfer leg, or a card purchase and its"
              + " fee), whose financial fields cannot be corrected yet. Delete it and record it"
              + " again instead.");
    }
  }

  // The replacement as a new transaction of the original's type. An estimated rate sent back
  // unchanged is not a disclosed one: it is left out, so the replacement's own is estimated anew.
  private static CreateTransactionRequest replacementRequest(
      Transaction original, UpdateTransactionRequest request) {
    BigDecimal requested = request.fxRateToAccountCurrency();
    boolean echoedEstimate =
        original.isFxRateEstimated()
            && requested != null
            && TransactionService.sameDecimal(original.getFxRateToAccountCurrency(), requested);
    BigDecimal rate = echoedEstimate ? null : requested;
    return new CreateTransactionRequest(
        original.getTransactionType(),
        request.bookingDate(),
        request.amount(),
        request.currency(),
        request.merchantDescription(),
        null,
        request.notes(),
        null,
        rate,
        request.billedAmount(),
        request.feeAmount(),
        request.securityId(),
        request.quantity(),
        request.unitPrice(),
        request.tradeDate(),
        request.settlementDate(),
        request.grossAmount(),
        request.taxWithheldAmount(),
        null,
        null);
  }
}
