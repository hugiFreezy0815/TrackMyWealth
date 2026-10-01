package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-10-01/FR-CF-004/005: a member resolves a one-sided transfer leg - money moved to or from an
 * own account that is not tracked here - by confirming it as such. The leg is then an internal
 * transfer ({@code is_internal_transfer}) with no counterpart account, so it counts as neither
 * income nor spending and leaves pending review; a counterpart recorded later still pairs with it
 * ({@link TransferDetectionService} treats it as a candidate). Undoing the confirmation puts it
 * back in pending review. Both need EDIT on the account and are idempotent.
 *
 * <p>The confirmation is not remembered apart from the flag. If the leg is later matched with a
 * counterpart and that match is undone ({@code reject} on a confirmed match), the leg reverts to an
 * ordinary, unresolved transfer leg - pending review again, not "untracked" - by design: rejecting
 * the match is new information about where the money went, so the member decides the leg afresh.
 */
@Service
public class TransferResolutionService {

  private static final String NOT_FOUND = "Not found.";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final TransferDetectionService transferDetectionService;
  private final TransactionService transactionService;
  private final VersionPreconditionService versionPreconditionService;

  public TransferResolutionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      TransferDetectionService transferDetectionService,
      TransactionService transactionService,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.transferDetectionService = transferDetectionService;
    this.transactionService = transactionService;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional
  public TransactionResponse confirmUntracked(
      UUID accountId,
      UUID transactionId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Transaction leg = requireOneSidedLeg(accountId, transactionId, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, leg.getVersion(), TransactionService.VERSIONED_RESOURCE);
    if (!leg.isInternalTransfer()) {
      leg.setInternalTransfer(true);
      transactionRepository.saveAndFlush(leg);
    }
    return transactionService.toResponses(List.of(leg)).get(0);
  }

  @Transactional
  public TransactionResponse undoUntracked(
      UUID accountId,
      UUID transactionId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Transaction leg = requireOneSidedLeg(accountId, transactionId, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, leg.getVersion(), TransactionService.VERSIONED_RESOURCE);
    if (leg.isInternalTransfer()) {
      leg.setInternalTransfer(false);
      transactionRepository.saveAndFlush(leg);
      transferDetectionService.detectAfterWrite(leg.getAccount(), leg.getBookingDate());
    }
    return transactionService.toResponses(List.of(leg)).get(0);
  }

  // A TRANSFER-type leg of this account that is not linked to a counterpart account: a matched or
  // two-sided transfer already says where the money went.
  private Transaction requireOneSidedLeg(
      UUID accountId, UUID transactionId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    Transaction leg =
        transactionRepository
            .findByIdForUpdate(transactionId)
            .filter(row -> row.getAccount().getId().equals(accountId))
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "Transaction", transactionId));
    if (!TransferRecordingService.TRANSFER_TYPES.contains(leg.getTransactionType())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Only a TRANSFER or PENSION_CONTRIBUTION leg can be confirmed as a transfer to an"
              + " untracked account.");
    }
    if (leg.getVoidedAt() != null || leg.isReversal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A voided transaction or a reversing entry cannot be changed.");
    }
    if (leg.getCounterpartyAccountId() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This leg is already linked to its counterpart account; reject that match first.");
    }
    return leg;
  }
}
