package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionCorrectionResponse;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-06 / FR-LIF-004: corrects a transaction without rewriting committed financial history.
 *
 * <p>A text-only edit changes {@code merchantDescription}/{@code notes} in place. Any immutable
 * financial difference uses the existing lifecycle rules atomically: T1 is soft-deleted, T2 is
 * voided and reversed, then a validated replacement is inserted with an immutable correction link.
 * A member's current category override is carried to the replacement after ordinary automatic
 * categorization has run.
 */
@Service
public class TransactionCorrectionService {

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final TransactionRemovalService transactionRemovalService;
  private final TransactionService transactionService;
  private final CategorizationService categorizationService;
  private final VersionPreconditionService versionPreconditionService;

  public TransactionCorrectionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      TransactionRemovalService transactionRemovalService,
      TransactionService transactionService,
      CategorizationService categorizationService,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.transactionRemovalService = transactionRemovalService;
    this.transactionService = transactionService;
    this.categorizationService = categorizationService;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional
  public TransactionCorrectionResponse correct(
      UUID accountId,
      UUID transactionId,
      CorrectTransactionRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Transaction original =
        transactionRemovalService.lockActiveTransaction(accountId, transactionId, actor);
    Account sourceAccount = original.getAccount();

    if (TransactionService.removalOf(original) == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          original.isReversal()
              ? "A reversing entry cannot be corrected on its own."
              : "A removed transaction cannot be corrected.");
    }

    versionPreconditionService.requireCurrent(
        expectedVersion, original.getVersion(), TransactionService.VERSIONED_RESOURCE);

    UUID targetAccountId =
        request.targetAccountId() == null ? sourceAccount.getId() : request.targetAccountId();
    if (!targetAccountId.equals(sourceAccount.getId())) {
      Account targetAccount = accountLookupService.findAccountOrThrow(targetAccountId, actor);
      accessControlService.requireAccountAccess(actor, targetAccount, AccessLevelValues.EDIT);
      if (!targetAccount.getWorkspace().getId().equals(sourceAccount.getWorkspace().getId())) {
        throw accessControlService.denyAsNotFound(actor, "Account", targetAccountId);
      }
    }
    CreateTransactionRequest replacementRequest = request.replacementRequest();
    boolean financialChange =
        !transactionService.financialStateMatches(original, targetAccountId, replacementRequest);

    if (!financialChange) {
      return editTextOnly(original, request);
    }

    boolean carryOverride = categorizationService.isOverridden(original);
    UUID overriddenCategory = carryOverride ? original.getCategoryId() : null;

    TransactionRemovalResponse removal =
        transactionRemovalService.remove(
            accountId, transactionId, request.reason(), expectedVersion, actor);

    TransactionResponse created =
        transactionService.recordCorrectionReplacement(
            targetAccountId,
            replacementRequest,
            original.getSource(),
            original.getRawSourceData(),
            original.getId(),
            actor);

    if (overriddenCategory != null) {
      Transaction replacement =
          transactionRepository
              .findByIdForUpdate(created.id())
              .orElseThrow(() -> new IllegalStateException("Correction replacement disappeared."));
      categorizationService.override(replacement, overriddenCategory, actor);
      created = transactionService.toResponses(List.of(replacement)).get(0);
    }

    return new TransactionCorrectionResponse(created.version(), created, removal);
  }

  private TransactionCorrectionResponse editTextOnly(
      Transaction transaction, CorrectTransactionRequest request) {
    boolean changed =
        !Objects.equals(transaction.getMerchantDescription(), request.merchantDescription())
            || !Objects.equals(transaction.getNotes(), request.notes());
    if (changed) {
      transaction.setMerchantDescription(request.merchantDescription());
      transaction.setNotes(request.notes());
      transactionRepository.saveAndFlush(transaction);
    }
    TransactionResponse response = transactionService.toResponses(List.of(transaction)).get(0);
    return new TransactionCorrectionResponse(response.version(), response, null);
  }
}
