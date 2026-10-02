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
  private final CategoryService categoryService;
  private final VersionPreconditionService versionPreconditionService;

  public TransactionCorrectionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      TransactionRemovalService transactionRemovalService,
      TransactionService transactionService,
      CategorizationService categorizationService,
      CategoryService categoryService,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.transactionRemovalService = transactionRemovalService;
    this.transactionService = transactionService;
    this.categorizationService = categorizationService;
    this.categoryService = categoryService;
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

    // A client that sends back the row it read echoes a server-estimated rate as if it were an
    // explicit one. Unchanged, it is not a disclosed rate: left out, it counts as "derive it", so
    // a description edit stays in place and a replacement estimates its own rate again.
    CorrectTransactionRequest desired =
        echoesEstimatedRate(original, request) ? request.withoutFxRate() : request;

    UUID targetAccountId =
        desired.targetAccountId() == null ? sourceAccount.getId() : desired.targetAccountId();
    if (!targetAccountId.equals(sourceAccount.getId())) {
      Account targetAccount = accountLookupService.findAccountOrThrow(targetAccountId, actor);
      accessControlService.requireAccountAccess(actor, targetAccount, AccessLevelValues.EDIT);
      if (!targetAccount.getWorkspace().getId().equals(sourceAccount.getWorkspace().getId())) {
        throw accessControlService.denyAsNotFound(actor, "Account", targetAccountId);
      }
    }
    CreateTransactionRequest replacementRequest = desired.replacementRequest();
    boolean financialChange =
        !transactionService.financialStateMatches(original, targetAccountId, replacementRequest);

    if (!financialChange) {
      return editTextOnly(original, desired);
    }

    // An override carries over only while its category can still be assigned; otherwise the
    // replacement keeps the automatic category it got like any new row (US-08-01/02).
    UUID overriddenCategory =
        categorizationService.isOverridden(original)
                && categoryService
                    .assignableCategoryIds(sourceAccount.getWorkspace().getId())
                    .contains(original.getCategoryId())
            ? original.getCategoryId()
            : null;

    TransactionRemovalResponse removal =
        transactionRemovalService.remove(
            accountId, transactionId, desired.reason(), expectedVersion, actor);

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

  static boolean echoesEstimatedRate(Transaction original, CorrectTransactionRequest request) {
    return original.isFxRateEstimated()
        && request.billedAmount() == null
        && request.fxRateToAccountCurrency() != null
        && original.getFxRateToAccountCurrency() != null
        && original.getFxRateToAccountCurrency().compareTo(request.fxRateToAccountCurrency()) == 0;
  }

  private TransactionCorrectionResponse editTextOnly(
      Transaction transaction, CorrectTransactionRequest request) {
    boolean descriptionChanged =
        !Objects.equals(transaction.getMerchantDescription(), request.merchantDescription());
    boolean changed =
        descriptionChanged || !Objects.equals(transaction.getNotes(), request.notes());
    if (changed) {
      transaction.setMerchantDescription(request.merchantDescription());
      transaction.setNotes(request.notes());
      transactionRepository.saveAndFlush(transaction);
    }
    if (descriptionChanged) {
      // Rules and fuzzy matching read the merchant description, so the automatic category may
      // change with it. categorize() leaves a member's override alone (RULE-031, FR-CAT-014).
      categorizationService.categorize(transaction);
      // Flushed so the returned ETag is the stored version (ADR 0004).
      transactionRepository.flush();
    }
    TransactionResponse response = transactionService.toResponses(List.of(transaction)).get(0);
    return new TransactionCorrectionResponse(response.version(), response, null);
  }
}
